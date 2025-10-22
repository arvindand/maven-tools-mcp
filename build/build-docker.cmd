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
echo 1. Use pre-built native image from Docker Hub (fastest, recommended)
echo 2. Build locally with Spring Boot buildpacks - Native Image (requires Maven + time)
echo 3. Build locally with Spring Boot buildpacks - JVM Image (faster build)
echo.

REM Check if argument provided
if not "%1"=="" (
    set choice=%1
    echo Running in non-interactive mode with option: %1
) else (
    set /p choice="Choose option (1-3): "
)

if "%choice%"=="1" (
    echo Pulling pre-built native images from Docker Hub...
    
    echo Pulling image WITH Context7...
    docker pull arvindand/maven-tools-mcp:latest
    
    echo.
    echo Pulling image WITHOUT Context7 (noc7)...
    docker pull arvindand/maven-tools-mcp:latest-noc7
    
    echo.
    echo Two images are now available:
    echo   1. With Context7:    docker run -i arvindand/maven-tools-mcp:latest
    echo   2. Without Context7: docker run -i arvindand/maven-tools-mcp:latest-noc7
    goto :end
)

if "%choice%"=="2" (
    echo Building Native Images with Spring Boot buildpacks...
    echo This creates highly optimized native executables but takes longer to build
    echo Building TWO images: with and without Context7
    
    REM Check if Maven wrapper is available
    if not exist "..\mvnw.cmd" (
        echo Maven wrapper not found. Run this script from the build\ directory.
        exit /b 1
    )
    
    goto :build_native
)

if "%choice%"=="3" (
    echo Building JVM Image with Spring Boot buildpacks...
    echo This creates optimized, layered JVM images using Cloud Native Buildpacks
    
    REM Check if Maven wrapper is available
    if not exist "..\mvnw.cmd" (
        echo Maven wrapper not found. Run this script from the build\ directory.
        exit /b 1
    )
    
    goto :build_jvm
)

echo Invalid option. Please choose 1, 2, or 3.
exit /b 1

:build_native
echo Running Maven package (skipping tests for faster build)...
REM Change to parent directory to run Maven wrapper
cd /d "%~dp0.."
call "mvnw.cmd" clean package -DskipTests

if errorlevel 1 (
    echo Maven package failed
    exit /b 1
)

REM Get project version
for /f "tokens=*" %%i in ('call "mvnw.cmd" help:evaluate -Dexpression=project.version -q -DforceStdout 2^>nul') do set PROJECT_VERSION=%%i
if "%PROJECT_VERSION%"=="" set PROJECT_VERSION=1.5.1

echo.
echo Building native image WITH Context7...
set SPRING_PROFILES_ACTIVE=docker
call "mvnw.cmd" -Pnative spring-boot:build-image -Dspring-boot.build-image.imageName=maven-tools-mcp:%PROJECT_VERSION%

if errorlevel 1 (
    echo Native Docker image build failed
    exit /b 1
)

echo.
echo Building native image WITHOUT Context7 (noc7)...
set SPRING_PROFILES_ACTIVE=docker,no-context7
call "mvnw.cmd" -Pnative spring-boot:build-image -Dspring-boot.build-image.imageName=maven-tools-mcp:%PROJECT_VERSION%-noc7

if errorlevel 1 (
    echo Native Docker image build failed
    exit /b 1
)

echo.
echo Built TWO native images:
echo   1. maven-tools-mcp:%PROJECT_VERSION% (with Context7)
echo   2. maven-tools-mcp:%PROJECT_VERSION%-noc7 (without Context7)
echo.
echo Run with Context7:
echo    docker run -i maven-tools-mcp:%PROJECT_VERSION%
echo.
echo Run without Context7:
echo    docker run -i maven-tools-mcp:%PROJECT_VERSION%-noc7
goto :end

:build_jvm
echo Running Maven package (skipping tests for faster build)...
REM Change to parent directory to run Maven wrapper
cd /d "%~dp0.."
call "mvnw.cmd" clean package -DskipTests

if errorlevel 1 (
    echo Maven package failed
    exit /b 1
)

echo Building JVM Docker image with buildpacks...
set SPRING_PROFILES_ACTIVE=docker
call "mvnw.cmd" spring-boot:build-image

if errorlevel 1 (
    echo JVM Docker image build failed
    exit /b 1
)

REM Get project version
for /f "tokens=*" %%i in ('call "mvnw.cmd" help:evaluate -Dexpression=project.version -q -DforceStdout 2^>nul') do set PROJECT_VERSION=%%i
if "%PROJECT_VERSION%"=="" set PROJECT_VERSION=1.5.1

echo.
echo Built JVM image: maven-tools-mcp:%PROJECT_VERSION%
echo.
echo Run with Context7 (default):
echo    docker run -i maven-tools-mcp:%PROJECT_VERSION%
echo.
echo Run without Context7 (use env vars):
echo    docker run -i -e SPRING_AI_MCP_CLIENT_ENABLED=false ^
echo      -e CONTEXT7_ENABLED=false ^
echo      maven-tools-mcp:%PROJECT_VERSION%
goto :end

:end
echo.
echo Docker setup complete!
