@echo off
setlocal EnableExtensions
REM Maven Tools MCP Server - Docker Build Helper
REM @author Arvind Menon
REM Share build behavior with build.cmd so image tags and profiles stay consistent.
docker --version >nul 2>&1
if errorlevel 1 (
    echo Docker is not installed or not in PATH.
    exit /b 1
)
set "choice=%~1"
if defined choice goto :process_choice
echo 1. Pull both published native images
echo 2. Build both native images locally
echo 3. Build JVM image locally
set /p choice="Choose option (1-3): "

:process_choice
if "%choice%"=="1" goto :pull
if "%choice%"=="2" goto :native
if "%choice%"=="3" goto :jvm
echo Invalid option. Please choose 1-3.
exit /b 1

:pull
docker pull arvindand/maven-tools-mcp:latest
if errorlevel 1 exit /b 1
docker pull arvindand/maven-tools-mcp:latest-noc7
exit /b %errorlevel%

:native
call "%~dp0build.cmd" 3
exit /b %errorlevel%

:jvm
call "%~dp0build.cmd" 4
exit /b %errorlevel%
