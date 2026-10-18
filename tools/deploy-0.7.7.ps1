param(
    [switch]$VerifyOnly,
    [string]$EvidenceDirectory='verification/combat-revision-20261018'
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$project=(Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$evidence=if([IO.Path]::IsPathRooted($EvidenceDirectory)){[IO.Path]::GetFullPath($EvidenceDirectory)}else{[IO.Path]::GetFullPath((Join-Path $project $EvidenceDirectory))}
$jar=Join-Path $project 'build/libs/fsrpg-neoforge-0.7.7.jar'
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
$gameEvidence=Join-Path $evidence 'game'
$stage=(Resolve-Path -LiteralPath (Join-Path $evidence 'config-stage/fsrpg')).Path
$migration=ReadJson (Join-Path $evidence 'config-stage/report.json')
$review=ReadJson (Join-Path $gameEvidence 'combat-revision-review.json')
$gestureEvidence=Join-Path $evidence 'gesture'
$gesture=ReadJson (Join-Path $gestureEvidence 'spear-gesture-review.json')
$buildLog=Join-Path $evidence 'build.log'
$buildText=Get-Content -LiteralPath $buildLog -Raw
if($buildText -notmatch '(?m)^> Task :build(?:\s|$)' -or $buildText -notmatch 'BUILD SUCCESSFUL' -or
    (Get-Item -LiteralPath $buildLog).LastWriteTimeUtc -lt (Get-Item -LiteralPath $jar).LastWriteTimeUtc){throw 'Required complete build of the current 0.7.7 candidate was not established'}
if($review.passed -ne $true -or $review.modJarSha256 -cne $sha -or $review.monitorAdapter -cne '\\.\DISPLAY5' -or
    $review.copiedWorld -cne 'FSRPG Combat Revision Review 20261018 01' -or $review.windowWidth -ne 1000 -or $review.windowHeight -ne 680){
    throw 'The combat revision review did not pass on the exact candidate JAR on DISPLAY5'
}

function AssertCombatReview($report){
    $wanted=@('dual-idle','dual-walk','dual-run','dual-attack-return','spear-turn','rapier-turn','spear-hold-sweep',
        'spear-tap','spear-lease','spear-cancel','static-regen','combat-regen','miss-cost','contact-cost')
    if(@($report.scenarios).Count -ne $wanted.Count){throw 'All fourteen native combat revision scenarios are required'}
    $rows=@{}
    foreach($name in $wanted){
        $matches=@($report.scenarios | Where-Object {$_.id -ceq $name})
        if($matches.Count -ne 1 -or $matches[0].passed -ne $true -or $matches[0].actualElapsedTicks -lt 1){throw "Missing or failed native scenario: $name"}
        $rows[$name]=$matches[0]
    }
    foreach($name in @('static-regen','combat-regen')){
        $row=$rows[$name];$amount=if($name -ceq 'static-regen'){15}else{7.5};$rate=if($name -ceq 'static-regen'){5}else{2.5}
        if($row.actualElapsedTicks -ne 60){throw "Recovery was not measured over three actual server seconds: $name"}
        RequireNear $amount $row.recovered "Integrated actual recovery: $name" .015
        RequireNear $rate $row.regenPerSecond "Actual server recovery rate: $name" .015
    }
    foreach($name in @('miss-cost','contact-cost')){
        $row=$rows[$name];$cost=if($name -ceq 'miss-cost'){2.5}else{5};$contacts=if($name -ceq 'miss-cost'){0}else{1}
        RequireNear $cost $row.finalNetCost "Actual sword cost after integrating recovery: $name" .015
        if($row.observedDamageEvents -ne $contacts -or [double]$row.integratedRecovery -le 0){throw "Actual outgoing contacts/recovery are missing: $name"}
    }
    if($rows['miss-cost'].fullRateUninterrupted -ne $true){throw 'Emitted miss interrupted ongoing full-rate recovery'}
    $hold=$rows['spear-hold-sweep'];$contacts=@($hold.targetContacts.PSObject.Properties)
    if($contacts.Count -ne 2 -or @($contacts | Where-Object {$_.Value -ne 1}).Count -gt 0 -or $hold.additionalSwingCount -ne 0 -or
        $hold.heldTicks -lt 30 -or $hold.releasedToReady -ne $true){throw 'Held spear did not sweep two actual targets exactly once on a single paid/released swing'}
    RequireNear 10 $hold.initialReservedCost 'One initial spear stamina reservation' .015
    if($rows['spear-tap'].durationTicks -ne 4 -or $rows['spear-lease'].leaseCancelled -ne $true -or
        $rows['spear-lease'].additionalSwingCount -ne 0 -or $rows['spear-cancel'].interruptCancelled -ne $true -or
        $rows['spear-cancel'].staleMoveRejected -ne $true -or $rows['spear-cancel'].additionalSwingCount -ne 0){throw 'Spear tap, lost renewal lease or actual interruption did not retract safely'}
    foreach($name in @('DUAL_WALK','DUAL_RUN')){
        $visual=$report.nativeVisualScenarios.$name;$minimum=if($name -ceq 'DUAL_WALK'){2.85}else{1.5}
        if($visual.changedLegPairs -le 40 -or ($visual.lastTick-$visual.firstTick)/20 -lt $minimum -or $visual.sameFrameChecks -le 40){
            throw "Original dual gait failed to move submitted legs over three complete source cycles: $name"
        }
    }
    foreach($name in @('DUAL_IDLE','DUAL_ATTACK_RETURN')){
        $visual=$report.nativeVisualScenarios.$name
        if($visual.sourceIdleFrames -le 5){throw "Native READY player never returned to the original source idle: $name"}
        RequireFiniteAtMost $visual.maxIdleError .001 "Original source idle matrix error: $name"
    }
    foreach($name in @('SPEAR_TURN','RAPIER_TURN')){
        $visual=$report.nativeVisualScenarios.$name
        RequireFiniteAtMost $visual.maxTwist 45.05 "Additional submitted waist twist: $name"
        if($visual.supportedTurnFrames -le 30 -or $visual.hardTurns -lt 1 -or $visual.steppingFrames -le 3 -or $visual.maxLift -le .1){throw "Large turn never submitted grounded, bounded direct leg turning and a stepping foot: $name"}
    }
    $visual=$report.nativeVisualScenarios.SPEAR_HOLD_SWEEP
    if($visual.heldFrames -le 30){throw 'Actual held spear was not presented extended over native render frames'}
    RequireFiniteAtMost $visual.maxHeldClockError .0001 'Actual held spear endpoint clock error'
    if($report.officialMainSubmissionsDuringCapture -lt 1 -or $report.officialOffhandSubmissionsDuringCapture -lt 1){throw 'Both official ItemStack submissions are required'}
}
AssertCombatReview $review
if($gesture.passed -ne $true -or $gesture.modJarSha256 -cne $sha -or $gesture.monitorAdapter -cne '\\.\DISPLAY5' -or
    $gesture.copiedWorld -cne 'FSRPG Spear Gesture Review 20261018 01' -or $gesture.directServerAttackCalls -ne 0 -or
    $gesture.heldSourceFrames -le 30 -or $gesture.heldSourceHz -lt 30 -or $gesture.sourceEndpointMaxError -ge .0001 -or
    $gesture.serverAimYawSpanDegrees -le 1 -or $gesture.screenshots -ne 4 -or $gesture.overflowTested -ne $true -or
    $gesture.overflowRootTurnDegrees -le 30 -or $gesture.heldServerTicksAfterOverflow -lt 3){
    throw 'Independent actual-client spear gesture, overflow hold continuity or native endpoint review failed'
}
RequireFiniteAtMost $gesture.heldSourceHz 10000 'Actual-client native held source Hz'
RequireFiniteAtMost $gesture.sourceEndpointMaxError .0001 'Actual-client held source endpoint error'
RequireFiniteAtMost $gesture.serverAimYawSpanDegrees 360 'Accepted actual-client spear aim span'
RequireFiniteAtMost $gesture.overflowRootTurnDegrees 180 'Actual-client root overflow turn'
$gestureWorld=ChildPath $project 'run/client/saves/FSRPG Spear Gesture Review 20261018 01'
if(-not (Test-Path -LiteralPath (ChildPath $gestureWorld '.fsrpg-spear-gesture-test-world') -PathType Leaf)){
    throw 'The independent actual-client spear gesture world is not the expected marked test copy'
}
$nativeGesture=@($gesture.nativeSubmittedTrace)
if($nativeGesture.Count -ne $gesture.heldSourceFrames){throw 'Actual-client held native frame inventory differs from its source count'}
$previousGestureTime=-1L;$previousGestureFrame=-1L
foreach($sample in $nativeGesture){
    if($sample.stage -cne 'HOLD_AND_DRAG' -or $sample.nativeNanoTime -le $previousGestureTime -or
        $sample.nativePoseSnapshotStage -cne 'RenderLivingEvent.Post actual avatar submission' -or
        $sample.nativePoseSnapshotRenderFrame -le $previousGestureFrame -or $sample.sourceEndpointClockError -ge .0001){
        throw 'Actual-client held frames are duplicated, reordered or detached from native avatar submissions'
    }
    RequireFiniteAtMost $sample.sourceEndpointClockError .0001 'Actual-client per-frame held endpoint error'
    $previousGestureTime=$sample.nativeNanoTime;$previousGestureFrame=$sample.nativePoseSnapshotRenderFrame
}
$gestureDuration=($nativeGesture[-1].nativeNanoTime-$nativeGesture[0].nativeNanoTime)/1e9
if($gestureDuration -le 1){throw 'Independent actual-client held native source does not span one second'}
RequireNear (($nativeGesture.Count-1)/$gestureDuration) $gesture.heldSourceHz 'Independent actual-client held source FPS' .00000001
foreach($name in @('01-real-client-press','02-real-client-held-drag','03-before-focus-cancel','04-released-after-focus-cancel')){
    $screenshot=ChildPath $gestureEvidence ('screenshots/'+$name+'.png')
    if(-not (Test-Path -LiteralPath $screenshot -PathType Leaf)){throw "Actual-client gesture screenshot is missing: $name"}
    $png=[IO.File]::ReadAllBytes($screenshot)
    if($png.Length -lt 24 -or [BitConverter]::ToString($png,0,8) -cne '89-50-4E-47-0D-0A-1A-0A'){throw "Actual-client gesture screenshot is not PNG: $name"}
}
$capture=ReadJson (Join-Path $gameEvidence 'render-capture.json')
if($capture.captureEvent -cne 'RenderFrameEvent.Post' -or $capture.pendingWrites -ne 0 -or @($capture.clips).Count -ne 1){throw 'Native framebuffer recording was not completed'}
$clip=@($capture.clips)[0];$frames=@($clip.frames)
if($clip.id -cne 'combat-revision' -or $frames.Count -lt 31 -or $clip.capturedFrames -ne $frames.Count -or
    $clip.writtenFrames -ne $frames.Count -or $clip.sourceFps -lt 30 -or $clip.distinctSamplesWithinGameTick -lt 1 -or $clip.changedRigPairs -le 50){
    throw 'Recording does not establish genuine native 30 Hz changing rig presentation'
}
$duration=$frames[-1].capturedAtSeconds-$frames[0].capturedAtSeconds
if($duration -le 0 -or ($frames.Count-1)/$duration -lt 30){throw 'Raw native source frame timestamps do not establish 30 Hz'}
RequireNear (($frames.Count-1)/$duration) $clip.sourceFps 'Independent native source FPS' .00000001
$previousTime=-1.;$previousFrame=-1L;$previousRigFrame=-1L;$submittedFrames=0
for($i=0;$i -lt $frames.Count;$i++){
    $frame=$frames[$i]
    if($frame.index -ne $i -or $frame.capturedAtSeconds -le $previousTime -or $frame.renderFrame -le $previousFrame -or
        $frame.paused -ne $false -or $frame.levelRunningNormally -ne $true -or
        $frame.file -cne ('screenshots/render-frames/combat-revision/frame-'+$i.ToString('D6')+'.png')){throw 'Native source frames are reordered, duplicated, paused or misnamed'}
    $image=ChildPath $gameEvidence $frame.file
    if(-not (Test-Path -LiteralPath $image -PathType Leaf) -or (Get-Item -LiteralPath $image).Length -le 0){throw 'A native framebuffer PNG is missing'}
    if($frame.observation.PSObject.Properties.Name -notcontains 'submittedAvatar' -or $frame.observation.PSObject.Properties.Name -notcontains 'rig' -or
        $frame.observation.submittedAvatar.nativePoseSnapshotStage -cne 'RenderLivingEvent.Post actual avatar submission' -or
        $frame.observation.submittedAvatar.nativePoseSnapshotRenderFrame -le $previousRigFrame -or
        $frame.observation.actualCameraType -cne 'THIRD_PERSON_BACK'){throw 'A recorded source rig was not submitted by the actual native player in the current rendered frame'}
    if(@($frame.observation.rig.PSObject.Properties).Count -gt 0){$submittedFrames++}
    $previousRigFrame=$frame.observation.submittedAvatar.nativePoseSnapshotRenderFrame
    $previousTime=$frame.capturedAtSeconds;$previousFrame=$frame.renderFrame
}
if($submittedFrames -lt 31){throw 'Too few actual changing player rig submissions'}
$encoded=ReadJson (Join-Path $gameEvidence 'encoded-review.json')
if($encoded.passed -ne $true -or $encoded.originalCaptureUnchanged -ne $true -or $encoded.motionInterpolation -ne $false -or
    $encoded.sourceCaptureSha256 -cne (Hash (Join-Path $gameEvidence 'render-capture.json')) -or $encoded.clipId -cne $clip.id -or
    $encoded.capturedFrames -ne $frames.Count -or $encoded.outputFps -ne 30 -or $encoded.independentPngValidation.passed -ne $true -or
    $encoded.independentPngValidation.verifiedPngFiles -ne $frames.Count -or
    $encoded.outputSha256 -cne (Hash (ChildPath $gameEvidence $encoded.output)) -or
    $encoded.independentPngValidation.reportSha256 -cne (Hash (ChildPath $gameEvidence $encoded.independentPngValidation.report))){
    throw 'Actual-frame review video or independently verified source PNG inventory is incomplete'
}
if($migration.passed -ne $true -or $migration.version -cne '0.7.7' -or $migration.sourceCopiedByteExactly -ne $true -or
    $migration.officialSourceUntouched -ne $true -or $migration.onlyMissingStaminaCombatFieldsAdded -ne $true -or
    $migration.allOtherExistingFieldsAndFilesPreserved -ne $true -or $migration.exactOriginalBackups -ne $true -or
    $migration.secondReloadIdempotent -ne $true -or $migration.existingFileCount -ne 47 -or $migration.stagedFileCount -ne 49 -or
    $migration.staminaCombatRules.combatTicks -ne 60 -or $migration.staminaCombatRules.regenMultiplier -ne .5 -or
    $migration.staminaCombatRules.missCostMultiplier -ne .5 -or $migration.baseMaxStamina -ne 50 -or $migration.baseStaminaRegenPerSecond -ne 5){
    throw 'The exact stamina-only 47-to-49-file staged migration failed validation'
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
function ModToml([string]$path){
    $archive=[IO.Compression.ZipFile]::OpenRead($path)
    try{$entry=$archive.GetEntry('META-INF/neoforge.mods.toml');if($null -eq $entry){return ''}
        $reader=[IO.StreamReader]::new($entry.Open());try{return $reader.ReadToEnd()}finally{$reader.Dispose()}
    }finally{$archive.Dispose()}
}
function IsFsrpg([string]$path){$toml=ModToml $path;return [regex]::IsMatch(($toml -split '\[\[dependencies\.')[0],'(?m)^\s*modId\s*=\s*"fsrpg"')}
if(-not (IsFsrpg $jar) -or (ModToml $jar) -notmatch '(?m)^\s*version\s*=\s*"0\.7\.7"'){throw 'Candidate JAR metadata is not FSRPG 0.7.7'}
$sourceAssets=@{
    'legacy_dual_blade.fsvoxel'='7e4accde21d0d4b8a9249501e3df7a10b8a6e5c6e22bfb254ee5b1ec7017a2b0'
    'legacy_dual_blade_fp.fsvoxel'='ad74c113e8da9b741553690d4eb09218c723ed58ef3489892c69d58e88549f76'
}
$archive=[IO.Compression.ZipFile]::OpenRead($jar)
try{
    if(@($archive.Entries | Where-Object {$_.FullName -match '(^org/fsrpg/smoke/)|MonitorAgent|forging|ArtContent|art/combat_arena'}).Count){throw 'Development code or excluded other-project materials are present in the release JAR'}
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
if($before.Count -ne 47 -or $after.Count -ne 49){throw 'Unexpected official/staged FSRPG configuration inventory'}
$edited=@('combat.json');$allowedNew=@('stamina-combat-migration-v1.json','stamina-combat-backup-v1/combat.json');$newFiles=@()
foreach($name in $before.Keys){
    if(-not $after.ContainsKey($name)){throw "Migration removed existing file: $name"}
    if($edited -notcontains $name -and $before[$name] -cne $after[$name]){throw "Migration changed unrelated existing file: $name"}
}
foreach($name in $after.Keys){
    [void](ChildPath $stage $name);[void](ChildPath $config $name)
    if($before.ContainsKey($name)){continue};if($allowedNew -notcontains $name){throw "Unapproved new staged file: $name"};$newFiles+=,$name
}
if($newFiles.Count -ne 2 -or $after['stamina-combat-backup-v1/combat.json'] -cne $before['combat.json']){throw 'Missing approved migration files or byte-exact original combat backup'}
$oldCombat=ReadJson (ChildPath $config 'combat.json');$newCombat=ReadJson (ChildPath $stage 'combat.json')
if($oldCombat.PSObject.Properties.Name -notcontains 'staminaCombatRules'){$newCombat.PSObject.Properties.Remove('staminaCombatRules')}
else{
    foreach($field in @('combatTicks','regenMultiplier','missCostMultiplier')){
        if($oldCombat.staminaCombatRules.PSObject.Properties.Name -notcontains $field){$newCombat.staminaCombatRules.PSObject.Properties.Remove($field)}
    }
}
if((CanonicalJson $oldCombat) -cne (CanonicalJson $newCombat)){throw 'Staged combat JSON changed an existing field rather than adding absent stamina fields'}
$dependency=Join-Path $mods 'fsvoxel-renderer-neoforge-0.6.0.jar'
if((Hash $dependency) -cne (Hash (Join-Path $project 'libs/fsvoxel-renderer-neoforge-0.6.0.jar'))){throw 'Official required dependency differs from the tested dependency'}
foreach($name in $sourceAssets.Keys){if((Hash (ChildPath $config ('models/'+$name))) -cne $sourceAssets[$name]){throw "Official original dual model differs from tested source: $name"}}
$old=@(Get-ChildItem -LiteralPath $mods -File -Filter '*.jar' | Where-Object {IsFsrpg $_.FullName})
if($old.Count -ne 1 -or (ModToml $old[0].FullName) -notmatch '(?m)^\s*version\s*=\s*"0\.7\.6"'){throw 'Expected one installed FSRPG 0.7.6 JAR for this replacement'}
$oldPaths=@($old | ForEach-Object {$_.FullName});$installed=ChildPath $mods 'fsrpg-neoforge-0.7.7.jar'
if(Test-Path -LiteralPath $installed){throw 'The 0.7.7 destination already exists; no unknown file will be overwritten'}
$preserved=@(Get-ChildItem -LiteralPath $mods -Recurse -File | Where-Object {$oldPaths -notcontains $_.FullName} | ForEach-Object {@{path=$_.FullName;sha256=(Hash $_.FullName)}})
foreach($name in @('options.txt','config/fsrpg-client.toml')){$path=ChildPath $instance $name;if(Test-Path -LiteralPath $path){$preserved+=@{path=$path;sha256=(Hash $path)}}}
$allConfigBefore=TreeHashes $configRoot
$saves=ChildPath $instance 'saves';$worlds=if(Test-Path -LiteralPath $saves -PathType Container){TreeHashes $saves}else{@{}}
if($VerifyOnly){Write-Output "VERIFY ONLY PASSED: exact candidate $sha; DISPLAY5 combat revision and native 30 Hz; exact 47-to-49 staged config; dependency hashes; official instance stopped. No files were written.";return}
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss-fff';$backup=ChildPath $instance "fsrpg-backups/$stamp-before-0.7.7"
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
    $record=[ordered]@{version='0.7.7';installedJar=$installed;jarSha256=$sha;testedJarSha256=$review.modJarSha256;backup=$backup;
        previousMods=$previous;editedConfiguration=$edited;newMigrationFiles=$newFiles;configurationSha256=$after;configurationFileCount=$after.Count;
        staminaCombatRules=$migration.staminaCombatRules;existingConfigurationFieldsPreserved=$true;clientTomlByteExactlyPreserved=$true;
        dualSourceAssets=$sourceAssets;nativeSourceFps=$clip.sourceFps;nativeSourceFrames=$frames.Count;combatReview=(Join-Path $gameEvidence 'combat-revision-review.json');
        gestureReview=(Join-Path $gestureEvidence 'spear-gesture-review.json');gestureNativeSourceFps=$gesture.heldSourceHz;gestureNativeSourceFrames=$gesture.heldSourceFrames;
        gestureOverflowRootTurnDegrees=$gesture.overflowRootTurnDegrees;gestureHeldTicksAfterOverflow=$gesture.heldServerTicksAfterOverflow;
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
Write-Output "Installed $installed; SHA-256 $sha; tested/deployed hashes match; 49 config files with only absent stamina fields added; backup $backup"
