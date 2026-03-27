@echo off
rem ↑ 先頭に空行や rem を入れると文字化けの直撃を避けやすいです
chcp 65001 >nul
setlocal enabledelayedexpansion

:: 1. 基準パスの設定
set "BASE_DIR=%~dp0"
set "SRC_DIR=%BASE_DIR%src"
set "OUT_DIR=%BASE_DIR%generated"
set "TEMP_LIST=%BASE_DIR%sources_list.txt"

echo [INFO] ビルドを開始します...

:: 2. 出力先のクリーンアップ
if exist "%OUT_DIR%" (
    rd /s /q "%OUT_DIR%"
)
mkdir "%OUT_DIR%"

:: 3. ファイルリスト作成
dir /s /b "%SRC_DIR%\*.java" > "%TEMP_LIST%" 2>nul

:: 4. コンパイル
echo [INFO] コンパイルを実行中...
javac -d "%OUT_DIR%" @"%TEMP_LIST%"

if %ERRORLEVEL% EQU 0 (
    echo [SUCCESS] ビルドが正常に完了しました。
) else (
    echo [ERROR] コンパイルエラーが発生しました。
)

if exist "%TEMP_LIST%" del "%TEMP_LIST%"
pause