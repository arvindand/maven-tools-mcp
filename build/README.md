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
  - Build JVM Image with buildpacks (faster build, larger image)

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

## Output Location

## Build Outputs

Built JAR files are placed in: `target/maven-tools-mcp-2.0.1.jar`

## Native Image Builds

The project now builds **Native Images** by default using GraalVM and Spring Boot 3.5.9's built-in native profile.

### Build Commands

```bash
# Build native Docker image
./mvnw -Pnative spring-boot:build-image

# Build JVM Docker image 
./mvnw spring-boot:build-image
```

### CI/CD Integration

- GitHub Actions automatically builds native images on push to main/develop
- Published to Docker Hub as `arvindand/maven-tools-mcp:latest`
