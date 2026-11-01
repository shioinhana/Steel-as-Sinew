param(
    [switch]$VerifyOnly,
    [string]$EvidenceDirectory='verification/run-air-20261021',
    [string]$NativeReport='game/run-air-review.json'
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$project=(Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$evidence=if([IO.Path]::IsPathRooted($EvidenceDirectory)){[IO.Path]::GetFullPath($EvidenceDirectory)}else{[IO.Path]::GetFullPath((Join-Path $project $EvidenceDirectory))}
$jar=Join-Path $project 'build/libs/fsrpg-neoforge-0.8.0.jar'
function Hash([string]$path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
function ReadJson([string]$path){Get-Content -LiteralPath $path -Raw | ConvertFrom-Json}
function ChildPath([string]$directory,[string]$relative){
    if([string]::IsNullOrWhiteSpace($relative) -or [IO.Path]::IsPathRooted($relative) -or $relative -match '(^|[\\/])\.\.([\\/]|$)'){throw "Invalid relative path: $relative"}
    $parent=[IO.Path]::GetFullPath($directory).TrimEnd('\','/')
    $result=[IO.Path]::GetFullPath((Join-Path $parent $relative))
    if(-not $result.StartsWith($parent+'\',[StringComparison]::OrdinalIgnoreCase)){throw "Path outside intended directory: $result"}
    return $result
}
function AssertNoReparsePath([string]$path,[string]$boundary){
    $limit=[IO.Path]::GetFullPath($boundary).TrimEnd('\','/')
    $current=[IO.Path]::GetFullPath($path)
    if(-not [string]::Equals($current,$limit,[StringComparison]::OrdinalIgnoreCase) -and
        -not $current.StartsWith($limit+'\',[StringComparison]::OrdinalIgnoreCase)){throw "Path outside checked boundary: $current"}
    while($true){
        if(Test-Path -LiteralPath $current){if(((Get-Item -LiteralPath $current -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw "Linked deployment path is not supported: $current"}}
        if([string]::Equals($current,$limit,[StringComparison]::OrdinalIgnoreCase)){break}
        $current=Split-Path -Parent $current
    }
}
function TreeHashes([string]$directory){
    if(((Get-Item -LiteralPath $directory -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw "Linked preserved tree is not supported: $directory"}
    $result=@{}
    $entries=@(Get-ChildItem -LiteralPath $directory -Recurse -Force)
    if(@($entries | Where-Object {($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0}).Count){throw "Links inside preserved configuration/mod/world trees are not supported: $directory"}
    foreach($file in ($entries | Where-Object {-not $_.PSIsContainer})){
        $relative=[IO.Path]::GetRelativePath($directory,$file.FullName).Replace('\','/')
        $result[$relative]=Hash $file.FullName
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
function RequireNear([double]$expected,[double]$actual,[string]$description,[double]$tolerance=.003){
    if([double]::IsNaN($actual) -or [double]::IsInfinity($actual) -or [Math]::Abs($expected-$actual) -gt $tolerance){throw "$description : expected $expected, actual $actual"}
}
function RequireFiniteAtMost([double]$actual,[double]$limit,[string]$description){
    if([double]::IsNaN($actual) -or [double]::IsInfinity($actual) -or $actual -lt 0 -or $actual -gt $limit){throw "$description exceeds $limit : $actual"}
}
function AssertPng([string]$path){
    $png=[IO.File]::ReadAllBytes($path)
    if($png.Length -lt 24 -or [BitConverter]::ToString($png,0,8) -cne '89-50-4E-47-0D-0A-1A-0A'){throw "Invalid native PNG: $path"}
}
$sha=Hash $jar
$reviewPath=ChildPath $evidence $NativeReport
$gameEvidence=Split-Path -Parent $reviewPath
$review=ReadJson $reviewPath
$buildLog=ChildPath $evidence 'build.log'
$buildText=Get-Content -LiteralPath $buildLog -Raw
$testTasks=@([regex]::Matches($buildText,'(?m)^> Task :(?<name>[A-Za-z][A-Za-z0-9]*Test)(?:[ \t\r]|$)') | ForEach-Object {$_.Groups['name'].Value} | Sort-Object -Unique)
if($buildText -notmatch '(?m)^> Task :build(?:\s|$)' -or $buildText -notmatch 'BUILD SUCCESSFUL' -or
    $buildText -match '(?m)^> Task :[^\r\n]+ FAILED' -or $testTasks.Count -lt 71 -or
    (Get-Item -LiteralPath $buildLog).LastWriteTimeUtc -lt (Get-Item -LiteralPath $jar).LastWriteTimeUtc){throw 'A complete successful build with at least 71 custom test tasks of this exact 0.8.0 JAR was not established'}
if($review.passed -ne $true -or $review.modJarSha256 -cne $sha -or $review.monitorAdapter -cne '\\.\DISPLAY5' -or
    $review.copiedWorld -cne 'FSRPG Run Air Review 20261021 01' -or $review.windowWidth -ne 1000 -or $review.windowHeight -ne 680){throw 'Native report did not pass on the exact JAR, copied world and DISPLAY5'}
$copy=ChildPath $project ('run/client/saves/'+$review.copiedWorld)
if(-not (Test-Path -LiteralPath (ChildPath $copy '.fsrpg-run-air-test-world') -PathType Leaf)){throw 'The named copied test-world marker is missing'}
if($review.perTickPlayerTeleport -ne $false -or $review.perTickVelocityOverwrite -ne $false -or $review.playerTeleportsPerCase -ne 1){throw 'Native run/jump review did not use actual vanilla movement'}
$caseIds=@('spear-run-rear','spear-run-front','spear-stop-reaim','spear-run-turn','spear-run-attack','spear-walk',
    'spear-jump','spear-run-jump','rapier-jump','dual-jump','bow-jump','spear-air-attack','rapier-air-attack','spear-air-guard','spear-air-disabled')
if(@($review.scenarios).Count -ne $caseIds.Count){throw 'Native scenario inventory differs from the fifteen required cases'}
foreach($id in $caseIds){
    $rows=@($review.scenarios | Where-Object {$_.id -ceq $id})
    if($rows.Count -ne 1 -or $rows[0].passed -ne $true -or $rows[0].elapsedTicks -lt 1){throw "Native scenario is absent, duplicated or failed: $id"}
    $v=$review.nativeVisuals.$id
    if($v.frames -le 30){throw "Too few actual submitted avatar frames: $id"}
    RequireFiniteAtMost $v.maxWaistPixels .025 "Actual attached native waist: $id"
    RequireFiniteAtMost $v.maxBodyPitchDegrees 3.001 "Bounded airborne body response: $id"
    RequireFiniteAtMost $v.maxLegPitchDegrees 4.001 "Bounded airborne leg response: $id"
}
foreach($id in @('spear-run-rear','spear-run-front')){
    $v=$review.nativeVisuals.$id
    if($v.carryFrames -le 70 -or $v.maxLeftArm-$v.minLeftArm -le .15 -or $v.maxLeg-$v.minLeg -le .2){throw "Sustained low rear carry, free arm and actual running legs were not established: $id"}
    if([double]::IsNaN($v.maxPalmForward) -or [double]::IsInfinity($v.maxPalmForward) -or $v.maxPalmForward -ge -15 -or
        [double]::IsNaN($v.maxShaftForward) -or [double]::IsInfinity($v.maxShaftForward) -or $v.maxShaftForward -ge -.8 -or
        [double]::IsNaN($v.maxShaftUp) -or [double]::IsInfinity($v.maxShaftUp) -or $v.maxShaftUp -ge .05 -or
        [double]::IsNaN($v.maxPalmUp) -or [double]::IsInfinity($v.maxPalmUp) -or $v.maxPalmUp -ge 50 -or
        [double]::IsNaN($v.maxWorldShaftUp) -or [double]::IsInfinity($v.maxWorldShaftUp) -or $v.maxWorldShaftUp -ge .02){throw "Native pole does not point low behind the torso: $id"}
}
$turn=$review.nativeVisuals.'spear-run-turn'
if($turn.carryFrames -le 30 -or [double]::IsNaN($turn.maxYaw-$turn.minYaw) -or [double]::IsInfinity($turn.maxYaw-$turn.minYaw) -or
    $turn.maxYaw-$turn.minYaw -le 25){throw 'Actual running turn did not retain sustained trailing carry through a twenty-five-degree yaw range'}
if($review.nativeVisuals.'spear-stop-reaim'.returnFrames -le 10){throw 'Stopping after changed aim did not restore two-handed grip'}
if($review.nativeVisuals.'spear-walk'.carryFrames -ne 0){throw 'Ordinary walking unexpectedly uses one-handed running carry'}
foreach($id in @('spear-run-attack','spear-air-attack','rapier-air-attack')){
    $v=$review.nativeVisuals.$id
    if($v.attackFrames -le 1 -or [double]::IsNaN($v.attackGripTravel) -or [double]::IsInfinity($v.attackGripTravel) -or $v.attackGripTravel -le .5){throw "Actual attack did not advance its authored grip: $id"}
}
if($review.nativeVisuals.'spear-air-guard'.guardFrames -le 1){throw 'Explicit actual airborne guard did not override cosmetic motion'}
$airIds=@('spear-jump','spear-run-jump','rapier-jump','dual-jump','bow-jump','spear-air-attack','rapier-air-attack','spear-air-guard','spear-air-disabled')
foreach($id in $airIds){$v=$review.nativeVisuals.$id;if($v.upFrames -le 3 -or $v.downFrames -le 3 -or $v.landedFrames -le 10){throw "Actual ascent, descent and landing were not established: $id"}}
foreach($id in @('spear-jump','spear-run-jump','rapier-jump','dual-jump','bow-jump')){
    $v=$review.nativeVisuals.$id
    if($v.activeInertiaFrames -le 10 -or [double]::IsNaN($v.maximumAirEpisodeRotationRadians) -or [double]::IsInfinity($v.maximumAirEpisodeRotationRadians) -or
        $v.maximumAirEpisodeRotationRadians -le .005){throw "Changing actual airborne body response was not established inside a single jump: $id"}
}
$disabled=$review.nativeVisuals.'spear-air-disabled'
if($disabled.activeInertiaFrames -ne 0 -or [double]::IsNaN($disabled.maximumAirEpisodeRotationRadians) -or [double]::IsInfinity($disabled.maximumAirEpisodeRotationRadians) -or
    $disabled.maximumAirEpisodeRotationRadians -lt 0 -or $disabled.maximumAirEpisodeRotationRadians -ge .0001 -or
    $disabled.maxBodyPitchDegrees -ne 0 -or $disabled.maxLegPitchDegrees -ne 0){throw 'Zero-strength did not preserve the exact retained airborne pose'}

$capturePath=ChildPath $gameEvidence 'render-capture.json'
$capture=ReadJson $capturePath
if($capture.captureEvent -cne 'RenderFrameEvent.Post' -or $capture.pendingWrites -ne 0 -or @($capture.clips).Count -ne 1){throw 'Native framebuffer capture was not completed'}
if($review.renderCapture.captureEvent -cne $capture.captureEvent -or $review.renderCapture.pendingWrites -ne 0 -or
    (ConvertTo-Json -InputObject $review.renderCapture.clips -Depth 100 -Compress) -cne
    (ConvertTo-Json -InputObject $capture.clips -Depth 100 -Compress)){throw 'Native report embeds a different frame capture from the encoded evidence'}
$clip=@($capture.clips)[0];$frames=@($clip.frames)
if($clip.id -cne 'run-air' -or $frames.Count -lt 31 -or $clip.capturedFrames -ne $frames.Count -or $clip.writtenFrames -ne $frames.Count -or
    $clip.sourceFps -lt 30 -or $clip.distinctSamplesWithinGameTick -lt 1 -or $clip.changedRigPairs -le 50){throw 'Native source does not establish genuine changing 30 Hz frames'}
$duration=$frames[-1].capturedAtSeconds-$frames[0].capturedAtSeconds
if($duration -le 0 -or ($frames.Count-1)/$duration -lt 30){throw 'Actual capture timestamps do not establish thirty Hz'}
RequireNear (($frames.Count-1)/$duration) $clip.sourceFps 'Independent source capture FPS' .00000001
$encoded=ReadJson (ChildPath $gameEvidence 'encoded-review.json')
if($encoded.passed -ne $true -or $encoded.originalCaptureUnchanged -ne $true -or $encoded.motionInterpolation -ne $false -or
    $encoded.sourceCaptureSha256 -cne (Hash $capturePath) -or $encoded.clipId -cne $clip.id -or $encoded.capturedFrames -ne $frames.Count -or
    $encoded.outputFps -ne 30 -or $encoded.encodingThreads -ne 2 -or $encoded.independentPngValidation.passed -ne $true -or
    $encoded.independentPngValidation.verifiedPngFiles -ne $frames.Count -or $encoded.independentPngValidation.width -ne 1000 -or $encoded.independentPngValidation.height -ne 680 -or
    $encoded.outputSha256 -cne (Hash (ChildPath $gameEvidence $encoded.output)) -or
    $encoded.independentPngValidation.reportSha256 -cne (Hash (ChildPath $gameEvidence $encoded.independentPngValidation.report)) -or
    $encoded.timelineSha256 -cne (Hash (ChildPath $gameEvidence $encoded.timeline))){throw 'Native video, two-thread encoding, timestamp timeline or independent source PNG validation failed'}
$encoderCommand=@($encoded.ffmpegCommand)
foreach($option in @('-threads','-filter_threads')){
    $positions=@(for($j=0;$j -lt $encoderCommand.Count;$j++){if($encoderCommand[$j] -ceq $option){$j}})
    if($positions.Count -lt 1){throw "Encoder command omits the explicit two-thread setting: $option"}
    foreach($position in $positions){if($position+1 -ge $encoderCommand.Count -or $encoderCommand[$position+1] -cne '2'){throw "Encoder command uses an unexpected thread count: $option"}}
}
$filterIndex=[Array]::IndexOf($encoderCommand,'-vf')
if($filterIndex -lt 0 -or $filterIndex+1 -ge $encoderCommand.Count -or $encoderCommand[$filterIndex+1] -cne 'fps=30:round=near'){throw 'Encoder must select actual source images by time without motion interpolation'}
$videoStreams=@($encoded.ffprobe.streams)
if($videoStreams.Count -ne 1 -or $videoStreams[0].avg_frame_rate -cne '30/1' -or $videoStreams[0].r_frame_rate -cne '30/1' -or
    $videoStreams[0].width -ne 1000 -or $videoStreams[0].height -ne 680 -or
    [long]$videoStreams[0].nb_read_frames -ne $encoded.encodedFrames -or [long]$videoStreams[0].nb_frames -ne $encoded.encodedFrames){throw 'Independent video decode did not establish every encoded thirty-FPS frame'}
RequireNear $duration ([double]$videoStreams[0].duration) 'Encoded duration follows actual source timestamps' (2./30)
$pngValidation=ReadJson (ChildPath $gameEvidence $encoded.independentPngValidation.report)
if($pngValidation.passed -ne $true -or $pngValidation.captureSha256 -cne (Hash $capturePath) -or $pngValidation.verifiedPngFiles -ne $frames.Count -or
    @($pngValidation.frames).Count -ne $frames.Count -or $pngValidation.uniquePngByteHashes -lt 30){throw 'Independent PNG report has the wrong source capture or frame inventory'}
$previousTime=-1.;$previousFrame=-1L;$previousRig=-1L;$submitted=0;$caseSubmissions=@{};$actualUp=@{};$actualDown=@{}
for($i=0;$i -lt $frames.Count;$i++){
    $frame=$frames[$i];$pngRow=$pngValidation.frames[$i]
    if($frame.index -ne $i -or $frame.capturedAtSeconds -le $previousTime -or $frame.renderFrame -le $previousFrame -or
        $frame.paused -ne $false -or $frame.levelRunningNormally -ne $true -or
        $frame.file -cne ('screenshots/render-frames/run-air/frame-'+$i.ToString('D6')+'.png')){throw 'Native frames are duplicated, reordered, paused or misnamed'}
    if($i -gt 0 -and $frame.capturedAtSeconds-$previousTime -gt .25){throw 'Native framebuffer capture contains an unverified stalled interval'}
    $pngPath=ChildPath $gameEvidence $frame.file
    AssertPng $pngPath
    if($pngRow.index -ne $i -or $pngRow.file -cne $frame.file -or $pngRow.verifiedPng -ne $true -or $pngRow.sha256 -cne (Hash $pngPath)){throw "Native PNG no longer matches its independently verified source: $i"}
    RequireNear $frame.capturedAtSeconds $pngRow.capturedAtSeconds 'Independent PNG capture timestamp' .000000001
    if($frame.observation.PSObject.Properties.Name -contains 'submittedAvatar'){
        $avatar=$frame.observation.submittedAvatar
        if($avatar.nativePoseSnapshotStage -cne 'RenderLivingEvent.Post actual avatar submission' -or $avatar.nativePoseSnapshotRenderFrame -le $previousRig){throw 'A native rig was detached from its actual rendered player frame'}
        $previousRig=$avatar.nativePoseSnapshotRenderFrame;$submitted++
        $id=[string]$frame.observation.caseId
        if($caseIds -contains $id){
            if(-not $caseSubmissions.ContainsKey($id)){$caseSubmissions[$id]=0;$actualUp[$id]=0;$actualDown[$id]=0}
            $caseSubmissions[$id]++
            if($frame.observation.onGround -eq $false){
                if($frame.observation.actualVerticalDisplacement -gt .02){$actualUp[$id]++}
                if($frame.observation.actualVerticalDisplacement -lt -.02){$actualDown[$id]++}
            }
            if($frame.observation.onGround -eq $true -or $id -ceq 'spear-air-disabled' -or
                ($id -ceq 'spear-air-guard' -and $avatar.phase -ceq 'GUARDING')){
                $offset=$frame.observation.inertia
                foreach($field in @('pitchDegrees','rollDegrees','legPitchDegrees','legRollDegrees')){
                    RequireNear 0 $offset.$field "Exact inactive native air offset: $id/$field" 0
                }
            }
        }
    }
    $previousTime=$frame.capturedAtSeconds;$previousFrame=$frame.renderFrame
}
if($submitted -lt 31){throw 'Too few native actual-player rig submissions'}
foreach($id in $caseIds){if(-not $caseSubmissions.ContainsKey($id) -or $caseSubmissions[$id] -le 30){throw "Native capture does not cover the submitted avatar for $id"}}
foreach($id in $airIds){if($actualUp[$id] -le 3 -or $actualDown[$id] -le 3){throw "Individual native observations do not establish ascent and descent for $id"}}
$visualPath=ChildPath $gameEvidence 'visual-inspection.json'
$visual=ReadJson $visualPath
if($visual.passed -ne $true -or $visual.modJarSha256 -cne $sha -or @($visual.samples).Count -lt 15){throw 'Original native-image visual review of the exact candidate is missing'}
foreach($id in $caseIds){if(@($visual.samples | Where-Object {$_.id -ceq $id}).Count -lt 1){throw "No reviewed original native image for $id"}}
foreach($sample in $visual.samples){
    if($caseIds -notcontains $sample.id -or $sample.index -lt 0 -or $sample.index -ge $frames.Count){throw 'Visual review sample is outside the native scenario/frame inventory'}
    $frame=$frames[$sample.index]
    if($sample.file -cne $frame.file -or $frame.observation.caseId -cne $sample.id -or $sample.sha256 -cne (Hash (ChildPath $gameEvidence $sample.file))){throw 'Visual review image differs from its actual native case/frame'}
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
function ModToml([string]$path){
    $archive=[IO.Compression.ZipFile]::OpenRead($path)
    try{$entry=$archive.GetEntry('META-INF/neoforge.mods.toml');if($null -eq $entry){return ''}
        $reader=[IO.StreamReader]::new($entry.Open());try{return $reader.ReadToEnd()}finally{$reader.Dispose()}
    }finally{$archive.Dispose()}
}
function IsFsrpg([string]$path){$toml=ModToml $path;return [regex]::IsMatch(($toml -split '\[\[dependencies\.')[0],'(?m)^\s*modId\s*=\s*"fsrpg"')}
function JarEntryHash([IO.Compression.ZipArchive]$archive,[string]$name){
    $entry=$archive.GetEntry($name);if($null -eq $entry){throw "Required release entry is missing: $name"}
    $stream=$entry.Open();try{return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)).ToLowerInvariant()}finally{$stream.Dispose()}
}
if(-not (IsFsrpg $jar) -or (ModToml $jar) -notmatch '(?m)^\s*version\s*=\s*"0\.8\.0"'){throw 'Candidate JAR metadata is not FSRPG 0.8.0'}
$sourceAssets=@{
    'legacy_dual_blade.fsvoxel'='7e4accde21d0d4b8a9249501e3df7a10b8a6e5c6e22bfb254ee5b1ec7017a2b0'
    'legacy_dual_blade_fp.fsvoxel'='ad74c113e8da9b741553690d4eb09218c723ed58ef3489892c69d58e88549f76'
}
$archive=[IO.Compression.ZipFile]::OpenRead($jar)
try{
    if(@($archive.Entries | Where-Object {$_.FullName -match '(^org/fsrpg/smoke/)|MonitorAgent|forging|ArtContent|art/combat_arena'}).Count){throw 'Development code or excluded other-project materials are present in the release JAR'}
    if($null -ne $archive.GetEntry('org/fsrpg/mixin/BurstDashTravelMixin.class')){throw 'The old every-tick movement override is still packaged'}
    foreach($name in @('org/fsrpg/client/AirInertia.class','org/fsrpg/client/SpearRunCarry.class')){if($null -eq $archive.GetEntry($name)){throw "Required tested presentation layer is missing: $name"}}
    foreach($name in $sourceAssets.Keys){if((JarEntryHash $archive ('assets/fsrpg/fsvoxel/models/'+$name)) -cne $sourceAssets[$name]){throw "Original dual source asset changed: $name"}}
    $protocolClassSha=JarEntryHash $archive 'org/fsrpg/FsRpg.class'
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
AssertNoReparsePath $mods $instance
AssertNoReparsePath $config $instance
$configurationBefore=TreeHashes $config
$allConfigBefore=TreeHashes $configRoot
if($configurationBefore.Count -lt 54){throw 'Expected the preserved 0.7.9 configuration inventory (at least 54 existing files); this release performs no migration'}
$dependency=Join-Path $mods 'fsvoxel-renderer-neoforge-0.6.0.jar'
if((Hash $dependency) -cne (Hash (Join-Path $project 'libs/fsvoxel-renderer-neoforge-0.6.0.jar'))){throw 'Official required dependency differs from the tested dependency'}
$old=@(Get-ChildItem -LiteralPath $mods -File -Filter '*.jar' | Where-Object {IsFsrpg $_.FullName})
if($old.Count -ne 1 -or (ModToml $old[0].FullName) -notmatch '(?m)^\s*version\s*=\s*"0\.7\.9"'){throw 'Expected one installed FSRPG 0.7.9 JAR for this replacement'}
if((Hash $old[0].FullName) -cne '9a3fc6648fd30f84605433bf5a86ea75a5e63dc2d58edc6bb96685cf2ac0a592'){throw 'Installed 0.7.9 JAR differs from the previously tested/deployed release'}
$oldArchive=[IO.Compression.ZipFile]::OpenRead($old[0].FullName)
try{if((JarEntryHash $oldArchive 'org/fsrpg/FsRpg.class') -cne $protocolClassSha){throw 'The protocol-10 registration class changed in this presentation-only release'}}finally{$oldArchive.Dispose()}
$installed=ChildPath $mods 'fsrpg-neoforge-0.8.0.jar'
if(Test-Path -LiteralPath $installed){throw 'The 0.8.0 destination already exists; no unknown file will be overwritten'}
$modsBefore=TreeHashes $mods
$expectedMods=@{};foreach($entry in $modsBefore.GetEnumerator()){$expectedMods[$entry.Key]=$entry.Value}
[void]$expectedMods.Remove($old[0].Name);$expectedMods['fsrpg-neoforge-0.8.0.jar']=$sha
$preserved=@()
foreach($name in @('options.txt','config/fsrpg-client.toml')){$path=ChildPath $instance $name;if(Test-Path -LiteralPath $path){$preserved+=@{path=$path;sha256=(Hash $path)}}}
$saves=ChildPath $instance 'saves';$worlds=if(Test-Path -LiteralPath $saves -PathType Container){TreeHashes $saves}else{@{}}
if($VerifyOnly){Write-Output "VERIFY ONLY PASSED: exact candidate $sha; 71+ custom test tasks; fifteen DISPLAY5 run/air scenes; all original PNGs and true timestamps; two-thread 30 FPS movie without interpolation; all $($configurationBefore.Count) FSRPG config files and full config tree will remain byte-exact; dependency hashes; official instance stopped. No files were written.";return}
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$backup=ChildPath $instance "fsrpg-backups/$stamp-before-0.8.0"
AssertNoReparsePath $backup $instance
if(Test-Path -LiteralPath $backup){throw 'Dated backup already exists'}
New-Item -ItemType Directory -Path (Join-Path $backup 'mods') -Force | Out-Null
Copy-Item -LiteralPath $configRoot -Destination (Join-Path $backup 'config') -Recurse -Force
SameTree $allConfigBefore (Join-Path $backup 'config') 'Full configuration backup'
$previous=@()
foreach($file in $old){
    if($file.DirectoryName -cne $mods -or $file.Extension -cne '.jar'){throw 'Previous mod is outside the exact mods directory'}
    $saved=ChildPath (Join-Path $backup 'mods') $file.Name
    Copy-Item -LiteralPath $file.FullName -Destination $saved
    if((Hash $saved) -cne (Hash $file.FullName)){throw 'Previous FSRPG JAR backup hash mismatch'}
    $previous+=@{path=$file.FullName;backup=$saved;sha256=(Hash $saved)}
}
AssertInstanceStopped
SameTree $allConfigBefore $configRoot 'All official configuration immediately before deployment'
SameTree $modsBefore $mods 'All official mods immediately before deployment'
if((Hash $jar) -cne $sha){throw 'Candidate JAR changed after verification'}
$removed=@();$installedTouched=$false
try{
    foreach($file in $previous){
        if((Hash $file.path) -cne $file.sha256){throw 'Previous FSRPG JAR changed immediately before replacement'}
        Remove-Item -LiteralPath $file.path
        $removed+=,$file
    }
    # CreateNew refuses a concurrent destination instead of overwriting it.
    $destinationStream=$null;$sourceStream=$null
    try{
        $destinationStream=[IO.File]::Open($installed,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        $installedTouched=$true
        $sourceStream=[IO.File]::OpenRead($jar);$sourceStream.CopyTo($destinationStream)
    }finally{if($null -ne $sourceStream){$sourceStream.Dispose()};if($null -ne $destinationStream){$destinationStream.Dispose()}}
    if((Hash $installed) -cne $sha){throw 'Installed JAR is not the exact tested candidate'}
    SameTree $expectedMods $mods 'Only this tested FSRPG JAR was replaced'
    SameTree $allConfigBefore $configRoot 'Every existing configuration file stays byte-exact'
    SameTree $configurationBefore $config 'Every FSRPG configuration file stays byte-exact'
    foreach($file in $preserved){if((Hash $file.path) -cne $file.sha256){throw "Preserved file changed: $($file.path)"}}
    if(Test-Path -LiteralPath $saves -PathType Container){SameTree $worlds $saves 'Unchanged user worlds'}elseif($worlds.Count -gt 0){throw 'User worlds disappeared'}
    $record=[ordered]@{version='0.8.0';networkProtocol=10;installedJar=$installed;jarSha256=$sha;testedJarSha256=$review.modJarSha256;backup=$backup;
        previousMods=$previous;configurationSha256=$configurationBefore;configurationFileCount=$configurationBefore.Count;allConfigurationSha256=$allConfigBefore;
        configurationFilesWritten=0;configurationMigration=$false;existingConfigurationByteExactlyPreserved=$true;clientTomlByteExactlyPreserved=$true;
        clientAirInertiaDefault='NeoForge may add its new default on the next user launch; deployment does not edit TOML';
        dualSourceAssets=$sourceAssets;nativeReview=$reviewPath;nativeReviewSha256=(Hash $reviewPath);nativeScenarioCount=$caseIds.Count;
        nativeSourceFps=$clip.sourceFps;nativeSourceFrames=$frames.Count;nativeMotionInterpolation=$false;encodingThreads=2;
        captureSha256=(Hash $capturePath);encodedReviewSha256=(Hash (ChildPath $gameEvidence 'encoded-review.json'));visualReviewSha256=(Hash $visualPath);
        customTestTasks=$testTasks;buildLogSha256=(Hash $buildLog);worldsModified=$false;worldFileCount=$worlds.Count;otherModsPreserved=$true;
        modsBeforeSha256=$modsBefore;modsAfterSha256=$expectedMods;preserved=$preserved;evidence=$evidence;deployedAt=(Get-Date).ToString('o')}
    $record | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $backup 'deployment.json') -Encoding utf8
    $record | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $evidence 'deployment.json') -Encoding utf8
}catch{
    $originalFailure=$_;$rollbackErrors=@()
    try{if($installedTouched -and (Test-Path -LiteralPath $installed)){Remove-Item -LiteralPath $installed}}catch{$rollbackErrors+=$_.ToString()}
    foreach($file in $removed){try{
        if(Test-Path -LiteralPath $file.path){if((Hash $file.path) -cne $file.sha256){throw "Concurrent file occupies rollback destination: $($file.path)"}}
        else{[IO.File]::Copy($file.backup,$file.path,$false)}
        if((Hash $file.path) -cne $file.sha256){throw 'Rolled-back JAR mismatch'}
    }catch{$rollbackErrors+=$_.ToString()}}
    # Configuration and worlds were never written: rollback must not overwrite them.
    try{SameTree $allConfigBefore $configRoot 'Configuration untouched during failed deployment'}catch{$rollbackErrors+=$_.ToString()}
    try{SameTree $modsBefore $mods 'Mod inventory after rollback'}catch{$rollbackErrors+=$_.ToString()}
    @{deploymentFailure=$originalFailure.ToString();rollbackErrors=$rollbackErrors;backup=$backup;configurationFilesWritten=0;worldsModified=$false} |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $backup 'rollback.json') -Encoding utf8
    if($rollbackErrors.Count -gt 0){throw "Deployment failed: $originalFailure; rollback needs attention: $($rollbackErrors -join ' | '); backup: $backup"}
    throw $originalFailure
}
Write-Output "Installed $installed; SHA-256 $sha; tested/deployed hashes match; all $($configurationBefore.Count) FSRPG configuration files, every other config/TOML, other mods and worlds stay byte-exact; backup $backup"
