param(
    [switch]$VerifyOnly,
    [string]$EvidenceDirectory='verification/inspection-impulse-20261020',
    [string]$InspectionReport='game/inspection-carry-review.json',
    [string]$ImpulseReport='impulse/ground-impulse-review.json'
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$project=(Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$evidence=if([IO.Path]::IsPathRooted($EvidenceDirectory)){[IO.Path]::GetFullPath($EvidenceDirectory)}else{[IO.Path]::GetFullPath((Join-Path $project $EvidenceDirectory))}
$jar=Join-Path $project 'build/libs/fsrpg-neoforge-0.7.9.jar'
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
    $entries=@(Get-ChildItem -LiteralPath $directory -Recurse -Force)
    if(@($entries | Where-Object {($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0}).Count){throw "Links inside preserved configuration/world trees are not supported: $directory"}
    foreach($file in ($entries | Where-Object {-not $_.PSIsContainer})){
        if(($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw "Configuration/world links are not supported: $($file.FullName)"}
        $relative=[IO.Path]::GetRelativePath($directory,$file.FullName).Replace('\','/')
        $result[$relative]=Hash $file.FullName
    }
    return $result
}
function HashMap($json){$result=@{};foreach($property in $json.PSObject.Properties){$result[$property.Name]=[string]$property.Value};return $result}
function SameTree([hashtable]$expected,[string]$directory,[string]$description){
    $actual=TreeHashes $directory
    if($actual.Count -ne $expected.Count){throw "$description file inventory changed"}
    foreach($entry in $expected.GetEnumerator()){
        if(-not $actual.ContainsKey($entry.Key) -or $actual[$entry.Key] -cne $entry.Value){throw "$description hash mismatch: $($entry.Key)"}
    }
}
function RequireNear([double]$expected,[double]$actual,[string]$description,[double]$tolerance=.003){
    if([double]::IsNaN($actual) -or [double]::IsInfinity($actual) -or [Math]::Abs($expected-$actual) -gt $tolerance){throw "$description : expected $expected, actual $actual"}
}
function RequireFiniteAtMost([double]$actual,[double]$limit,[string]$description){
    if([double]::IsNaN($actual) -or [double]::IsInfinity($actual) -or $actual -lt 0 -or $actual -gt $limit){throw "$description exceeds $limit : $actual"}
}
function CanonicalJson($value){
    if($null -eq $value){return 'null'}
    if($value -is [System.Management.Automation.PSCustomObject]){
        $fields=@(foreach($property in ($value.PSObject.Properties | Sort-Object Name)){($property.Name | ConvertTo-Json -Compress)+':'+(CanonicalJson $property.Value)})
        return '{'+($fields -join ',')+'}'
    }
    if($value -is [Array]){return '['+(@(foreach($item in $value){CanonicalJson $item}) -join ',')+']'}
    return ($value | ConvertTo-Json -Depth 100 -Compress)
}
$sha=Hash $jar
$inspectionPath=ChildPath $evidence $InspectionReport;$impulsePath=ChildPath $evidence $ImpulseReport
$gameEvidence=Split-Path -Parent $inspectionPath;$impulseEvidence=Split-Path -Parent $impulsePath
$review=ReadJson $inspectionPath;$impulse=ReadJson $impulsePath
$stage=(Resolve-Path -LiteralPath (ChildPath $evidence 'config-stage/fsrpg')).Path
$migration=ReadJson (ChildPath $evidence 'config-stage/report.json')
$buildLog=ChildPath $evidence 'build.log';$buildText=Get-Content -LiteralPath $buildLog -Raw
if($buildText -notmatch '(?m)^> Task :build(?:\s|$)' -or $buildText -notmatch 'BUILD SUCCESSFUL' -or
    (Get-Item -LiteralPath $buildLog).LastWriteTimeUtc -lt (Get-Item -LiteralPath $jar).LastWriteTimeUtc){throw 'Required complete build of this 0.7.9 JAR was not established'}
function AssertNativeEnvironment($report,[string]$world,[string]$marker){
    if($report.passed -ne $true -or $report.modJarSha256 -cne $sha -or $report.monitorAdapter -cne '\\.\DISPLAY5' -or
        $report.copiedWorld -cne $world -or $report.windowWidth -ne 1000 -or $report.windowHeight -ne 680){throw "Native report did not pass on the exact JAR and DISPLAY5: $world"}
    $copy=ChildPath $project ('run/client/saves/'+$world)
    if(-not (Test-Path -LiteralPath (ChildPath $copy $marker) -PathType Leaf)){throw "Named copied world marker is missing: $world"}
}
function ScenarioRows($report,[string[]]$wanted,[string]$ticksField){
    if(@($report.scenarios).Count -ne $wanted.Count){throw 'Native scenario inventory differs from the required cases'}
    $rows=@{};foreach($name in $wanted){$matches=@($report.scenarios | Where-Object {$_.id -ceq $name})
        if($matches.Count -ne 1 -or $matches[0].passed -ne $true -or $matches[0].$ticksField -lt 1){throw "Native scenario is absent, duplicated or failed: $name"};$rows[$name]=$matches[0]}
    return $rows
}
function AssertPng([string]$path){
    $png=[IO.File]::ReadAllBytes($path)
    if($png.Length -lt 24 -or [BitConverter]::ToString($png,0,8) -cne '89-50-4E-47-0D-0A-1A-0A'){throw "Invalid native PNG: $path"}
}
AssertNativeEnvironment $review 'FSRPG Inspection Carry Review 20261020 01' '.fsrpg-inspection-carry-test-world'
AssertNativeEnvironment $impulse 'FSRPG Ground Impulse Review 20261020 01' '.fsrpg-ground-impulse-test-world'
$catalog=ReadJson (ChildPath $stage 'weapon_inspections.json')
$actions=@($catalog.actions)
if($actions.Count -ne 12 -or $catalog.schemaVersion -ne 1){throw 'The staged inspection seed must contain the twelve validated authored actions'}
foreach($style in @('rapier','dual_blade','spear','bow')){if(@($actions | Where-Object {$_.style -ceq $style}).Count -ne 3){throw "Expected three inspection actions for $style"}}
$inspectionIds=@($actions | ForEach-Object {$_.id.Replace('_','-')})
$carryIds=@('spear-fast-run','spear-stop-return','spear-run-attack','spear-walk-two-hand','inspection-move-cancel','inspection-attack-cancel')
$inspectionRows=ScenarioRows $review @($inspectionIds+$carryIds) 'elapsedTicks'
if($review.manualHClicks -ne 14 -or $review.prewarmTicks -lt 50){throw 'Actual H mapping or prewarmed native inspections were not established'}
foreach($id in $inspectionIds){
    if($inspectionRows[$id].noCombatOrResourceMutation -ne $true){throw "Cosmetic inspection changed combat/resources: $id"}
    $visual=$review.nativeVisuals.$id
    if($visual.activeFrames -le 30 -or $visual.maximumPoseChange -le .05){throw "Inspection lacks actual changing submitted avatar frames: $id"}
    RequireFiniteAtMost $visual.maximumWaistJoinPixels .02 "Actual attached inspection waist: $id"
}
foreach($id in @('spear-fast-run','spear-stop-return','spear-run-attack')){
    $visual=$review.nativeVisuals.$id
    if($visual.carryFrames -le 10 -or ($visual.maximumLeftArmAngle-$visual.minimumLeftArmAngle) -le .05){throw "Actual one-hand back carry/free running arm was not established: $id"}
}
if($review.nativeVisuals.'spear-stop-return'.twoHandReturnFrames -le 10 -or $review.nativeVisuals.'spear-run-attack'.attackReturnFrames -le 1){throw 'Actual stop/attack did not restore two-handed spear grip'}

$capturePath=ChildPath $gameEvidence 'render-capture.json';$capture=ReadJson $capturePath
if($capture.captureEvent -cne 'RenderFrameEvent.Post' -or $capture.pendingWrites -ne 0 -or @($capture.clips).Count -ne 1){throw 'Native framebuffer capture was not completed'}
if($review.renderCapture.captureEvent -cne $capture.captureEvent -or $review.renderCapture.pendingWrites -ne 0 -or
    (ConvertTo-Json -InputObject $review.renderCapture.clips -Depth 100 -Compress) -cne
    (ConvertTo-Json -InputObject $capture.clips -Depth 100 -Compress)){throw 'Native review embeds a different frame capture from the encoded evidence'}
$clip=@($capture.clips)[0];$frames=@($clip.frames)
if($clip.id -cne 'inspection-carry' -or $frames.Count -lt 31 -or $clip.capturedFrames -ne $frames.Count -or $clip.writtenFrames -ne $frames.Count -or
    $clip.sourceFps -lt 30 -or $clip.distinctSamplesWithinGameTick -lt 1 -or $clip.changedRigPairs -le 50){throw 'Native source does not establish genuine changing 30 Hz frames'}
$duration=$frames[-1].capturedAtSeconds-$frames[0].capturedAtSeconds
if($duration -le 0 -or ($frames.Count-1)/$duration -lt 30){throw 'Actual capture timestamps do not establish 30 Hz'}
RequireNear (($frames.Count-1)/$duration) $clip.sourceFps 'Independent source capture FPS' .00000001
$previousTime=-1.;$previousFrame=-1L;$previousRig=-1L;$submitted=0
for($i=0;$i -lt $frames.Count;$i++){
    $frame=$frames[$i]
    if($frame.index -ne $i -or $frame.capturedAtSeconds -le $previousTime -or $frame.renderFrame -le $previousFrame -or
        $frame.paused -ne $false -or $frame.levelRunningNormally -ne $true -or
        $frame.file -cne ('screenshots/render-frames/inspection-carry/frame-'+$i.ToString('D6')+'.png')){throw 'Native frames are duplicated, reordered, paused or misnamed'}
    if($i -gt 0 -and $frame.capturedAtSeconds-$previousTime -gt .25){throw 'Native framebuffer capture contains an unverified stalled interval'}
    AssertPng (ChildPath $gameEvidence $frame.file)
    if($frame.observation.PSObject.Properties.Name -contains 'submittedAvatar'){
        $avatar=$frame.observation.submittedAvatar
        if($avatar.nativePoseSnapshotStage -cne 'RenderLivingEvent.Post actual avatar submission' -or $avatar.nativePoseSnapshotRenderFrame -le $previousRig){throw 'A native rig was detached from its actual rendered player frame'}
        $previousRig=$avatar.nativePoseSnapshotRenderFrame;$submitted++
    }
    $previousTime=$frame.capturedAtSeconds;$previousFrame=$frame.renderFrame
}
if($submitted -lt 31){throw 'Too few native actual-player rig submissions'}
$encoded=ReadJson (ChildPath $gameEvidence 'encoded-review.json')
if($encoded.passed -ne $true -or $encoded.originalCaptureUnchanged -ne $true -or $encoded.motionInterpolation -ne $false -or
    $encoded.sourceCaptureSha256 -cne (Hash $capturePath) -or $encoded.clipId -cne $clip.id -or $encoded.capturedFrames -ne $frames.Count -or
    $encoded.outputFps -ne 30 -or $encoded.independentPngValidation.passed -ne $true -or $encoded.independentPngValidation.verifiedPngFiles -ne $frames.Count -or
    $encoded.outputSha256 -cne (Hash (ChildPath $gameEvidence $encoded.output)) -or
    $encoded.independentPngValidation.reportSha256 -cne (Hash (ChildPath $gameEvidence $encoded.independentPngValidation.report))){throw 'Native video or independent source PNG validation failed'}

$impulseRows=ScenarioRows $impulse @('ground-coast','ice-coast','wall-collision','ground-jump','held-vanilla-turn','air-reject-then-land') 'actualElapsedTicks'
if($impulse.perTickPlayerTeleport -ne $false -or $impulse.perTickVelocityOverwrite -ne $false -or $impulse.playerTeleportsPerCase -ne 1 -or
    $impulse.nativeFrames -le 30 -or $impulse.nativeSourceHz -lt 30 -or $impulse.screenshots -lt 6){throw 'One-shot impulse review did not use real local physics and native render sampling'}
foreach($id in $impulseRows.Keys){
    $row=$impulseRows[$id]
    if($row.clientVelocityApplications -ne 1){throw "One input did not produce exactly one velocity application: $id"}
    RequireNear 5 $row.finalNetStaminaCost "Single actual impulse payment: $id" .025
    AssertPng (ChildPath $impulseEvidence ('screenshots/'+$id+'.png'))
}
RequireNear 2.97 $impulseRows['ground-coast'].serverForwardDistance 'Actual natural standard-ground coast' .35
RequireNear .546 $impulseRows['ground-coast'].observedMeanDecay 'Actual vanilla standard-ground friction' .04
RequireNear .8918 $impulseRows['ice-coast'].observedMeanDecay 'Actual vanilla packed-ice friction' .04
if($impulseRows['ground-coast'].decaySamples -lt 3 -or
    $impulseRows['ice-coast'].serverForwardDistance -le $impulseRows['ground-coast'].serverForwardDistance*2.5){throw 'Distinct actual floor friction was not established'}
if($impulseRows['wall-collision'].finalServerPosition.z -gt 1.701 -or $impulseRows['wall-collision'].finalServerPosition.z -le 1.3){throw 'Actual wall collision did not stop the impulse'}
if($impulseRows['ground-jump'].jumpPeak -lt 1 -or [Math]::Abs($impulseRows['held-vanilla-turn'].sidewaysDistanceAfterTurn) -le 1){throw 'Vanilla jump or WASD redirect after the impulse was not established'}
RequireNear 0 $impulseRows['air-reject-then-land'].airRejectCost 'Rejected airborne request costs no stamina' .02
$native=@($impulse.nativeSubmittedTrace)
if($native.Count -ne $impulse.nativeFrames){throw 'Native impulse trace frame count differs'}
$previousNanos=-1L;$previousNative=-1L
foreach($sample in $native){
    if($sample.nativeNanoTime -le $previousNanos -or $sample.nativePoseSnapshotRenderFrame -le $previousNative -or
        $sample.nativePoseSnapshotStage -cne 'RenderLivingEvent.Post actual avatar submission'){throw 'Impulse native submissions are duplicated or not actual player frames'}
    $previousNanos=$sample.nativeNanoTime;$previousNative=$sample.nativePoseSnapshotRenderFrame
}
$nativeDuration=($native[-1].nativeNanoTime-$native[0].nativeNanoTime)/1e9
if($nativeDuration -le 0 -or ($native.Count-1)/$nativeDuration -lt 30){throw 'Native impulse timestamps do not establish thirty Hz'}
RequireNear (($native.Count-1)/$nativeDuration) $impulse.nativeSourceHz 'Independent impulse native source FPS' .00000001
if($migration.passed -ne $true -or $migration.version -cne '0.7.9' -or $migration.sourceCopiedByteExactly -ne $true -or
    $migration.officialSourceUntouched -ne $true -or $migration.onlyMissingInitialSpeedAndInspectionSeedAdded -ne $true -or
    $migration.allOtherExistingFieldsAndFilesPreserved -ne $true -or $migration.altDodgeTuningPreserved -ne $true -or
    $migration.exactOriginalBackups -ne $true -or $migration.secondReloadIdempotent -ne $true -or
    $migration.existingFileCount -ne 51 -or $migration.stagedFileCount -ne 54 -or $migration.inspectionVariants -ne 12){throw 'The exact field-only 51-to-54-file staged migration failed'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
function ModToml([string]$path){
    $archive=[IO.Compression.ZipFile]::OpenRead($path)
    try{$entry=$archive.GetEntry('META-INF/neoforge.mods.toml');if($null -eq $entry){return ''}
        $reader=[IO.StreamReader]::new($entry.Open());try{return $reader.ReadToEnd()}finally{$reader.Dispose()}
    }finally{$archive.Dispose()}
}
function IsFsrpg([string]$path){$toml=ModToml $path;return [regex]::IsMatch(($toml -split '\[\[dependencies\.')[0],'(?m)^\s*modId\s*=\s*"fsrpg"')}
if(-not (IsFsrpg $jar) -or (ModToml $jar) -notmatch '(?m)^\s*version\s*=\s*"0\.7\.9"'){throw 'Candidate JAR metadata is not FSRPG 0.7.9'}
$sourceAssets=@{
    'legacy_dual_blade.fsvoxel'='7e4accde21d0d4b8a9249501e3df7a10b8a6e5c6e22bfb254ee5b1ec7017a2b0'
    'legacy_dual_blade_fp.fsvoxel'='ad74c113e8da9b741553690d4eb09218c723ed58ef3489892c69d58e88549f76'
}
$archive=[IO.Compression.ZipFile]::OpenRead($jar)
try{
    if(@($archive.Entries | Where-Object {$_.FullName -match '(^org/fsrpg/smoke/)|MonitorAgent|forging|ArtContent|art/combat_arena'}).Count){throw 'Development code or excluded other-project materials are present in the release JAR'}
    if($null -ne $archive.GetEntry('org/fsrpg/mixin/BurstDashTravelMixin.class')){throw 'The old every-tick movement override is still packaged'}
    $seed=$archive.GetEntry('assets/fsrpg/animations/weapon_inspections.json');if($null -eq $seed){throw 'Inspection seed is absent from the tested JAR'}
    $stream=$seed.Open();try{$seedSha=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)).ToLowerInvariant()}finally{$stream.Dispose()}
    if($seedSha -cne (Hash (ChildPath $stage 'weapon_inspections.json'))){throw 'Staged inspection seed differs from the tested release resource'}
    foreach($name in $sourceAssets.Keys){
        $entry=$archive.GetEntry('assets/fsrpg/fsvoxel/models/'+$name);if($null -eq $entry){throw "Original dual source asset is missing: $name"}
        $stream=$entry.Open();try{$assetSha=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)).ToLowerInvariant()}finally{$stream.Dispose()}
        if($assetSha -cne $sourceAssets[$name]){throw "Original dual source asset changed: $name"}
    }
}finally{$archive.Dispose()}
$instance=(Resolve-Path -LiteralPath 'C:/Users/34003/AppData/Roaming/.minecraft/versions/1.21.11-NeoForge').Path
$versionText=Get-Content -LiteralPath (Join-Path $instance '1.21.11-NeoForge.json') -Raw
if(($versionText | ConvertFrom-Json).id -cne '1.21.11-NeoForge' -or -not $versionText.Contains('21.11.45')){throw 'Official Minecraft/NeoForge instance version changed'}
$latestLog=Get-Content -LiteralPath (Join-Path $instance 'logs/latest.log') -Raw
if(-not $latestLog.Contains("Game directory: $instance") -and -not $latestLog.Contains('Game directory: '+$instance.Replace('\','/'))){throw 'Actual official game directory was not confirmed by its log'}
function AssertInstanceStopped{
    if(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | Where-Object {$_.CommandLine -and
        ($_.CommandLine.IndexOf($instance,[StringComparison]::OrdinalIgnoreCase) -ge 0 -or $_.CommandLine.IndexOf($instance.Replace('\','/'),[StringComparison]::OrdinalIgnoreCase) -ge 0)}){
        throw 'Official instance is running; loaded JARs will not be replaced'
    }
}
AssertInstanceStopped
$mods=(Resolve-Path -LiteralPath (Join-Path $instance 'mods')).Path
$configRoot=(Resolve-Path -LiteralPath (Join-Path $instance 'config')).Path
$config=(Resolve-Path -LiteralPath (Join-Path $configRoot 'fsrpg')).Path
if(-not [string]::Equals([IO.Path]::GetFullPath($migration.sourceDirectory),$config,[StringComparison]::OrdinalIgnoreCase) -or
    -not [string]::Equals([IO.Path]::GetFullPath($migration.stageDirectory),$stage,[StringComparison]::OrdinalIgnoreCase)){throw 'Staging report refers to a different source/destination directory'}
$before=HashMap $migration.beforeSha256;$after=HashMap $migration.afterSha256
SameTree $before $config 'Official configuration after staging';SameTree $after $stage 'Staged configuration after validation'
if($before.Count -ne 51 -or $after.Count -ne 54){throw 'Unexpected official/staged FSRPG configuration inventory'}
$edited=@('combat.json');$allowedNew=@('ground-impulse-migration-v1.json','ground-impulse-backup-v1/combat.json','weapon_inspections.json');$newFiles=@()
foreach($name in $before.Keys){
    if(-not $after.ContainsKey($name)){throw "Migration removed existing file: $name"}
    if($edited -notcontains $name -and $before[$name] -cne $after[$name]){throw "Migration changed unrelated existing file: $name"}
}
foreach($name in $after.Keys){
    [void](ChildPath $stage $name);[void](ChildPath $config $name)
    if($before.ContainsKey($name)){continue};if($allowedNew -notcontains $name){throw "Unapproved new staged file: $name"};$newFiles+=,$name
}
if($newFiles.Count -ne 3 -or $after['ground-impulse-backup-v1/combat.json'] -cne $before['combat.json']){throw 'Missing approved migration files or byte-exact original combat backup'}
$oldCombat=ReadJson (ChildPath $config 'combat.json');$newCombat=ReadJson (ChildPath $stage 'combat.json')
if($oldCombat.PSObject.Properties.Name -notcontains 'burstDash'){$newCombat.PSObject.Properties.Remove('burstDash')}
elseif($oldCombat.burstDash.PSObject.Properties.Name -notcontains 'initialSpeed'){$newCombat.burstDash.PSObject.Properties.Remove('initialSpeed')}
if((CanonicalJson $oldCombat) -cne (CanonicalJson $newCombat)){throw 'Staged combat changed an existing value rather than only adding absent initialSpeed'}
$dependency=Join-Path $mods 'fsvoxel-renderer-neoforge-0.6.0.jar'
if((Hash $dependency) -cne (Hash (Join-Path $project 'libs/fsvoxel-renderer-neoforge-0.6.0.jar'))){throw 'Official required dependency differs from the tested dependency'}
$old=@(Get-ChildItem -LiteralPath $mods -File -Filter '*.jar' | Where-Object {IsFsrpg $_.FullName})
if($old.Count -ne 1 -or (ModToml $old[0].FullName) -notmatch '(?m)^\s*version\s*=\s*"0\.7\.8"'){throw 'Expected one installed FSRPG 0.7.8 JAR for this replacement'}
if((Hash $old[0].FullName) -cne 'c812619af0ca73226ff05dd56a4d7d56d3844334443b190eb6c698d194db5b4e'){throw 'Installed 0.7.8 JAR differs from the previously tested/deployed release'}
$oldPaths=@($old | ForEach-Object {$_.FullName});$installed=ChildPath $mods 'fsrpg-neoforge-0.7.9.jar'
if(Test-Path -LiteralPath $installed){throw 'The 0.7.9 destination already exists; no unknown file will be overwritten'}
$preserved=@(Get-ChildItem -LiteralPath $mods -Recurse -File | Where-Object {$oldPaths -notcontains $_.FullName} | ForEach-Object {@{path=$_.FullName;sha256=(Hash $_.FullName)}})
foreach($name in @('options.txt','config/fsrpg-client.toml')){$path=ChildPath $instance $name;if(Test-Path -LiteralPath $path){$preserved+=@{path=$path;sha256=(Hash $path)}}}
$allConfigBefore=TreeHashes $configRoot
$saves=ChildPath $instance 'saves';$worlds=if(Test-Path -LiteralPath $saves -PathType Container){TreeHashes $saves}else{@{}}
if($VerifyOnly){Write-Output "VERIFY ONLY PASSED: exact candidate $sha; DISPLAY5 inspection/carry and one-shot impulse reports with native 30 Hz; exact 51-to-54 staged config; dependency hashes; official instance stopped. No files were written.";return}
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss-fff';$backup=ChildPath $instance "fsrpg-backups/$stamp-before-0.7.9"
if(Test-Path -LiteralPath $backup){throw 'Dated backup already exists'}
New-Item -ItemType Directory -Path (Join-Path $backup 'mods') -Force | Out-Null
Copy-Item -LiteralPath $configRoot -Destination (Join-Path $backup 'config') -Recurse
SameTree $allConfigBefore (Join-Path $backup 'config') 'Full configuration backup'
$previous=@()
foreach($file in $old){
    if($file.DirectoryName -cne $mods -or $file.Extension -cne '.jar'){throw 'Previous mod is outside the exact mods directory'}
    $saved=ChildPath (Join-Path $backup 'mods') $file.Name;Copy-Item -LiteralPath $file.FullName -Destination $saved
    if((Hash $saved) -cne (Hash $file.FullName)){throw 'Previous FSRPG JAR backup hash mismatch'}
    $previous+=@{path=$file.FullName;backup=$saved;sha256=(Hash $saved)}
}
AssertInstanceStopped;SameTree $allConfigBefore $configRoot 'All official configuration immediately before deployment'
$created=@();$installedTouched=$false
try{
    foreach($file in $previous){Remove-Item -LiteralPath $file.path}
    $installedTouched=$true;Copy-Item -LiteralPath $jar -Destination $installed
    foreach($name in $edited){Copy-Item -LiteralPath (ChildPath $stage $name) -Destination (ChildPath $config $name) -Force}
    foreach($name in ($newFiles | Sort-Object {$_ -notmatch '-backup-v1/'},{$_})){
        $target=ChildPath $config $name;if(Test-Path -LiteralPath $target){throw "Unexpected concurrent new migration file: $name"}
        New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
        $created+=,$target;Copy-Item -LiteralPath (ChildPath $stage $name) -Destination $target
    }
    if((Hash $installed) -cne $sha){throw 'Installed JAR is not the exact tested candidate'}
    SameTree $after $config 'Deployed FSRPG configuration'
    foreach($entry in $allConfigBefore.GetEnumerator()){
        if($entry.Key -cne 'fsrpg/combat.json' -and (Hash (ChildPath $configRoot $entry.Key)) -cne $entry.Value){throw "Unrelated configuration changed: $($entry.Key)"}
    }
    foreach($file in $preserved){if((Hash $file.path) -cne $file.sha256){throw "Preserved file changed: $($file.path)"}}
    if(Test-Path -LiteralPath $saves -PathType Container){SameTree $worlds $saves 'Unchanged user worlds'}elseif($worlds.Count -gt 0){throw 'User worlds disappeared'}
    $record=[ordered]@{version='0.7.9';installedJar=$installed;jarSha256=$sha;testedJarSha256=$review.modJarSha256;impulseTestedJarSha256=$impulse.modJarSha256;backup=$backup;
        previousMods=$previous;editedConfiguration=$edited;newMigrationFiles=$newFiles;configurationSha256=$after;configurationFileCount=$after.Count;
        burstDash=$migration.burstDash;spearSwipeKnockbackMultiplier=$migration.spearSwipeKnockbackMultiplier;existingConfigurationFieldsPreserved=$true;altDodgeTuningPreserved=$true;
        clientTomlByteExactlyPreserved=$true;dualSourceAssets=$sourceAssets;nativeSourceFps=$clip.sourceFps;nativeSourceFrames=$frames.Count;
        inspectionReview=$inspectionPath;inspectionReviewSha256=(Hash $inspectionPath);impulseReview=$impulsePath;impulseReviewSha256=(Hash $impulsePath);
        impulseNativeSourceFps=$impulse.nativeSourceHz;impulseNativeSourceFrames=$impulse.nativeFrames;nativeMotionInterpolation=$false;inspectionVariants=12;networkProtocol=10;
        worldsModified=$false;worldFileCount=$worlds.Count;otherModsPreserved=$true;preserved=$preserved;evidence=$evidence;deployedAt=(Get-Date).ToString('o')}
    $record | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $backup 'deployment.json') -Encoding utf8
    $record | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $evidence 'deployment.json') -Encoding utf8
}catch{
    $originalFailure=$_;$rollbackErrors=@()
    try{if($installedTouched -and (Test-Path -LiteralPath $installed)){Remove-Item -LiteralPath $installed}}catch{$rollbackErrors+=$_.ToString()}
    foreach($file in $previous){try{Copy-Item -LiteralPath $file.backup -Destination $file.path -Force}catch{$rollbackErrors+=$_.ToString()}}
    foreach($name in $edited){try{Copy-Item -LiteralPath (ChildPath (Join-Path $backup 'config/fsrpg') $name) -Destination (ChildPath $config $name) -Force}catch{$rollbackErrors+=$_.ToString()}}
    foreach($path in $created){try{if(Test-Path -LiteralPath $path){Remove-Item -LiteralPath $path}}catch{$rollbackErrors+=$_.ToString()}}
    try{SameTree $allConfigBefore $configRoot 'Rolled-back configuration';foreach($file in $previous){if((Hash $file.path) -cne $file.sha256){throw 'Rolled-back JAR mismatch'}}}catch{$rollbackErrors+=$_.ToString()}
    @{deploymentFailure=$originalFailure.ToString();rollbackErrors=$rollbackErrors;backup=$backup} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $backup 'rollback.json') -Encoding utf8
    if($rollbackErrors.Count -gt 0){throw "Deployment failed: $originalFailure; rollback needs attention: $($rollbackErrors -join ' | '); backup: $backup"};throw $originalFailure
}
Write-Output "Installed $installed; SHA-256 $sha; tested/deployed hashes match; 54 config files with only absent initialSpeed, its exact backup/marker and the inspection seed added; backup $backup"
