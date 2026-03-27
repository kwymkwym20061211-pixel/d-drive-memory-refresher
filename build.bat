@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

:: 1. 基準となるディレクトリをこのファイルがある場所に設定
set BASE_DIR=%~dp0
set SRC_DIR=%BASE_DIR%src
set OUT_DIR=%BASE_DIR%generated
set TEMP_LIST=%BASE_DIR%sources_list.txt

echo [INFO] ビルドを開始します...

:: 2. 出力先のクリーンアップ
if exist "%OUT_DIR%" (
    rd /s /q "%OUT_DIR%"
)
mkdir "%OUT_DIR%"

:: 3. src 以下のすべての .java ファイルを再帰的にリスト化
:: /s でサブディレクトリ含め、/b でフルパスのみ抽出
dir /s /b "%SRC_DIR%\*.java" > "%TEMP_LIST%" 2>nul

:: 4. ファイルが存在するかチェックしてコンパイル
for %%i in ("%TEMP_LIST%") do if %%~zi equ 0 (
    echo [ERROR] Javaファイルが見つかりませんでした。srcフォルダを確認してください。
    del "%TEMP_LIST%"
    pause
    exit /b
)

echo [INFO] コンパイルを実行中...
javac -d "%OUT_DIR%" @"%TEMP_LIST%"

if %ERRORLEVEL% EQU 0 (
    echo [SUCCESS] ビルドが正常に完了しました。
    echo 出力先: %OUT_DIR%
) else (
    echo [ERROR] コンパイル中にエラーが発生しました。
)

:: 5. 後片付け
if exist "%TEMP_LIST%" del "%TEMP_LIST%"

pause