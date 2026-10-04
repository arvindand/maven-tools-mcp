"""Compatibility checks against the installed Copilot SDK without model calls."""

from types import SimpleNamespace
from unittest.mock import create_autospec, patch

import pytest
from copilot.session import CopilotSession

from src.copilot.sdk_client import CopilotSDKClient


@pytest.mark.parametrize("transport", ["stdio", "http"])
async def test_current_sdk_lifecycle_and_mcp_configuration(monkeypatch, transport):
    monkeypatch.setenv("COPILOT_GITHUB_TOKEN", "test-token")
    session = create_autospec(CopilotSession, instance=True, spec_set=True)
    # Autospec uses the real installed SDK's signatures: the old positional
    # SubprocessConfig constructor and session.destroy API would fail this test.
    with patch("copilot.CopilotClient", autospec=True) as sdk:
        sdk.return_value.create_session.return_value = session
        client = CopilotSDKClient(
            working_dir="/workspace",
            mcp_transport=transport,
            mcp_url="http://localhost:8123/mcp",
        )
        async with client:
            sdk.assert_called_once_with(
                working_directory="/workspace", log_level="warning", github_token="test-token"
            )
            sdk.return_value.start.assert_awaited_once()
            config = sdk.return_value.create_session.call_args.kwargs
            assert config["model"] == client.model
            assert config["streaming"] is False
            if transport == "http":
                assert config["mcp_servers"]["maven-tools"]["url"] == "http://localhost:8123/mcp"
                assert (
                    CopilotSDKClient.MAVEN_TOOLS_MCP_HTTP["maven-tools"]["url"]
                    == "http://localhost:8080/mcp"
                )
            else:
                assert config["mcp_servers"]["maven-tools"]["command"] == "docker"
        session.disconnect.assert_awaited_once()
        sdk.return_value.stop.assert_awaited_once()
        assert client._client is None
        assert client._session is None


async def test_submit_returns_final_reply_without_accumulating_stream_events():
    client = CopilotSDKClient()
    session = create_autospec(CopilotSession, instance=True, spec_set=True)
    session.send_and_wait.return_value = SimpleNamespace(
        data=SimpleNamespace(content='{"ok":true}')
    )
    client._session = session

    assert await client.submit("Inspect the POM", timeout=12.0) == '{"ok":true}'
    session.send_and_wait.assert_awaited_once_with("Inspect the POM", timeout=12.0)
    session.on.assert_not_called()


async def test_submit_reports_idle_without_a_reply():
    client = CopilotSDKClient()
    client._session = create_autospec(CopilotSession, instance=True, spec_set=True)
    client._session.send_and_wait.return_value = None
    with pytest.raises(RuntimeError, match="without an assistant response"):
        await client.submit("Inspect the POM")


async def test_submit_preserves_timeout_context():
    client = CopilotSDKClient()
    client._session = create_autospec(CopilotSession, instance=True, spec_set=True)
    client._session.send_and_wait.side_effect = TimeoutError("SDK wait expired")
    with pytest.raises(RuntimeError, match="after 2.0s") as error:
        await client.submit("Inspect the POM", timeout=2.0)
    assert isinstance(error.value.__cause__, TimeoutError)


async def test_submit_wraps_sdk_session_error_for_the_cli():
    client = CopilotSDKClient()
    client._session = create_autospec(CopilotSession, instance=True, spec_set=True)
    sdk_error = Exception("Session error: provider unavailable")
    client._session.send_and_wait.side_effect = sdk_error
    with pytest.raises(RuntimeError, match="Copilot error: Session error") as error:
        await client.submit("Inspect the POM")
    assert error.value.__cause__ is sdk_error


@pytest.mark.parametrize("disconnect_fails", [False, True])
async def test_cleanup_errors_preserve_the_review_and_always_stop_the_client(disconnect_fails):
    client = CopilotSDKClient()
    session = create_autospec(CopilotSession, instance=True, spec_set=True)
    session.send_and_wait.return_value = SimpleNamespace(data=SimpleNamespace(content="review"))
    if disconnect_fails:
        session.disconnect.side_effect = Exception("JSON-RPC disconnect failed")
    with patch("copilot.CopilotClient", autospec=True) as sdk:
        client._session = session
        client._client = sdk.return_value
        sdk.return_value.stop.side_effect = ExceptionGroup(
            "Cleanup failed", [OSError("pipe closed")]
        )
        try:
            result = await client.submit("Inspect the POM")
        finally:
            await client.__aexit__(None, None, None)
        assert result == "review"
        session.disconnect.assert_awaited_once()
        sdk.return_value.stop.assert_awaited_once()
        assert client._session is None and client._client is None
        await client.stop()
        sdk.return_value.stop.assert_awaited_once()
