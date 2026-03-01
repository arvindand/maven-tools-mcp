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

## Related Docs

- [`setup.md`](setup.md)
- [`architecture.md`](architecture.md)
