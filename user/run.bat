@echo off
chcp 65001 >nul

:: 1. スリープを一時的に無効化（AC電源時: 0は無効）
powercfg /x -standby-timeout-ac 0

:: 2. 元の処理
cd /d "%~dp0"
java -cp "..\generated" src.Main

:: 3. スリープ設定を元に戻す（例: 30分に戻す場合。元の設定値を確認しておく必要があります）
powercfg /x -standby-timeout-ac 30

pause