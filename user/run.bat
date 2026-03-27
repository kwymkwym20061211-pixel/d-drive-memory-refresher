@echo off
chcp 65001 >nul
setlocal

:: 1. 基準となるディレクトリを設定（user/ の一つ上がプロジェクトルート）
set PROJECT_ROOT=%~dp0..
set CLASSPATH=%PROJECT_ROOT%\generated

echo [INFO] D-Drive Memory Refresher を起動しています...
echo [INFO] Classpath: %CLASSPATH%

:: 2. Javaの実行
:: パッケージ名を含めた完全修飾名 (src.Main) で指定します
java -cp "%CLASSPATH%" src.Main

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] 実行中にエラーが発生しました（終了コード: %ERRORLEVEL%）
    pause
)

endlocal