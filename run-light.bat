@echo off
chcp 65001 >nul

:: まずは重い処理であることを通知して、実行するか許可を取る。lightバージョンとはいえ重め。
echo ====== Light Refresh ======
echo [INFO] This script will perform a light version of heavy operations that may take a long time to complete.
set /p userInput=Do you want to continue? (Y/N):

:: ユーザーの入力を確認。明確に賛成されなければ拒否判定
if /I "%userInput%" NEQ "Y" (
    echo [INFO] Operation cancelled by user.
    exit /b
)

:: 拒否されなければ実行開始。

:: まずカレントディレクトリ調整
cd /d "%~dp0"

:: ビルドしてバイナリを最新版にする。
.\build.bat

:: その後、ハッシュ照合、リハッシュをこの順で行う。ハッシュ照合するときにロードするのでローダーの実行は不要。
.\run.bat --check-hash --rehash

:: 異常がなければそのまま終了。異常があればエラーコードを返してpause。
if %errorlevel% NEQ 0 (
    echo [ERROR] An error occurred during the operations. Please check the logs for details. errorlevel=%errorlevel%
    pause
    exit /b %errorlevel%
)