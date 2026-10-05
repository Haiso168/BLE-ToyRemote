@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

echo ============================================================
echo   YCM 遥控器 —— release 签名密钥生成工具
echo ============================================================
echo.
echo 这个脚本会生成一个用于发布 App 的签名密钥（keystore），
echo 并输出 GitHub Secret 需要的 base64 内容。
echo.
echo ！！ 重要 ！！
echo   1. 生成的 .jks 文件必须长期备份（网盘 + U盘）
echo   2. 丢失后将永远无法给已安装的用户更新
echo   3. 绝对不要提交到 GitHub
echo.

set /p KEYDIR="密钥保存目录（默认 D:\AndroidKeys）: "
if "%KEYDIR%"=="" set KEYDIR=D:\AndroidKeys

set /p ALIAS="密钥别名（默认 ycm）: "
if "%ALIAS%"=="" set ALIAS=ycm

echo.
set /p PASSWORD="设置密码（storepass 与 keypass 相同）: "
if "%PASSWORD%"=="" (
    echo 错误：密码不能为空
    pause
    exit /b 1
)

if not exist "%KEYDIR%" mkdir "%KEYDIR%"

set JKS=%KEYDIR%\ycm-release.jks
if exist "%JKS%" (
    echo.
    echo 警告：%JKS% 已存在！
    set /p OVERWRITE="要覆盖它吗？覆盖会导致签名变更，已安装用户无法更新 (y/N): "
    if /i not "!OVERWRITE!"=="y" (
        echo 已取消。
        pause
        exit /b 0
    )
    del "%JKS%"
)

echo.
echo 正在生成密钥...
echo.

keytool -genkeypair -v ^
  -keystore "%JKS%" ^
  -alias "%ALIAS%" ^
  -keyalg RSA -keysize 2048 -validity 36500 ^
  -storepass "%PASSWORD%" ^
  -keypass "%PASSWORD%" ^
  -dname "CN=YCM Remote, OU=Personal, O=Personal, L=Unknown, ST=Unknown, C=CN"

if errorlevel 1 (
    echo.
    echo 生成失败。请确认已安装 JDK 且 keytool 在 PATH 中。
    pause
    exit /b 1
)

echo.
echo ============================================================
echo   生成成功
echo ============================================================
echo   文件位置：%JKS%
echo   别名    ：%ALIAS%
echo.

echo 正在生成 base64（请稍候）...
powershell -NoProfile -Command "[Convert]::ToBase64String([IO.File]::ReadAllBytes('%JKS%')) | Set-Content -Encoding ASCII '%KEYDIR%\keystore.b64'"

echo.
echo ============================================================
echo   接下来在 GitHub 仓库添加 4 个 Secret
echo   （Settings - Secrets and variables - Actions - New repository secret）
echo ============================================================
echo.
echo   KEYSTORE_BASE64    = keystore.b64 文件的全部内容
echo                        （已同时复制到剪贴板，直接粘贴即可）
echo   KEYSTORE_PASSWORD  = %PASSWORD%
echo   KEY_ALIAS          = %ALIAS%
echo   KEY_PASSWORD       = %PASSWORD%
echo.

powershell -NoProfile -Command "Get-Content -Raw '%KEYDIR%\keystore.b64' | Set-Clipboard"
echo base64 内容已复制到剪贴板。
echo.

echo 备份提醒：
echo   - 请把 %KEYDIR% 整个目录备份到网盘和 U 盘
echo   - keystore.b64 里含密钥，同样需要妥善保管
echo.

pause
