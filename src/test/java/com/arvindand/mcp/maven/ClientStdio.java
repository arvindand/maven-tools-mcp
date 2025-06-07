package com.arvindand.mcp.maven;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import java.util.Map;

/**
 * With stdio transport, the MCP server is automatically started by the client. But you have to
 * build the server jar first:
 *
 * <pre>
 * ./mvnw clean install -DskipTests
 * </pre>
 *
 * @author Arvind Menon
 * @since 0.1.0
 */
public class ClientStdio {
  public static void main(String[] args) {

    var stdioParams =
        ServerParameters.builder("java")
            .args("-jar", "target/maven-tools-mcp-0.1.1-SNAPSHOT.jar")
            .build();

    var transport = new StdioClientTransport(stdioParams);
    var client = McpClient.sync(transport).build();

    client.initialize(); // List and demonstrate tools
    ListToolsResult toolsList = client.listTools();
    System.out.println("Available Tools = " + toolsList);

    // Test maven_get_latest tool
    CallToolResult latestVersionResult =
        client.callTool(
            new CallToolRequest(
                "maven_get_latest", Map.of("dependency", "org.springframework:spring-core")));
    System.out.println("Latest Version Result: " + latestVersionResult);

    // Test maven_check_exists tool
    CallToolResult versionExistsResult =
        client.callTool(
            new CallToolRequest(
                "maven_check_exists",
                Map.of("dependency", "org.springframework:spring-core", "version", "6.0.0")));
    System.out.println("Version Exists Result: " + versionExistsResult);

    // Test maven_get_stable tool with Jackson (which often has RC versions)
    CallToolResult stableVersionResult =
        client.callTool(
            new CallToolRequest(
                "maven_get_stable",
                Map.of("dependency", "com.fasterxml.jackson.core:jackson-core")));
    System.out.println("Stable Version Result: " + stableVersionResult);

    client.closeGracefully();
  }
}
