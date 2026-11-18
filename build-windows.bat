@echo off
cd /d "%~dp0mod"
java -version 2>&1 | findstr /C:"25." >nul
if errorlevel 1 (
  echo Java 25 is required. Select or install JDK 25.
  exit /b 1
)
gradle clean build
if errorlevel 1 exit /b 1
echo Build complete. See mod\build\libs\
