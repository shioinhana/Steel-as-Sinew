param(
    [switch]$VerifyOnly,
    [string]$BuildJar = '',
    [string]$Instance = 'C:\Users\34003\AppData\Roaming\.minecraft\versions\1.21.11-NeoForge'
)

# Deployment preserves configuration bytes; new optional camera fields load in the client.
# VerifyOnly performs the same preflight reads, then returns before any write.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$officialInstance = 'C:\Users\34003\AppData\Roaming\.minecraft\versions\1.21.11-NeoForge'
$oldName = 'fsrpg-neoforge-0.10.1.jar'
$oldSha = '7973485fe22019b360792f62595b5094a54f006e3f8a24add73218105fc41502'
$newName = 'fsrpg-neoforge-0.10.2.jar'
$releaseZone = [TimeZoneInfo]::FindSystemTimeZoneById('China Standard Time')

function FullPath([string]$Path) {
    return [IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
}
function Assert-NoReparsePath([string]$Path) {
    # Inspect every existing component, including parents outside the instance.
    # Do not use recursive enumeration until each child has passed this check.
    $cursor = [IO.Path]::GetFullPath($Path)
    while (-not [string]::IsNullOrEmpty($cursor)) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Reparse points are not allowed: $cursor"
            }
        }
        $parent = [IO.Path]::GetDirectoryName($cursor.TrimEnd('\', '/'))
        if ($parent -eq $cursor) { break }
        $cursor = $parent
    }
}
function Assert-InRoot([string]$Path, [string]$Root) {
    $full = FullPath $Path
    $base = FullPath $Root
    if ($full -ine $base -and -not $full.StartsWith($base + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Path escaped its intended root: $full (root $base)"
    }
    Assert-NoReparsePath $full
    if (Test-Path -LiteralPath $full) {
        $resolved = FullPath (Resolve-Path -LiteralPath $full).ProviderPath
        if ($resolved -ine $full) { throw "Resolved path changed: $full -> $resolved" }
    }
}
function Hash([string]$Path) {
    Assert-NoReparsePath $Path
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "File is missing: $Path" }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Assert-Hash([string]$Path, [string]$Expected, [string]$Label) {
    if ((Hash $Path) -cne $Expected) { throw "$Label SHA-256 changed: $Path" }
}
function Assert-NoGameRunning {
    $java = @(Get-CimInstance Win32_Process | Where-Object { $_.Name -in @('java.exe', 'javaw.exe') })
    foreach ($process in $java) {
        if ([string]::IsNullOrWhiteSpace($process.CommandLine)) {
            throw "Cannot verify the command line of $($process.Name) PID $($process.ProcessId); refusing to replace loaded mods"
        }
        $command = $process.CommandLine.Replace('/', '\')
        # The instance marker catches launcher response-file paths too. Also
        # refuse other identifiable Minecraft clients while installing this mod.
        $minecraftClient = $command -match 'net\.minecraft\.client\.main\.Main|net\.neoforged\.fml\.startup\.Client|net\.neoforged\.devlaunch\.Main|--launchTarget\s+[^\s]*client'
        if ($command.IndexOf($script:instance, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
            $command.IndexOf('1.21.11-NeoForge', [StringComparison]::OrdinalIgnoreCase) -ge 0 -or $minecraftClient) {
            throw "Minecraft may be running in $($process.Name) PID $($process.ProcessId); close it before deployment"
        }
    }
}
function TreeSnapshot([string]$Root) {
    Assert-NoReparsePath $Root
    if (-not (Test-Path -LiteralPath $Root -PathType Container)) { throw "Directory is missing: $Root" }
    $prefix = (FullPath $Root) + '\'
    $files = [ordered]@{}
    $directories = [Collections.Generic.List[string]]::new()
    $pending = [Collections.Generic.Stack[string]]::new()
    $pending.Push((FullPath $Root))
    while ($pending.Count -gt 0) {
        $directory = $pending.Pop()
        Assert-InRoot $directory $Root
        foreach ($entry in @(Get-ChildItem -LiteralPath $directory -Force | Sort-Object FullName)) {
            Assert-InRoot $entry.FullName $Root
            $relative = $entry.FullName.Substring($prefix.Length).Replace('\', '/')
            if ($entry.PSIsContainer) {
                $directories.Add($relative)
                $pending.Push($entry.FullName)
            } else {
                $files[$relative] = Hash $entry.FullName
            }
        }
    }
    return [pscustomobject]@{ files = $files; directories = @($directories | Sort-Object) }
}
function Assert-Snapshot($Expected, [string]$Root, [string]$Label) {
    $actual = TreeSnapshot $Root
    if ($Expected.files.Count -ne $actual.files.Count -or
        ($Expected.directories -join "`n") -cne ($actual.directories -join "`n")) {
        throw "$Label file/directory inventory changed: $Root"
    }
    foreach ($key in $Expected.files.Keys) {
        if (-not $actual.files.Contains($key) -or $actual.files[$key] -cne $Expected.files[$key]) {
            throw "$Label file changed or disappeared: $key"
        }
    }
}
function OptionalFileSnapshot([string]$Path) {
    Assert-InRoot $Path $script:instance
    if (Test-Path -LiteralPath $Path) {
        return [pscustomobject]@{ exists = $true; sha256 = Hash $Path }
    }
    return [pscustomobject]@{ exists = $false; sha256 = $null }
}
function Assert-OptionalFile($Expected, [string]$Path, [string]$Label) {
    $actual = OptionalFileSnapshot $Path
    if ($actual.exists -ne $Expected.exists -or $actual.sha256 -cne $Expected.sha256) {
        throw "$Label changed, appeared, or disappeared: $Path"
    }
}
function New-InstanceDirectory([string]$Path) {
    Assert-InRoot $Path $script:instance
    if (Test-Path -LiteralPath $Path) { throw "Refusing to reuse an existing backup directory: $Path" }
    New-Item -ItemType Directory -Path $Path | Out-Null
    Assert-InRoot $Path $script:instance
}
function Copy-NewVerifiedFile([string]$Source, [string]$Destination, [string]$Expected) {
    Assert-NoReparsePath $Source
    Assert-InRoot $Destination $script:instance
    Assert-Hash $Source $Expected 'Copy source'
    # File.Copy(false) is exclusive: never overwrite a concurrently created file.
    [IO.File]::Copy($Source, $Destination, $false)
    Assert-Hash $Destination $Expected 'Copied file'
    Assert-Hash $Source $Expected 'Copy source after copy'
}
function Copy-ConfigBackup($Snapshot, [string]$Destination) {
    New-InstanceDirectory $Destination
    foreach ($relative in @($Snapshot.directories | Sort-Object Length, { $_ })) {
        New-InstanceDirectory (Join-Path $Destination $relative)
    }
    foreach ($relative in $Snapshot.files.Keys) {
        Copy-NewVerifiedFile (Join-Path $script:config $relative) (Join-Path $Destination $relative) $Snapshot.files[$relative]
    }
    Assert-Snapshot $Snapshot $Destination 'Complete config backup'
}
function Move-InstanceFileExclusive([string]$Source, [string]$Destination) {
    Assert-InRoot $Source $script:instance
    Assert-InRoot $Destination $script:instance
    # Both paths are on the same instance volume; File.Move is an atomic rename
    # and fails if the destination already exists. No -Force, overwrite or delete.
    [IO.File]::Move($Source, $Destination)
}
function Write-NewJson([string]$Path, $Value, [string]$Root) {
    Assert-InRoot $Path $Root
    $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::Read)
    try {
        $writer = [IO.StreamWriter]::new($stream, [Text.UTF8Encoding]::new($false))
        try { $writer.WriteLine(($Value | ConvertTo-Json -Depth 16)) } finally { $writer.Dispose() }
    } finally { $stream.Dispose() }
}
function InstalledFsrpgJars {
    Assert-InRoot $script:mods $script:instance
    return @(Get-ChildItem -LiteralPath $script:mods -Force -File | Where-Object { $_.Name -match '^fsrpg-neoforge-.*\.jar$' })
}

$project = FullPath (Split-Path -Parent $PSScriptRoot)
$instance = FullPath $Instance
if ($instance -ine (FullPath $officialInstance)) { throw "Only the verified official instance is allowed: $officialInstance" }
Assert-InRoot $instance $officialInstance
if (-not (Test-Path -LiteralPath $instance -PathType Container)) { throw "Official instance is missing: $instance" }
# First substantive preflight: do not inspect/build backups while the game runs.
Assert-NoGameRunning
$mods = Join-Path $instance 'mods'
$config = Join-Path $instance 'config'
$options = Join-Path $instance 'options.txt'
$oldJar = Join-Path $mods $oldName
$installed = Join-Path $mods $newName
$jar = if ([string]::IsNullOrWhiteSpace($BuildJar)) { Join-Path $project ('build/libs/' + $newName) } else { FullPath $BuildJar }
if ([IO.Path]::GetFileName($jar) -cne $newName) { throw "Expected the 0.10.2 build filename: $newName" }
foreach ($path in @($mods, $config, $options, $oldJar, $installed)) { Assert-InRoot $path $instance }
foreach ($path in @($mods, $config)) {
    if (-not (Test-Path -LiteralPath $path -PathType Container)) { throw "Required instance directory is missing: $path" }
}
if (Test-Path -LiteralPath $installed) { throw "New-version target already exists; refusing to overwrite: $installed" }

$versionFile = Join-Path $instance '1.21.11-NeoForge.json'
Assert-InRoot $versionFile $instance
$versionSha = Hash $versionFile
$metadata = Get-Content -LiteralPath $versionFile -Raw | ConvertFrom-Json
if ($metadata.id -cne '1.21.11-NeoForge' -or $metadata.mainClass -cne 'net.neoforged.fml.startup.Client') { throw 'Official launcher identity/main class mismatch' }
$arguments = @($metadata.arguments.game)
foreach ($pair in @(@('--fml.mcVersion', '1.21.11'), @('--fml.neoForgeVersion', '21.11.45'))) {
    $indices = @(for ($i = 0; $i -lt $arguments.Count; $i++) { if ($arguments[$i] -ceq $pair[0]) { $i } })
    if ($indices.Count -ne 1 -or $indices[0] + 1 -ge $arguments.Count -or $arguments[$indices[0] + 1] -cne $pair[1]) {
        throw "Official launcher version mismatch: $($pair[0])"
    }
}
$latestLog = Join-Path $instance 'logs/latest.log'
Assert-InRoot $latestLog $instance
$logSha = Hash $latestLog
$directoryMatches = [regex]::Matches((Get-Content -LiteralPath $latestLog -Raw), '(?m)Game directory:\s*([^\r\n]+)')
if ($directoryMatches.Count -eq 0) { throw 'latest.log does not report the actual game directory' }
$actualGameDirectory = FullPath ($directoryMatches[$directoryMatches.Count - 1].Groups[1].Value.Trim().Trim('"'))
if ($actualGameDirectory -ine $instance) { throw "Latest actual game directory differs from the intended instance: $actualGameDirectory" }
Assert-InRoot $actualGameDirectory $instance

$sha = Hash $jar
$evidenceRoot = Join-Path $project 'verification/camera-schedule-20261026'
$reviewFile = Join-Path $evidenceRoot 'game/camera-schedule-review.json'
$reloadFile = Join-Path $evidenceRoot 'game/camera-schedule-reload-review.json'
$visualFile = Join-Path $evidenceRoot 'visual-review.json'
$evidenceFile = Join-Path $evidenceRoot 'deployment.json'
foreach ($path in @($reviewFile, $reloadFile, $visualFile, $evidenceFile)) { Assert-InRoot $path $project }
$reviewSha = Hash $reviewFile
$reloadSha = Hash $reloadFile
$visualSha = Hash $visualFile
$review = Get-Content -LiteralPath $reviewFile -Raw | ConvertFrom-Json
$reload = Get-Content -LiteralPath $reloadFile -Raw | ConvertFrom-Json
$visual = Get-Content -LiteralPath $visualFile -Raw | ConvertFrom-Json
if ($review.passed -isnot [bool] -or -not $review.passed -or $review.modJarSha256 -cne $sha -or $review.monitorAdapter -cne '\\.\DISPLAY5') {
    throw 'DISPLAY5 game acceptance must pass for this exact 0.10.2 JAR SHA-256'
}
if ($visual.passed -isnot [bool] -or -not $visual.passed -or $visual.modJarSha256 -cne $sha) {
    throw 'Visual acceptance must pass for this exact 0.10.2 JAR SHA-256'
}
if ($reload.passed -isnot [bool] -or -not $reload.passed -or $reload.modJarSha256 -cne $sha -or $reload.monitorAdapter -cne '\\.\DISPLAY5') {
    throw 'A separate DISPLAY5 client launch must verify saved camera profiles for this exact JAR'
}
if (Test-Path -LiteralPath $evidenceFile) { throw "Deployment record already exists; refusing to overwrite: $evidenceFile" }
$oldJars = @(InstalledFsrpgJars)
if ($oldJars.Count -ne 1 -or $oldJars[0].Name -cne $oldName) { throw 'Expected exactly the installed 0.10.1 FSRPG JAR and no other FSRPG versions' }
Assert-Hash $oldJar $oldSha 'Installed 0.10.1 baseline'
$modsBefore = TreeSnapshot $mods
$configBefore = TreeSnapshot $config
$optionsBefore = OptionalFileSnapshot $options
$expectedFiles = [ordered]@{}
foreach ($key in $modsBefore.files.Keys) { $expectedFiles[$key] = $modsBefore.files[$key] }
$expectedFiles.Remove($oldName)
$expectedFiles[$newName] = $sha
$expectedMods = [pscustomobject]@{ files = $expectedFiles; directories = $modsBefore.directories }
Assert-NoGameRunning

if ($VerifyOnly) {
    Write-Output "VERIFY ONLY PASSED: $jar SHA-256 $sha; official 1.21.11/NeoForge 21.11.45; DISPLAY5 and visual evidence bound; baseline $oldSha; config files=$($configBefore.files.Count); no files written."
    return
}

$backupRoot = Join-Path $instance 'fsrpg-backups'
$stamp = [TimeZoneInfo]::ConvertTimeFromUtc([DateTime]::UtcNow, $releaseZone).ToString('yyyyMMdd-HHmmss-fff')
$backup = Join-Path $backupRoot ($stamp + '-before-0.10.2-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
$backupMods = Join-Path $backup 'mods'
$backupOld = Join-Path $backupMods $oldName
$staged = Join-Path $backup ($newName + '.staged')
$oldMoved = $false
$newInstalled = $false
try {
    Assert-InRoot $backupRoot $instance
    if (-not (Test-Path -LiteralPath $backupRoot)) { New-InstanceDirectory $backupRoot }
    if (-not (Test-Path -LiteralPath $backupRoot -PathType Container)) { throw 'Backup root is not a directory' }
    New-InstanceDirectory $backup
    New-InstanceDirectory $backupMods
    Copy-ConfigBackup $configBefore (Join-Path $backup 'config')
    if ($optionsBefore.exists) { Copy-NewVerifiedFile $options (Join-Path $backup 'options.txt') $optionsBefore.sha256 }
    Copy-NewVerifiedFile $jar $staged $sha
    Write-NewJson (Join-Path $backup 'before.json') ([ordered]@{
        version = '0.10.2'; priorJar = $oldJar; priorJarSha256 = $oldSha; testedJar = $jar; testedJarSha256 = $sha
        mods = $modsBefore; completeConfig = $configBefore; options = $optionsBefore
        launcherMetadataSha256 = $versionSha; latestLogSha256 = $logSha
        gameReviewSha256 = $reviewSha; visualReviewSha256 = $visualSha
        profileReloadReviewSha256 = $reloadSha
    }) $instance

    # Nothing in the original instance has been replaced yet. Recheck every
    # protected input immediately before the two exclusive rename operations.
    Assert-NoGameRunning
    Assert-Hash $versionFile $versionSha 'Launcher metadata'
    Assert-Hash $latestLog $logSha 'Actual-game-directory evidence'
    Assert-Hash $reviewFile $reviewSha 'Game acceptance'
    Assert-Hash $reloadFile $reloadSha 'Separate-process camera profile persistence acceptance'
    Assert-Hash $visualFile $visualSha 'Visual acceptance'
    Assert-Hash $jar $sha 'Tested build'
    Assert-Snapshot $modsBefore $mods 'Original mods'
    Assert-Snapshot $configBefore $config 'Original complete config tree'
    Assert-OptionalFile $optionsBefore $options 'Original options.txt'
    Assert-Hash $staged $sha 'Staged non-JAR'
    Assert-Hash $oldJar $oldSha 'Old JAR immediately before moving'
    Move-InstanceFileExclusive $oldJar $backupOld
    $oldMoved = $true
    Assert-Hash $backupOld $oldSha 'Moved old JAR backup'
    Assert-NoGameRunning
    Move-InstanceFileExclusive $staged $installed
    $newInstalled = $true
    Assert-Hash $installed $sha 'Installed exact tested JAR'
    Assert-Snapshot $expectedMods $mods 'Only the FSRPG JAR replacement'
    Assert-Snapshot $configBefore $config 'Byte-exact complete config preservation'
    Assert-OptionalFile $optionsBefore $options 'Byte-exact options.txt preservation'
    Assert-Snapshot $configBefore (Join-Path $backup 'config') 'Verified complete config backup'
    if ($optionsBefore.exists) { Assert-Hash (Join-Path $backup 'options.txt') $optionsBefore.sha256 'Verified options backup' }
    $record = [ordered]@{
        version = '0.10.2'; installedJar = $installed; jarSha256 = $sha; testedJar = $jar
        officialInstance = $instance; minecraftVersion = '1.21.11'; neoForgeVersion = '21.11.45'
        backup = $backup; previousJar = [ordered]@{ name = $oldName; sha256 = $oldSha }
        completeConfigPreserved = $true; configurationMigration = $false; configSnapshot = $configBefore
        optionsPreserved = $true; optionsSnapshot = $optionsBefore; otherModsPreserved = $true; worldsModified = $false
        modsBefore = $modsBefore; modsAfter = $expectedMods
        gameReview = $reviewFile; gameReviewSha256 = $reviewSha; visualReview = $visualFile; visualReviewSha256 = $visualSha
        profileReloadReview = $reloadFile; profileReloadReviewSha256 = $reloadSha
        monitorAdapter = '\\.\DISPLAY5'; deployedAt = [TimeZoneInfo]::ConvertTimeFromUtc([DateTime]::UtcNow, $releaseZone).ToString('o')
    }
    Write-NewJson (Join-Path $backup 'deployment.json') $record $instance
    Write-NewJson $evidenceFile $record $project
    Write-Output "Installed $installed; SHA-256 $sha; complete config/options and other mods unchanged; backup $backup"
} catch {
    $failure = $_.Exception.Message
    $rollbackNotes = [Collections.Generic.List[string]]::new()
    try {
        if ($oldMoved -or $newInstalled) {
            Assert-NoGameRunning
            if ($newInstalled -and (Test-Path -LiteralPath $installed)) {
                # Never remove or overwrite a concurrently edited/replaced JAR.
                Assert-Hash $installed $sha 'Rollback-owned new JAR'
                $failedNew = Join-Path $backup ($newName + '.failed.staged')
                Move-InstanceFileExclusive $installed $failedNew
                if ((Hash $failedNew) -cne $sha) {
                    # The path changed between validation and rename. Put that
                    # external file back only if nobody has occupied its path.
                    if (-not (Test-Path -LiteralPath $installed)) { Move-InstanceFileExclusive $failedNew $installed }
                    throw 'New JAR changed concurrently during rollback; no old JAR was restored over it'
                }
            }
            if ($oldMoved) {
                $backupOldSha = Hash $backupOld
                if ($backupOldSha -cne $oldSha) {
                    # A writer may have changed the original after its last
                    # validation but before our rename. Return those exact
                    # external bytes only to an empty installation, then report
                    # the conflict rather than replacing them with our baseline.
                    if ((Test-Path -LiteralPath $oldJar) -or @(InstalledFsrpgJars).Count -ne 0) {
                        throw 'Old JAR changed concurrently; its moved bytes remain in backup because the original installation is occupied'
                    }
                    Move-InstanceFileExclusive $backupOld $oldJar
                    Assert-Hash $oldJar $backupOldSha 'Returned concurrently changed old JAR'
                    throw 'Old JAR changed concurrently; its bytes were returned to the empty original path without overwriting anything; baseline rollback needs review'
                }
                if (Test-Path -LiteralPath $oldJar) {
                    Assert-Hash $oldJar $oldSha 'Concurrently restored old JAR'
                } else {
                    if (@(InstalledFsrpgJars).Count -ne 0) { throw 'Another FSRPG JAR appeared; refusing to create competing installed versions during rollback' }
                    Move-InstanceFileExclusive $backupOld $oldJar
                    Assert-Hash $oldJar $oldSha 'Restored baseline JAR'
                }
            }
            Assert-Snapshot $modsBefore $mods 'Mods after rollback'
        }
    } catch { $rollbackNotes.Add($_.Exception.Message) }
    # We never wrote these files, so external edits must NOT be overwritten by
    # their backup. Report differences instead of pretending to roll them back.
    try { Assert-Snapshot $configBefore $config 'Config after failed deployment' } catch { $rollbackNotes.Add($_.Exception.Message) }
    try { Assert-OptionalFile $optionsBefore $options 'Options after failed deployment' } catch { $rollbackNotes.Add($_.Exception.Message) }
    try {
        if (Test-Path -LiteralPath $backup -PathType Container) {
            Write-NewJson (Join-Path $backup 'deployment-failure.json') ([ordered]@{
                deploymentSucceeded = $false; failure = $failure; rollbackComplete = ($rollbackNotes.Count -eq 0)
                rollbackNotes = @($rollbackNotes); priorJarSha256 = $oldSha; attemptedJarSha256 = $sha
                supersedesAnyEarlierDeploymentRecord = $true; concurrentEditsNotOverwritten = $true
                recordedAt = [TimeZoneInfo]::ConvertTimeFromUtc([DateTime]::UtcNow, $releaseZone).ToString('o')
            }) $instance
        }
    } catch { $rollbackNotes.Add('Could not write failure journal: ' + $_.Exception.Message) }
    if ($rollbackNotes.Count -gt 0) {
        throw "Deployment failed: $failure. Automatic rollback needs attention: $($rollbackNotes -join ' | '). Concurrent edits were not overwritten. Backup: $backup"
    }
    throw "Deployment failed: $failure. Owned JAR changes were rolled back; original config/options preserved. Backup: $backup"
}
