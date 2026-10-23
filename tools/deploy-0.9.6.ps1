param(
    [switch]$VerifyOnly,
    [string]$BuildJar = '',
    [string]$Instance = 'C:\Users\34003\AppData\Roaming\.minecraft\versions\1.21.11-NeoForge'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$releaseZone = [TimeZoneInfo]::FindSystemTimeZoneById("China Standard Time")
function ReleaseTime { [TimeZoneInfo]::ConvertTimeFromUtc([DateTime]::UtcNow, $releaseZone) }
$project = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$instance = [IO.Path]::GetFullPath($Instance).TrimEnd('\', '/')
$mods = Join-Path $instance 'mods'
$config = Join-Path $instance 'config/fsrpg'
$clientConfig = Join-Path $instance 'config/fsrpg-client.toml'
$jar = if ([string]::IsNullOrWhiteSpace($BuildJar)) { Join-Path $project 'build/libs/fsrpg-neoforge-0.9.6.jar' } else { [IO.Path]::GetFullPath($BuildJar) }
$targetName = 'fsrpg-neoforge-0.9.6.jar'
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
    $temp = Join-Path $parent ('.fsrpg-096-' + [Guid]::NewGuid().ToString('N') + '.tmp')
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
$versionFile = Join-Path $instance '1.21.11-NeoForge.json'
$versionData = Get-Content -LiteralPath $versionFile -Raw | ConvertFrom-Json
if ($versionData.id -cne '1.21.11-NeoForge' -or $versionData.mainClass -cne 'net.neoforged.fml.startup.Client') { throw 'Instance launcher metadata mismatch' }
$arguments = @($versionData.arguments.game)
$mcIndex = [Array]::IndexOf($arguments, '--fml.mcVersion')
$neoIndex = [Array]::IndexOf($arguments, '--fml.neoForgeVersion')
if ($mcIndex -lt 0 -or $arguments[$mcIndex+1] -cne '1.21.11' -or $neoIndex -lt 0 -or $arguments[$neoIndex+1] -cne '21.11.45') { throw 'Instance game/NeoForge version mismatch' }
$latestLog = Join-Path $instance 'logs/latest.log'
if (-not (Select-String -LiteralPath $latestLog -SimpleMatch "Game directory: $instance" -Quiet)) { throw 'Latest actual game directory does not match target instance' }
$sha = Hash $jar
$reviewFile = Join-Path $project 'verification/imported-pouch-sequence-20261024/game/imported-item-hands-review.json'
$review = Get-Content -LiteralPath $reviewFile -Raw | ConvertFrom-Json
if (-not $review.passed -or $review.modJarSha256 -cne $sha -or $review.monitorAdapter -cne '\\.\DISPLAY5') { throw 'Real-client acceptance must pass on DISPLAY5 with this exact JAR SHA-256' }
$visualFile = Join-Path $project 'verification/imported-pouch-sequence-20261024/visual-review.json'
$visual = Get-Content -LiteralPath $visualFile -Raw | ConvertFrom-Json
if (-not $visual.passed -or $visual.modJarSha256 -cne $sha) { throw 'Screenshot visual acceptance must cover this exact tested JAR SHA-256' }
$running = @(Get-CimInstance Win32_Process | Where-Object { $_.Name -in @('java.exe','javaw.exe') } |
    Where-Object { $_.CommandLine -and ($_.CommandLine.Contains($instance) -or $_.CommandLine.Contains('1.21.11-NeoForge')) })
if ($running.Count -gt 0) { throw "Game appears to be running ($($running[0].ProcessId)); close it before replacing mods" }
$oldJars = @(Get-ChildItem -LiteralPath $mods -Filter 'fsrpg-neoforge-*.jar' -File)
if ($oldJars.Count -ne 1) { throw "Expected exactly one installed FSRPG JAR, found $($oldJars.Count)" }
$oldJar = $oldJars[0]
if ($oldJar.Name -cne 'fsrpg-neoforge-0.9.5.jar') { throw "Expected 0.9.5 before this release; refusing to replace $($oldJar.Name)" }
$modsBefore = TreeHashes $mods
$configBefore = TreeHashes $config
$clientBefore = if (Test-Path -LiteralPath $clientConfig -PathType Leaf) { Hash $clientConfig } else { $null }
$clientBytes = if ($null -ne $clientBefore) { [IO.File]::ReadAllBytes($clientConfig) } else { [byte[]]@() }
$clientText = [Text.Encoding]::UTF8.GetString($clientBytes)
$clientSeeds = [ordered]@{ importedPouchTossSeconds = '0.70'; importedPouchTossHeight = '0.22' }
$addedClientFields = @()
$clientAddition = ''
foreach ($key in $clientSeeds.Keys) {
    if ($clientText -notmatch ('(?m)^\s*' + [regex]::Escape($key) + '\s*=')) {
        $clientAddition += "`r`n# FSRPG 0.9.6: initial pouch toss before two hefts.`r`n$key = $($clientSeeds[$key])`r`n"
        $addedClientFields += $key
    }
}
[byte[]]$expectedClientBytes = $clientBytes + [Text.Encoding]::UTF8.GetBytes($clientAddition)
$shaProvider = [Security.Cryptography.SHA256]::Create()
try { $expectedClientSha = [BitConverter]::ToString($shaProvider.ComputeHash($expectedClientBytes)).Replace('-', '').ToLowerInvariant() }
finally { $shaProvider.Dispose() }
$options = Join-Path $instance 'options.txt'
$optionsBefore = if (Test-Path -LiteralPath $options -PathType Leaf) { Hash $options } else { $null }
$expectedMods = [ordered]@{}
foreach ($key in $modsBefore.Keys) { $expectedMods[$key] = $modsBefore[$key] }
$expectedMods.Remove($oldJar.Name)
$expectedMods[$targetName] = $sha

if ($VerifyOnly) {
    Write-Output "VERIFY ONLY PASSED: JAR $jar SHA-256 $sha; installed $($oldJar.Name) $($modsBefore[$oldJar.Name]); instance $instance; game running=$($running.Count -gt 0); config files=$($configBefore.Count); new client fields=$($addedClientFields -join ","); no files written."
    return
}

$stamp = (ReleaseTime).ToString('yyyyMMdd-HHmmss')
$backup = Join-Path $instance "fsrpg-backups/$stamp-before-0.9.6"
if (Test-Path -LiteralPath $backup) { throw "Dated backup already exists: $backup" }
New-Item -ItemType Directory -Path (Join-Path $backup 'mods') -Force | Out-Null
Copy-Item -LiteralPath $config -Destination (Join-Path $backup 'config') -Recurse -Force
SameTree $configBefore (Join-Path $backup 'config') 'Full configuration backup'
Copy-Item -LiteralPath $oldJar.FullName -Destination (Join-Path $backup ('mods/' + $oldJar.Name)) -Force
if ((Hash (Join-Path $backup ('mods/' + $oldJar.Name))) -cne $modsBefore[$oldJar.Name]) { throw 'Previous FSRPG JAR backup hash mismatch' }
if ($null -ne $optionsBefore) { Copy-Item -LiteralPath $options -Destination (Join-Path $backup 'options.txt') -Force }
if ($null -ne $clientBefore) {
    Copy-Item -LiteralPath $clientConfig -Destination (Join-Path $backup 'fsrpg-client.toml') -Force
    if ((Hash (Join-Path $backup 'fsrpg-client.toml')) -cne $clientBefore) { throw 'Client TOML backup hash mismatch' }
}

$installed = Join-Path $mods $targetName
try {
    VerifiedCopy $jar $installed $sha 'FSRPG JAR'
    if ($clientAddition.Length -gt 0) {
        if ($null -ne $clientBefore -and (Hash $clientConfig) -cne $clientBefore) { throw 'Client config changed during preparation' }
        [IO.File]::WriteAllBytes($clientConfig, $expectedClientBytes)
    }
    if ($oldJar.FullName -ine $installed) { Remove-Item -LiteralPath $oldJar.FullName -Force }
    SameTree $expectedMods $mods 'Only the tested FSRPG JAR was replaced'
    SameTree $configBefore $config 'Existing FSRPG JSON configuration byte-exactly preserved'
    if ((Hash $clientConfig) -cne $expectedClientSha) { throw 'Client TOML differs from the byte-preserved prefix plus missing new fields' }
    if ($null -ne $optionsBefore -and (Hash $options) -cne $optionsBefore) { throw 'options.txt changed during deployment' }
    $record = [ordered]@{
        version = '0.9.6'; networkProtocol = 12; installedJar = $installed; jarSha256 = $sha; testedJar = $jar
        backup = $backup; previousJar = [ordered]@{ name = $oldJar.Name; sha256 = $modsBefore[$oldJar.Name] }
        configFileCount = $configBefore.Count; configurationPreservedSha256 = $configBefore
        clientConfigBeforeSha256 = $clientBefore; clientConfigAfterSha256 = $expectedClientSha; existingClientConfigBytesPreserved = $true; addedClientFields = $addedClientFields
        otherModsPreserved = $true; optionsPreserved = ($null -eq $optionsBefore -or (Hash $options) -ceq $optionsBefore)
        worldsModified = $false; uiEvidence = 'verification/imported-pouch-sequence-20261024/game/imported-item-hands-review.json'
        deployedAt = (ReleaseTime).ToString('o')
    }
    $record | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $backup 'deployment.json') -Encoding utf8
    $evidence = Join-Path $project 'verification/imported-pouch-sequence-20261024/deployment.json'
    New-Item -ItemType Directory -Path (Split-Path -Parent $evidence) -Force | Out-Null
    $record | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $evidence -Encoding utf8
    Write-Output "Installed $installed; SHA-256 $sha; existing config preserved; missing pouch toss fields seeded; other mods/options/worlds preserved; backup $backup"
} catch {
    $failure = $_.ToString() + "`nSTACK:`n" + $_.ScriptStackTrace
    $rollback = @()
    try {
        if (Test-Path -LiteralPath $installed) { Remove-Item -LiteralPath $installed -Force }
        VerifiedCopy (Join-Path $backup ('mods/' + $oldJar.Name)) $oldJar.FullName $modsBefore[$oldJar.Name] 'restored FSRPG JAR'
        SameTree $modsBefore $mods 'Mods rollback'
        SameTree $configBefore $config 'Configuration rollback'
        if ($null -ne $clientBefore) { VerifiedCopy (Join-Path $backup 'fsrpg-client.toml') $clientConfig $clientBefore 'Client TOML rollback' }
        if ($null -ne $optionsBefore -and (Hash $options) -cne $optionsBefore) { throw 'options.txt differs after rollback' }
    } catch { $rollback += $_.ToString() }
    if ($rollback.Count -gt 0) { throw "Deployment failed: $failure; rollback needs attention: $($rollback -join ' | '); backup: $backup" }
    throw "Deployment failed and was rolled back: $failure; backup: $backup"
}
