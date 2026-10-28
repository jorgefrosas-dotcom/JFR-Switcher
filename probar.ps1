# Compila las dos apps y las instala en la tablet y en el celular conectados.
#
# Uso:  doble clic en PROBAR.cmd  (o:  powershell -ExecutionPolicy Bypass -File probar.ps1)
#
# No sube nada a GitHub ni cambia la versión: sirve para probar cambios pequeños en los equipos.
# Busca solo los dispositivos (por WiFi o por cable) y elige la tablet por su modelo.

$ErrorActionPreference = "Stop"
$raiz = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }

function Aviso($texto, $color = "Yellow") { Write-Host $texto -ForegroundColor $color }

# --- 1. Java y SDK -------------------------------------------------------------------------
if (-not $env:JAVA_HOME) {
    $jbr = "C:\Program Files\Android\Android Studio\jbr"
    if (Test-Path $jbr) { $env:JAVA_HOME = $jbr } else { Aviso "[!] No encuentro JAVA_HOME ni Android Studio." }
}
$sdk = $null
$props = Join-Path $raiz "local.properties"
if (Test-Path $props) {
    $linea = Get-Content $props | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if ($linea) { $sdk = ($linea -replace '^sdk\.dir=', '') -replace '\\:', ':' -replace '\\\\', '\' }
}
if (-not $sdk -or -not (Test-Path $sdk)) { $sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$adb = Join-Path $sdk "platform-tools\adb.exe"
if (-not (Test-Path $adb)) { Aviso "[X] No encuentro adb en $sdk"; exit 1 }

# --- 2. Compilar ---------------------------------------------------------------------------
Write-Host ""
Aviso "=== Compilando las dos apps (release) ===" Cyan
Push-Location $raiz
& ".\gradlew.bat" :studio:assembleRelease :camera:assembleRelease --console=plain
$compilo = $LASTEXITCODE
Pop-Location
if ($compilo -ne 0) { Aviso "[X] Falló la compilación. Revisa el mensaje de arriba." Red; exit 1 }

$apkTablet = Join-Path $raiz "studio\build\outputs\apk\release\studio-release.apk"
$apkCel    = Join-Path $raiz "camera\build\outputs\apk\release\camera-release.apk"
foreach ($a in @($apkTablet, $apkCel)) {
    if (-not (Test-Path $a)) { Aviso "[X] No se generó $a" Red; exit 1 }
}

# --- 3. Buscar equipos ---------------------------------------------------------------------
Write-Host ""
Aviso "=== Buscando equipos conectados ===" Cyan
& $adb start-server | Out-Null
& $adb mdns check | Out-Null
$lineas = & $adb devices -l | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice\s' }
if (-not $lineas) {
    Aviso "[!] No hay ningún equipo conectado."
    Aviso "    Tablet: revisa que esté en la misma WiFi y con depuración inalámbrica activa."
    Aviso "    Celular: conéctalo por USB o por WiFi."
    Aviso "    Ayuda:  adb mdns services   y luego   adb connect <ip>:<puerto>"
    exit 1
}

$equipos = @()
foreach ($l in $lineas) {
    $serial = ($l -split '\s+')[0]
    $modelo = if ($l -match 'model:(\S+)') { $Matches[1] } else { "?" }
    $equipos += [pscustomobject]@{ Serial = $serial; Modelo = $modelo }
    Write-Host ("  - {0}  ({1})" -f $serial, $modelo)
}

$tablet = $equipos | Where-Object { $_.Modelo -match 'KINGKONG|TAB' } | Select-Object -First 1
$celular = $equipos | Where-Object { $_.Serial -ne $tablet.Serial } | Select-Object -First 1

# --- 4. Instalar ---------------------------------------------------------------------------
function Instalar($serial, $apk, $nombre, $esCelular) {
    Write-Host ""
    Aviso "=== Instalando $nombre en $serial ===" Cyan
    if ($esCelular) {
        # MIUI/HyperOS rechaza las instalaciones por adb; con `-i com.android.vending` sí las acepta.
        & $adb -s $serial install -r -i com.android.vending -g $apk
    } else {
        & $adb -s $serial install -r $apk
    }
    if ($LASTEXITCODE -ne 0) {
        Aviso "[!] No se pudo instalar en $serial." Yellow
        if ($esCelular) { Aviso "    En Xiaomi: toca el APK desde Archivos, o activa «Instalar por USB»." Yellow }
        return $false
    }
    return $true
}

$okTablet = $false
$okCel = $false
if ($tablet) { $okTablet = Instalar $tablet.Serial $apkTablet "JFR Switcher" $false }
if ($celular) { $okCel = Instalar $celular.Serial $apkCel "JFR Camera" $true }

# --- 5. Resumen ----------------------------------------------------------------------------
Write-Host ""
Aviso "=== Versiones instaladas ===" Cyan
if ($tablet) {
    $v = (& $adb -s $tablet.Serial shell "dumpsys package com.jfrmedia.switcher.studio | grep versionName") -join " "
    Write-Host ("  Tablet  {0}: {1}" -f $tablet.Modelo, ($v -replace '\s+', ' ').Trim())
}
if ($celular) {
    $v = (& $adb -s $celular.Serial shell "dumpsys package com.jfrmedia.switcher.cam | grep versionName") -join " "
    Write-Host ("  Celular {0}: {1}" -f $celular.Modelo, ($v -replace '\s+', ' ').Trim())
}
Write-Host ""
if ($okTablet -or $okCel) {
    Aviso "Listo. Esto NO toca GitHub ni cambia la versión: es solo para probar." Green
} else {
    Aviso "[X] No se instaló nada." Red
}
