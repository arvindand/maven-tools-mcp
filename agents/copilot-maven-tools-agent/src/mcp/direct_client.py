"""
Direct MCP client for deterministic Maven Tools calls.

The weekly dependency updater should not need an LLM session to perform a
bounded compare_dependency_versions call. This client speaks the small MCP
subset needed by the dogfooding agent over either Streamable HTTP or stdio.
"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Any, Optional

import aiohttp

logger = logging.getLogger(__name__)

MCP_PROTOCOL_VERSION = "2025-06-18"


class DirectMcpClient:
    """Minimal MCP client for direct Maven Tools tool calls."""

    STDIO_COMMAND = ["docker", "run", "-i", "--rm", "arvindand/maven-tools-mcp:latest"]

    def __init__(
        self,
        transport: str = "stdio",
        url: Optional[str] = None,
        command: Optional[list[str]] = None,
        timeout_seconds: float = 60.0,
    ):
        self.transport = transport
        self.url = url or "http://localhost:8080/mcp"
        self.command = command or self.STDIO_COMMAND
        self.timeout_seconds = timeout_seconds
        self._request_id = 0
        self._session_id: Optional[str] = None
        self._http_session: Optional[aiohttp.ClientSession] = None
        self._process: Optional[asyncio.subprocess.Process] = None
        self._stderr_task: Optional[asyncio.Task[None]] = None

    async def __aenter__(self) -> "DirectMcpClient":
        await self.start()
        return self

    async def __aexit__(self, exc_type: Any, exc_val: Any, exc_tb: Any) -> None:
        await self.stop()

    async def start(self) -> None:
        """Start the transport and complete the MCP initialization handshake."""
        if self.transport == "http":
            self._http_session = aiohttp.ClientSession()
            logger.info("Using direct MCP HTTP transport: %s", self.url)
        elif self.transport == "stdio":
            self._process = await asyncio.create_subprocess_exec(
                *self.command,
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            self._stderr_task = asyncio.create_task(self._drain_stderr())
            logger.info("Using direct MCP stdio transport: %s", " ".join(self.command))
        else:
            raise ValueError(f"Unsupported MCP transport: {self.transport}")

        try:
            await self._request(
                "initialize",
                {
                    "protocolVersion": MCP_PROTOCOL_VERSION,
                    "capabilities": {},
                    "clientInfo": {
                        "name": "copilot-maven-tools-agent",
                        "version": "0.1.0",
                    },
                },
                timeout=120.0,
            )
            await self._notify("notifications/initialized", {})
        except Exception:
            await self.stop()
            raise

    async def stop(self) -> None:
        """Stop the transport."""
        if self._http_session:
            await self._http_session.close()
            self._http_session = None

        if self._process:
            if self._process.returncode is None:
                self._process.terminate()
                try:
                    await asyncio.wait_for(self._process.wait(), timeout=5.0)
                except asyncio.TimeoutError:
                    self._process.kill()
                    await self._process.wait()
            self._process = None

        if self._stderr_task:
            self._stderr_task.cancel()
            self._stderr_task = None

    async def compare_versions(
        self,
        dependencies: str,
        stability_filter: str = "STABLE_ONLY",
        include_security: bool = True,
    ) -> dict[str, Any]:
        """Call compare_dependency_versions directly and return the parsed tool payload."""
        result = await self._request(
            "tools/call",
            {
                "name": "compare_dependency_versions",
                "arguments": {
                    "currentDependencies": dependencies,
                    "stabilityFilter": stability_filter,
                    "includeSecurityScan": include_security,
                },
            },
            timeout=600.0,
        )
        return extract_tool_response_payload(result)

    async def _request(
        self,
        method: str,
        params: Optional[dict[str, Any]] = None,
        timeout: Optional[float] = None,
    ) -> Any:
        self._request_id += 1
        request_id = self._request_id
        message: dict[str, Any] = {
            "jsonrpc": "2.0",
            "id": request_id,
            "method": method,
        }
        if params is not None:
            message["params"] = params

        response = await self._roundtrip(message, timeout or self.timeout_seconds)
        if not isinstance(response, dict):
            raise RuntimeError(f"Invalid MCP response for {method}: {response!r}")
        if "error" in response:
            raise RuntimeError(f"MCP {method} failed: {response['error']}")
        if response.get("id") != request_id:
            raise RuntimeError(f"MCP response id mismatch for {method}: {response!r}")
        return response.get("result", {})

    async def _notify(self, method: str, params: Optional[dict[str, Any]] = None) -> None:
        message: dict[str, Any] = {
            "jsonrpc": "2.0",
            "method": method,
        }
        if params is not None:
            message["params"] = params
        await self._roundtrip(message, self.timeout_seconds)

    async def _roundtrip(self, message: dict[str, Any], timeout: float) -> Optional[dict[str, Any]]:
        if self.transport == "http":
            return await self._http_roundtrip(message, timeout)
        return await self._stdio_roundtrip(message, timeout)

    async def _http_roundtrip(
        self,
        message: dict[str, Any],
        timeout: float,
    ) -> Optional[dict[str, Any]]:
        if not self._http_session:
            raise RuntimeError("HTTP MCP session is not started")

        headers = {
            "Accept": "application/json, text/event-stream",
            "Content-Type": "application/json",
            "MCP-Protocol-Version": MCP_PROTOCOL_VERSION,
        }
        if self._session_id:
            headers["Mcp-Session-Id"] = self._session_id

        async with self._http_session.post(
            self.url,
            headers=headers,
            json=message,
            timeout=aiohttp.ClientTimeout(total=timeout),
        ) as response:
            if response.status in (202, 204):
                return None

            body = await response.text()
            if response.status >= 400:
                raise RuntimeError(f"MCP HTTP {response.status}: {body[:500]}")

            session_id = response.headers.get("Mcp-Session-Id")
            if session_id:
                self._session_id = session_id

            return parse_http_mcp_response(body)

    async def _stdio_roundtrip(
        self,
        message: dict[str, Any],
        timeout: float,
    ) -> Optional[dict[str, Any]]:
        if not self._process or not self._process.stdin or not self._process.stdout:
            raise RuntimeError("stdio MCP process is not started")

        payload = json.dumps(message, separators=(",", ":")).encode("utf-8") + b"\n"
        self._process.stdin.write(payload)
        await self._process.stdin.drain()

        if "id" not in message:
            return None

        request_id = message["id"]
        while True:
            line = await asyncio.wait_for(self._process.stdout.readline(), timeout=timeout)
            if not line:
                raise RuntimeError("MCP stdio process exited before sending a response")

            text = line.decode("utf-8", errors="replace").strip()
            if not text:
                continue

            try:
                response = json.loads(text)
            except json.JSONDecodeError:
                logger.debug("Ignoring non-JSON stdout from MCP process: %s", text[:200])
                continue

            if response.get("id") == request_id:
                return response
            logger.debug("Ignoring unrelated MCP message: %s", response)

    async def _drain_stderr(self) -> None:
        if not self._process or not self._process.stderr:
            return
        while True:
            line = await self._process.stderr.readline()
            if not line:
                return
            logger.debug("MCP stderr: %s", line.decode("utf-8", errors="replace").rstrip())


def parse_http_mcp_response(body: str) -> dict[str, Any]:
    """Parse JSON or SSE-framed MCP HTTP responses."""
    stripped = body.strip()
    if not stripped:
        return {}

    if any(line.strip().startswith("data:") for line in stripped.splitlines()):
        return _parse_sse_response(stripped)

    parsed = json.loads(stripped)
    if not isinstance(parsed, dict):
        raise ValueError(f"Expected MCP JSON object, got {type(parsed).__name__}")
    return parsed


def _parse_sse_response(body: str) -> dict[str, Any]:
    """Extract the last JSON-RPC message from an SSE response body."""
    events: list[dict[str, Any]] = []
    data_lines: list[str] = []

    for raw_line in body.splitlines():
        line = raw_line.strip()
        if line.startswith("data:"):
            data_lines.append(line[5:].strip())
        elif not line and data_lines:
            events.append(_parse_sse_data(data_lines))
            data_lines = []

    if data_lines:
        events.append(_parse_sse_data(data_lines))

    if not events:
        raise ValueError(f"No SSE data events found in MCP response: {body[:200]}")
    return events[-1]


def _parse_sse_data(data_lines: list[str]) -> dict[str, Any]:
    payload = "\n".join(data_lines)
    parsed = json.loads(payload)
    if not isinstance(parsed, dict):
        raise ValueError(f"Expected SSE JSON object, got {type(parsed).__name__}")
    return parsed


def extract_tool_response_payload(result: dict[str, Any]) -> dict[str, Any]:
    """Extract the JSON payload from an MCP tools/call result."""
    if result.get("isError"):
        raise RuntimeError(f"MCP tool returned an error: {result}")

    structured = result.get("structuredContent")
    if isinstance(structured, dict):
        return structured

    content = result.get("content")
    if isinstance(content, list):
        for item in content:
            if not isinstance(item, dict) or item.get("type") != "text":
                continue
            text = item.get("text")
            try:
                parsed = _parse_json_text(text) if isinstance(text, str) else None
            except json.JSONDecodeError:
                logger.debug("Ignoring non-JSON MCP text content: %s", str(text)[:200])
                continue
            if isinstance(parsed, dict):
                return parsed

    if isinstance(result, dict):
        return result
    raise ValueError(f"Could not extract MCP tool payload: {result!r}")


def _parse_json_text(text: str) -> Any:
    parsed = json.loads(text.strip())
    if isinstance(parsed, str) and parsed.strip().startswith(("{", "[")):
        return json.loads(parsed.strip())
    return parsed
