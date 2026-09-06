# Build Scripts

This directory contains convenient build scripts for the Maven Tools MCP Server project.

## Scripts Overview

### Linux/macOS Scripts

- **`build.sh`** - Complete build helper with options for:
  - Build JAR (skip tests) - Fast development builds
  - Build JAR (with tests) - Full validation builds  
  - Build Native Docker image - Optimized native executable (slow build)
  - Build JVM Docker image - Traditional JVM build (faster build)
  - Clean build artifacts - Reset build state
  - Run tests only - Validation without building

- **`build-docker.sh`** - Docker-focused build options:
  - Pull from Docker Hub (fastest, recommended) - Pre-built native images
  - Build Native Image with buildpacks (optimized native executable, slow build)
  - Build JVM Image with Jib (faster build, larger image)

### Windows Scripts

- **`build.cmd`** - Windows equivalent of `build.sh` with proper path handling for spaces
- **`build-docker.cmd`** - Windows equivalent of `build-docker.sh`

## Usage

From the project root directory:

```bash
# Linux/macOS
./build/build.sh
./build/build-docker.sh

# Windows
.\build\build.cmd
.\build\build-docker.cmd
```

Or run directly from the `build/` directory:

```bash
# Unix/Linux
./build.sh

# Windows
.\build.cmd
```

### Non-Interactive Mode

Scripts support non-interactive mode for CI/CD by passing the option number:

```bash
# Unix/Linux
./build.sh 1

# Windows  
.\build.cmd 1
```

## Build Outputs

Built JAR files are placed in: `target/maven-tools-mcp-<version>.jar`

## Native Image Builds

Native image builds use GraalVM and Spring Boot 4.1's `native` profile. The helpers use Jib for JVM images, because Paketo JVM launch helpers write diagnostics to STDOUT and corrupt MCP framing. Direct `spring-boot:build-image` without `-Pnative` still selects a JVM buildpack image, but is unsuitable for MCP STDIO. The scripts use distinct tags: `<version>` (native), `<version>-noc7` (native without Context7), `<version>-http` (native HTTP), and `<version>-jvm`.

### Build Commands

```bash
# Build native Docker image
./mvnw -Pnative spring-boot:build-image

# Build JVM Docker image 
./build/build.sh 4
```

### CI/CD Integration

- GitHub Actions validates changes and publishes images only on release tag pushes.
- Released images are published to Docker Hub as `arvindand/maven-tools-mcp:<version>` and the corresponding `latest` variant tags.
- Local build scripts do not publish images.

### Build and run every local variant

Run from the project root (Windows uses the matching `.cmd` scripts):

```bash
./build/build.sh 2          # JAR with unit tests
./build/build-docker.sh 2   # native STDIO, with and without Context7
./build/build.sh 8          # native HTTP
./build/build-docker.sh 3   # JVM image via Jib
```

JAR profiles are selected at runtime:

```bash
java -jar target/maven-tools-mcp-<version>.jar
java -jar target/maven-tools-mcp-<version>.jar --spring.profiles.active=no-context7
java -jar target/maven-tools-mcp-<version>.jar --spring.profiles.active=http
```

Native profiles are selected during AOT compilation. Use the separately built `-noc7` and `-http` images for those modes. JVM images support `SPRING_PROFILES_ACTIVE=docker,no-context7` at runtime.

For local HTTP testing, bind Docker's published port to localhost:

```bash
docker run --rm -p 127.0.0.1:8080:8080 maven-tools-mcp:<version>-http
```

Jib uses the maintained Eclipse Temurin Java 25 JRE image, runs as an unprivileged user, and launches Java directly. The architecture defaults to AMD64 and selects ARM64 on AArch64 hosts; use `-Djib.architecture=arm64` or `amd64` with Maven when targeting a different Docker host.
