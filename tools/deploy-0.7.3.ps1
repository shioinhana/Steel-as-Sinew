$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$project=(Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$evidence=Join-Path $project 'verification/spear-realtime-20261016'
$jar=Join-Path $project 'build/libs/fsrpg-neoforge-0.7.3.jar'
$stage=(Resolve-Path -LiteralPath (Join-Path $evidence 'config-stage-final/fsrpg')).Path
function Hash([string]$path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
function ReadJson([string]$path){Get-Content -LiteralPath $path -Raw | ConvertFrom-Json}
function ChildPath([string]$directory,[string]$relative){
    if([string]::IsNullOrWhiteSpace($relative) -or [IO.Path]::IsPathRooted($relative) -or $relative -match '(^|[\\/])\.\.([\\/]|$)'){throw "Invalid relative path: $relative"}
    $parent=[IO.Path]::GetFullPath($directory).TrimEnd('\','/')
    $result=[IO.Path]::GetFullPath((Join-Path $parent $relative))
    if(-not $result.StartsWith($parent+'\',[StringComparison]::OrdinalIgnoreCase)){throw "Path outside intended directory: $result"}
    return $result
}
function TreeHashes([string]$directory){
    $result=@{}
    foreach($file in Get-ChildItem -LiteralPath $directory -Recurse -File){
        $relative=[IO.Path]::GetRelativePath($directory,$file.FullName).Replace('\','/')
        $result[$relative]=Hash $file.FullName
    }
    return $result
}
function HashMap($json){
    $result=@{}
    foreach($entry in $json.PSObject.Properties){
        if($entry.Value -cnotmatch '^[0-9a-f]{64}$'){throw "Invalid expected SHA-256 for $($entry.Name)"}
        $result[$entry.Name]=$entry.Value
    }
    return $result
}
function SameTree([hashtable]$expected,[string]$directory,[string]$description){
    $actual=TreeHashes $directory
    if($actual.Count -ne $expected.Count){throw "$description file inventory changed"}
    foreach($entry in $expected.GetEnumerator()){
        if(-not $actual.ContainsKey($entry.Key) -or $actual[$entry.Key] -cne $entry.Value){throw "$description hash mismatch: $($entry.Key)"}
    }
}
$sha=Hash $jar
$runtime=ReadJson (Join-Path $evidence 'optimized/game/spear-performance-review.json')
$userSettings=ReadJson (Join-Path $evidence 'user-settings/game/spear-performance-review.json')
$combat=ReadJson (Join-Path $evidence 'combat/game/combat-balance-review.json')
$badge=ReadJson (Join-Path $evidence 'badge/game/white-adventurer-review.json')
$comparison=ReadJson (Join-Path $evidence 'comparison.json')
$migration=ReadJson (Join-Path $evidence 'config-stage-final/report.json')
foreach($report in @($runtime,$userSettings,$combat,$badge)){
    if($report.passed -ne $true -or $report.modJarSha256 -cne $sha -or $report.monitorAdapter -cne '\\.\DISPLAY5'){throw 'A real DISPLAY5 report did not pass on the exact candidate JAR'}
}
function CheckPerformance($report,[bool]$official){
    $requiredScenes=if($official){@('sheathed-control','spear-stress-30s')}else{@('sheathed-control','spear-idle','spear-aim-sweep','spear-movement','spear-attacks','spear-stress-30s')}
    if($report.completedScenes -ne $requiredScenes.Count -or @($report.scenes).Count -ne $requiredScenes.Count){throw 'Performance scene inventory is incomplete'}
    foreach($name in $requiredScenes){
        $rows=@($report.scenes | Where-Object {$_.scene -ceq $name})
        if($rows.Count -ne 1){throw "Missing or duplicate performance scene: $name"}
        if($name -ceq 'sheathed-control'){continue}
        $row=$rows[0]
        if($row.motionAtLeast30Hz -ne $true -or $row.qualityObservedHz -lt 30 -or $row.qualityFrames -lt 1 -or
            $row.badGripFrames -ne 0 -or $row.completeShaftClearFailures -ne 0 -or $row.framesWithMoreThanOneSpearSolve -ne 0 -or
            $row.changedCachedPosePairs -lt 1 -or $row.nativeSubtickPosePairs -lt 1 -or $row.observedRenderedRigFrames -lt 1 -or
            $row.officialHeldItemSubmissions -lt 1 -or $row.solverCounters.spearSolveCalls -lt 1){throw "Strict submitted-pose acceptance failed: $name"}
        if($name -ceq 'spear-stress-30s' -and ($row.ironArmorStress.frames -lt 1 -or $row.unarmoredStress.frames -lt 1 -or $row.sampleSeconds -lt 29)){throw 'Thirty-second armor stress is incomplete'}
    }
    if(@($report.realServerThrustTimings).Count -lt 8){throw 'Not enough real server thrust timings'}
    foreach($timing in $report.realServerThrustTimings){
        if($timing.configuredDurationTicks -ne 4 -or $timing.observedDurationTicks -ne 4 -or $timing.observedSeconds -ne .2){throw 'A real server point thrust did not last exactly 0.2 seconds'}
    }
    if($official -and ($report.officialVideoSettingsConfirmation -ne $true -or $report.configuredVsync -ne $true -or
        $report.configuredFpsCap -ne 120 -or $report.configuredRenderDistance -ne 12)){throw 'Actual official video settings were not verified'}
    if($report.PSObject.Properties.Name -contains 'separateUntimedNativeVisualCapture'){
        foreach($clip in $report.separateUntimedNativeVisualCapture.clips){
            if($clip.sourceFps -lt 30 -or $clip.distinctSamplesWithinGameTick -lt 1){throw 'Native visual capture did not sustain real 30 Hz motion'}
        }
    }
}
CheckPerformance $runtime $false
CheckPerformance $userSettings $true
if($comparison.passed -ne $true -or $comparison.optimizedJarSha256 -cne $sha -or $comparison.monitorAdapter -cne '\\.\DISPLAY5'){throw 'Exact performance comparison is not verified'}
if($combat.screenshots -lt 3 -or @($combat.meleeDebits).Count -ne 3 -or @($combat.bowReleases).Count -ne 6){throw 'Real combat and resource review is incomplete'}
if($badge.staticArtOnly -ne $true -or $badge.nativeRenderFrames -le 30 -or @($badge.screenshots).Count -lt 1){throw 'New badge was not verified by the actual renderer'}
if((Get-Content -LiteralPath (Join-Path $evidence 'build.log') -Raw) -notmatch 'BUILD SUCCESSFUL'){throw 'Required full build did not pass'}
if($migration.passed -ne $true -or $migration.allOtherExistingFieldsAndFilesPreserved -ne $true -or
    $migration.exactOriginalBackups -ne $true -or $migration.secondReloadIdempotent -ne $true -or
    $migration.staminaRegenPerSecond -ne 3 -or $migration.durationSeconds -ne .2 -or $migration.perfectGuardTicks -ne 2 -or
    $migration.counterWindowTicks -ne 4 -or $migration.counterDamageMultiplier -ne 1.5 -or $migration.parryStunTicks -ne 4 -or
    $migration.spearThrustCost -ne 10 -or $migration.singleSwordCost -ne 5 -or $migration.dualBladeCost -ne 8 -or
    $migration.rapidCost -ne 5 -or $migration.snipeCost -ne 15 -or $migration.partialRapidCost -ne 3.5 -or $migration.partialSnipeCost -ne 10.5){throw 'The final staged configuration did not pass exact field and backup validation'}

Add-Type -AssemblyName System.IO.Compression.FileSystem
function ModToml([string]$path){
    $archive=[IO.Compression.ZipFile]::OpenRead($path)
    try{
        $entry=$archive.GetEntry('META-INF/neoforge.mods.toml');if($null -eq $entry){return ''}
        $reader=[IO.StreamReader]::new($entry.Open());try{return $reader.ReadToEnd()}finally{$reader.Dispose()}
    }finally{$archive.Dispose()}
}
function IsFsrpg([string]$path){
    $toml=ModToml $path
    return [regex]::IsMatch(($toml -split '\[\[dependencies\.')[0],'(?m)^\s*modId\s*=\s*"fsrpg"')
}
$archive=[IO.Compression.ZipFile]::OpenRead($jar)
try{
    if(@($archive.Entries | Where-Object {$_.FullName -match '(^org/fsrpg/smoke/)|MonitorAgent|forging|ArtContent|art/combat_arena'}).Count){throw 'Smoke code or excluded other-project materials found in release JAR'}
}finally{$archive.Dispose()}
if(-not (IsFsrpg $jar) -or (ModToml $jar) -notmatch '(?m)^\s*version\s*=\s*"0\.7\.3"'){throw 'Candidate JAR metadata is not FSRPG 0.7.3'}
$instance=(Resolve-Path -LiteralPath 'C:/Users/34003/AppData/Roaming/.minecraft/versions/1.21.11-NeoForge').Path
$versionText=Get-Content -LiteralPath (Join-Path $instance '1.21.11-NeoForge.json') -Raw
if(($versionText | ConvertFrom-Json).id -cne '1.21.11-NeoForge' -or -not $versionText.Contains('21.11.45')){throw 'Official Minecraft/NeoForge instance version changed'}
$latestLog=Get-Content -LiteralPath (Join-Path $instance 'logs/latest.log') -Raw
if(-not $latestLog.Contains("Game directory: $instance") -and -not $latestLog.Contains('Game directory: '+$instance.Replace('\','/'))){throw 'Actual official game directory was not confirmed by its log'}
function AssertInstanceStopped{
    if(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | Where-Object {$_.CommandLine -and
        ($_.CommandLine.Contains($instance) -or $_.CommandLine.Contains($instance.Replace('\','/')))}){throw 'Official instance is running; loaded JARs will not be replaced'}
}
AssertInstanceStopped
$mods=(Resolve-Path -LiteralPath (Join-Path $instance 'mods')).Path
$config=(Resolve-Path -LiteralPath (Join-Path $instance 'config/fsrpg')).Path
if((Hash (Join-Path $mods 'fsvoxel-renderer-neoforge-0.6.0.jar')) -cne (Hash (Join-Path $project 'libs/fsvoxel-renderer-neoforge-0.6.0.jar'))){throw 'Official required dependency differs from the tested dependency'}
if((Hash (Join-Path $mods 'geckolib-neoforge-1.21.11-5.4.5.jar')) -cne (Hash (Join-Path $project 'run/client/mods/geckolib-neoforge-1.21.11-5.4.5.jar'))){throw 'Official creature rendering library differs from the tested library'}
$before=HashMap $migration.beforeSha256
$after=HashMap $migration.afterSha256
SameTree $before $config 'Official configuration after staging'
SameTree $after $stage 'Staged configuration after validation'
$edited=@('combat.json','styles.json','skills.json','archery.json')
$allowedNew=@('spear-thrust-timing-migration-v1.json','spear-thrust-timing-backup-v1/skills.json',
    'weapon-stamina-balance-v1.json','weapon-stamina-balance-backup-v1/skills.json','weapon-stamina-balance-backup-v1/archery.json',
    'perfect-guard-migration-v1.json','perfect-guard-backup-v1/combat.json','perfect-guard-backup-v1/styles.json')
$newFiles=@()
foreach($name in $before.Keys){
    if(-not $after.ContainsKey($name)){throw "Staged migration removed existing file: $name"}
    if($edited -notcontains $name -and $before[$name] -cne $after[$name]){throw "Staged migration changed unrelated existing file: $name"}
}
foreach($name in $after.Keys){
    [void](ChildPath $stage $name);[void](ChildPath $config $name)
    if($before.ContainsKey($name)){continue}
    if($allowedNew -notcontains $name){throw "Unapproved new staged file: $name"}
    $newFiles+=,$name
}
foreach($name in $edited){if(-not $before.ContainsKey($name) -or -not $after.ContainsKey($name)){throw "Missing edited configuration: $name"}}
$old=@(Get-ChildItem -LiteralPath $mods -File -Filter '*.jar' | Where-Object {IsFsrpg $_.FullName})
$oldPaths=@($old | ForEach-Object {$_.FullName})
$installed=ChildPath $mods 'fsrpg-neoforge-0.7.3.jar'
if((Test-Path -LiteralPath $installed) -and $oldPaths -notcontains $installed){throw 'Candidate release filename belongs to an unrelated mod'}
$preserved=@(Get-ChildItem -LiteralPath $mods -Recurse -File | Where-Object {$oldPaths -notcontains $_.FullName} | ForEach-Object {@{path=$_.FullName;sha256=(Hash $_.FullName)}})
foreach($name in $before.Keys){if($edited -notcontains $name){$preserved+=@{path=(ChildPath $config $name);sha256=$before[$name]}}}
foreach($name in @('options.txt','config/fsrpg-client.toml')){
    $path=ChildPath $instance $name;if(Test-Path -LiteralPath $path){$preserved+=@{path=$path;sha256=(Hash $path)}}
}
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$backup=ChildPath $instance "fsrpg-backups/$stamp-before-0.7.3"
if(Test-Path -LiteralPath $backup){throw 'Dated backup already exists'}
New-Item -ItemType Directory -Path (Join-Path $backup 'mods') -Force | Out-Null
Copy-Item -LiteralPath $config -Destination (Join-Path $backup 'fsrpg') -Recurse
SameTree $before (Join-Path $backup 'fsrpg') 'Full configuration backup'
$previous=@()
foreach($file in $old){
    if($file.DirectoryName -cne $mods -or $file.Extension -cne '.jar'){throw 'Previous mod is outside the exact mods directory'}
    $saved=ChildPath (Join-Path $backup 'mods') $file.Name
    Copy-Item -LiteralPath $file.FullName -Destination $saved
    if((Hash $saved) -cne (Hash $file.FullName)){throw 'Previous FSRPG JAR backup hash mismatch'}
    $previous+=@{path=$file.FullName;backup=$saved;sha256=(Hash $saved)}
}
# Recheck immediately before mutation: never overwrite newer user edits or newly loaded files.
AssertInstanceStopped
SameTree $before $config 'Official configuration before deployment'
$created=@();$installedTouched=$false
try{
    foreach($file in $previous){Remove-Item -LiteralPath $file.path}
    $installedTouched=$true;Copy-Item -LiteralPath $jar -Destination $installed
    foreach($name in $edited){Copy-Item -LiteralPath (ChildPath $stage $name) -Destination (ChildPath $config $name) -Force}
    foreach($name in ($newFiles | Sort-Object {$_ -notmatch '-backup-v1/'},{$_})){
        $target=ChildPath $config $name
        if(Test-Path -LiteralPath $target){throw "Unexpected concurrent new migration file: $name"}
        New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
        $created+=,$target;Copy-Item -LiteralPath (ChildPath $stage $name) -Destination $target
    }
    if((Hash $installed) -cne $sha){throw 'Installed JAR is not the exact tested candidate'}
    SameTree $after $config 'Deployed configuration'
    foreach($file in $preserved){if((Hash $file.path) -cne $file.sha256){throw "Preserved file changed: $($file.path)"}}
    $record=[ordered]@{version='0.7.3';installedJar=$installed;jarSha256=$sha;testedJarSha256=$runtime.modJarSha256;backup=$backup;
        previousMods=$previous;editedConfiguration=$edited;newMigrationFiles=$newFiles;configurationSha256=$after;
        staminaRegenPerSecond=3;thrustDurationSeconds=.2;perfectGuardTicks=2;counterWindowTicks=4;counterDamageMultiplier=1.5;parryStunTicks=4;
        singleSwordCost=5;dualBladeCost=8;spearThrustCost=10;rapidCost=5;snipeCost=15;partialRapidCost=3.5;partialSnipeCost=10.5;
        existingConfigurationFieldsPreserved=$true;worldsModified=$false;otherModsPreserved=$true;preserved=$preserved;
        evidence=$evidence;comparison=(Join-Path $evidence 'comparison.json');deployedAt=(Get-Date).ToString('o')}
    $record | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $backup 'deployment.json') -Encoding utf8
    $record | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $evidence 'deployment.json') -Encoding utf8
}catch{
    $originalFailure=$_;$rollbackErrors=@()
    try{if($installedTouched -and (Test-Path -LiteralPath $installed)){Remove-Item -LiteralPath $installed}}catch{$rollbackErrors+=$_.ToString()}
    foreach($file in $previous){try{Copy-Item -LiteralPath $file.backup -Destination $file.path -Force}catch{$rollbackErrors+=$_.ToString()}}
    foreach($name in $edited){try{Copy-Item -LiteralPath (ChildPath (Join-Path $backup 'fsrpg') $name) -Destination (ChildPath $config $name) -Force}catch{$rollbackErrors+=$_.ToString()}}
    foreach($path in $created){try{if(Test-Path -LiteralPath $path){Remove-Item -LiteralPath $path}}catch{$rollbackErrors+=$_.ToString()}}
    try{SameTree $before $config 'Rolled-back configuration';foreach($file in $previous){if((Hash $file.path) -cne $file.sha256){throw 'Rolled-back JAR mismatch'}}}catch{$rollbackErrors+=$_.ToString()}
    @{deploymentFailure=$originalFailure.ToString();rollbackErrors=$rollbackErrors;backup=$backup} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $backup 'rollback.json') -Encoding utf8
    if($rollbackErrors.Count -gt 0){throw "Deployment failed: $originalFailure; rollback needs attention: $($rollbackErrors -join ' | '); backup: $backup"}
    throw $originalFailure
}
Write-Output "Installed $installed; SHA-256 $sha; tested and deployed hashes match; backup $backup"
