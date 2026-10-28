@echo off
chcp 65001 >nul
cd /d "%~dp0"
set "MENSAJE=%~1"
if "%MENSAJE%"=="" set "MENSAJE=Cambios desde %COMPUTERNAME% %DATE% %TIME%"
echo === Trayendo cambios de GitHub ===
git pull --rebase
if errorlevel 1 goto conflicto
echo.
echo === Guardando y subiendo ===
git add -A
git commit -m "%MENSAJE%"
echo.
git push
if errorlevel 1 goto error
echo.
echo Listo: tus cambios ya estan en GitHub.
pause
exit /b 0
:conflicto
echo.
echo [X] Hay cambios de la otra PC que chocan con los tuyos.
echo     NO borres nada. Avisa para fusionarlos juntos.
pause
exit /b 1
:error
echo.
echo [X] No se pudo subir. Revisa el mensaje de arriba.
pause
exit /b 1
