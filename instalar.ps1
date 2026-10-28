# Compila e instala JFR Switcher en los equipos conectados por USB.
# Uso:  .\instalar.ps1              (compila y pregunta qué instalar)
#       .\instalar.ps1 -Studio      (solo la app de la tablet)
#       .\instalar.ps1 -Cam         (solo la app del celular)
#       .\instalar.ps1 -Todo        (las dos apps)
param(
    [switch]$Studio,
    [switch]$Cam,
    [switch]$Todo,
    [switch]$SinCompilar
)

$ErrorActionPreference = "Stop"
$proyecto = $PSScriptRoot
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

if (-not $SinCompilar) {
    Write-Host "=== Compilando APK (esto tarda la primera vez) ===" -ForegroundColor Cyan
    Push-Location $proyecto
    & cmd /c "gradlew.bat :studio:assembleDebug :camera:assembleDebug --console=plain"
    if ($LASTEXITCODE -ne 0) {
        Pop-Location
        throw "La compilación falló. Revisa los mensajes de arriba."
    }
    Pop-Location
}

Write-Host "=== Equipos conectados ===" -ForegroundColor Cyan
& $adb devices
$equipos = (& $adb devices) | Select-String "device$" | ForEach-Object { ($_ -split "\s+")[0] }
if (-not $equipos) {
    Write-Host "No hay equipos autorizados." -ForegroundColor Yellow
    Write-Host "Activa 'Depuración USB' en Opciones de desarrollador y acepta el aviso en el equipo."
    exit 1
}

$apkStudio = Join-Path $proyecto "studio\build\outputs\apk\debug\studio-debug.apk"
$apkCam = Join-Path $proyecto "camera\build\outputs\apk\debug\camera-debug.apk"

foreach ($eq in $equipos) {
    $modelo = (& $adb -s $eq shell getprop ro.product.model) -join ""
    Write-Host "`n>>> Equipo $eq ($modelo)" -ForegroundColor Green

    if ($Todo -or $Studio -or (-not $Cam -and -not $Studio)) {
        Write-Host "Instalando JFR Switcher (tablet)..."
        & $adb -s $eq install -r $apkStudio
    }
    if ($Todo -or $Cam -or (-not $Cam -and -not $Studio)) {
        Write-Host "Instalando JFR Cam (celular)..."
        & $adb -s $eq install -r $apkCam
    }
}

Write-Host "`nListo. Abre 'JFR Switcher' en la tablet y 'JFR Cam' en los celulares." -ForegroundColor Cyan
