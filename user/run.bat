@echo off
chcp 65001 >nul

:: 1. バッチファイルがある "project/user" に移動
cd /d "%~dp0"

:: 2. 一つ上の "project" ディレクトリを起点にして、
::    "generated" をクラスパスに指定し、"src.Main" を呼び出す
java -cp "..\generated" src.Main

pause