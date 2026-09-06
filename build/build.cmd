@echo off
setlocal EnableExtensions
REM Maven Tools MCP Server - Windows Build Helper
REM @author Arvind Menon
REM Resolve all paths from the script, including calls from the project root.
pushd "%~dp0.." || exit /b 1

set "choice=%~1"
if defined choice goto :process_choice
echo Maven Tools MCP Server - Windows Build Options
echo 1. Build JAR (skip tests)
echo 2. Build JAR (with tests)
echo 3. Build both native Docker images
echo 4. Build JVM Docker image
echo 5. Clean build artifacts
echo 6. Run tests only
echo 7. Build native Docker image WITHOUT Context7
echo 8. Build native Docker image with HTTP transport
set /p choice="Choose option (1-8): "

:process_choice
if "%choice%"=="1" goto :jar_fast
if "%choice%"=="2" goto :jar_tests
if "%choice%"=="3" goto :native_both
if "%choice%"=="4" goto :jvm
if "%choice%"=="5" goto :clean
if "%choice%"=="6" goto :tests
if "%choice%"=="7" goto :native_noc7
if "%choice%"=="8" goto :native_http
echo Invalid option. Please choose 1-8.
goto :error

:jar_fast
call "mvnw.cmd" clean package -DskipTests
goto :show_result

:jar_tests
call "mvnw.cmd" clean package
goto :show_result

:clean
call "mvnw.cmd" clean
goto :show_result

:tests
call "mvnw.cmd" test
goto :show_result

:native_both
call :prepare_image
if errorlevel 1 goto :error
set "SPRING_PROFILES_ACTIVE=docker"
call "mvnw.cmd" -Pnative spring-boot:build-image -Dspring-boot.build-image.imageName=maven-tools-mcp:%PROJECT_VERSION%
if errorlevel 1 goto :error
set "SPRING_PROFILES_ACTIVE=docker,no-context7"
call "mvnw.cmd" -Pnative spring-boot:build-image -Dspring-boot.build-image.imageName=maven-tools-mcp:%PROJECT_VERSION%-noc7
if errorlevel 1 goto :error
echo Built maven-tools-mcp:%PROJECT_VERSION% and maven-tools-mcp:%PROJECT_VERSION%-noc7
goto :end

:jvm
call :prepare_image
if errorlevel 1 goto :error
set "SPRING_PROFILES_ACTIVE=docker"
call "mvnw.cmd" jib:dockerBuild -Dimage=maven-tools-mcp:%PROJECT_VERSION%-jvm
if errorlevel 1 goto :error
echo Run: docker run --rm -i maven-tools-mcp:%PROJECT_VERSION%-jvm
echo Without Context7: docker run --rm -i -e SPRING_PROFILES_ACTIVE=docker,no-context7 maven-tools-mcp:%PROJECT_VERSION%-jvm
goto :end

:native_noc7
call :prepare_image
if errorlevel 1 goto :error
set "SPRING_PROFILES_ACTIVE=docker,no-context7"
call "mvnw.cmd" -Pnative spring-boot:build-image -Dspring-boot.build-image.imageName=maven-tools-mcp:%PROJECT_VERSION%-noc7
if errorlevel 1 goto :error
echo Run: docker run --rm -i maven-tools-mcp:%PROJECT_VERSION%-noc7
goto :end

:native_http
call :prepare_image
if errorlevel 1 goto :error
set "SPRING_PROFILES_ACTIVE=http"
call "mvnw.cmd" -Pnative spring-boot:build-image -Dspring-boot.build-image.imageName=maven-tools-mcp:%PROJECT_VERSION%-http
if errorlevel 1 goto :error
echo Run: docker run --rm -p 127.0.0.1:8080:8080 maven-tools-mcp:%PROJECT_VERSION%-http
echo Connect to http://localhost:8080/mcp
goto :end

:prepare_image
call "mvnw.cmd" clean package -DskipTests
if errorlevel 1 exit /b 1
set "PROJECT_VERSION="
for /f "tokens=*" %%i in ('call "mvnw.cmd" help:evaluate -Dexpression=project.version -q -DforceStdout 2^>nul') do set "PROJECT_VERSION=%%i"
if not defined PROJECT_VERSION set "PROJECT_VERSION=3.2.2"
exit /b 0

:show_result
if errorlevel 1 goto :error
echo Command completed successfully.
if exist "target\*.jar" dir /b "target\*.jar"
goto :end

:error
echo Command failed. Check the output above for details.
popd
exit /b 1

:end
popd
if "%~1"=="" pause
exit /b 0
