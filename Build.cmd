@echo off
cd /d "%~dp0"
call gradlew.bat build --console=plain %*
exit /b %errorlevel%
