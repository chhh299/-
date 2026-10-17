@echo off
chcp 65001 >nul
title 推送无障碍服务到 GitHub
cd /d "%~dp0"
echo 正在推送到 GitHub 仓库: https://github.com/chhh299/-.git ...
echo 如果弹出浏览器窗口，请点击授权登录 GitHub 即可。
echo.
git push -u origin main
echo.
if %errorlevel% equ 0 (
    echo ========================================================
    echo 【推送成功！】
    echo GitHub Actions 正在自动启动编译 (约需 1~2 分钟)...
    echo 你可以在此查看编译进度: https://github.com/chhh299/-/actions
    echo ========================================================
) else (
    echo [错误] 推送失败，请检查网络或 GitHub 登录凭据。
)
pause
