@echo off
REM Maven Tools MCP Server - Simplified Docker Build Script
REM Focuses on buildpacks with Docker Hub fallback

echo Maven Tools MCP Server - Docker Build Options
echo ==============================================

REM Check if Docker is available
docker --version >nul 2>&1
if errorlevel 1 (
    echo Docker is not installed or not in PATH
    exit /b 1
)

echo.
echo Available options:
echo 1. Use pre-built image from Docker Hub (fastest, recommended)
echo 2. Build locally with Spring Boot buildpacks (requires Maven)
echo.

REM Check if argument provided
if not "%1"=="" (
    set choice=%1
    echo Running in non-interactive mode with option: %1
) else (
    set /p choice="Choose option (1-2): "
)

if "%choice%"=="1" (
    echo Pulling pre-built image from Docker Hub...
    docker pull arvindand/maven-tools-mcp:latest
    echo Ready to use: docker run -i arvindand/maven-tools-mcp:latest
    goto :end
)

if "%choice%"=="2" (
    echo Building with Spring Boot buildpacks...
    echo This creates optimized, layered images using Cloud Native Buildpacks
    
    REM Check if Maven wrapper is available
    if not exist "..\mvnw.cmd" (
        echo Maven wrapper not found. Run this script from the build\ directory.
        exit /b 1
    )
    
    goto :build_local
)

echo Invalid option. Please choose 1 or 2.
exit /b 1

:build_local
echo Running Maven package (skipping tests for faster build)...
REM Change to parent directory to run Maven wrapper
cd /d "%~dp0.."
call "mvnw.cmd" clean package -DskipTests

if errorlevel 1 (
    echo Maven package failed
    exit /b 1
)

echo Building Docker image with buildpacks...
call "mvnw.cmd" spring-boot:build-image

if errorlevel 1 (
    echo Docker image build failed
    exit /b 1
)

REM Get project version
for /f "tokens=*" %%i in ('call "mvnw.cmd" help:evaluate -Dexpression=project.version -q -DforceStdout 2^>nul') do set PROJECT_VERSION=%%i

REM Fallback to default version if Maven command failed
if "%PROJECT_VERSION%"=="" set PROJECT_VERSION=0.1.2-SNAPSHOT

echo Built image: maven-tools-mcp:%PROJECT_VERSION%
echo Run with: docker run -i maven-tools-mcp:%PROJECT_VERSION%
goto :end

:end
echo.
echo Docker setup complete!
