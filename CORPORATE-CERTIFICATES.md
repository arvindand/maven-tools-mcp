# Corporate Certificate Guide

This guide explains how to build custom Docker images with your corporate SSL certificates for environments with SSL inspection/MITM proxies.

## Problem

Corporate networks often use SSL inspection (MITM proxies) that intercept HTTPS traffic. This requires applications to trust the corporate CA certificates. If your environment blocks outbound connections to `https://mcp.context7.com`, you have two options:

1. **Use the `-noc7` image variant** (simplest - no Context7 integration)
2. **Build a custom image with your corporate certificates** (includes Context7 with custom certs)

This guide covers option 2 for native buildpack images. Disabling Context7 removes only calls to `mcp.context7.com`; Maven repository and optional OSV requests still need network access and trusted certificates.

## Solution: Custom Certificate Binding

Spring Boot's Maven plugin supports [certificate bindings](https://docs.spring.io/spring-boot/maven-plugin/build-image.html) that inject your corporate certificates during the native image build process. The Paketo buildpacks automatically configure the JVM truststore with your certificates, which are then compiled into the native image.

## Prerequisites

- Docker installed and running
- Java 25
- The project Maven wrapper (`./mvnw`)
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

Add the `bindings` section to the existing Spring Boot Maven plugin configuration; preserve its current environment and registry settings. `-Pnative` already sets the native-image build flag:

```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration>
        <image>
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
./mvnw clean package -Pci
SPRING_PROFILES_ACTIVE=docker ./mvnw -Pnative spring-boot:build-image \
  -Dspring-boot.build-image.imageName=my-maven-tools-mcp:corporate
```

**Build time:** Native compilation takes several minutes per variant and depends on CPU, memory and cached build layers.

**Note:** This builds a Context7-enabled image. Trusted certificates address certificate validation; they do not bypass a proxy that blocks a domain. If only Context7 is inaccessible, `latest-noc7` is sufficient. If the Maven repository or OSV is also TLS-inspected, those connections still require the corporate CA.

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

Run the repository's STDIO conformance checks against the custom image:

```bash
./mvnw verify -Pintegration -Dit.test=McpStdioConformanceIT \
  -Dmaven.tools.test.image=my-maven-tools-mcp:corporate
```

These exercise protocol framing and Maven calls. In your MCP client, also call `resolve_library_id` followed by `query_docs` to check Context7, and run a security-enabled comparison to check OSV. An `initialize` response alone does not prove that upstream TLS connections work. Unexpected launcher output on STDOUT is a protocol failure even when the process starts.

## How It Works

1. **Build-time Injection:** The Maven plugin binds your `certs/` directory to `/platform/bindings/ca-certificates` inside the build container
2. **Buildpack Processing:** The Paketo CA Certificates buildpack detects the binding and adds your certificates to the JVM truststore
3. **Native Compilation:** GraalVM native-image compiles the application with the updated truststore
4. **Runtime:** The native image trusts your corporate certificates without any runtime configuration

## Profiles Explained

- Maven `-Pnative`: enables Spring AOT and native buildpacks.
- Spring `docker`: STDIO profile used while compiling the default native image.
- Spring `docker,no-context7`: compile the native variant without Context7.
- Spring `http`: compile the native HTTP variant.

The custom certificate build uses the `docker` profile and **enables Context7 integration**. This is the whole point - your corporate certificates allow Context7 to work through SSL inspection.

Use the pre-built `latest-noc7` image when only Context7 is blocked. If the Maven repository or OSV needs a custom CA, build with the appropriate certificates even when Context7 is disabled.

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

This image makes no Context7 connections. It still contacts the configured Maven repository and OSV when security scanning is requested.

### Build takes longer than expected

**Normal:** Native compilation is CPU- and memory-intensive. Check the build logs for progress; build time varies by architecture, runner size and cache state.

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
- ✅ Avoids Context7-specific network restrictions
- ✅ Requires no custom build when the remaining upstream connections already have trusted certificates
- ✅ Provides all Maven dependency analysis tools

## JVM Builds

The local `-jvm` image uses Jib, so these Paketo bindings do not apply to it. Configure a Java truststore for the JAR/JVM runtime or use an organization-maintained Java base image with the required trust. Validate each required upstream connection rather than assuming that disabling Context7 solves all TLS failures.

## References

- [Spring Boot Maven Plugin - Build Image](https://docs.spring.io/spring-boot/maven-plugin/build-image.html)
- [Paketo CA Certificates Buildpack](https://github.com/paketo-buildpacks/ca-certificates)
- [GraalVM Native Image certificate management](https://www.graalvm.org/jdk25.1/reference-manual/native-image/dynamic-features/CertificateManagement/)
- [Paketo Service Bindings Specification](https://github.com/buildpacks/spec/blob/main/extensions/bindings.md)
