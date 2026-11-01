param(
    [switch]$VerifyOnly,
    [string]$Instance = 'C:\Users\34003\AppData\Roaming\.minecraft\versions\1.21.11-NeoForge'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$project = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$instance = [IO.Path]::GetFullPath($Instance).TrimEnd('\', '/')
$mods = Join-Path $instance 'mods'
$config = Join-Path $instance 'config/fsrpg'
$jar = Join-Path $project 'build/libs/fsrpg-neoforge-0.9.2.jar'
$targetName = 'fsrpg-neoforge-0.9.2.jar'
function Hash([string]$path) { (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
function TreeHashes([string]$directory) {
    $result = [ordered]@{}
    $prefix = ([IO.Path]::GetFullPath($directory)).TrimEnd('\', '/') + '\'
    foreach ($file in (Get-ChildItem -LiteralPath $directory -Recurse -Force -File)) {
        if (-not $file.FullName.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw "File escaped intended directory: $($file.FullName)" }
        $result[$file.FullName.Substring($prefix.Length).Replace('\', '/')] = Hash $file.FullName
    }
    return $result
}
function SameTree($expected, [string]$directory, [string]$label) {
    $actual = TreeHashes $directory
    if ($expected.Count -ne $actual.Count) { throw "$label file count changed: $($expected.Count) -> $($actual.Count)" }
    foreach ($key in $expected.Keys) {
        if (-not $actual.Contains($key)) { throw "$label lost $key" }
        if ($actual[$key] -cne $expected[$key]) { throw "$label changed $key" }
    }
}
function VerifiedCopy([string]$source, [string]$destination, [string]$sha, [string]$label) {
    $parent = Split-Path -Parent $destination
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    $temp = Join-Path $parent ('.fsrpg-091-' + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        Copy-Item -LiteralPath $source -Destination $temp -Force
        if ((Hash $temp) -cne $sha) { throw "$label staged copy hash mismatch" }
        Move-Item -LiteralPath $temp -Destination $destination -Force
        if ((Hash $destination) -cne $sha) { throw "$label installed hash mismatch" }
    } finally {
        if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force }
    }
}

if (-not (Test-Path -LiteralPath $instance -PathType Container)) { throw "Official instance not found: $instance" }
if ($instance -notmatch '1\.21\.11-NeoForge$') { throw "Unexpected official instance: $instance" }
if (-not (Test-Path -LiteralPath $mods -PathType Container) -or -not (Test-Path -LiteralPath $config -PathType Container)) {
    throw "Official mods/ or config/fsrpg/ missing under $instance"
}
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw "Built JAR not found: $jar" }
$sha = Hash $jar
$running = @(Get-CimInstance Win32_Process -Filter "name='java.exe'" |
    Where-Object { $_.CommandLine -and ($_.CommandLine.Contains($instance) -or $_.CommandLine.Contains('1.21.11-NeoForge')) })
if ($running.Count -gt 0) { throw "Game appears to be running ($($running[0].ProcessId)); close it before replacing mods" }
$oldJars = @(Get-ChildItem -LiteralPath $mods -Filter 'fsrpg-neoforge-*.jar' -File)
if ($oldJars.Count -ne 1) { throw "Expected exactly one installed FSRPG JAR, found $($oldJars.Count)" }
$oldJar = $oldJars[0]
$modsBefore = TreeHashes $mods
$configBefore = TreeHashes $config
$options = Join-Path $instance 'options.txt'
$optionsBefore = if (Test-Path -LiteralPath $options -PathType Leaf) { Hash $options } else { $null }
$expectedMods = [ordered]@{}
foreach ($key in $modsBefore.Keys) { $expectedMods[$key] = $modsBefore[$key] }
$expectedMods.Remove($oldJar.Name)
$expectedMods[$targetName] = $sha

if ($VerifyOnly) {
    Write-Output "VERIFY ONLY PASSED: JAR $jar SHA-256 $sha; installed $($oldJar.Name) $($modsBefore[$oldJar.Name]); instance $instance; game running=$($running.Count -gt 0); config files=$($configBefore.Count); no files written."
    return
}

$stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
$backup = Join-Path $instance "fsrpg-backups/$stamp-before-0.9.2"
if (Test-Path -LiteralPath $backup) { throw "Dated backup already exists: $backup" }
New-Item -ItemType Directory -Path (Join-Path $backup 'mods') -Force | Out-Null
Copy-Item -LiteralPath $config -Destination (Join-Path $backup 'config') -Recurse -Force
SameTree $configBefore (Join-Path $backup 'config') 'Full configuration backup'
Copy-Item -LiteralPath $oldJar.FullName -Destination (Join-Path $backup ('mods/' + $oldJar.Name)) -Force
if ((Hash (Join-Path $backup ('mods/' + $oldJar.Name))) -cne $modsBefore[$oldJar.Name]) { throw 'Previous FSRPG JAR backup hash mismatch' }
if ($null -ne $optionsBefore) { Copy-Item -LiteralPath $options -Destination (Join-Path $backup 'options.txt') -Force }

$installed = Join-Path $mods $targetName
try {
    VerifiedCopy $jar $installed $sha 'FSRPG JAR'
    Remove-Item -LiteralPath $oldJar.FullName -Force
    SameTree $expectedMods $mods 'Only the tested FSRPG JAR was replaced'
    SameTree $configBefore $config 'Configuration byte-exactly preserved (0.9.2 has no config migration)'
    if ($null -ne $optionsBefore -and (Hash $options) -cne $optionsBefore) { throw 'options.txt changed during deployment' }
    $record = [ordered]@{
        version = '0.9.2'; networkProtocol = 12; installedJar = $installed; jarSha256 = $sha; testedJar = $jar
        backup = $backup; previousJar = [ordered]@{ name = $oldJar.Name; sha256 = $modsBefore[$oldJar.Name] }
        configFileCount = $configBefore.Count; configurationPreservedSha256 = $configBefore
        otherModsPreserved = $true; optionsPreserved = ($null -eq $optionsBefore -or (Hash $options) -ceq $optionsBefore)
        worldsModified = $false; uiEvidence = 'verification/skill-tree-20261022/game/skill-tree-review.json'
        deployedAt = (Get-Date).ToString('o')
    }
    $record | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $backup 'deployment.json') -Encoding utf8
    $evidence = Join-Path $project 'verification/skill-tree-20261022/deployment.json'
    New-Item -ItemType Directory -Path (Split-Path -Parent $evidence) -Force | Out-Null
    $record | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $evidence -Encoding utf8
    Write-Output "Installed $installed; SHA-256 $sha; config byte-exactly preserved; other mods/options/worlds preserved; backup $backup"
} catch {
    $failure = $_.ToString() + "`nSTACK:`n" + $_.ScriptStackTrace
    $rollback = @()
    try {
        if (Test-Path -LiteralPath $installed) { Remove-Item -LiteralPath $installed -Force }
        VerifiedCopy (Join-Path $backup ('mods/' + $oldJar.Name)) $oldJar.FullName $modsBefore[$oldJar.Name] 'restored FSRPG JAR'
        SameTree $modsBefore $mods 'Mods rollback'
        SameTree $configBefore $config 'Configuration rollback'
        if ($null -ne $optionsBefore -and (Hash $options) -cne $optionsBefore) { throw 'options.txt differs after rollback' }
    } catch { $rollback += $_.ToString() }
    if ($rollback.Count -gt 0) { throw "Deployment failed: $failure; rollback needs attention: $($rollback -join ' | '); backup: $backup" }
    throw "Deployment failed and was rolled back: $failure; backup: $backup"
}
