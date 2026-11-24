# Genera studio/src/main/assets/mazos_extra.json
#
# Saca los mazos del Estándar que aparecen en los torneos de play.limitlesstcg.com y se queda con
# los que NO están en el top que publica limitlesstcg.com/decks (ese top son solo ~29 arquetipos).
# Los iconos son los mismos sprites de Limitless y se verifican uno por uno antes de escribirlos.
#
# Uso:  powershell -File herramientas\generar-mazos-extra.ps1 [-Cuantos 50] [-Torneos 40]

param(
    [int]$Cuantos = 50,
    [int]$Torneos = 40,
    [string]$Salida = "studio\src\main\assets\mazos_extra.json",
    [string]$Cache = "$env:TEMP\jfr-mazos-cache.json",
    [switch]$Forzar
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$h = @{ "User-Agent" = "Mozilla/5.0"; "Accept" = "application/json" }
$raiz = Split-Path $PSScriptRoot -Parent

function Norm([string]$s) { ($s.ToLowerInvariant() -replace "[^a-z0-9]", "") }

# 1. El top oficial del sitio (para no repetir lo que ya viene de ahí).
$oficial = @()
foreach ($pagina in 1, 2) {
    $html = (Invoke-WebRequest "https://limitlesstcg.com/decks?page=$pagina" -UseBasicParsing -TimeoutSec 60).Content
    foreach ($fila in [regex]::Matches($html, "(?s)<tr>(.*?)</tr>")) {
        $t = $fila.Groups[1].Value
        $a = [regex]::Match($t, '(?s)<a href="/decks/(\d+)">(.*?)</a>')
        if (-not $a.Success) { continue }
        $iconos = [regex]::Matches($t, 'src="(https://r2\.limitlesstcg\.net/pokemon/[^"]+)"') |
            ForEach-Object { ($_.Groups[1].Value -split "/")[-1] -replace "\.png$", "" }
        $oficial += [pscustomobject]@{
            id     = $a.Groups[1].Value
            nombre = (($a.Groups[2].Value -replace "<[^>]+>", "" -replace "\s+", " ").Trim())
            slugs  = @($iconos)
        }
    }
}
Write-Host "top oficial del sitio: $($oficial.Count) mazos"

# 2. Los mazos que aparecen de verdad en los torneos (API de Limitless).
#    La API corta con 429 si se le pide muy seguido, así que se guarda la muestra en un archivo y se
#    reutiliza: para volver a bajarla, -Forzar.
$cuenta = @{}
if ((Test-Path $Cache) -and (-not $Forzar)) {
    $guardado = Get-Content $Cache -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($m in $guardado) {
        $cuenta[$m.id] = [pscustomobject]@{ id = $m.id; nombre = $m.nombre; slugs = @($m.slugs); n = $m.n }
    }
    Write-Host "muestra leída de la caché: $($cuenta.Count) arquetipos ($Cache)"
} else {
    $todos = Invoke-RestMethod "https://play.limitlesstcg.com/api/tournaments?game=PTCG&format=STANDARD&limit=500" -Headers $h -TimeoutSec 90
    $sel = $todos | Where-Object { $_.players -ge 64 -and $_.players -le 250 } |
        Sort-Object date -Descending | Select-Object -First $Torneos

    for ($i = 0; $i -lt $sel.Count; $i++) {
        $tr = $sel[$i]
        try {
            $st = Invoke-RestMethod "https://play.limitlesstcg.com/api/tournaments/$($tr.id)/standings?game=PTCG" -Headers $h -TimeoutSec 120
            foreach ($p in $st) {
                if (-not $p.deck -or -not $p.deck.name) { continue }
                $k = [string]$p.deck.id
                if (-not $cuenta.ContainsKey($k)) {
                    $cuenta[$k] = [pscustomobject]@{
                        id     = [string]$p.deck.id
                        nombre = [string]$p.deck.name
                        slugs  = @($p.deck.icons | Where-Object { $_ })
                        n      = 0
                    }
                }
                $cuenta[$k].n++
            }
            Write-Host ("  [{0}/{1}] {2} jugadores ({3})" -f ($i + 1), $sel.Count, $tr.players, $tr.id)
        } catch {
            Write-Host ("  [{0}/{1}] fallo al leer {2}: {3}" -f ($i + 1), $sel.Count, $tr.id, $_.Exception.Message)
        }
        Start-Sleep -Milliseconds 1500
    }
    $cuenta.Values | ConvertTo-Json -Depth 4 |
        Set-Content -Path $Cache -Encoding UTF8
    Write-Host "muestra guardada en la caché: $Cache"
}
Write-Host "arquetipos distintos en torneos: $($cuenta.Count)"

# 3. Quitar los que ya están en el top. Se comparan los **iconos exactos** y no el nombre, porque la
#    API usa la forma corta («Dragapult», «Slowking») y el sitio la larga («Dragapult ex», «Slowking
#    Seek Inspiration»). Con el conjunto exacto, «Dragapult» se descarta (es el mismo mazo) pero
#    «Dragapult Dudunsparce» se queda (es otro mazo distinto). También se quita el cajón «Other».
$claveOficial = @{}
foreach ($m in $oficial) {
    $k = (@($m.slugs) | Sort-Object) -join "+"
    if ($k) { $claveOficial[$k] = $true }
}

$candidatos = $cuenta.Values |
    Where-Object { (Norm $_.nombre) -ne "other" } |
    Where-Object { -not $claveOficial.ContainsKey(((@($_.slugs) | Sort-Object) -join "+")) } |
    Sort-Object n -Descending

Write-Host "candidatos nuevos: $($candidatos.Count)"

# 4. Verificar los iconos (algunos slugs no existen en el CDN) y quedarse con los que tengan al menos uno.
$lista = @()
foreach ($m in $candidatos) {
    if ($lista.Count -ge $Cuantos) { break }
    $urls = @()
    foreach ($slug in ($m.slugs | Select-Object -First 2)) {
        $url = "https://r2.limitlesstcg.net/pokemon/gen9/$slug.png"
        try {
            $r = Invoke-WebRequest $url -Method Head -Headers @{ "User-Agent" = "Mozilla/5.0" } -UseBasicParsing -TimeoutSec 30
            if ($r.StatusCode -eq 200) { $urls += $url }
        } catch { Write-Host ("  sin icono: {0} ({1})" -f $slug, $m.nombre) }
    }
    if ($urls.Count -eq 0) { Write-Host ("  se omite {0}: sin icono válido" -f $m.nombre); continue }
    $lista += [pscustomobject]@{ id = $m.id; nombre = $m.nombre; uso = ""; iconos = @($urls) }
    Write-Host ("  + {0,-32} {1,4} veces" -f $m.nombre, $m.n)
}

# 5. Escribir el asset.
$destino = Join-Path $raiz $Salida
New-Item -ItemType Directory -Force -Path (Split-Path $destino -Parent) | Out-Null
$json = $lista | ConvertTo-Json -Depth 4
[System.IO.File]::WriteAllText($destino, $json, (New-Object System.Text.UTF8Encoding($false)))
Write-Host ""
Write-Host ("escrito {0} con {1} mazos" -f $destino, $lista.Count)
