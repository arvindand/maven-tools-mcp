@echo off
REM Maven wrapper helper for Windows paths with spaces
REM This script handles the path issues that can occur with mvnw.cmd in directories with spaces
REM Falls back to system Maven if wrapper fails

REM Check for command line argument (for CI use)
if not "%1"=="" (
    set choice=%1
    echo Running in non-interactive mode with option: %choice%
    goto :process_choice
)

REM Interactive mode
echo Maven Tools MCP Server - Windows Helper
echo ==========================================

REM Check if we're in the right directory
if not exist "..\mvnw.cmd" (
    echo ❌ This script must be run from the maven-tools-mcp project root directory or scripts\ subdirectory
    echo Current directory: %CD%
    pause
    exit /b 1
)

echo Available commands:
echo 1. Build JAR (skip tests)
echo 2. Build JAR (with tests)  
echo 3. Build Native Docker images (slow, optimized - builds 2 variants)
echo 4. Build JVM Docker image (faster build)
echo 5. Clean build artifacts
echo 6. Run tests only
echo 7. Build Native Docker image WITHOUT Context7
echo.

set /p choice="Choose option (1-7): "

:process_choice
if "%choice%"=="1" (
    echo 📦 Building JAR without tests...
    call :run_maven clean package -DskipTests
    goto :show_result
)

if "%choice%"=="2" (
    echo 📦 Building JAR with tests...
    call :run_maven clean package
    goto :show_result
)

if "%choice%"=="3" (
    echo 🐳 Building Native Docker images with buildpacks...
    echo ⏳ This may take 20-25 minutes for native compilation (building 2 images)...
    echo Step 1: Package application...
    call :run_maven clean package -DskipTests
    if errorlevel 1 goto :error
    
    REM Get project version
    for /f "tokens=*" %%i in ('pushd "%~dp0.." ^& call "mvnw.cmd" help:evaluate -Dexpression=project.version -q -DforceStdout 2^>nul ^& popd') do set PROJECT_VERSION=%%i
    if "%PROJECT_VERSION%"=="" set PROJECT_VERSION=2.0.0
    
    echo.
    echo Step 2: Build Native Docker image WITH Context7...
    pushd "%~dp0.."
    set SPRING_PROFILES_ACTIVE=docker
    call "mvnw.cmd" -Pnative spring-boot:build-image -Dspring-boot.build-image.imageName=maven-tools-mcp:%PROJECT_VERSION%
    if errorlevel 1 popd & goto :error
    popd
    
    echo.
    echo Step 3: Build Native Docker image WITHOUT Context7...
    pushd "%~dp0.."
    set SPRING_PROFILES_ACTIVE=docker,no-context7
    call "mvnw.cmd" -Pnative spring-boot:build-image -Dspring-boot.build-image.imageName=maven-tools-mcp:%PROJECT_VERSION%-noc7
    if errorlevel 1 popd & goto :error
    popd
    
    echo.
    echo ✅ Native Docker images built successfully!
    echo.
    echo Two images created:
    echo   1. maven-tools-mcp:%PROJECT_VERSION% (with Context7)
    echo   2. maven-tools-mcp:%PROJECT_VERSION%-noc7 (without Context7)
    echo.
    echo 🚀 Run with Context7 enabled:
    echo    docker run -i maven-tools-mcp:%PROJECT_VERSION%
    echo.
    echo 🚀 Run without Context7:
    echo    docker run -i maven-tools-mcp:%PROJECT_VERSION%-noc7
    goto :end
)

if "%choice%"=="4" (
    echo 🐳 Building JVM Docker image with buildpacks...
    echo Step 1: Package application...
    call :run_maven clean package -DskipTests
    if errorlevel 1 goto :error
    echo Step 2: Build JVM Docker image...
    pushd "%~dp0.."
    set SPRING_PROFILES_ACTIVE=docker
    call "mvnw.cmd" spring-boot:build-image
    if errorlevel 1 popd & goto :error
    popd
    
    REM Get project version
    for /f "tokens=*" %%i in ('pushd "%~dp0.." ^& call "mvnw.cmd" help:evaluate -Dexpression=project.version -q -DforceStdout 2^>nul ^& popd') do set PROJECT_VERSION=%%i
    if "%PROJECT_VERSION%"=="" set PROJECT_VERSION=2.0.0
    
    echo ✅ JVM Docker image built successfully!
    echo.
    echo 🚀 Run with Context7 (default):
    echo    docker run -i maven-tools-mcp:%PROJECT_VERSION%
    echo.
    echo 🚀 Run without Context7 (use env vars):
    echo    docker run -i -e SPRING_AI_MCP_CLIENT_ENABLED=false ^
    echo      -e CONTEXT7_ENABLED=false ^
    echo      maven-tools-mcp:%PROJECT_VERSION%
    goto :end
)

if "%choice%"=="5" (
    echo 🧹 Cleaning build artifacts...
    call :run_maven clean
    goto :show_result
)

if "%choice%"=="6" (
    echo 🧪 Running tests...
    call :run_maven test
    goto :show_result
)

if "%choice%"=="7" (
    echo 🐳 Building Native Docker image WITHOUT Context7...
    echo ⏳ This may take 10-15 minutes for native compilation...
    echo Step 1: Package application...
    call :run_maven clean package -DskipTests
    if errorlevel 1 goto :error
    
    REM Get project version
    for /f "tokens=*" %%i in ('pushd "%~dp0.." ^& call "mvnw.cmd" help:evaluate -Dexpression=project.version -q -DforceStdout 2^>nul ^& popd') do set PROJECT_VERSION=%%i
    if "%PROJECT_VERSION%"=="" set PROJECT_VERSION=2.0.0
    
    echo.
    echo Step 2: Build Native Docker image with no-context7 profile...
    pushd "%~dp0.."
    set SPRING_PROFILES_ACTIVE=docker,no-context7
    call "mvnw.cmd" -Pnative spring-boot:build-image -Dspring-boot.build-image.imageName=maven-tools-mcp:%PROJECT_VERSION%-noc7
    if errorlevel 1 popd & goto :error
    popd
    
    echo.
    echo ✅ Native Docker image built successfully!
    echo.
    echo Image created: maven-tools-mcp:%PROJECT_VERSION%-noc7
    echo.
    echo 🚀 Run with:
    echo    docker run -i maven-tools-mcp:%PROJECT_VERSION%-noc7
    goto :end
)

echo ❌ Invalid option. Please choose 1-7.
exit /b 1

:show_result
if errorlevel 1 (
    goto :error
) else (
    echo ✅ Command completed successfully!
    
    REM Look for JAR file (similar to build.sh)
    for /f %%i in ('dir /b /s ..\target\*.jar 2^>nul ^| findstr /v ".original"') do set JAR_FILE=%%i
    if defined JAR_FILE (
        echo.
        echo JAR file created: %JAR_FILE%
        echo To run: java -jar %JAR_FILE%
    )
)
goto :end

:error
echo ❌ Command failed. Check the output above for details.
echo.
echo Common solutions:
echo - Ensure Java 24 is installed and in PATH
echo - Check internet connection for Maven dependencies
echo - Try running from a directory path without spaces
exit /b 1

:end
REM Skip pause in non-interactive mode
if not "%1"=="" exit /b 0
echo.
pause
exit /b 0

:run_maven
REM Function to run Maven - changes to parent directory and runs wrapper
echo Using Maven wrapper...
pushd "%~dp0.."
call "mvnw.cmd" %*
set MAVEN_EXIT_CODE=%errorlevel%
popd
if not %MAVEN_EXIT_CODE% equ 0 goto :maven_fallback
goto :eof

:maven_fallback
echo Maven wrapper failed, trying system Maven as fallback...
where mvn >nul 2>&1
if errorlevel 1 (
    echo ❌ No Maven found. Please install Maven or check the Maven wrapper.
    echo.
    echo The Maven wrapper has been fixed for paths with spaces.
    echo If you still see issues, try: ..\mvnw.cmd --version
    exit /b 1
)

pushd "%~dp0.."
mvn %*
set MAVEN_EXIT_CODE=%errorlevel%
popd
exit /b %MAVEN_EXIT_CODE%
