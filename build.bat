@echo off
REM
REM JVMMonitor - Build script (Windows)
REM Produces: jvmmonitor.dll + jvmmonitor.jar
REM
REM Prerequisites (all free):
REM   - MinGW-w64: https://www.mingw-w64.org/ (or via MSYS2: pacman -S mingw-w64-x86_64-gcc)
REM   - CMake:     https://cmake.org/download/
REM   - JDK:       https://adoptium.net/
REM   - Maven:     https://maven.apache.org/download.cgi
REM
REM All must be on PATH. With MSYS2 the typical PATH addition is:
REM   set PATH=C:\msys64\mingw64\bin;%PATH%
REM

setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
set BUILD_DIR=%SCRIPT_DIR%build-win
set DIST_DIR=%SCRIPT_DIR%dist\windows

echo === JVMMonitor Build (Windows) ===

REM Check MinGW
where gcc >nul 2>nul
if errorlevel 1 (
    echo ERROR: gcc not found on PATH.
    echo Install MinGW-w64 via MSYS2:
    echo   1. Download MSYS2 from https://www.msys2.org/
    echo   2. Open MSYS2 terminal and run: pacman -S mingw-w64-x86_64-gcc mingw-w64-x86_64-cmake
    echo   3. Add C:\msys64\mingw64\bin to your PATH
    exit /b 1
)

REM Check CMake
where cmake >nul 2>nul
if errorlevel 1 (
    echo ERROR: cmake not found on PATH.
    echo Download from https://cmake.org/download/ or: pacman -S mingw-w64-x86_64-cmake
    exit /b 1
)

REM 1. Build native agent with MinGW
echo Building native agent (MinGW)...
if not exist "%BUILD_DIR%" mkdir "%BUILD_DIR%"
cmake -B "%BUILD_DIR%" -G "MinGW Makefiles" -DCMAKE_BUILD_TYPE=Release "%SCRIPT_DIR%"
if errorlevel 1 (
    echo ERROR: CMake configure failed.
    exit /b 1
)
cmake --build "%BUILD_DIR%" --config Release
if errorlevel 1 (
    echo ERROR: Native agent build failed.
    exit /b 1
)

REM 2. Build Java collector
echo Building Java collector...
cd "%SCRIPT_DIR%collector"
call mvn clean package -q
if errorlevel 1 (
    echo ERROR: Java collector build failed.
    exit /b 1
)
cd "%SCRIPT_DIR%"

REM 3. Assemble dist
echo Assembling distribution...
if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"

REM Find the agent DLL
set AGENT_DLL=
for /r "%BUILD_DIR%" %%f in (jvmmonitor.dll) do set AGENT_DLL=%%f
if "!AGENT_DLL!"=="" (
    echo WARNING: jvmmonitor.dll not found. Check build output.
) else (
    copy "!AGENT_DLL!" "%DIST_DIR%\jvmmonitor.dll" >nul
    echo   Agent: %DIST_DIR%\jvmmonitor.dll
)

REM Copy collector JAR
copy "%SCRIPT_DIR%collector\target\jvmmonitor-collector-*-SNAPSHOT.jar" "%DIST_DIR%\jvmmonitor.jar" >nul 2>nul

echo.
echo === Build complete ===
echo   Agent:     %DIST_DIR%\jvmmonitor.dll
echo   Collector: %DIST_DIR%\jvmmonitor.jar
echo.
echo Usage:
echo   java -jar %DIST_DIR%\jvmmonitor.jar --attach ^<PID^>
echo   # or
echo   java -agentpath:%DIST_DIR%\jvmmonitor.dll=host=127.0.0.1,port=9090 -jar your-app.jar

endlocal
