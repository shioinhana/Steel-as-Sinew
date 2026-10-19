param(
    [switch]$VerifyOnly,
    [string]$EvidenceDirectory='verification/shoulder-combat-20261019',
    [string]$ShoulderReport='game/shoulder-combat-review.json',
    [string]$BurstReport='burst-swipe/burst-swipe-review.json'
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$project=(Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$evidence=if([IO.Path]::IsPathRooted($EvidenceDirectory)){[IO.Path]::GetFullPath($EvidenceDirectory)}else{[IO.Path]::GetFullPath((Join-Path $project $EvidenceDirectory))}
$jar=Join-Path $project 'build/libs/fsrpg-neoforge-0.7.8.jar'
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
$shoulderPath=ChildPath $evidence $ShoulderReport;$burstPath=ChildPath $evidence $BurstReport
$gameEvidence=Split-Path -Parent $shoulderPath;$burstEvidence=Split-Path -Parent $burstPath
$review=ReadJson $shoulderPath;$burst=ReadJson $burstPath
$stage=(Resolve-Path -LiteralPath (ChildPath $evidence 'config-stage/fsrpg')).Path
$migration=ReadJson (ChildPath $evidence 'config-stage/report.json')
$buildLog=ChildPath $evidence 'build.log';$buildText=Get-Content -LiteralPath $buildLog -Raw
if($buildText -notmatch '(?m)^> Task :build(?:\s|$)' -or $buildText -notmatch 'BUILD SUCCESSFUL' -or
    (Get-Item -LiteralPath $buildLog).LastWriteTimeUtc -lt (Get-Item -LiteralPath $jar).LastWriteTimeUtc){throw 'Required complete build of this 0.7.8 JAR was not established'}
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
function IsLocked($snapshot){return $snapshot.enabled -eq $true -and $snapshot.targetId -ge 0 -and -not [string]::IsNullOrWhiteSpace($snapshot.targetUuid)}
AssertNativeEnvironment $review 'FSRPG Shoulder Combat Review 20261019 01' '.fsrpg-shoulder-combat-test-world'
AssertNativeEnvironment $burst 'FSRPG Burst Swipe Review 20261019 01' '.fsrpg-burst-swipe-test-world'
$shoulderRows=ScenarioRows $review @('equip-sheathed','select-husk','keep-passive','r-unlock-sheath','source-combo',
    'air-rapier','air-dual','air-spear','air-bow','air-attack','dual-armor','camera-only') 'elapsedTicks'
if($review.prewarmedProductionModels -ne 10 -or $review.prewarmClientTicks -lt 50 -or
    $review.officialMainHandSubmissions -lt 1 -or $review.officialOffHandSubmissions -lt 1 -or @($review.nativeSkinModels).Count -lt 1){throw 'Prewarmed real player/official item native submissions are missing'}
if($shoulderRows['equip-sheathed'].autoDrawOnEquip -ne $false -or $shoulderRows['r-unlock-sheath'].drawn -ne $false -or
    (IsLocked $shoulderRows['r-unlock-sheath'].targetLock)){throw 'Manual draw or first-unlock/second-sheath behavior was not established'}
foreach($id in @('select-husk','keep-passive')){
    if($shoulderRows[$id].targetUuid -cne $shoulderRows[$id].targetLock.targetUuid -or -not (IsLocked $shoulderRows[$id].targetLock)){throw "Explicit selected target was not retained: $id"}
}
$selection=$review.clientCameraSelection
if($selection.clientTargetAttackCalls -ne 1 -or $selection.directServerAttackCalls -ne 0 -or
    $selection.reticleIntersectsTarget -ne $true -or $review.lockedOrbitNativeFrames -le 10 -or
    $selection.targetUuid -cne $shoulderRows['select-husk'].targetUuid -or
    $selection.targetId -ne $shoulderRows['select-husk'].targetLock.targetId){throw 'Actual client reticle selection or native locked-camera orbit is missing'}
RequireNear 18 $selection.unlockedRawYawDegrees 'Unlocked actual raw-mouse yaw delta' .01
RequireNear 60 $selection.lockedRawYawDegrees 'Locked actual raw-mouse yaw delta' .01
foreach($errorValue in @($selection.unlockedActorCameraYawError,$selection.lockedActorTargetYawError,
    $shoulderRows['select-husk'].unlockedServerYawError,$shoulderRows['select-husk'].serverLockedYawError)){
    RequireFiniteAtMost $errorValue .2 'Actual client/server actor facing error'
    if([double]$errorValue -ge .2){throw 'Actual client/server actor facing error must remain below 0.2 degrees'}
}
RequireFiniteAtMost $selection.lockedCameraActorYawSeparation 180 'Locked camera/actor separation'
if($selection.lockedCameraActorYawSeparation -le 45){throw 'Actual locked orbit did not decouple the camera by over 45 degrees'}
$qualifiedTransitions=@{enter=$false;exit=$false}
foreach($transition in @($review.cameraTransitions)){
    if($transition.direction -cnotin @('enter','exit')){throw 'Unknown native camera transition direction'}
    RequireNear .5 $transition.configuredSeconds 'Configured camera transition duration' .000001
    RequireFiniteAtMost $transition.durationSeconds 10 'Actual camera transition duration'
    RequireFiniteAtMost $transition.minimumIntermediateEyeDistance 64 'Minimum intermediate camera eye distance'
    RequireFiniteAtMost $transition.maximumIntermediateEyeDistance 64 'Maximum intermediate camera eye distance'
    $eyeTravel=$transition.maximumIntermediateEyeDistance-$transition.minimumIntermediateEyeDistance
    # A reversal can interrupt another transition; both complete directions must independently qualify.
    if([Math]::Abs($transition.durationSeconds-.5) -le .085 -and $transition.intermediateFrames -ge 8 -and
        $transition.middleFrames -ge 3 -and $eyeTravel -gt .5){$qualifiedTransitions[$transition.direction]=$true}
}
if(-not $qualifiedTransitions.enter -or -not $qualifiedTransitions.exit){throw 'Native half-second entry and exit each need eight intermediate frames, three middle frames and over half a block of eye travel'}
$v=$review.nativeVisuals.SOURCE_COMBO
if($v.sourceFrames -le 20 -or $v.bridgeFrames -ne 0){throw 'Source sword combo was not submitted without default-pose bridges'}
RequireFiniteAtMost $v.sourceError .003 'Submitted source combo matrix error'
foreach($name in @('AIR_RAPIER','AIR_DUAL','AIR_SPEAR','AIR_BOW')){$v=$review.nativeVisuals.$name
    if($v.airHeldFrames -le 5){throw "Too few actual airborne pose submissions: $name"}
    RequireFiniteAtMost $v.airError .001 "Air rig retention: $name";RequireFiniteAtMost $v.nativeAirError .001 "Native air model retention: $name"
}
$v=$review.nativeVisuals.AIR_ATTACK
if($v.airAttackFrames -le 2 -or $v.airHeldFrames -le 3){throw 'Airborne thrust or endpoint was not actually presented'}
RequireFiniteAtMost $v.lowerAirError .001 'Air attack lower rig retention';RequireFiniteAtMost $v.nativeLowerAirError .001 'Air attack native lower pose'
RequireFiniteAtMost $v.waistJoinError .003 'Air attack attached waist';RequireFiniteAtMost $v.airError .001 'Air attack endpoint retention'
$v=$review.nativeVisuals.DUAL_ARMOR
if($v.shoulderFrames -le 20 -or $v.armorFrames -le 5){throw 'Dual shoulder and equipped armor were not actually submitted'}
RequireFiniteAtMost $v.shoulderError .003 'Shared dual shoulder/item chain error'

$capturePath=ChildPath $gameEvidence 'render-capture.json';$capture=ReadJson $capturePath
if($capture.captureEvent -cne 'RenderFrameEvent.Post' -or $capture.pendingWrites -ne 0 -or @($capture.clips).Count -ne 1){throw 'Native framebuffer capture was not completed'}
if($review.renderCapture.captureEvent -cne $capture.captureEvent -or $review.renderCapture.pendingWrites -ne 0 -or
    (ConvertTo-Json -InputObject $review.renderCapture.clips -Depth 100 -Compress) -cne
    (ConvertTo-Json -InputObject $capture.clips -Depth 100 -Compress)){throw 'Native review embeds a different frame capture from the encoded evidence'}
$clip=@($capture.clips)[0];$frames=@($clip.frames)
if($clip.id -cne 'shoulder-combat' -or $frames.Count -lt 31 -or $clip.capturedFrames -ne $frames.Count -or $clip.writtenFrames -ne $frames.Count -or
    $clip.sourceFps -lt 30 -or $clip.distinctSamplesWithinGameTick -lt 1 -or $clip.changedRigPairs -le 50){throw 'Native source does not establish genuine changing 30 Hz frames'}
$duration=$frames[-1].capturedAtSeconds-$frames[0].capturedAtSeconds
if($duration -le 0 -or ($frames.Count-1)/$duration -lt 30){throw 'Actual capture timestamps do not establish 30 Hz'}
RequireNear (($frames.Count-1)/$duration) $clip.sourceFps 'Independent source capture FPS' .00000001
$previousTime=-1.;$previousFrame=-1L;$previousRig=-1L;$submitted=0;$observedOrbitFrames=0;$lastOrbitFrame=0
for($i=0;$i -lt $frames.Count;$i++){$frame=$frames[$i]
    if($frame.index -ne $i -or $frame.capturedAtSeconds -le $previousTime -or $frame.renderFrame -le $previousFrame -or
        $frame.paused -ne $false -or $frame.levelRunningNormally -ne $true -or
        $frame.file -cne ('screenshots/render-frames/shoulder-combat/frame-'+$i.ToString('D6')+'.png')){throw 'Native frames are duplicated, reordered, paused or misnamed'}
    AssertPng (ChildPath $gameEvidence $frame.file)
    # Camera-only views may omit the local avatar; observed avatars must still originate in this actual frame.
    if($frame.observation.PSObject.Properties.Name -contains 'submittedAvatar'){
        $avatar=$frame.observation.submittedAvatar
        if($avatar.nativePoseSnapshotStage -cne 'RenderLivingEvent.Post actual avatar submission' -or $avatar.nativePoseSnapshotRenderFrame -le $previousRig){throw 'A native rig was detached from its actual rendered player frame'}
        $previousRig=$avatar.nativePoseSnapshotRenderFrame;$submitted++
    }
    if($frame.observation.PSObject.Properties.Name -contains 'lockedOrbitNativeFrame'){
        if($frame.observation.clientReticleAttackSent -ne $true -or $frame.observation.clientReticleLockVerified -ne $true -or
            $frame.observation.lockedOrbitNativeFrame -le $lastOrbitFrame){throw 'Recorded locked-camera orbit is not bound to actual client selection and advancing native frames'}
        $lastOrbitFrame=$frame.observation.lockedOrbitNativeFrame;$observedOrbitFrames++
    }
    $previousTime=$frame.capturedAtSeconds;$previousFrame=$frame.renderFrame
}
if($submitted -lt 31){throw 'Too few native actual-player rig submissions'}
if($observedOrbitFrames -le 10 -or $observedOrbitFrames -ne $review.lockedOrbitNativeFrames){throw 'Locked orbit count differs from the actual captured source frames'}
$encoded=ReadJson (ChildPath $gameEvidence 'encoded-review.json')
if($encoded.passed -ne $true -or $encoded.originalCaptureUnchanged -ne $true -or $encoded.motionInterpolation -ne $false -or
    $encoded.sourceCaptureSha256 -cne (Hash $capturePath) -or $encoded.clipId -cne $clip.id -or $encoded.capturedFrames -ne $frames.Count -or
    $encoded.outputFps -ne 30 -or $encoded.independentPngValidation.passed -ne $true -or $encoded.independentPngValidation.verifiedPngFiles -ne $frames.Count -or
    $encoded.outputSha256 -cne (Hash (ChildPath $gameEvidence $encoded.output)) -or
    $encoded.independentPngValidation.reportSha256 -cne (Hash (ChildPath $gameEvidence $encoded.independentPngValidation.report))){throw 'Native video or independent source PNG validation failed'}

$burstRows=ScenarioRows $burst @('ground-tap','held-run','jump-burst','swipe-left','swipe-right','ground-down','air-down') 'actualElapsedTicks'
if($burst.perTickPlayerTeleport -ne $false -or $burst.playerTeleportsPerCase -ne 1 -or $burst.nativeFrames -le 30 -or $burst.nativeSourceHz -lt 30 -or $burst.screenshots -lt 7){throw 'Burst/swipe review did not use real local physics and native render sampling'}
foreach($id in @('ground-tap','held-run','jump-burst')){$row=$burstRows[$id]
    RequireNear 5 $row.initialReservedCost "Initial actual Shift payment: $id" .02
    RequireNear 5 $row.finalNetStaminaCost "Final Shift payment after real recovery: $id" .02
}
RequireNear 3 $burstRows['ground-tap'].serverDistanceAt10 'Ten actual burst ticks cover three blocks' .55
RequireNear 5.6 $burstRows['held-run'].runDistance20Ticks 'Twenty actual held-run ticks' .65
RequireNear 0 $burstRows['held-run'].distanceLastFourTicksAfterRelease 'Released run friction settling' .08
$row=$burstRows['jump-burst']
if($row.clientJumpPeak -lt 1 -or $row.positiveYDuringBurst -ne $true -or $row.negativeYDuringBurst -ne $true -or $row.landed -ne $true -or $row.serverHorizontalDistance -le 2.4){throw 'Burst overwrote actual jump ascent/fall or failed normal collision landing'}
foreach($id in @('swipe-left','swipe-right','ground-down','air-down')){$row=$burstRows[$id]
    if($row.damageEvents -ne 1){throw "Held swipe did not deduplicate actual contacts: $id"}
    $velocity=$row.firstContactVelocity
    if($id -ceq 'swipe-left' -or $id -ceq 'swipe-right'){
        if(($id -ceq 'swipe-left' -and $velocity.x -ge 0) -or ($id -ceq 'swipe-right' -and $velocity.x -le 0)){throw 'Actual lateral swipe direction was reversed'}
        RequireNear 0 $velocity.z 'Lateral swipe has no invented forward shove' .055
        RequireNear 0 $velocity.y 'Lateral swipe has no ground hop' .001
        RequireNear $row.configuredHalfKnockback ([Math]::Sqrt($velocity.x*$velocity.x+$velocity.z*$velocity.z)) 'Actual configured half knockback' .025
    }elseif($id -ceq 'ground-down'){
        if($row.groundedAtContact -ne $true){throw 'Ground downcut target was not grounded'};RequireNear 0 $velocity.y 'Ground downcut has no bounce' .001
    }elseif($row.groundedAtContact -ne $false -or $row.beforeContactVelocity.y -le 0 -or $velocity.y -ge 0){throw 'Air downcut did not reverse real upward velocity into descent'}
}
$native=@($burst.nativeSubmittedTrace)
if($native.Count -ne $burst.nativeFrames){throw 'Native burst/swipe trace frame count differs'}
$previousNanos=-1L;$previousNative=-1L
foreach($sample in $native){
    if($sample.nativeNanoTime -le $previousNanos -or $sample.nativePoseSnapshotRenderFrame -le $previousNative -or
        $sample.nativePoseSnapshotStage -cne 'RenderLivingEvent.Post actual avatar submission'){throw 'Burst/swipe native submissions are duplicated or not actual player frames'}
    $previousNanos=$sample.nativeNanoTime;$previousNative=$sample.nativePoseSnapshotRenderFrame
}
$nativeDuration=($native[-1].nativeNanoTime-$native[0].nativeNanoTime)/1e9
if($nativeDuration -le 0 -or ($native.Count-1)/$nativeDuration -lt 30){throw 'Native burst source timestamps do not establish thirty Hz'}
RequireNear (($native.Count-1)/$nativeDuration) $burst.nativeSourceHz 'Independent burst/swipe native source FPS' .00000001
foreach($id in $burstRows.Keys){AssertPng (ChildPath $burstEvidence ('screenshots/'+$id+'.png'))}
if($migration.passed -ne $true -or $migration.version -cne '0.7.8' -or $migration.sourceCopiedByteExactly -ne $true -or
    $migration.officialSourceUntouched -ne $true -or $migration.onlyMissingBurstAndSwipeFieldsAdded -ne $true -or
    $migration.allOtherExistingFieldsAndFilesPreserved -ne $true -or $migration.altDodgeTuningPreserved -ne $true -or
    $migration.exactOriginalBackups -ne $true -or $migration.secondReloadIdempotent -ne $true -or
    $migration.existingFileCount -ne 49 -or $migration.stagedFileCount -ne 51){throw 'The exact field-only 49-to-51-file staged migration failed'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
function ModToml([string]$path){
    $archive=[IO.Compression.ZipFile]::OpenRead($path)
    try{$entry=$archive.GetEntry('META-INF/neoforge.mods.toml');if($null -eq $entry){return ''}
        $reader=[IO.StreamReader]::new($entry.Open());try{return $reader.ReadToEnd()}finally{$reader.Dispose()}
    }finally{$archive.Dispose()}
}
function IsFsrpg([string]$path){$toml=ModToml $path;return [regex]::IsMatch(($toml -split '\[\[dependencies\.')[0],'(?m)^\s*modId\s*=\s*"fsrpg"')}
if(-not (IsFsrpg $jar) -or (ModToml $jar) -notmatch '(?m)^\s*version\s*=\s*"0\.7\.8"'){throw 'Candidate JAR metadata is not FSRPG 0.7.8'}
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
if($before.Count -ne 49 -or $after.Count -ne 51){throw 'Unexpected official/staged FSRPG configuration inventory'}
$edited=@('combat.json');$allowedNew=@('burst-dash-migration-v1.json','burst-dash-backup-v1/combat.json');$newFiles=@()
foreach($name in $before.Keys){
    if(-not $after.ContainsKey($name)){throw "Migration removed existing file: $name"}
    if($edited -notcontains $name -and $before[$name] -cne $after[$name]){throw "Migration changed unrelated existing file: $name"}
}
foreach($name in $after.Keys){
    [void](ChildPath $stage $name);[void](ChildPath $config $name)
    if($before.ContainsKey($name)){continue};if($allowedNew -notcontains $name){throw "Unapproved new staged file: $name"};$newFiles+=,$name
}
if($newFiles.Count -ne 2 -or $after['burst-dash-backup-v1/combat.json'] -cne $before['combat.json']){throw 'Missing approved migration files or byte-exact original combat backup'}
$oldCombat=ReadJson (ChildPath $config 'combat.json');$newCombat=ReadJson (ChildPath $stage 'combat.json')
if($oldCombat.PSObject.Properties.Name -notcontains 'burstDash'){$newCombat.PSObject.Properties.Remove('burstDash')}
else{foreach($field in @('distance','durationTicks','staminaCost','cooldownTicks','runSpeed','inputLeaseTicks')){
    if($oldCombat.burstDash.PSObject.Properties.Name -notcontains $field){$newCombat.burstDash.PSObject.Properties.Remove($field)}
}}
if($oldCombat.PSObject.Properties.Name -notcontains 'spearSwipeKnockbackMultiplier'){$newCombat.PSObject.Properties.Remove('spearSwipeKnockbackMultiplier')}
if((CanonicalJson $oldCombat) -cne (CanonicalJson $newCombat)){throw 'Staged combat changed an existing value rather than only adding absent burst/swipe fields'}
$dependency=Join-Path $mods 'fsvoxel-renderer-neoforge-0.6.0.jar'
if((Hash $dependency) -cne (Hash (Join-Path $project 'libs/fsvoxel-renderer-neoforge-0.6.0.jar'))){throw 'Official required dependency differs from the tested dependency'}
$old=@(Get-ChildItem -LiteralPath $mods -File -Filter '*.jar' | Where-Object {IsFsrpg $_.FullName})
if($old.Count -ne 1 -or (ModToml $old[0].FullName) -notmatch '(?m)^\s*version\s*=\s*"0\.7\.7"'){throw 'Expected one installed FSRPG 0.7.7 JAR for this replacement'}
$oldPaths=@($old | ForEach-Object {$_.FullName});$installed=ChildPath $mods 'fsrpg-neoforge-0.7.8.jar'
if(Test-Path -LiteralPath $installed){throw 'The 0.7.8 destination already exists; no unknown file will be overwritten'}
$preserved=@(Get-ChildItem -LiteralPath $mods -Recurse -File | Where-Object {$oldPaths -notcontains $_.FullName} | ForEach-Object {@{path=$_.FullName;sha256=(Hash $_.FullName)}})
foreach($name in @('options.txt','config/fsrpg-client.toml')){$path=ChildPath $instance $name;if(Test-Path -LiteralPath $path){$preserved+=@{path=$path;sha256=(Hash $path)}}}
$allConfigBefore=TreeHashes $configRoot
$saves=ChildPath $instance 'saves';$worlds=if(Test-Path -LiteralPath $saves -PathType Container){TreeHashes $saves}else{@{}}
if($VerifyOnly){Write-Output "VERIFY ONLY PASSED: exact candidate $sha; DISPLAY5 shoulder/combat and burst/swipe reports with native 30 Hz; exact 49-to-51 staged config; dependency hashes; official instance stopped. No files were written.";return}
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss-fff';$backup=ChildPath $instance "fsrpg-backups/$stamp-before-0.7.8"
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
    $record=[ordered]@{version='0.7.8';installedJar=$installed;jarSha256=$sha;testedJarSha256=$review.modJarSha256;burstTestedJarSha256=$burst.modJarSha256;backup=$backup;
        previousMods=$previous;editedConfiguration=$edited;newMigrationFiles=$newFiles;configurationSha256=$after;configurationFileCount=$after.Count;
        burstDash=$migration.burstDash;spearSwipeKnockbackMultiplier=$migration.spearSwipeKnockbackMultiplier;existingConfigurationFieldsPreserved=$true;altDodgeTuningPreserved=$true;
        clientTomlByteExactlyPreserved=$true;dualSourceAssets=$sourceAssets;nativeSourceFps=$clip.sourceFps;nativeSourceFrames=$frames.Count;
        shoulderReview=$shoulderPath;shoulderReviewSha256=(Hash $shoulderPath);burstReview=$burstPath;burstReviewSha256=(Hash $burstPath);
        burstNativeSourceFps=$burst.nativeSourceHz;burstNativeSourceFrames=$burst.nativeFrames;nativeMotionInterpolation=$false;
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
Write-Output "Installed $installed; SHA-256 $sha; tested/deployed hashes match; 51 config files with only absent burst/swipe fields added; backup $backup"
