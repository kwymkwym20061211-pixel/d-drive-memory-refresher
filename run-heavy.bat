@echo off
chcp 65001 >nul

:: まずは重い処理であることを通知して、実行するか許可を取る。
echo ====== Heavy Refresh ======
echo [INFO] This script will perform heavy operations that may take a long time to complete.
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
call .\build.bat

:: 処理にかかった時間計測のための変数
set startTimeRunHeavy=%time%

:: その後、ハッシュ照合、ABリフレッシュ、リハッシュをこの順で行う。
call .\run.bat --check-hash --refresh --rehash

set endTimeRunHeavy=%time%

:: 異常がなければそのまま終了。異常があればエラーコードを返してpause。
if %errorlevel% NEQ 0 (
    echo [ERROR] An error occurred during the operations. Please check the logs for details. errorlevel=%errorlevel%
    pause
    exit /b %errorlevel%
)

:: 処理の開始時間と終了時間を表示
echo [INFO] Heavy Refresh completed successfully.
echo [INFO] Start Time: %startTimeRunHeavy%
echo [INFO] End Time: %endTimeRunHeavy%
