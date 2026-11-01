param(
    [switch]$VerifyOnly,
    [string]$BuildJar = '',
    [string]$Instance = 'C:\Users\34003\AppData\Roaming\.minecraft\versions\1.21.11-NeoForge',
    [string]$EvidenceDirectory = '',
    [string]$StageDirectory = '',
    [string]$StageReport = ''
)

# Apply only the reviewed field-preserving production migration from an isolated
# exact-JAR stage. Every original configuration file is backed up before any write.
# VerifyOnly performs the same preflight reads, then returns before any write.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$officialInstance = 'C:\Users\34003\AppData\Roaming\.minecraft\versions\1.21.11-NeoForge'
$oldName = 'fsrpg-neoforge-0.11.0.jar'
$oldSha = '3c18f0e3581cc2c51649f71866f421d8bae3bdc16bafd7757a3b6b735590d66b'
$newName = 'fsrpg-neoforge-0.12.0.jar'
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

function Property-Or($Value, [string]$Name, $Fallback = $null) {
    $property = $Value.PSObject.Properties[$Name]
    if ($null -eq $property) { return $Fallback }
    return $property.Value
}
function Read-LauncherVersion([string]$Id, [Collections.Generic.HashSet[string]]$Visited) {
    if ($Id -notmatch '^[A-Za-z0-9_.-]+$' -or -not $Visited.Add($Id) -or $Visited.Count -gt 16) {
        throw "Invalid or cyclic launcher version inheritance: $Id"
    }
    $file = Join-Path $script:versions ($Id + '/' + $Id + '.json')
    Assert-InRoot $file $script:versions
    $script:launcherInputs[$file] = Hash $file
    $value = Get-Content -LiteralPath $file -Raw | ConvertFrom-Json
    if ($value.id -cne $Id) { throw "Launcher version ID mismatch: $file" }
    $parent = [string](Property-Or $value 'inheritsFrom' '')
    $chain = if ($parent) { @(Read-LauncherVersion $parent $Visited) } else { @() }
    return @($chain) + @($value)
}
function Profile-UsesTarget([string]$Id) {
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    while ($Id -and $Id -match '^[A-Za-z0-9_.-]+$' -and $seen.Add($Id) -and $seen.Count -le 16) {
        if ($Id -ceq '1.21.11-NeoForge') { return $true }
        $file = Join-Path $script:versions ($Id + '/' + $Id + '.json')
        Assert-InRoot $file $script:versions
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { return $false }
        $script:launcherInputs[$file] = Hash $file
        $value = Get-Content -LiteralPath $file -Raw | ConvertFrom-Json
        $Id = [string](Property-Or $value 'inheritsFrom' '')
    }
    return $false
}
function Assert-ConfigPreserved($Original, $Expected) {
    # Complete expected inventory includes precisely the initially absent inactive seeds.
    Assert-Snapshot $Expected $script:config 'Original config plus declared inactive avatar seeds'
    foreach ($relative in $Original.files.Keys) {
        Assert-Hash (Join-Path $script:config $relative) $Original.files[$relative] ('Preserved existing config ' + $relative)
    }
}
function Ensure-NewDirectory([string]$Path, [string]$Root, [Collections.Generic.List[string]]$Created) {
    Assert-InRoot $Path $Root
    if (Test-Path -LiteralPath $Path -PathType Container) { return }
    if (Test-Path -LiteralPath $Path) { throw "Required directory is occupied by a file: $Path" }
    $parent = [IO.Path]::GetDirectoryName($Path)
    if ($parent -ine (FullPath $Root)) { Ensure-NewDirectory $parent $Root $Created }
    New-InstanceDirectory $Path
    if ($null -ne $Created) { $Created.Add($Path) }
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
$minecraft = FullPath (Split-Path -Parent (Split-Path -Parent $instance))
$versions = Join-Path $minecraft 'versions'
$options = Join-Path $instance 'options.txt'
$oldJar = Join-Path $mods $oldName
$installed = Join-Path $mods $newName
$jar = if ([string]::IsNullOrWhiteSpace($BuildJar)) { Join-Path $project ('build/libs/' + $newName) } else { FullPath $BuildJar }
if ([IO.Path]::GetFileName($jar) -cne $newName) { throw "Expected the 0.12.0 build filename: $newName" }
foreach ($path in @($mods, $config, $options, $oldJar, $installed)) { Assert-InRoot $path $instance }
foreach ($path in @($mods, $config)) {
    if (-not (Test-Path -LiteralPath $path -PathType Container)) { throw "Required instance directory is missing: $path" }
}
if (Test-Path -LiteralPath $installed) { throw "New-version target already exists; refusing to overwrite: $installed" }

$versionFile = Join-Path $instance '1.21.11-NeoForge.json'
Assert-InRoot $versionFile $instance
$versionSha = Hash $versionFile
$metadata = Get-Content -LiteralPath $versionFile -Raw | ConvertFrom-Json
if ($metadata.id -cne '1.21.11-NeoForge') { throw 'Official launcher identity mismatch' }
$launcherInputs = [ordered]@{}
$chain = @(Read-LauncherVersion '1.21.11-NeoForge' ([Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)))
$mainClass = ''
foreach ($entry in $chain) { $mainClass = [string](Property-Or $entry 'mainClass' $mainClass) }
if ($mainClass -cne 'net.neoforged.fml.startup.Client') { throw 'Official launcher inherited main class mismatch' }
$arguments = @(foreach ($entry in $chain) {
    $entryArguments = Property-Or $entry 'arguments'
    if ($null -ne $entryArguments) { @(Property-Or $entryArguments 'game' @()) }
})
foreach ($pair in @(@('--fml.mcVersion', '1.21.11'), @('--fml.neoForgeVersion', '21.11.45'))) {
    $indices = @(for ($i = 0; $i -lt $arguments.Count; $i++) { if ($arguments[$i] -ceq $pair[0]) { $i } })
    if ($indices.Count -ne 1 -or $indices[0] + 1 -ge $arguments.Count -or $arguments[$indices[0] + 1] -cne $pair[1]) {
        throw "Official launcher version mismatch: $($pair[0])"
    }
}
$profileMatches = [Collections.Generic.List[object]]::new()
Assert-NoReparsePath $minecraft
foreach ($profileFile in @(Get-ChildItem -LiteralPath $minecraft -Filter 'launcher_profiles*.json' -File)) {
    Assert-InRoot $profileFile.FullName $minecraft
    $launcherInputs[$profileFile.FullName] = Hash $profileFile.FullName
    $profiles = Property-Or (Get-Content -LiteralPath $profileFile.FullName -Raw | ConvertFrom-Json) 'profiles'
    if ($null -eq $profiles) { continue }
    foreach ($profile in $profiles.PSObject.Properties) {
        $id = [string](Property-Or $profile.Value 'lastVersionId' '')
        if (-not (Profile-UsesTarget $id)) { continue }
        $directory = [string](Property-Or $profile.Value 'gameDir' $minecraft)
        if ([string]::IsNullOrWhiteSpace($directory)) { $directory = $minecraft }
        $directory = FullPath ([Environment]::ExpandEnvironmentVariables($directory))
        if ($directory -ine $instance) { throw "Launcher profile $($profile.Name) targets a different game directory: $directory" }
        $profileMatches.Add([pscustomobject]@{ file = $profileFile.FullName; key = $profile.Name; lastVersionId = $id; gameDirectory = $directory })
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
$evidenceRoot = if ([string]::IsNullOrWhiteSpace($EvidenceDirectory)) { Join-Path $project 'verification/full-body-sword-20261031' } else { FullPath $EvidenceDirectory }
Assert-InRoot $evidenceRoot $project
$reviewFile = Join-Path $evidenceRoot 'game/full-body-sword-review.json'
$visualFile = Join-Path $evidenceRoot 'visual-review.json'
$evidenceFile = Join-Path $evidenceRoot 'deployment.json'
$stage = if ([string]::IsNullOrWhiteSpace($StageDirectory)) { Join-Path $evidenceRoot 'fsrpg' } else { FullPath $StageDirectory }
$stageReportFile = if ([string]::IsNullOrWhiteSpace($StageReport)) { Join-Path $evidenceRoot 'stage-config.json' } else { FullPath $StageReport }
Assert-InRoot $stage (Join-Path $project 'verification')
Assert-InRoot $stageReportFile (Join-Path $project 'verification')
if ([IO.Path]::GetFileName($stage) -cne 'fsrpg') { throw 'Stage must be an isolated verification/.../fsrpg directory' }
$stageReportSha = Hash $stageReportFile
$staging = Get-Content -LiteralPath $stageReportFile -Raw | ConvertFrom-Json
if ($staging.passed -isnot [bool] -or -not $staging.passed -or $staging.version -cne '0.12.0' -or
    (FullPath $staging.directory) -ine (FullPath $stage) -or $staging.modJarSha256 -cne $sha -or
    $staging.secondReloadIdempotent -ne $true -or $staging.preexistingFilesRetained -ne $true -or $staging.migration.customFieldsPreserved -ne $true) {
    throw 'The same-JAR production migration stage must pass and identify the exact candidate'
}
$stageBefore = [ordered]@{}
$stageAfter = [ordered]@{}
foreach ($property in $staging.beforeSha256.PSObject.Properties) { $stageBefore[$property.Name] = [string]$property.Value }
foreach ($property in $staging.afterSha256.PSObject.Properties) { $stageAfter[$property.Name] = [string]$property.Value }
$liveFsrpg = Join-Path $config 'fsrpg'
$liveFsrpgSnapshot = TreeSnapshot $liveFsrpg
$stageSnapshot = TreeSnapshot $stage
if ($stageBefore.Count -ne $liveFsrpgSnapshot.files.Count -or $stageAfter.Count -ne $stageSnapshot.files.Count) { throw 'Official/staged file inventory differs from migration evidence' }
foreach ($relative in $stageBefore.Keys) {
    if (-not $liveFsrpgSnapshot.files.Contains($relative) -or $liveFsrpgSnapshot.files[$relative] -cne $stageBefore[$relative]) { throw "Official config preimage differs from the production stage: $relative" }
    if (-not $stageAfter.Contains($relative)) { throw "Stage deletes an existing config: $relative" }
}
foreach ($relative in $stageAfter.Keys) {
    if (-not $stageSnapshot.files.Contains($relative) -or $stageSnapshot.files[$relative] -cne $stageAfter[$relative]) { throw "Staged config differs from evidence: $relative" }
}
$configChanges = @($stageAfter.Keys | Where-Object { -not $stageBefore.Contains($_) -or $stageBefore[$_] -cne $stageAfter[$_] })
foreach ($relative in $configChanges) {
    if ($relative -cnotmatch '^(combat\.json|skills\.json|skill_trees\.json|sword-combat-migration-v1\.json|sword-combat-backup-v1/(plan\.json|combat\.json|skills\.json|skill_trees\.json)|models/rapier_fullbody(_fp)?\.fsvoxel)$') { throw "Unexpected production migration change: $relative" }
    if ($relative.StartsWith('models/') -and $stageBefore.Contains($relative)) { throw "Existing custom model would be overwritten: $relative" }
    if ($relative -match '^sword-combat-backup-v1/(combat\.json|skills\.json|skill_trees\.json)$' -and $stageAfter[$relative] -cne $stageBefore[$Matches[1]]) { throw "Migration backup differs from the actual preimage: $relative" }
}
foreach ($path in @($reviewFile, $visualFile, $evidenceFile)) { Assert-InRoot $path $project }
$reviewSha = Hash $reviewFile
$visualSha = Hash $visualFile
$review = Get-Content -LiteralPath $reviewFile -Raw | ConvertFrom-Json
$visual = Get-Content -LiteralPath $visualFile -Raw | ConvertFrom-Json
if ($review.passed -isnot [bool] -or -not $review.passed -or $review.modJarSha256 -cne $sha -or $review.monitorAdapter -cne '\\.\DISPLAY5') {
    throw 'Full-body sword acceptance must pass on DISPLAY5 for the exact 0.12.0 JAR SHA-256'
}
$expectedScenes = @('standing_chain','walking_chain','stop_and_guard','dodge_cancel','wall_contact','c_guard','x_miss','x_contact_return','x_punished','heavy_partial','heavy_full','melee_clash','immune_target','first_person_chain','spear_guard','spear_thrust')
$sceneIds = @($review.scenes | ForEach-Object id)
if ($sceneIds.Count -ne $expectedScenes.Count -or @($sceneIds | Select-Object -Unique).Count -ne $expectedScenes.Count) { throw 'Acceptance scenario inventory is incomplete or duplicated' }
foreach ($expected in $expectedScenes) {
    $scene = @($review.scenes | Where-Object id -CEQ $expected)
    if ($scene.Count -ne 1 -or $scene[0].passed -isnot [bool] -or -not $scene[0].passed) { throw "Missing successful native acceptance scenario: $expected" }
}
$clips = @($review.renderCapture.clips)
if ($clips.Count -ne $expectedScenes.Count -or $review.renderCapture.pendingWrites -ne 0) { throw 'Native-frame capture is incomplete or still pending' }
foreach ($clip in $clips) {
    if ($clip.sourceFps -lt 30 -or $clip.capturedFrames -lt 8 -or $clip.capturedFrames -ne $clip.writtenFrames) { throw "Invalid actual rendered-frame recording: $($clip.id)" }
}
if ($visual.passed -isnot [bool] -or -not $visual.passed -or $visual.modJarSha256 -cne $sha -or $visual.monitorAdapter -cne '\\.\DISPLAY5') {
    throw 'Independent visual acceptance must pass for the same JAR and DISPLAY5'
}
$imageEvidence = [ordered]@{}
foreach ($shot in @($visual.staticImages)) {
    if ($shot.actuallyViewedOriginal -isnot [bool] -or -not $shot.actuallyViewedOriginal -or [string]::IsNullOrWhiteSpace($shot.observation)) { throw 'Visual evidence must record actual original-image inspection and observations' }
    $path = FullPath (Join-Path $evidenceRoot ([string]$shot.file))
    Assert-InRoot $path $evidenceRoot
    Assert-Hash $path ([string]$shot.sha256) 'Actually inspected screenshot'
    if ($imageEvidence.Contains($path)) { throw 'Duplicate visual evidence screenshot' }
    $imageEvidence[$path] = [string]$shot.sha256
}
if ($imageEvidence.Count -ne $expectedScenes.Count) { throw 'Every acceptance scenario requires an original screenshot visual review' }
foreach ($shot in @($review.screenshots)) {
    $path = FullPath (Join-Path (Join-Path $evidenceRoot 'game') ([string]$shot.file))
    if (-not $imageEvidence.Contains($path)) { throw "Native screenshot lacks independent visual acceptance: $path" }
}
$encodingFile = FullPath (Join-Path $evidenceRoot ([string]$visual.encodingSummary))
Assert-InRoot $encodingFile $evidenceRoot
$encodingSha = [string]$visual.encodingSummarySha256
Assert-Hash $encodingFile $encodingSha 'Independently verified source timestamps and encoding summary'
$encoding = Get-Content -LiteralPath $encodingFile -Raw | ConvertFrom-Json
if ($encoding.passed -isnot [bool] -or -not $encoding.passed) { throw 'Native frames and source timestamps have not passed independent verification' }
if (Test-Path -LiteralPath $evidenceFile) { throw "Deployment record already exists: $evidenceFile" }

# The published JAR may contain only production content. New files are supplied
# inside the tested JAR; external configuration is never replaced by default bytes.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($jar)
try {
    if (@($archive.Entries | Where-Object { $_.FullName -match '^org/fsrpg/(smoke|dev)/' }).Count -gt 0) { throw 'Production JAR contains development smoke/monitor classes' }
    foreach ($model in @('rapier_fullbody.fsvoxel','rapier_fullbody_fp.fsvoxel')) {
        if ($null -eq $archive.GetEntry('assets/fsrpg/fsvoxel/models/' + $model)) { throw "Published JAR lacks tested full-body model: $model" }
    }
} finally { $archive.Dispose() }
$oldJars = @(InstalledFsrpgJars)
if ($oldJars.Count -ne 1 -or $oldJars[0].Name -cne $oldName) { throw 'Expected exactly the verified 0.11.0 FSRPG JAR' }
Assert-Hash $oldJar $oldSha 'Installed 0.11.0 baseline'
$modsBefore = TreeSnapshot $mods
$configBefore = TreeSnapshot $config
$expectedConfigFiles = [ordered]@{}
foreach ($relative in $configBefore.files.Keys) { $expectedConfigFiles[$relative] = $configBefore.files[$relative] }
foreach ($relative in $configChanges) { $expectedConfigFiles['fsrpg/' + $relative] = $stageAfter[$relative] }
$expectedConfigDirectories = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($relative in $configBefore.directories) { [void]$expectedConfigDirectories.Add($relative) }
foreach ($relative in $stageSnapshot.directories) { [void]$expectedConfigDirectories.Add('fsrpg/' + $relative) }
$expectedConfig = [pscustomobject]@{ files=$expectedConfigFiles; directories=@($expectedConfigDirectories | Sort-Object) }
$optionsBefore = OptionalFileSnapshot $options
$expectedFiles = [ordered]@{}
foreach ($key in $modsBefore.files.Keys) { $expectedFiles[$key] = $modsBefore.files[$key] }
$expectedFiles.Remove($oldName)
$expectedFiles[$newName] = $sha
$expectedMods = [pscustomobject]@{ files = $expectedFiles; directories = $modsBefore.directories }
Assert-NoGameRunning
if ($VerifyOnly) {
    Write-Output "VERIFY ONLY PASSED: $jar SHA-256 $sha; official Minecraft 1.21.11/NeoForge 21.11.45; 16 DISPLAY5 scenarios and visual evidence; baseline $oldSha; field-preserving same-JAR config changes=$($configChanges.Count); fullBodyEnabled=$($staging.fullBodyEnabled); actual game directory verified; no files written."
    return
}

$backupRoot = Join-Path $instance 'fsrpg-backups'
$stamp = [TimeZoneInfo]::ConvertTimeFromUtc([DateTime]::UtcNow, $releaseZone).ToString('yyyyMMdd-HHmmss-fff')
$backup = Join-Path $backupRoot ($stamp + '-before-0.12.0-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
$backupMods = Join-Path $backup 'mods'
$backupOld = Join-Path $backupMods $oldName
$staged = Join-Path $backup ($newName + '.staged')
$oldMoved = $false
$newInstalled = $false
$configWrites = [Collections.Generic.List[object]]::new()
$createdConfigDirectories = [Collections.Generic.List[string]]::new()
try {
    Assert-InRoot $backupRoot $instance
    if (-not (Test-Path -LiteralPath $backupRoot)) { New-InstanceDirectory $backupRoot }
    New-InstanceDirectory $backup
    New-InstanceDirectory $backupMods
    Copy-ConfigBackup $configBefore (Join-Path $backup 'config')
    if ($optionsBefore.exists) { Copy-NewVerifiedFile $options (Join-Path $backup 'options.txt') $optionsBefore.sha256 }
    Copy-NewVerifiedFile $jar $staged $sha
    Write-NewJson (Join-Path $backup 'before.json') ([ordered]@{
        version='0.12.0'; priorJar=$oldJar; priorJarSha256=$oldSha; testedJar=$jar; testedJarSha256=$sha
        mods=$modsBefore; completeConfig=$configBefore; options=$optionsBefore
        launcherMetadataSha256=$versionSha; latestLogSha256=$logSha; launcherInputs=$launcherInputs
        gameReviewSha256=$reviewSha; visualReviewSha256=$visualSha; encodingSummarySha256=$encodingSha; visualImages=$imageEvidence
        stageReport=$stageReportFile; stageReportSha256=$stageReportSha; configurationBefore=$stageBefore; configurationAfter=$stageAfter
    }) $instance
    Assert-NoGameRunning
    Assert-Hash $versionFile $versionSha 'Launcher metadata'
    Assert-Hash $latestLog $logSha 'Actual game directory evidence'
    foreach ($path in $launcherInputs.Keys) { Assert-Hash $path $launcherInputs[$path] 'Launcher inheritance/profile' }
    Assert-Hash $reviewFile $reviewSha 'Native acceptance'
    Assert-Hash $visualFile $visualSha 'Visual acceptance'
    Assert-Hash $encodingFile $encodingSha 'Source-frame verification'
    foreach ($path in $imageEvidence.Keys) { Assert-Hash $path $imageEvidence[$path] 'Inspected original screenshot' }
    Assert-Hash $jar $sha 'Tested build'
    Assert-Hash $stageReportFile $stageReportSha 'Same-JAR production stage report'
    Assert-Snapshot $stageSnapshot $stage 'Reviewed staged migration result'
    Assert-Snapshot $modsBefore $mods 'Original mods'
    Assert-Snapshot $configBefore $config 'Complete original configuration'
    Assert-OptionalFile $optionsBefore $options 'Original options'
    Assert-Hash $staged $sha 'Staged exact tested JAR'
    Assert-Hash $oldJar $oldSha 'Old JAR immediately before moving'
    $stageConfigRoot=Join-Path $backup 'staged-config'
    $preimageRoot=Join-Path $backup 'config-preimages'
    New-InstanceDirectory $stageConfigRoot
    New-InstanceDirectory $preimageRoot
    foreach ($relative in $configChanges) {
        Assert-NoGameRunning
        $destination=Join-Path $liveFsrpg $relative
        $stagedConfig=Join-Path $stageConfigRoot $relative
        $preimage=Join-Path $preimageRoot $relative
        Ensure-NewDirectory ([IO.Path]::GetDirectoryName($stagedConfig)) $stageConfigRoot $null
        Ensure-NewDirectory ([IO.Path]::GetDirectoryName($preimage)) $preimageRoot $null
        Ensure-NewDirectory ([IO.Path]::GetDirectoryName($destination)) $config $createdConfigDirectories
        Copy-NewVerifiedFile (Join-Path $stage $relative) $stagedConfig $stageAfter[$relative]
        $previous=if($stageBefore.Contains($relative)){$stageBefore[$relative]}else{$null}
        if($null -ne $previous){Assert-Hash $destination $previous 'Configuration preimage immediately before atomic replacement'}
        elseif(Test-Path -LiteralPath $destination){throw "Concurrent file occupies new configuration destination: $destination"}
        $write=[pscustomobject]@{relative=$relative;destination=$destination;preimage=$preimage;previous=$previous;sha256=$stageAfter[$relative];committed=$false}
        $configWrites.Add($write)
        if($null -ne $previous){[IO.File]::Replace($stagedConfig,$destination,$preimage,$false)}
        else{[IO.File]::Move($stagedConfig,$destination)}
        $write.committed=$true
        Assert-Hash $destination $write.sha256 'Applied reviewed configuration'
        if($null -ne $previous){Assert-Hash $preimage $previous 'Atomic configuration preimage'}
    }
    Assert-Snapshot $expectedConfig $config 'Exact field-preserving configuration migration'
    Assert-NoGameRunning
    Move-InstanceFileExclusive $oldJar $backupOld
    $oldMoved=$true
    Assert-Hash $backupOld $oldSha 'Backed-up old JAR'
    Assert-NoGameRunning
    Move-InstanceFileExclusive $staged $installed
    $newInstalled=$true
    Assert-Hash $installed $sha 'Installed exact tested JAR'
    Assert-Snapshot $expectedMods $mods 'Only intended FSRPG replacement'
    Assert-Snapshot $expectedConfig $config 'Reviewed production configuration migration'
    Assert-Snapshot $configBefore (Join-Path $backup 'config') 'Complete verified configuration backup'
    Assert-OptionalFile $optionsBefore $options 'Byte-preserved original options'
    $record=[ordered]@{
        version='0.12.0'; installedJar=$installed; jarSha256=$sha; testedJar=$jar; officialInstance=$instance
        minecraftVersion='1.21.11'; neoForgeVersion='21.11.45'; monitorAdapter='\\.\DISPLAY5'
        backup=$backup; previousJar=[ordered]@{name=$oldName;sha256=$oldSha}
        configBefore=$configBefore; configAfter=$expectedConfig; customFieldsPreserved=$true; configurationMigrationByDeployment=$true
        stageDirectory=$stage; stageReport=$stageReportFile; stageReportSha256=$stageReportSha; fullBodyEnabled=$staging.fullBodyEnabled; configurationChanges=$configChanges
        optionsPreserved=$true; otherModsPreserved=$true; worldsModified=$false; modsBefore=$modsBefore; modsAfter=$expectedMods
        gameReview=$reviewFile; gameReviewSha256=$reviewSha; visualReview=$visualFile; visualReviewSha256=$visualSha
        encodingSummary=$encodingFile; encodingSummarySha256=$encodingSha; visualImages=$imageEvidence
        actualGameDirectory=$actualGameDirectory; actualGameDirectoryEvidence=$latestLog; launcherInputs=$launcherInputs
        matchingLauncherProfiles=$profileMatches.ToArray(); deployedAt=[TimeZoneInfo]::ConvertTimeFromUtc([DateTime]::UtcNow,$releaseZone).ToString('o')
    }
    Write-NewJson (Join-Path $backup 'deployment.json') $record $instance
    Write-NewJson $evidenceFile $record $project
    Write-Output "Installed $installed; SHA-256 $sha; reviewed config changes=$($configChanges.Count), fullBodyEnabled=$($staging.fullBodyEnabled); custom fields/options, other mods and worlds preserved; backup $backup"
} catch {
    $failure=$_.Exception.Message
    $rollbackNotes=[Collections.Generic.List[string]]::new()
    try {
        Assert-NoGameRunning
        $failedConfigRoot=Join-Path $backup 'failed-config'
        if(@($configWrites | Where-Object committed).Count -gt 0){Ensure-NewDirectory $failedConfigRoot $backup $null}
        for($i=$configWrites.Count-1;$i -ge 0;$i--){
            $write=$configWrites[$i];if(-not $write.committed){continue}
            Assert-Hash $write.destination $write.sha256 'Rollback-owned migrated configuration'
            $failed=Join-Path $failedConfigRoot $write.relative
            Ensure-NewDirectory ([IO.Path]::GetDirectoryName($failed)) $failedConfigRoot $null
            if($null -ne $write.previous){Assert-Hash $write.preimage $write.previous 'Exact atomic configuration preimage';[IO.File]::Replace($write.preimage,$write.destination,$failed,$false);Assert-Hash $write.destination $write.previous 'Restored exact original config'}
            else{Move-InstanceFileExclusive $write.destination $failed}
        }
        foreach($directory in @($createdConfigDirectories | Sort-Object Length -Descending)){Assert-InRoot $directory $config;if((Test-Path -LiteralPath $directory -PathType Container)-and @(Get-ChildItem -LiteralPath $directory -Force).Count -eq 0){[IO.Directory]::Delete($directory,$false)}}
    } catch { $rollbackNotes.Add('Configuration rollback: '+$_.Exception.Message) }
    try {
        Assert-NoGameRunning
        if ($newInstalled -and (Test-Path -LiteralPath $installed)) {
            Assert-Hash $installed $sha 'Rollback-owned new JAR'
            Move-InstanceFileExclusive $installed (Join-Path $backup ($newName + '.failed.staged'))
        }
        if ($oldMoved) {
            Assert-Hash $backupOld $oldSha 'Verified rollback baseline'
            if (Test-Path -LiteralPath $oldJar) { Assert-Hash $oldJar $oldSha 'Concurrently restored old JAR' }
            else {
                if (@(InstalledFsrpgJars).Count -ne 0) { throw 'Another FSRPG JAR appeared; refusing to overwrite it or install competing versions' }
                Move-InstanceFileExclusive $backupOld $oldJar
                Assert-Hash $oldJar $oldSha 'Restored old JAR'
            }
        }
        Assert-Snapshot $modsBefore $mods 'Mods after rollback'
    } catch { $rollbackNotes.Add($_.Exception.Message) }
    try { Assert-Snapshot $configBefore $config 'Preserved configurations after failure' } catch { $rollbackNotes.Add($_.Exception.Message) }
    try { Assert-OptionalFile $optionsBefore $options 'Preserved options after failure' } catch { $rollbackNotes.Add($_.Exception.Message) }
    if (Test-Path -LiteralPath $backup -PathType Container) {
        try { Write-NewJson (Join-Path $backup 'deployment-failure.json') ([ordered]@{
            deploymentSucceeded=$false; failure=$failure; rollbackComplete=($rollbackNotes.Count -eq 0); rollbackNotes=@($rollbackNotes)
            supersedesAnyEarlierDeploymentRecord=$true; concurrentEditsNotOverwritten=$true
        }) $instance } catch { $rollbackNotes.Add($_.Exception.Message) }
    }
    throw "Deployment failed: $failure. Rollback notes: $($rollbackNotes -join ' | '). Backup: $backup"
}
