# Corporate Certificate Guide

This guide explains how to build custom Docker images with your corporate SSL certificates for environments with SSL inspection/MITM proxies.

## Problem

Corporate networks often use SSL inspection (MITM proxies) that intercept HTTPS traffic. This requires applications to trust the corporate CA certificates. If your environment blocks outbound connections to `https://mcp.context7.com`, you have two options:

1. **Use the `-noc7` image variant** (simplest - no Context7 integration)
2. **Build a custom image with your corporate certificates** (includes Context7 with custom certs)

This guide covers option 2.

## Solution: Custom Certificate Binding

Spring Boot's Maven plugin supports [certificate bindings](https://docs.spring.io/spring-boot/maven-plugin/build-image.html) that inject your corporate certificates during the native image build process. The Paketo buildpacks automatically configure the JVM truststore with your certificates, which are then compiled into the native image.

## Prerequisites

- Docker installed and running
- Java 24
- Maven 3.9+
- Your corporate CA certificate(s) in `.crt` or `.pem` format

## Step-by-Step Instructions

### 1. Prepare Certificate Directory

Create a directory structure for your certificates:

```bash
mkdir certs
cd certs
```

Create a `type` file (required by Paketo buildpacks):

```bash
echo "ca-certificates" > type
```

Add your corporate certificate(s) to this directory:

```bash
# Copy your corporate CA certificate(s)
cp /path/to/your/corporate-ca.crt .
# You can add multiple certificates
cp /path/to/your/corporate-ca2.crt .
```

**Important:** Only include `.crt` or `.pem` certificate files. Do NOT include private key files (`.key`, `.pem` with private keys).

Your `certs/` directory should look like:

``` plaintext
certs/
├── type
├── corporate-ca.crt
└── corporate-ca2.crt (optional)
```

### 2. Configure Maven Plugin

Edit your `pom.xml` to add certificate bindings to the Spring Boot Maven plugin:

```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration>
        <image>
            <env>
                <BP_NATIVE_IMAGE>true</BP_NATIVE_IMAGE>
            </env>
            <bindings>
                <!-- Bind your certs directory to the buildpack's certificate location -->
                <binding>${project.basedir}/certs:/platform/bindings/ca-certificates</binding>
            </bindings>
        </image>
    </configuration>
</plugin>
```

### 3. Build Native Image

Build your custom native image with certificates and Context7 enabled:

```bash
./mvnw clean package -DskipTests
SPRING_PROFILES_ACTIVE=docker ./mvnw -Pnative spring-boot:build-image \
  -Dspring-boot.build-image.imageName=my-maven-tools-mcp:corporate
```

**Build time:** 10-15 minutes for native image compilation

**Note:** This builds an image WITH Context7 integration. The custom certificates allow Context7 to work through your corporate SSL inspection. If you don't need Context7 at all, use the pre-built `latest-noc7` image instead (no custom build needed).

### 4. Verify Certificate Inclusion

You can verify that the buildpack processed your certificates by checking the build output:

``` plaintext
[creator]     Paketo Buildpack for CA Certificates 3.10.4
[creator]       https://github.com/paketo-buildpacks/ca-certificates
[creator]       Launch Helper: Contributing to layer
[creator]       CA Certificates: Contributing to layer
[creator]         Added 1 additional CA certificate(s) to system truststore
```

### 5. Configure Claude Desktop

Update your Claude Desktop configuration to use the custom image:

**macOS:** `~/Library/Application Support/Claude/claude_desktop_config.json`
**Windows:** `%APPDATA%\Claude\claude_desktop_config.json`
**Linux:** `~/.config/Claude/claude_desktop_config.json`

```json
{
  "mcpServers": {
    "maven-tools": {
      "command": "docker",
      "args": [
        "run", "-i", "--rm",
        "my-maven-tools-mcp:corporate"
      ]
    }
  }
}
```

### 6. Test the Image

Test your custom image:

```bash
# Quick test - should show MCP initialization
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}' | \
  docker run -i --rm my-maven-tools-mcp:corporate
```

You should see a JSON-RPC response without SSL errors.

## How It Works

1. **Build-time Injection:** The Maven plugin binds your `certs/` directory to `/platform/bindings/ca-certificates` inside the build container
2. **Buildpack Processing:** The Paketo CA Certificates buildpack detects the binding and adds your certificates to the JVM truststore
3. **Native Compilation:** GraalVM native-image compiles the application with the updated truststore
4. **Runtime:** The native image trusts your corporate certificates without any runtime configuration

## Profiles Explained

- `docker`: Disables Spring Boot banner (required for MCP protocol)

The custom certificate build uses the `docker` profile and **enables Context7 integration**. This is the whole point - your corporate certificates allow Context7 to work through SSL inspection.

If you don't need Context7 at all, skip this custom build and use the pre-built `latest-noc7` image instead.

## Troubleshooting

### Build fails with "failed to parse certificate"

**Problem:** You likely included a private key file (`.key` or `.pem` with private keys) in the `certs/` directory.

**Solution:** Remove all private key files. Only include certificate files (`.crt` or certificate-only `.pem` files).

### Image still fails to connect to Context7

**Problem:** Your corporate proxy blocks `mcp.context7.com` entirely (domain/IP blocking, not just SSL inspection).

**Solution:** If the domain is blocked, custom certificates won't help. Use the pre-built `-noc7` image variant instead:

```bash
docker pull arvindand/maven-tools-mcp:latest-noc7
```

This image has no Context7 integration and doesn't attempt any outbound connections.

### Build takes longer than expected

**Normal:** Native image compilation takes 10-15 minutes. This is expected for GraalVM native images.

### Certificate not being picked up

**Check:**

1. Ensure `type` file contains exactly: `ca-certificates`
2. Verify certificate files are in `.crt` or `.pem` format
3. Check that the binding path in `pom.xml` is correct: `${project.basedir}/certs:/platform/bindings/ca-certificates`
4. Look for the CA Certificates buildpack output in the build logs

## Alternative: Use Pre-built `-noc7` Image

If you don't need Context7 integration, the simplest solution is to use the pre-built `-noc7` image variant:

```json
{
  "mcpServers": {
    "maven-tools": {
      "command": "docker",
      "args": [
        "run", "-i", "--rm",
        "arvindand/maven-tools-mcp:latest-noc7"
      ]
    }
  }
}
```

This image:

- ✅ Has no Context7 integration (no outbound connections to `mcp.context7.com`)
- ✅ Works in environments with SSL inspection
- ✅ Requires no custom build
- ✅ Provides all Maven dependency analysis tools

## References

- [Spring Boot Maven Plugin - Build Image](https://docs.spring.io/spring-boot/maven-plugin/build-image.html)
- [Paketo CA Certificates Buildpack](https://github.com/paketo-buildpacks/ca-certificates)
- [Paketo Service Bindings Specification](https://github.com/buildpacks/spec/blob/main/extensions/bindings.md)
