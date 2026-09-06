# Troubleshooting

This page keeps the common environment and runtime issues in one place.

## Context7 Connection Issues

### Symptom

Context7 raw tools fail, are unavailable, or return auth-related errors in Context7-enabled images.

### Likely causes

- your network blocks `https://mcp.context7.com`
- your environment requires Context7 authentication and no API key is present
- outbound connectivity is limited by a corporate proxy or firewall

### Fixes

If you do not need raw Context7 tools, use the Context7-free image:

```json
{
  "mcpServers": {
    "maven-tools": {
      "command": "docker",
      "args": ["run", "-i", "--rm", "arvindand/maven-tools-mcp:latest-noc7"]
    }
  }
}
```

If you want to keep Context7 enabled, pass `CONTEXT7_API_KEY` through Docker. The key is optional by default, but some environments may require it.

The native `-noc7` image can still return static Context7 guidance text in some analytical responses. This text does not mean a Context7 client or tool is connected; the variant omits the raw documentation tools.

## SSL Inspection / Corporate Certificates

### Symptom

SSL handshake failures or certificate trust errors.

### Likely cause

A corporate proxy is intercepting TLS with a custom CA certificate.

### Fix

Build a custom image with your corporate certificates. See [`../CORPORATE-CERTIFICATES.md`](../CORPORATE-CERTIFICATES.md).

## Slow First Query

### Symptom

The first request takes noticeably longer than later requests.

### Cause

Cold start plus the first uncached Maven Central request.

### Expected behavior

This is normal. After warm-up, repeated queries should be much faster because responses are cached.

## Docker Permission Issues

### Symptom

`permission denied` when the client tries to run Docker.

### Fix

On Linux:

```bash
sudo usermod -aG docker $USER
```

Then sign out and back in.

On macOS and Windows, make sure Docker Desktop is installed and running.

## MCP Client Startup Issues

If a desktop client cannot connect:

- confirm the Docker image tag exists locally or can be pulled
- verify the client config points to the right transport (`:latest` for stdio, `:latest-http` for HTTP)
- if using HTTP, check the health endpoints first
- if using stdio, make sure the MCP client is not wrapping the command in a shell that changes stdin/stdout behavior

## Private Repository Redirects or Authentication Errors

Use the final repository URL, including its repository path. Repository/API redirects are rejected, and authenticated requests cannot switch origin. Repository credentials are deliberately absent from OSV requests. Maven `settings.xml`, mirrors and repositories declared inside submitted POMs do not configure this server; use the environment properties in [setup](setup.md#private-repository-authentication).

## POM Limits and Profile Warnings

Split very large sideloaded bundles and remove DTD declarations. The resolver enforces document, nesting, expansion and total-model budgets; see [architecture](architecture.md#caches-and-input-limits).

A warning about environment activation can refer to a fetched parent or imported BOM. For example, Spring Boot can import an Infinispan BOM containing a `community` profile even when your input has no profiles. Such warnings indicate a limited activation context, not leaked input from another request. Review unresolved or profile-dependent versions against your real Maven build before applying changes.

## JVM Image Starts but the MCP Client Cannot Parse Responses

Build the JVM image with `./build/build-docker.sh 3` (Jib). A direct JVM `spring-boot:build-image` can include Paketo launcher diagnostics on STDOUT before Java starts; application log settings do not suppress them. Native images use the `-Pnative` buildpack path. Every STDIO line must be a JSON-RPC message.

## Docker Build Reports "username must not be null"

An empty Docker Hub authentication entry can cause this error in Spring Boot's build-image plugin. Inspect the selected Docker context and authentication configuration. For anonymous builds of public images, use an isolated temporary `DOCKER_CONFIG` with `{"auths":{}}` and an explicit `DOCKER_HOST`. Keep the normal authenticated configuration for private pulls and publishing.

## Related Docs

- [`setup.md`](setup.md)
- [`architecture.md`](architecture.md)
