@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo === Trayendo cambios de GitHub ===
git pull --rebase
if errorlevel 1 goto error
echo.
echo Listo: ya tienes lo ultimo de la otra PC.
pause
exit /b 0
:error
echo.
echo [X] Hubo un problema. NO borres nada: avisa y ensena este mensaje.
pause
exit /b 1
