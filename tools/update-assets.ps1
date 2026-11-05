<#
Refreshes the app's game data and icons after a Clash of Clans update.

  powershell -ExecutionPolicy Bypass -File tools\update-assets.ps1

Writes:
  app/src/main/assets/gamedata.json   names + per-level upgrade times, keyed by export data id
  app/src/main/assets/icons/*.webp    <id>.webp, or <id>_<level>.webp for buildings and traps

Sources: the coc-rush-helper snapshot of ClashKingAssets game data, and ClashKing's
asset host (assets.clashk.ing). Clash of Clans art is (c) Supercell. Existing icons are
kept, so re-runs only fetch what's new.
#>
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = Split-Path $PSScriptRoot -Parent
$assets = Join-Path $root 'app\src\main\assets'
$icons = Join-Path $assets 'icons'
$work = Join-Path ([IO.Path]::GetTempPath()) 'clash-timers-assets'
New-Item -ItemType Directory -Force $work, $icons | Out-Null

function Get-Text([string]$url, [string]$out) {
    & curl.exe -fsSL --retry 4 --retry-all-errors -o $out $url
    if ($LASTEXITCODE -ne 0) { throw "download failed: $url" }
    return [IO.File]::ReadAllText($out, [Text.Encoding]::UTF8)
}

# Asset folders use the item name lowercased, spaces -> underscores, dots removed.
function Clean([string]$name) {
    $name.ToLowerInvariant().Replace([string][char]0x2019, "'").Replace(' ', '_').Replace('.', '').Replace('?', '')
}

$game = Get-Text 'https://raw.githubusercontent.com/mateobaricevic/coc-rush-helper/main/dist/game-data.json' "$work\game-data.json" | ConvertFrom-Json
$guardians = (Get-Text 'https://assets.clashk.ing/static_data/guardians.json' "$work\guardians.json" | ConvertFrom-Json).items |
    ForEach-Object { [pscustomobject]@{ id = $_._id; name = $_.name; levels = @() } }
$helpers = @(
    [pscustomobject]@{ id = 93000000; name = "Builder's Apprentice"; levels = @() },
    [pscustomobject]@{ id = 93000001; name = 'Lab Assistant'; levels = @() }
)

# --- gamedata.json: { "<id>": { "n": name, "s": { "<fromLevel>": secondsToNextLevel } } }
$items = [ordered]@{}
function Add-Items($list, [bool]$buildSemantics) {
    foreach ($it in $list) {
        $steps = [ordered]@{}
        foreach ($l in $it.levels) {
            if ($buildSemantics) {
                # build_time at level N = time to reach N, i.e. the (N-1) -> N step
                if ($l.build_time -gt 0) { $steps["$($l.level - 1)"] = [long]$l.build_time }
            } else {
                # upgrade_time at level N = time for the N -> N+1 step
                if ($l.upgrade_time -gt 0) { $steps["$($l.level)"] = [long]$l.upgrade_time }
            }
        }
        $items["$($it.id)"] = [ordered]@{ n = $it.name.Replace([string][char]0x2019, "'"); s = $steps }
    }
}
Add-Items $game.buildings $true
Add-Items $game.traps $true
Add-Items $game.builderBase.buildings $true
Add-Items ($game.heroes + $game.troops + $game.spells + $game.pets + $game.builderBase.heroes + $game.builderBase.troops) $false
Add-Items ($guardians + $helpers) $false
$json = [ordered]@{ v = $game.meta.fetchedAt; items = $items } | ConvertTo-Json -Depth 6 -Compress
[IO.File]::WriteAllText((Join-Path $assets 'gamedata.json'), $json, (New-Object Text.UTF8Encoding($false)))
Write-Output "gamedata.json: $($items.Count) items (snapshot $($game.meta.fetchedAt))"

# --- icons: map every id to ClashKing artwork, fetch the missing ones
# The file list comes from the GitHub API; anonymous calls are rate-limited, so prefer a signed-in gh.
$treeUrl = 'repos/ClashKingInc/ClashKingAssets/git/trees/main?recursive=1'
if (Get-Command gh -ErrorAction SilentlyContinue) {
    $tree = (& gh api $treeUrl | ConvertFrom-Json).tree
} else {
    try { $tree = (Get-Text "https://api.github.com/$treeUrl" "$work\tree.json" | ConvertFrom-Json).tree }
    catch { throw 'GitHub API refused the file list (rate limit). Install GitHub CLI and run "gh auth login", then retry.' }
}
$paths = New-Object 'System.Collections.Generic.HashSet[string]'
foreach ($b in $tree) { if ($b.type -eq 'blob') { [void]$paths.Add($b.path) } }

$wanted = [ordered]@{}   # local file name -> asset path
function Map-Leveled($list, [string]$folder) {
    foreach ($it in $list) {
        $prefix = "assets/$folder/$(Clean $it.name)/level_"
        foreach ($p in $paths) {
            if ($p.StartsWith($prefix) -and $p -match 'level_(\d+)\.webp$') { $wanted["$($it.id)_$($Matches[1]).webp"] = $p }
        }
    }
}
function Map-Single($list, [scriptblock]$pathFor) {
    foreach ($it in $list) {
        $p = & $pathFor (Clean $it.name)
        if ($paths.Contains($p)) { $wanted["$($it.id).webp"] = $p }
    }
}
Map-Leveled $game.buildings 'buildings/home-village'
Map-Leveled $game.traps 'traps/home-village'
Map-Leveled $game.builderBase.buildings 'buildings/builder-base'
Map-Single ($game.heroes + $game.builderBase.heroes) { param($c) "assets/heroes/$c/icon.webp" }
Map-Single ($game.troops + $game.builderBase.troops) { param($c) "assets/troops/$c/icon.webp" }
Map-Single $game.spells { param($c) "assets/spells/$c.webp" }
Map-Single $game.pets { param($c) "assets/pets/$c/icon.webp" }
Map-Single $guardians { param($c) "assets/guardians/$c/icon.webp" }
Map-Single $helpers { param($c) "assets/helpers/$c.webp" }

$config = New-Object Text.StringBuilder
$new = 0
foreach ($name in $wanted.Keys) {
    $out = Join-Path $icons $name
    if (Test-Path $out) { continue }
    $url = 'https://assets.clashk.ing/' + ($wanted[$name].Substring(7) -replace "'", '%27')
    [void]$config.AppendLine("url = `"$url`"").AppendLine("output = `"$($out.Replace('\', '/'))`"")
    $new++
}
if ($new -gt 0) {
    [IO.File]::WriteAllText("$work\curl.cfg", $config.ToString(), (New-Object Text.UTF8Encoding($false)))
    & curl.exe -sS -f --retry 4 --retry-all-errors -Z --parallel-max 16 -K "$work\curl.cfg"
    if ($LASTEXITCODE -ne 0) { throw 'some icon downloads failed; re-run to retry' }
}
Write-Output "icons: $($wanted.Count) mapped, $new downloaded"
