param(
    [string]$Instance = 'C:/Users/34003/AppData/Roaming/.minecraft/versions/1.21.11-NeoForge',
    [string]$Evidence = 'verification/collections-20261011'
)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $project
$version = ((Get-Content -LiteralPath 'gradle.properties' | Where-Object { $_ -match '^mod_version=' }) -replace '^mod_version=', '')
if ($version -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid release version' }
$instanceRoot = (Resolve-Path -LiteralPath $Instance).Path
$versionJson = Get-Content -LiteralPath (Join-Path $instanceRoot '1.21.11-NeoForge.json') -Raw | ConvertFrom-Json
$neoIndex = [array]::IndexOf([object[]]$versionJson.arguments.game, '--fml.neoForgeVersion')
$mcIndex = [array]::IndexOf([object[]]$versionJson.arguments.game, '--fml.mcVersion')
if ($versionJson.id -ne '1.21.11-NeoForge' -or $neoIndex -lt 0 -or $mcIndex -lt 0 -or $versionJson.arguments.game[$neoIndex + 1] -ne '21.11.45' -or $versionJson.arguments.game[$mcIndex + 1] -ne '1.21.11') { throw 'Unexpected Minecraft/NeoForge instance' }
$jarName = "fsrpg-neoforge-$version.jar"
$jarPath = Join-Path $project "build/libs/$jarName"
$jarHash = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.ToLower()
$report = Get-Content -LiteralPath "$Evidence/game/latest.json" -Raw | ConvertFrom-Json
$visual = Get-Content -LiteralPath "$Evidence/visual-review.json" -Raw | ConvertFrom-Json
$build = Get-Content -LiteralPath "$Evidence/build-offline.log" -Raw
if ($build -notmatch 'BUILD SUCCESSFUL' -or $build -notmatch 'CollectionQuestTest passed:' -or $build -notmatch 'WeaponDrawRulesTest passed:') { throw 'Build and relevant checks must pass' }
if (-not $report.capturePassed -or $report.jarSha256 -ne $jarHash -or $report.monitorAdapter -cne '\\.\DISPLAY5') { throw 'Packaged game evidence must validate this exact release on DISPLAY5' }
if (-not $visual.passed -or $visual.jarSha256 -ne $jarHash) { throw 'Inspect the real screenshots before deploying' }
foreach ($view in $report.views) {
    $file = Join-Path $report.directory $view.file
    if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLower() -ne $view.sha256) { throw 'Game evidence changed' }
}
foreach ($asset in (Get-Content -LiteralPath "$Evidence/assets-preserved.json" -Raw | ConvertFrom-Json)) {
    if ((Get-FileHash -LiteralPath $asset.path -Algorithm SHA256).Hash.ToLower() -ne $asset.sha256) { throw 'Existing model/HUD asset changed' }
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Get-ModIds([string]$path) {
    $zip = [IO.Compression.ZipFile]::OpenRead($path)
    try {
        $entry = $zip.GetEntry('META-INF/neoforge.mods.toml')
        if ($null -eq $entry) { return @() }
        $reader = [IO.StreamReader]::new($entry.Open())
        try { $metadata = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $modSection = ($metadata -split '\[\[dependencies\.')[0]
        return @([regex]::Matches($modSection, '(?m)^\s*modId\s*=\s*"([^"]+)"') | ForEach-Object { $_.Groups[1].Value })
    } finally { $zip.Dispose() }
}
$mods = Join-Path $instanceRoot 'mods'
$backup = Join-Path $instanceRoot ("fsrpg-backups/" + (Get-Date -Format 'yyyyMMdd-HHmmss') + "-before-$version")
New-Item -ItemType Directory -Path $mods, (Join-Path $backup 'mods') -Force | Out-Null
$configDir = Join-Path $instanceRoot 'config/fsrpg'
if (Test-Path -LiteralPath $configDir) { Copy-Item -LiteralPath $configDir -Destination (Join-Path $backup 'config-fsrpg') -Recurse }
$clientConfig = Join-Path $instanceRoot 'config/fsrpg-client.toml'
if (Test-Path -LiteralPath $clientConfig) { Copy-Item -LiteralPath $clientConfig -Destination $backup }
$removed = @()
foreach ($old in (Get-ChildItem -LiteralPath $mods -File -Filter '*.jar')) {
    $ids = @(Get-ModIds $old.FullName)
    if (($ids -contains 'fsrpg') -or ($ids -contains 'fsvoxel') -or ($ids -contains 'actioncombat')) {
        $hash = (Get-FileHash -LiteralPath $old.FullName -Algorithm SHA256).Hash.ToLower()
        $target = Join-Path (Join-Path $backup 'mods') $old.Name
        Copy-Item -LiteralPath $old.FullName -Destination $target
        if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLower() -ne $hash) { throw 'Backup verification failed' }
        Remove-Item -LiteralPath $old.FullName
        $removed += [ordered]@{ file = $old.Name; modIds = $ids; sha256 = $hash; backup = $target }
    }
}
Copy-Item -LiteralPath $jarPath, (Join-Path $project 'libs/fsvoxel-renderer-neoforge-0.6.0.jar') -Destination $mods
New-Item -ItemType Directory -Path $configDir, (Join-Path $configDir 'models') -Force | Out-Null
$seeded = @()
foreach ($source in (Get-ChildItem -LiteralPath 'src/main/resources/defaults' -File -Filter '*.json')) {
    $target = Join-Path $configDir $source.Name
    if (-not (Test-Path -LiteralPath $target)) { Copy-Item -LiteralPath $source.FullName -Destination $target; $seeded += $target }
}
foreach ($source in (Get-ChildItem -LiteralPath 'src/main/resources/assets/fsrpg/fsvoxel/models' -File -Filter '*.fsvoxel')) {
    $target = Join-Path (Join-Path $configDir 'models') $source.Name
    if (-not (Test-Path -LiteralPath $target)) { Copy-Item -LiteralPath $source.FullName -Destination $target; $seeded += $target }
}
$installed = @()
foreach ($name in @($jarName, 'fsvoxel-renderer-neoforge-0.6.0.jar')) {
    $target = Join-Path $mods $name
    $source = if ($name -eq $jarName) { $jarPath } else { Join-Path $project "libs/$name" }
    $hash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLower()
    if ($hash -ne (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLower()) { throw "Installed hash mismatch: $name" }
    $installed += [ordered]@{ path = $target; sha256 = $hash }
}
$record = [ordered]@{
    version = $version; instance = $instanceRoot; installedAt = (Get-Date).ToString('o'); backup = $backup
    installed = $installed; previousMods = $removed; seededFiles = $seeded
    gameChecks = @($report.checksPassed).Count; views = @($report.views).Count; monitor = $report.monitorAdapter
    jarSha256 = $jarHash; userWorldsPreserved = $true; biologicalActionsDeferred = $true
    legacyCombatImpact = 'actioncombat disabled after verified backup; its old monster levels/attribute bonuses also stop'
}
$record | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath "$Evidence/deployment.json" -Encoding utf8
$record | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $backup 'deployment.json') -Encoding utf8
Get-Item -LiteralPath (Join-Path $mods $jarName) | Select-Object FullName, Length
