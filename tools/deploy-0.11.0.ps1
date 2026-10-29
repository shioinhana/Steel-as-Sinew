param(
    [switch]$VerifyOnly,
    [string]$BuildJar = '',
    [string]$Instance = 'C:\Users\34003\AppData\Roaming\.minecraft\versions\1.21.11-NeoForge',
    [string]$CameraEvidenceDirectory = ''
)

# Existing configuration bytes are preserved. Only absent README/examples from
# avatar-seeds-0.11.0.json are seeded; no player/default/UUID assignment is activated.
# VerifyOnly performs the same preflight reads, then returns before any write.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$officialInstance = 'C:\Users\34003\AppData\Roaming\.minecraft\versions\1.21.11-NeoForge'
$oldName = 'fsrpg-neoforge-0.10.1.jar'
$oldSha = '7973485fe22019b360792f62595b5094a54f006e3f8a24add73218105fc41502'
$newName = 'fsrpg-neoforge-0.11.0.jar'
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
if ([IO.Path]::GetFileName($jar) -cne $newName) { throw "Expected the 0.11.0 build filename: $newName" }
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
$evidenceRoot = Join-Path $project 'verification/avatar-20261030'
$avatarFile = Join-Path $evidenceRoot 'game/avatar-appearance-review.json'
$cameraDirectory = if ([string]::IsNullOrWhiteSpace($CameraEvidenceDirectory)) { Join-Path $evidenceRoot 'camera' } else { FullPath $CameraEvidenceDirectory }
Assert-InRoot $cameraDirectory $project
$reviewFile = Join-Path $cameraDirectory 'camera-schedule-review.json'
$reloadFile = Join-Path $cameraDirectory 'camera-schedule-reload-review.json'
$cameraVisualFile = Join-Path $cameraDirectory 'visual-review.json'
$visualFile = Join-Path $evidenceRoot 'visual-review.json'
$evidenceFile = Join-Path $evidenceRoot 'deployment.json'
foreach ($path in @($avatarFile, $reviewFile, $reloadFile, $cameraVisualFile, $visualFile, $evidenceFile)) { Assert-InRoot $path $project }
$avatarSha = Hash $avatarFile
$reviewSha = Hash $reviewFile
$reloadSha = Hash $reloadFile
$visualSha = Hash $visualFile
$cameraVisualSha = Hash $cameraVisualFile
$avatar = Get-Content -LiteralPath $avatarFile -Raw | ConvertFrom-Json
$review = Get-Content -LiteralPath $reviewFile -Raw | ConvertFrom-Json
$reload = Get-Content -LiteralPath $reloadFile -Raw | ConvertFrom-Json
$visual = Get-Content -LiteralPath $visualFile -Raw | ConvertFrom-Json
$cameraVisual = Get-Content -LiteralPath $cameraVisualFile -Raw | ConvertFrom-Json
if ($avatar.passed -isnot [bool] -or -not $avatar.passed -or $avatar.modJarSha256 -cne $sha -or $avatar.monitorAdapter -cne '\\.\DISPLAY5') {
    throw 'Avatar acceptance must pass on DISPLAY5 for this exact 0.11.0 JAR SHA-256'
}
if ($review.passed -isnot [bool] -or -not $review.passed -or $review.modJarSha256 -cne $sha -or $review.monitorAdapter -cne '\\.\DISPLAY5') {
    throw 'DISPLAY5 game acceptance must pass for this exact 0.11.0 JAR SHA-256'
}
if ($visual.passed -isnot [bool] -or -not $visual.passed -or $visual.modJarSha256 -cne $sha) {
    throw 'Visual acceptance must pass for this exact 0.11.0 JAR SHA-256'
}
if ($reload.passed -isnot [bool] -or -not $reload.passed -or $reload.modJarSha256 -cne $sha -or $reload.monitorAdapter -cne '\\.\DISPLAY5') {
    throw 'A separate DISPLAY5 client launch must verify saved camera profiles for this exact JAR'
}
if ($cameraVisual.passed -isnot [bool] -or -not $cameraVisual.passed -or $cameraVisual.modJarSha256 -cne $sha) {
    throw 'Inherited camera visual acceptance must pass for this exact 0.11.0 JAR SHA-256'
}
if (Test-Path -LiteralPath $evidenceFile) { throw "Deployment record already exists; refusing to overwrite: $evidenceFile" }
$oldJars = @(InstalledFsrpgJars)
if ($oldJars.Count -ne 1 -or $oldJars[0].Name -cne $oldName) { throw 'Expected exactly the installed 0.10.1 FSRPG JAR and no other FSRPG versions' }
Assert-Hash $oldJar $oldSha 'Installed 0.10.1 baseline'
$modsBefore = TreeSnapshot $mods
$configBefore = TreeSnapshot $config
$optionsBefore = OptionalFileSnapshot $options
$seedManifestFile = Join-Path $PSScriptRoot 'avatar-seeds-0.11.0.json'
Assert-InRoot $seedManifestFile $project
$seedManifestSha = Hash $seedManifestFile
$seedManifest = Get-Content -LiteralPath $seedManifestFile -Raw | ConvertFrom-Json
if ($seedManifest.version -cne '0.11.0') { throw 'Wrong avatar seed manifest version' }
$seedPlan = [Collections.Generic.List[object]]::new()
$configExpectedFiles = [ordered]@{}
foreach ($relative in $configBefore.files.Keys) { $configExpectedFiles[$relative] = $configBefore.files[$relative] }
$configExpectedDirectories = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($relative in $configBefore.directories) { [void]$configExpectedDirectories.Add($relative) }
$seedTargets = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($seed in $seedManifest.files) {
    $relative = ([string]$seed.destination).Replace('\', '/')
    if ($relative -cne 'fsrpg/avatars/README.md' -and $relative -cnotmatch '^fsrpg/avatars/examples/[^:]+$') {
        throw "Only inactive avatar examples or README may be seeded: $relative"
    }
    if ($relative -match '(^|/)\.\.?(/|$)' -or -not $seedTargets.Add($relative)) { throw "Invalid or duplicate seed destination: $relative" }
    $source = FullPath (Join-Path $project ([string]$seed.source))
    Assert-InRoot $source (Join-Path $project 'config/fsrpg/avatars')
    $destination = FullPath (Join-Path $config $relative)
    Assert-InRoot $destination (Join-Path $config 'fsrpg/avatars')
    $sourceSha = Hash $source
    if (Test-Path -LiteralPath $destination -PathType Container) { throw "Avatar seed destination is a directory: $destination" }
    $exists = Test-Path -LiteralPath $destination -PathType Leaf
    $seedPlan.Add([pscustomobject]@{ relative = $relative; source = $source; destination = $destination; sha256 = $sourceSha; add = -not $exists })
    if ($exists) { continue } # Existing README or example belongs to the user, regardless of its content.
    $configExpectedFiles[$relative] = $sourceSha
    $directory = [IO.Path]::GetDirectoryName($relative).Replace('\', '/')
    while ($directory) {
        [void]$configExpectedDirectories.Add($directory)
        $fullDirectory = Join-Path $config $directory
        Assert-InRoot $fullDirectory $config
        if ((Test-Path -LiteralPath $fullDirectory) -and -not (Test-Path -LiteralPath $fullDirectory -PathType Container)) {
            throw "Avatar seed parent is occupied by a file: $fullDirectory"
        }
        $directory = [IO.Path]::GetDirectoryName($directory).Replace('\', '/')
    }
}
$expectedConfig = [pscustomobject]@{ files = $configExpectedFiles; directories = @($configExpectedDirectories | Sort-Object) }
$expectedFiles = [ordered]@{}
foreach ($key in $modsBefore.files.Keys) { $expectedFiles[$key] = $modsBefore.files[$key] }
$expectedFiles.Remove($oldName)
$expectedFiles[$newName] = $sha
$expectedMods = [pscustomobject]@{ files = $expectedFiles; directories = $modsBefore.directories }
Assert-NoGameRunning

if ($VerifyOnly) {
    Write-Output "VERIFY ONLY PASSED: $jar SHA-256 $sha; official 1.21.11/NeoForge 21.11.45; DISPLAY5 avatar/camera and visual evidence bound; baseline $oldSha; config files=$($configBefore.files.Count); inactive seeds to add=$(@($seedPlan | Where-Object add).Count); matching launcher profiles=$($profileMatches.Count); actual game directory verified from latest.log; no files written."
    return
}

$backupRoot = Join-Path $instance 'fsrpg-backups'
$stamp = [TimeZoneInfo]::ConvertTimeFromUtc([DateTime]::UtcNow, $releaseZone).ToString('yyyyMMdd-HHmmss-fff')
$backup = Join-Path $backupRoot ($stamp + '-before-0.11.0-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
$backupMods = Join-Path $backup 'mods'
$backupOld = Join-Path $backupMods $oldName
$staged = Join-Path $backup ($newName + '.staged')
$oldMoved = $false
$newInstalled = $false
$seededFiles = [Collections.Generic.List[object]]::new()
$createdAvatarDirectories = [Collections.Generic.List[string]]::new()
try {
    Assert-InRoot $backupRoot $instance
    if (-not (Test-Path -LiteralPath $backupRoot)) { New-InstanceDirectory $backupRoot }
    if (-not (Test-Path -LiteralPath $backupRoot -PathType Container)) { throw 'Backup root is not a directory' }
    New-InstanceDirectory $backup
    New-InstanceDirectory $backupMods
    Copy-ConfigBackup $configBefore (Join-Path $backup 'config')
    if ($optionsBefore.exists) { Copy-NewVerifiedFile $options (Join-Path $backup 'options.txt') $optionsBefore.sha256 }
    Copy-NewVerifiedFile $jar $staged $sha
    $stageSeeds = Join-Path $backup 'avatar-seeds'
    New-InstanceDirectory $stageSeeds
    foreach ($seed in @($seedPlan | Where-Object add)) {
        $seedStage = Join-Path $stageSeeds $seed.relative
        Ensure-NewDirectory ([IO.Path]::GetDirectoryName($seedStage)) $stageSeeds $null
        Copy-NewVerifiedFile $seed.source $seedStage $seed.sha256
    }
    Write-NewJson (Join-Path $backup 'before.json') ([ordered]@{
        version = '0.11.0'; priorJar = $oldJar; priorJarSha256 = $oldSha; testedJar = $jar; testedJarSha256 = $sha
        mods = $modsBefore; completeConfig = $configBefore; options = $optionsBefore
        launcherMetadataSha256 = $versionSha; latestLogSha256 = $logSha
        gameReviewSha256 = $reviewSha; visualReviewSha256 = $visualSha
        profileReloadReviewSha256 = $reloadSha
        avatarReviewSha256 = $avatarSha; cameraVisualReviewSha256 = $cameraVisualSha
        launcherInputs = $launcherInputs; matchingLauncherProfiles = $profileMatches.ToArray()
        avatarSeedManifestSha256 = $seedManifestSha; avatarSeedPlan = $seedPlan.ToArray()
    }) $instance

    # Nothing in the original instance has been replaced yet. Recheck every
    # protected input immediately before the two exclusive rename operations.
    Assert-NoGameRunning
    Assert-Hash $versionFile $versionSha 'Launcher metadata'
    Assert-Hash $latestLog $logSha 'Actual-game-directory evidence'
    Assert-Hash $reviewFile $reviewSha 'Game acceptance'
    Assert-Hash $reloadFile $reloadSha 'Separate-process camera profile persistence acceptance'
    Assert-Hash $visualFile $visualSha 'Visual acceptance'
    Assert-Hash $avatarFile $avatarSha 'Avatar acceptance'
    Assert-Hash $cameraVisualFile $cameraVisualSha 'Camera visual acceptance'
    foreach ($path in $launcherInputs.Keys) { Assert-Hash $path $launcherInputs[$path] 'Launcher profile/inheritance input' }
    Assert-Hash $seedManifestFile $seedManifestSha 'Inactive avatar seed manifest'
    foreach ($seed in $seedPlan) { Assert-Hash $seed.source $seed.sha256 'Avatar seed source' }
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
    foreach ($seed in @($seedPlan | Where-Object add)) {
        Assert-NoGameRunning
        Ensure-NewDirectory ([IO.Path]::GetDirectoryName($seed.destination)) $config $createdAvatarDirectories
        Copy-NewVerifiedFile (Join-Path $stageSeeds $seed.relative) $seed.destination $seed.sha256
        $seededFiles.Add($seed)
    }
    Assert-Snapshot $expectedMods $mods 'Only the FSRPG JAR replacement'
    Assert-ConfigPreserved $configBefore $expectedConfig
    Assert-OptionalFile $optionsBefore $options 'Byte-exact options.txt preservation'
    Assert-Snapshot $configBefore (Join-Path $backup 'config') 'Verified complete config backup'
    if ($optionsBefore.exists) { Assert-Hash (Join-Path $backup 'options.txt') $optionsBefore.sha256 'Verified options backup' }
    $record = [ordered]@{
        version = '0.11.0'; installedJar = $installed; jarSha256 = $sha; testedJar = $jar
        officialInstance = $instance; minecraftVersion = '1.21.11'; neoForgeVersion = '21.11.45'
        backup = $backup; previousJar = [ordered]@{ name = $oldName; sha256 = $oldSha }
        completeConfigPreserved = $true; configurationMigration = $false; configSnapshot = $configBefore
        existingConfigBytesPreserved = $true; configAfter = $expectedConfig
        avatarSeedsAdded = $seededFiles.ToArray(); avatarSeedPlan = $seedPlan.ToArray()
        avatarSeedManifest = $seedManifestFile; avatarSeedManifestSha256 = $seedManifestSha; playerAssignmentActivated = $false
        optionsPreserved = $true; optionsSnapshot = $optionsBefore; otherModsPreserved = $true; worldsModified = $false
        modsBefore = $modsBefore; modsAfter = $expectedMods
        gameReview = $reviewFile; gameReviewSha256 = $reviewSha; visualReview = $visualFile; visualReviewSha256 = $visualSha
        profileReloadReview = $reloadFile; profileReloadReviewSha256 = $reloadSha
        avatarReview = $avatarFile; avatarReviewSha256 = $avatarSha; cameraVisualReview = $cameraVisualFile; cameraVisualReviewSha256 = $cameraVisualSha
        launcherInputs = $launcherInputs; matchingLauncherProfiles = $profileMatches.ToArray(); launcherVersionInheritance = @($chain | ForEach-Object id)
        actualGameDirectory = $actualGameDirectory; actualGameDirectoryEvidence = $latestLog
        monitorAdapter = '\\.\DISPLAY5'; deployedAt = [TimeZoneInfo]::ConvertTimeFromUtc([DateTime]::UtcNow, $releaseZone).ToString('o')
    }
    Write-NewJson (Join-Path $backup 'deployment.json') $record $instance
    Write-NewJson (Join-Path $backup 'avatar-seeds-added.json') ([ordered]@{ version = '0.11.0'; activatedPlayerAssignment = $false; added = $seededFiles.ToArray(); manifestSha256 = $seedManifestSha }) $instance
    Write-NewJson $evidenceFile $record $project
    Write-Output "Installed $installed; SHA-256 $sha; original config/options, other mods and worlds preserved; inactive avatar seeds added=$($seededFiles.Count); backup $backup"
} catch {
    $failure = $_.Exception.Message
    $rollbackNotes = [Collections.Generic.List[string]]::new()
    try {
        if ($seededFiles.Count -gt 0 -or $createdAvatarDirectories.Count -gt 0) {
            Assert-NoGameRunning
            $failedSeeds = Join-Path $backup 'failed-avatar-seeds'
            Ensure-NewDirectory $failedSeeds $backup $null
            foreach ($seed in $seededFiles) {
                Assert-Hash $seed.destination $seed.sha256 'Rollback-owned inactive avatar seed'
                $recovered = Join-Path $failedSeeds $seed.relative
                Ensure-NewDirectory ([IO.Path]::GetDirectoryName($recovered)) $failedSeeds $null
                Move-InstanceFileExclusive $seed.destination $recovered
            }
            foreach ($directory in @($createdAvatarDirectories | Sort-Object Length -Descending)) {
                Assert-InRoot $directory $config
                if ((Test-Path -LiteralPath $directory -PathType Container) -and @(Get-ChildItem -LiteralPath $directory -Force).Count -eq 0) {
                    [IO.Directory]::Delete($directory, $false) # Only our recorded, empty directories, never a recursive removal.
                }
            }
        }
    } catch { $rollbackNotes.Add('Avatar seed rollback: ' + $_.Exception.Message) }
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
    # Original files were never written, so external edits must NOT be overwritten
    # by their backup. Only exclusively created, hash-verified seeds may be moved.
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
