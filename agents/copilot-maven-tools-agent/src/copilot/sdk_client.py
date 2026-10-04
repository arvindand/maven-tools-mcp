"""
Copilot SDK Client for Maven Tools Agent.

Uses the official GitHub Copilot SDK for cleaner integration
with automatic tool approval.

@author Arvind Menon
"""

from __future__ import annotations

import copy
import json
import logging
import os
import re
from typing import Any, Optional

logger = logging.getLogger(__name__)


def get_github_token() -> Optional[str]:
    """Get GitHub token from environment variables (in order of precedence)."""
    for var in ["COPILOT_GITHUB_TOKEN", "GH_TOKEN", "GITHUB_TOKEN"]:
        token = os.environ.get(var)
        if token:
            return token
    return None


class CopilotSDKClient:
    """
    Client using the official GitHub Copilot SDK.

    Provides cleaner API with MCP tool support.
    """

    # MCP config for Maven Tools - STDIO transport (Docker spawns container)
    MAVEN_TOOLS_MCP_STDIO = {
        "maven-tools": {
            "type": "local",
            "command": "docker",
            "args": ["run", "-i", "--rm", "arvindand/maven-tools-mcp:latest"],
            "tools": ["*"],
            "timeout": 60000,
        }
    }

    # MCP config for Maven Tools - HTTP transport (connects to running container)
    MAVEN_TOOLS_MCP_HTTP = {
        "maven-tools": {
            "type": "http",
            "url": "http://localhost:8080/mcp",
            "tools": ["*"],
            "timeout": 60000,
        }
    }

    def __init__(
        self,
        working_dir: Optional[str] = None,
        model: str = "claude-haiku-4.5",
        use_maven_tools_mcp: bool = True,
        mcp_transport: str = "stdio",  # "stdio" or "http"
        mcp_url: Optional[str] = None,  # Override URL for HTTP transport
    ):
        """
        Initialize SDK client.

        Args:
            working_dir: Working directory for the session
            model: AI model to use
            use_maven_tools_mcp: Whether to configure Maven Tools MCP
            mcp_transport: Transport type - "stdio" (spawn Docker) or "http" (connect to running server)
            mcp_url: Override URL for HTTP transport (default: http://localhost:8080/mcp)
        """
        self.working_dir = working_dir
        self.model = model
        self.use_maven_tools_mcp = use_maven_tools_mcp
        self.mcp_transport = mcp_transport
        self.mcp_url = mcp_url
        self._client = None
        self._session = None

    async def start(self) -> None:
        """Start the Copilot client."""
        try:
            from copilot import CopilotClient, PermissionHandler
        except ImportError:
            raise ImportError(
                "github-copilot-sdk not installed. Run: pip install github-copilot-sdk"
            )

        # SDK 1.x configures the managed runtime through keyword arguments.
        token = get_github_token()
        if not token:
            logger.warning(
                "No GitHub token found in COPILOT_GITHUB_TOKEN, GH_TOKEN, or GITHUB_TOKEN"
            )

        self._client = CopilotClient(
            working_directory=self.working_dir,
            log_level="warning",
            github_token=token,
        )

        await self._client.start()
        logger.info("Copilot SDK client started")

        # Build MCP server config
        mcp_servers = None
        if self.use_maven_tools_mcp:
            if self.mcp_transport == "http":
                mcp_servers = copy.deepcopy(self.MAVEN_TOOLS_MCP_HTTP)
                if self.mcp_url:
                    mcp_servers["maven-tools"]["url"] = self.mcp_url
                logger.info(f"Using HTTP transport: {mcp_servers['maven-tools']['url']}")
            else:
                mcp_servers = self.MAVEN_TOOLS_MCP_STDIO
                logger.info("Using STDIO transport (Docker)")

        # Auto-approve all MCP tool permission requests (unattended CI agent)
        self._session = await self._client.create_session(
            on_permission_request=PermissionHandler.approve_all,
            model=self.model,
            streaming=False,
            mcp_servers=mcp_servers,
        )
        logger.info(f"Session created with model {self.model}")

    async def stop(self) -> None:
        """Stop the Copilot client."""
        session, self._session = self._session, None
        client, self._client = self._client, None
        try:
            if session:
                try:
                    await session.disconnect()
                except Exception as error:
                    logger.debug("Session disconnect error: %s", error)
        finally:
            if client:
                try:
                    await client.stop()
                except Exception as error:
                    # SDK1.x may report cleanup failures as ExceptionGroup.
                    # Cleanup must not replace a completed review or its error.
                    logger.debug("Client stop error: %s", error)
        logger.info("Copilot SDK client stopped")

    async def __aenter__(self) -> "CopilotSDKClient":
        """Async context manager entry."""
        await self.start()
        return self

    async def __aexit__(self, exc_type: Any, exc_val: Any, exc_tb: Any) -> None:
        """Async context manager exit."""
        await self.stop()

    async def submit(self, prompt: str, timeout: float = 300.0) -> str:
        """
        Submit a prompt and collect the full response.

        Args:
            prompt: The prompt to submit
            timeout: Maximum time to wait for response

        Returns:
            Complete response text
        """
        if not self._session:
            raise RuntimeError("Session not started")

        try:
            response = await self._session.send_and_wait(prompt, timeout=timeout)
        except TimeoutError as error:
            raise RuntimeError(f"Timeout waiting for response after {timeout}s") from error
        except Exception as error:
            raise RuntimeError(f"Copilot error: {error}") from error

        if response is None:
            raise RuntimeError("Copilot session became idle without an assistant response")

        # send_and_wait returns the final root assistant message, excluding tool
        # progress, streaming deltas and responses from child agents.
        return response.data.content

    async def analyze_dependencies(self, pom_content: str) -> dict[str, Any]:
        """
        Analyze POM dependencies using Copilot + Maven Tools MCP.

        Args:
            pom_content: Maven POM XML content

        Returns:
            Structured analysis results
        """
        prompt = f"""Analyze this Maven POM for outdated dependencies.

Use the @maven-tools MCP tools to check each dependency for updates.

{pom_content}

Return a JSON object with this exact structure:
{{
  "health_score": <number 0-100 based on how up-to-date the dependencies are>,
  "updates": [
    {{"groupId": "...", "artifactId": "...", "current": "...", "latest": "...", "type": "major|minor|patch"}}
  ],
  "recommendations": ["recommendation strings"]
}}

IMPORTANT: Respond with ONLY the JSON, no markdown formatting or explanation."""

        response = await self.submit(prompt, timeout=600.0)
        return _extract_json_object(response)

    async def compare_versions(
        self,
        dependencies: str,
        stability_filter: str = "STABLE_ONLY",
        include_security: bool = True,
    ) -> dict[str, Any]:
        """
        Compare current dependency versions against latest available using MCP tool.

        Args:
            dependencies: Comma-separated list in format "groupId:artifactId:version,..."
            stability_filter: "ALL", "STABLE_ONLY", or "PREFER_STABLE"
            include_security: Whether to include security scan

        Returns:
            Parsed JSON response from the MCP tool
        """
        prompt = f"""You MUST call the compare_dependency_versions tool with these exact parameters:
- currentDependencies: "{dependencies}"
- stabilityFilter: "{stability_filter}"
- includeSecurityScan: {str(include_security).lower()}

Call the tool now and return ONLY the raw tool response as JSON. Do not add any explanation or markdown formatting."""

        response = await self.submit(prompt, timeout=600.0)
        return _extract_json_from_response(response)

    async def get_latest_version(self, dependency: str) -> str:
        """
        Get latest version of a single dependency.

        Args:
            dependency: Maven coordinate (groupId:artifactId)

        Returns:
            Latest version string
        """
        prompt = f"""Use @maven-tools get_latest_version to check: {dependency}
Return ONLY the version number, nothing else."""

        response = await self.submit(prompt, timeout=120.0)
        # Extract version from response (usually last word or number pattern)
        versions = re.findall(r"\d+\.\d+(?:\.\d+)?(?:-[A-Za-z0-9]+)?", response)
        if versions:
            return versions[-1]
        return response.strip()


def _extract_json_object(response: str) -> dict[str, Any]:
    """Extract first complete JSON object from response."""
    start = response.find("{")
    if start < 0:
        raise ValueError(f"No JSON found in response: {response[:200]}")

    depth = 0
    end = start
    for i, char in enumerate(response[start:], start):
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                end = i + 1
                break

    if depth != 0:
        raise ValueError(f"Unbalanced JSON in response: {response[:200]}")

    json_str = response[start:end]
    try:
        return json.loads(json_str)
    except json.JSONDecodeError as e:
        logger.error(f"Failed to parse response as JSON: {e}")
        logger.debug(f"Response was: {response[:500]}")
        raise


def _extract_json_from_response(response: str) -> dict[str, Any]:
    """Extract JSON object or array from response."""
    start = response.find("{")
    if start < 0:
        start = response.find("[")
        if start < 0:
            raise ValueError(f"No JSON found in response: {response[:500]}")

    open_char = response[start]
    close_char = "}" if open_char == "{" else "]"
    depth = 0
    end = start

    for i, char in enumerate(response[start:], start):
        if char == open_char:
            depth += 1
        elif char == close_char:
            depth -= 1
            if depth == 0:
                end = i + 1
                break

    if depth != 0:
        raise ValueError(f"Unbalanced JSON in response: {response[:500]}")

    json_str = response[start:end]
    try:
        return json.loads(json_str)
    except json.JSONDecodeError as e:
        logger.error(f"Failed to parse MCP response as JSON: {e}")
        logger.debug(f"Response was: {response[:1000]}")
        raise
