# Genera studio/src/main/assets/iconos_pokemon.json
#
# Los iconos de Pokémon de Limitless existen uno por uno
# (https://r2.limitlesstcg.net/pokemon/gen9/<slug>.png) pero el servidor NO publica un listado.
# Así que este script toma todos los nombres de PokeAPI (que sí incluye las formas: mega, alola,
# hisui, paldea…) y verifica cuáles tienen icono en Limitless. El resultado se empaqueta dentro del
# APK para que la app pueda buscar por nombre sin depender de la red al abrir el buscador.
#
# Uso:  powershell -File herramientas\generar-iconos-pokemon.ps1 [-Paralelo 8]

param(
    [int]$Paralelo = 8,
    [string]$Salida = "studio\src\main\assets\iconos_pokemon.json",
    [string]$Cache = "$env:TEMP\jfr-iconos-cache.json"
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$raiz = if ($PSScriptRoot) { Split-Path $PSScriptRoot -Parent } else { (Get-Location).Path }

# --- 1. Lista de Pokémon (con formas) ---
$lista = (Invoke-RestMethod "https://pokeapi.co/api/v2/pokemon?limit=3000" -TimeoutSec 90).results |
    ForEach-Object { $_.name }
Write-Host "nombres de PokeAPI: $($lista.Count)"

# --- 2. Verificar cuáles tienen icono en Limitless ---
function Existe([string]$slug) {
    try {
        $r = Invoke-WebRequest "https://r2.limitlesstcg.net/pokemon/gen9/$slug.png" `
            -UseBasicParsing -Method Head -TimeoutSec 12
        return ($r.StatusCode -eq 200)
    } catch { return $false }
}

$base = "https://r2.limitlesstcg.net/pokemon/gen9"
$encontrados = New-Object System.Collections.ArrayList
$vistos = 0
foreach ($slug in $lista) {
    $vistos++
    try {
        $r = Invoke-WebRequest "$base/$slug.png" -UseBasicParsing -Method Head -TimeoutSec 12
        if ($r.StatusCode -eq 200) { [void]$encontrados.Add([pscustomobject]@{ slug = $slug }) }
    } catch { }
    if ($vistos % 100 -eq 0) { Write-Host "  revisados $vistos / $($lista.Count)  (con icono: $($encontrados.Count))" }
}
Write-Host "con icono en Limitless: $($encontrados.Count)"

# --- 3. Nombre legible para mostrar en el buscador ---
function NombreBonito([string]$slug) {
    $partes = $slug -split "-"
    $prefijo = ""
    $resto = @()
    foreach ($p in $partes) {
        switch ($p) {
            "mega"   { if ($prefijo -eq "") { $prefijo = "Mega " } else { $resto += "Mega" } }
            "alola"  { $prefijo = "Alolan " }
            "galar"  { $prefijo = "Galarian " }
            "hisui"  { $prefijo = "Hisuian " }
            "paldea" { $prefijo = "Paldean " }
            "gmax"   { $resto += "Gmax" }
            "ex"     { $resto += "ex" }
            "gx"     { $resto += "GX" }
            "v"      { $resto += "V" }
            "vmax"   { $resto += "VMAX" }
            default  { $resto += (Get-Culture).TextInfo.ToTitleCase($p) }
        }
    }
    return ($prefijo + ($resto -join " ")).Trim()
}

$iconos = $encontrados |
    ForEach-Object { [pscustomobject]@{ n = (NombreBonito $_.slug); s = $_.slug } } |
    Sort-Object n |
    Select-Object -Unique n, s

New-Item -ItemType Directory -Force -Path (Split-Path (Join-Path $raiz $Salida) -Parent) | Out-Null
ConvertTo-Json -InputObject @($iconos) -Depth 3 -Compress |
    Set-Content -Path (Join-Path $raiz $Salida) -Encoding UTF8
Write-Host "escrito: $(Join-Path $raiz $Salida)  ($($iconos.Count) iconos)"
