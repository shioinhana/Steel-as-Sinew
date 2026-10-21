param(
    [switch]$VerifyOnly,
    [string]$StageDirectory = 'verification/legacy-branches-20261022/fsrpg',
    [string]$StageReport = 'verification/legacy-branches-20261022/stage-config.json',
    [string]$Instance = 'C:\Users\34003\AppData\Roaming\.minecraft\versions\1.21.11-NeoForge'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$project = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
function ProjectPath([string]$path) {
    if ([IO.Path]::IsPathRooted($path)) { return [IO.Path]::GetFullPath($path) }
    return [IO.Path]::GetFullPath((Join-Path $project $path))
}
function Hash([string]$path) { (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
function ReadJson([string]$path) { [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8) | ConvertFrom-Json }
function TreeHashes([string]$directory) {
    $result = [ordered]@{}
    $prefix = ([IO.Path]::GetFullPath($directory)).TrimEnd('\', '/') + '\'
    foreach ($file in (Get-ChildItem -LiteralPath $directory -Recurse -Force -File)) {
        if (-not $file.FullName.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw "File escaped intended directory: $($file.FullName)" }
        $relative = $file.FullName.Substring($prefix.Length).Replace('\', '/')
        $result[$relative] = Hash $file.FullName
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
function WriteVerifiedCopy([string]$source, [string]$destination, [string]$sha, [string]$label) {
    $parent = Split-Path -Parent $destination
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    $temp = Join-Path $parent ('.fsrpg-090-' + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        Copy-Item -LiteralPath $source -Destination $temp -Force
        if ((Hash $temp) -cne $sha) { throw "$label staged copy hash mismatch" }
        Move-Item -LiteralPath $temp -Destination $destination -Force
        if ((Hash $destination) -cne $sha) { throw "$label installed hash mismatch" }
    } finally {
        if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force }
    }
}

$instance = [IO.Path]::GetFullPath($Instance).TrimEnd('\', '/')
if (-not (Test-Path -LiteralPath $instance -PathType Container)) { throw "Official instance not found: $instance" }
if ($instance -notmatch '1\.21\.11-NeoForge$') { throw "Unexpected official instance: $instance" }
$mods = Join-Path $instance 'mods'
$configRoot = Join-Path $instance 'config/fsrpg'
if (-not (Test-Path -LiteralPath $mods -PathType Container) -or -not (Test-Path -LiteralPath $configRoot -PathType Container)) {
    throw "Official mods/ or config/fsrpg/ missing under $instance"
}
$running = @(Get-CimInstance Win32_Process -Filter "name='java.exe'" |
    Where-Object { $_.CommandLine -and ($_.CommandLine.Contains($instance) -or $_.CommandLine.Contains('1.21.11-NeoForge')) })
if ($running.Count -gt 0) { throw "Game appears to be running ($($running[0].ProcessId)); close it before replacing mods/configs" }

$jar = ProjectPath 'build/libs/fsrpg-neoforge-0.9.0.jar'
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw "Built JAR not found: $jar" }
$sha = Hash $jar
$stage = ProjectPath $StageDirectory
$reportPath = ProjectPath $StageReport
if (-not (Test-Path -LiteralPath $stage -PathType Container)) { throw "Staged configuration not found: $stage" }
if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) { throw "Stage report not found: $reportPath" }
if (-not $stage.Contains('verification')) { throw "Staged configuration must live under verification/: $stage" }
$report = ReadJson $reportPath
if (-not $report.passed -or $report.version -ne '0.9.0') { throw 'Stage report is not a passed 0.9.0 staging result' }
$stageAfter = TreeHashes $stage
foreach ($entry in $report.afterSha256.PSObject.Properties) {
    if (-not $stageAfter.Contains($entry.Name)) { throw "Staged file missing: $($entry.Name)" }
    if ($stageAfter[$entry.Name] -cne $entry.Value) { throw "Staged file changed after staging: $($entry.Name)" }
}
$changed = @($report.changedFiles)
if ($changed.Count -eq 0) { throw 'Stage report lists no changed configuration files' }
foreach ($relative in $changed) { if (-not $stageAfter.Contains($relative)) { throw "Reported changed file missing: $relative" } }

$oldJars = @(Get-ChildItem -LiteralPath $mods -Filter 'fsrpg-neoforge-*.jar' -File)
if ($oldJars.Count -ne 1) { throw "Expected exactly one installed FSRPG JAR, found $($oldJars.Count)" }
$oldJar = $oldJars[0]
$modsBefore = TreeHashes $mods
$configBefore = TreeHashes $configRoot
$expectedConfig = [ordered]@{}
foreach ($key in $configBefore.Keys) { $expectedConfig[$key] = $configBefore[$key] }
foreach ($relative in $changed) {
    $destination = Join-Path $configRoot $relative
    if (Test-Path -LiteralPath $destination -PathType Leaf) {
        if ((Hash $destination) -cne $configBefore[$relative]) { throw "Concurrent configuration edit: $relative" }
    }
    # New paths are exactly the migration-owned backup/marker artifacts the mod would create itself;
    # they are allowlisted only when reported by the passed staging run.
    $expectedConfig[$relative] = $stageAfter[$relative]
}
$expectedMods = [ordered]@{}
foreach ($key in $modsBefore.Keys) { $expectedMods[$key] = $modsBefore[$key] }
$expectedMods.Remove($oldJar.Name)
$expectedMods['fsrpg-neoforge-0.9.0.jar'] = $sha
$optionsBefore = $null
$options = Join-Path $instance 'options.txt'
if (Test-Path -LiteralPath $options -PathType Leaf) { $optionsBefore = Hash $options }

if ($VerifyOnly) {
    Write-Output "VERIFY ONLY PASSED: JAR $jar SHA-256 $sha; staged config $stage ($($changed.Count) changed files: $($changed -join ', ')); official instance $instance; game running=$($running.Count -gt 0); no files written."
    return
}

$stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
$backup = Join-Path $instance "fsrpg-backups/$stamp-before-0.9.0"
if (Test-Path -LiteralPath $backup) { throw "Dated backup already exists: $backup" }
New-Item -ItemType Directory -Path (Join-Path $backup 'mods') -Force | Out-Null
Copy-Item -LiteralPath $configRoot -Destination (Join-Path $backup 'config') -Recurse -Force
SameTree $configBefore (Join-Path $backup 'config') 'Full configuration backup'
Copy-Item -LiteralPath $oldJar.FullName -Destination (Join-Path $backup ('mods/' + $oldJar.Name)) -Force
if ((Hash (Join-Path $backup ('mods/' + $oldJar.Name))) -cne $modsBefore[$oldJar.Name]) { throw 'Previous FSRPG JAR backup hash mismatch' }
if ($null -ne $optionsBefore) { Copy-Item -LiteralPath $options -Destination (Join-Path $backup 'options.txt') -Force }

$written = @()
try {
    WriteVerifiedCopy $jar (Join-Path $mods 'fsrpg-neoforge-0.9.0.jar') $sha 'FSRPG JAR'
    if (-not $VerifyOnly) { Remove-Item -LiteralPath $oldJar.FullName -Force }
    foreach ($relative in $changed) {
        $destination = Join-Path $configRoot $relative
        WriteVerifiedCopy (Join-Path $stage $relative) $destination $stageAfter[$relative] "configuration $relative"
        $written += [ordered]@{ relative = $relative; existed = $configBefore.Contains($relative) }
    }
    SameTree $expectedMods $mods 'Only the tested FSRPG JAR was replaced'
    SameTree $expectedConfig $configRoot 'Only allowlisted configuration migrations changed'
    if ($null -ne $optionsBefore -and (Hash $options) -cne $optionsBefore) { throw 'options.txt changed during deployment' }
    $record = [ordered]@{
        version = '0.9.0'; networkProtocol = 12; installedJar = (Join-Path $mods 'fsrpg-neoforge-0.9.0.jar'); jarSha256 = $sha
        testedJar = $jar; backup = $backup; previousJar = [ordered]@{ name = $oldJar.Name; sha256 = $modsBefore[$oldJar.Name] }
        configurationBeforeSha256 = $configBefore; configurationAfterSha256 = $expectedConfig; configurationFilesWritten = $written
        stageDirectory = $stage; stageReport = $reportPath; stageReportSha256 = (Hash $reportPath)
        skillNodeCount = $report.skillNodeCount; footworkNodes = $report.footworkNodes
        guardCueCloseFraction = $report.guardCueCloseFraction; archeryBlockPierce = $report.archeryBlockPierce
        otherModsPreserved = $true; optionsPreserved = ($null -eq $optionsBefore -or (Hash $options) -ceq $optionsBefore)
        worldsModified = $false; deployedAt = (Get-Date).ToString('o')
    }
    $record | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $backup 'deployment.json') -Encoding utf8
    $deploymentReport = Join-Path (Split-Path -Parent $reportPath) 'deployment.json'
    $record | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $deploymentReport -Encoding utf8
    Write-Output "Installed $($record.installedJar); SHA-256 $sha; $($written.Count) configuration files migrated; other mods/options/worlds preserved; backup $backup"
} catch {
    $failure = $_.ToString() + "`nSTACK:`n" + $_.ScriptStackTrace
    $rollback = @()
    try {
        $installed = Join-Path $mods 'fsrpg-neoforge-0.9.0.jar'
        if (Test-Path -LiteralPath $installed) { Remove-Item -LiteralPath $installed -Force }
        WriteVerifiedCopy (Join-Path $backup ('mods/' + $oldJar.Name)) $oldJar.FullName $modsBefore[$oldJar.Name] 'restored FSRPG JAR'
        foreach ($file in $written) {
            $relative = $file.relative
            $destination = Join-Path $configRoot $relative
            if (-not $file.existed) {
                if (Test-Path -LiteralPath $destination) { Remove-Item -LiteralPath $destination -Force }
                $parent = Split-Path -Parent $destination
                $rootFull = [IO.Path]::GetFullPath($configRoot).TrimEnd('\', '/')
                while ($parent -and ([IO.Path]::GetFullPath($parent).TrimEnd('\', '/')) -ne $rootFull -and (Test-Path -LiteralPath $parent)) {
                    if (@(Get-ChildItem -LiteralPath $parent -Force).Count -gt 0) { break }
                    Remove-Item -LiteralPath $parent -Force
                    $parent = Split-Path -Parent $parent
                }
                continue
            }
            WriteVerifiedCopy (Join-Path $backup ('config/' + $relative)) $destination $configBefore[$relative] "restored $relative"
        }
        SameTree $configBefore $configRoot 'Configuration rollback'
        SameTree $modsBefore $mods 'Mods rollback'
        if ($null -ne $optionsBefore -and (Hash $options) -cne $optionsBefore) { throw 'options.txt differs after rollback' }
    } catch { $rollback += $_.ToString() }
    if ($rollback.Count -gt 0) { throw "Deployment failed: $failure; rollback needs attention: $($rollback -join ' | '); backup: $backup" }
    throw "Deployment failed and was rolled back: $failure; backup: $backup"
}
