# Build Scripts

This directory contains convenient build scripts for the Maven Tools MCP Server project.

## Scripts Overview

### Linux/macOS Scripts

- **`build.sh`** - Complete build helper with options for:
  - Build JAR (skip tests) - Fast development builds
  - Build JAR (with tests) - Full validation builds  
  - Build Docker image - Local Docker builds with buildpacks
  - Clean build artifacts - Reset build state
  - Run tests only - Validation without building

- **`build-docker.sh`** - Docker-focused build options:
  - Pull from Docker Hub (fastest, recommended)
  - Build with buildpacks (optimized local build using Spring Boot buildpacks)

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

Built JAR files are placed in: `target/maven-tools-mcp-0.1.2-SNAPSHOT.jar`
