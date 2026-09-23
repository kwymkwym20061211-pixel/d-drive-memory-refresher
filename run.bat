@echo off
chcp 65001 >nul

:: 処理
cd /d "%~dp0"
java -cp ".\generated" src.Main %*

pause