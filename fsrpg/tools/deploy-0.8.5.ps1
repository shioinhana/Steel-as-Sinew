param(
    [switch]$VerifyOnly,
    [string]$EvidenceDirectory='verification/spear-orbit-20261021',
    [string]$RapierEvidenceDirectory='verification/rapier-fencing-20261021',
    [string]$ConfigCandidate='verification/spear-orbit-20261021/official-config-candidate/fsrpg',
    [string]$ConfigReport='verification/spear-orbit-20261021/stage-config.json',
    [string]$RapierAssetManifest='docs/rapier-fencing-0.8.5-manifest.json',
    [string]$Python='python'
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$project=(Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
function ProjectPath([string]$path){if([IO.Path]::IsPathRooted($path)){return [IO.Path]::GetFullPath($path)};return [IO.Path]::GetFullPath((Join-Path $project $path))}
$evidence=ProjectPath $EvidenceDirectory
$rapierEvidence=ProjectPath $RapierEvidenceDirectory
$stage=ProjectPath $ConfigCandidate
$stageReport=ProjectPath $ConfigReport
$assetManifest=ProjectPath $RapierAssetManifest
$jar=Join-Path $project 'build/libs/fsrpg-neoforge-0.8.5.jar'
function Hash([string]$path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
$script:atomicArtifacts=@()
function RemoveAtomicArtifact([hashtable]$artifact){
    if(-not (Test-Path -LiteralPath $artifact.path)){return}
    if($artifact.ContainsKey('retain') -and $artifact.retain){throw "Atomic preimage retained until its exact bytes are restored: $($artifact.path)"}
    if(((Get-Item -LiteralPath $artifact.path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
        $null -eq $artifact.sha256 -or (Hash $artifact.path) -cne $artifact.sha256){throw "Unknown/concurrent temporary contents retained for review: $($artifact.path)"}
    Remove-Item -LiteralPath $artifact.path
}
function CleanupAtomicArtifacts{
    $failures=@()
    for($i=$script:atomicArtifacts.Count-1;$i -ge 0;$i--){
        try{RemoveAtomicArtifact $script:atomicArtifacts[$i]}catch{$failures+=$_.ToString()}
    }
    if($failures.Count){throw ($failures -join ' | ')}
}
function WriteAtomicVerified([string]$source,[string]$destination,[string]$expectedSha,[AllowNull()]$previousSha,[hashtable]$writeRecord){
    $parent=Split-Path -Parent $destination
    $nonce=[Guid]::NewGuid().ToString('N')
    $staged=ChildPath $parent ('.fsrpg-085-'+$nonce+'-stage.tmp')
    $preimage=ChildPath $parent ('.fsrpg-085-'+$nonce+'-preimage.tmp')
    $stagedArtifact=@{path=$staged;sha256=$null}
    $preimageArtifact=$null
    $writeRecord.committed=$false;$writeRecord.preimage=$null;$writeRecord.preimageSha256=$null;$writeRecord.preimageArtifact=$null
    try{
        $destinationStream=$null;$sourceStream=$null
        try{
            $destinationStream=[IO.File]::Open($staged,[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
            $script:atomicArtifacts+=,$stagedArtifact
            $sourceStream=[IO.File]::OpenRead($source)
            $sourceStream.CopyTo($destinationStream)
            $destinationStream.Flush($true)
        }finally{
            if($null -ne $sourceStream){$sourceStream.Dispose()}
            if($null -ne $destinationStream){
                try{$destinationStream.Position=0;$stagedArtifact.sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($destinationStream)).ToLowerInvariant()}
                finally{$destinationStream.Dispose()}
            }
        }
        if($stagedArtifact.sha256 -cne $expectedSha -or (Hash $staged) -cne $expectedSha){throw "Atomic staging SHA mismatch: $destination"}
        if($null -eq $previousSha){
            # A racing new file is never overwritten. A partial stage never has a .jar/.json name.
            [IO.File]::Move($staged,$destination,$false)
            $writeRecord.committed=$true
        }else{
            if(-not (Test-Path -LiteralPath $destination -PathType Leaf) -or (Hash $destination) -cne $previousSha){throw "Concurrent edit before atomic replacement: $destination"}
            if(Test-Path -LiteralPath $preimage){throw "Atomic preimage destination already exists: $preimage"}
            $preimageArtifact=@{path=$preimage;sha256=$null;retain=$true}
            $script:atomicArtifacts+=,$preimageArtifact
            $writeRecord.preimage=$preimage;$writeRecord.preimageArtifact=$preimageArtifact
            # Replace preserves the exact displaced bytes, including an edit racing the last hash.
            [IO.File]::Replace($staged,$destination,$preimage,$false)
            $writeRecord.committed=$true
            $preimageArtifact.sha256=Hash $preimage
            $writeRecord.preimageSha256=$preimageArtifact.sha256
            if($preimageArtifact.sha256 -cne $previousSha){throw "Concurrent edit captured intact by atomic replacement: $preimage"}
            $preimageArtifact.retain=$false
            RemoveAtomicArtifact $preimageArtifact
            $writeRecord.preimage=$null
        }
        if((Hash $destination) -cne $expectedSha){throw "Atomic destination changed after commit: $destination"}
    }catch{
        # File.Replace may have completed before a later I/O failure. Its unique preimage is
        # authoritative for rollback; never replace it with an older dated-backup version.
        if($null -ne $preimageArtifact -and (Test-Path -LiteralPath $preimage)){
            $writeRecord.committed=$true
            if($null -eq $preimageArtifact.sha256){$preimageArtifact.sha256=Hash $preimage}
            $writeRecord.preimageSha256=$preimageArtifact.sha256
        }
        throw
    }finally{
        RemoveAtomicArtifact $stagedArtifact
    }
}
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
function RequireFiniteValue([object]$value,[string]$description){
    if($null -eq $value -or $value -is [string] -or $value -is [bool]){throw "Missing or nonnumeric evidence: $description"}
    $actual=[double]$value
    if([double]::IsNaN($actual) -or [double]::IsInfinity($actual)){throw "Nonfinite evidence: $description"}
    return $actual
}
function AssertPng([string]$path){
    $png=[IO.File]::ReadAllBytes($path)
    if($png.Length -lt 24 -or [BitConverter]::ToString($png,0,8) -cne '89-50-4E-47-0D-0A-1A-0A'){throw "Invalid native PNG: $path"}
}
$sha=Hash $jar
$buildLog=ChildPath $evidence 'build.log'
$buildText=Get-Content -LiteralPath $buildLog -Raw
$testTasks=@([regex]::Matches($buildText,'(?m)^> Task :(?<name>[A-Za-z][A-Za-z0-9]*Test)(?:[ \t\r]|$)') | ForEach-Object {$_.Groups['name'].Value} | Sort-Object -Unique)
if($buildText -notmatch '(?m)^> Task :build(?:\s|$)' -or $buildText -notmatch '(?m)^> Task :compileSmokeJava(?:\s|$)' -or
    $buildText -notmatch 'BUILD SUCCESSFUL' -or $buildText -match '(?m)^> Task :[^\r\n]+ FAILED' -or $testTasks.Count -ne 77 -or
    (Get-Item -LiteralPath $buildLog).LastWriteTimeUtc -lt (Get-Item -LiteralPath $jar).LastWriteTimeUtc){throw 'A complete successful build plus compileSmokeJava with all 77 custom test tasks of this exact 0.8.5 JAR was not established'}
function VerifyNative([string]$evidence,[string]$NativeReport,[string]$ExpectedWorld,[string]$WorldMarker,[string]$ExpectedClip,[string]$SmokeSource,[int]$ExpectedCases,[string]$InventoryRegex,[string]$RestoreField){
$reviewPath=ChildPath $evidence $NativeReport
$gameEvidence=Split-Path -Parent $reviewPath
$review=ReadJson $reviewPath
if($review.passed -ne $true -or $review.modJarSha256 -cne $sha -or $review.monitorAdapter -cne '\\.\DISPLAY5' -or
    $review.copiedWorld -cne $ExpectedWorld -or $review.windowWidth -ne 1000 -or $review.windowHeight -ne 680 -or
    $review.$RestoreField -ne $true -or
    ($review.PSObject.Properties.Name -contains 'error' -and $null -ne $review.error)){throw 'Native review did not pass on the exact JAR, restored fixture, copied world and DISPLAY5'}
$centerX=(RequireFiniteValue $review.windowX 'windowX')+$review.windowWidth/2
$centerY=(RequireFiniteValue $review.windowY 'windowY')+$review.windowHeight/2
$monitorX=RequireFiniteValue $review.monitorBounds.x 'monitorBounds.x'
$monitorY=RequireFiniteValue $review.monitorBounds.y 'monitorBounds.y'
$monitorWidth=RequireFiniteValue $review.monitorBounds.width 'monitorBounds.width'
$monitorHeight=RequireFiniteValue $review.monitorBounds.height 'monitorBounds.height'
if($monitorWidth -le 0 -or $monitorHeight -le 0 -or $centerX -lt $monitorX -or $centerX -ge $monitorX+$monitorWidth -or
    $centerY -lt $monitorY -or $centerY -ge $monitorY+$monitorHeight){throw 'Actual native-window center is outside its reported DISPLAY5 monitor bounds'}
$copy=ChildPath $project ('run/client/saves/'+$review.copiedWorld)
if(-not (Test-Path -LiteralPath (ChildPath $copy $WorldMarker) -PathType Leaf)){throw 'The named copied test-world marker is missing'}
$smokeText=Get-Content -LiteralPath (ChildPath $project $SmokeSource) -Raw
$caseIds=@([regex]::Matches($smokeText,$InventoryRegex) | ForEach-Object {$_.Groups['id'].Value})
if($caseIds.Count -ne $ExpectedCases -or @($caseIds | Sort-Object -Unique).Count -ne $caseIds.Count -or @($review.scenarios).Count -ne $caseIds.Count){throw 'Native scenario inventory differs from its complete required smoke source'}
foreach($id in $caseIds){
    $rows=@($review.scenarios | Where-Object {$_.id -ceq $id})
    if($rows.Count -ne 1 -or $rows[0].passed -ne $true){throw "Native scenario is absent, duplicated or failed: $id"}
}
$nativeChecks=@($review.checks)
if($nativeChecks.Count -lt $caseIds.Count -or @($nativeChecks | Where-Object {$_ -isnot [string] -or [string]::IsNullOrWhiteSpace($_)}).Count){throw 'Native review does not contain its completed passing check inventory'}
$capturePath=ChildPath $gameEvidence 'render-capture.json'
$capture=ReadJson $capturePath
if($capture.captureEvent -cne 'RenderFrameEvent.Post' -or $capture.pendingWrites -ne 0 -or @($capture.clips).Count -ne 1){throw 'Native framebuffer capture was not completed'}
if($review.renderCapture.captureEvent -cne $capture.captureEvent -or $review.renderCapture.pendingWrites -ne 0 -or
    (ConvertTo-Json -InputObject $review.renderCapture.clips -Depth 100 -Compress) -cne
    (ConvertTo-Json -InputObject $capture.clips -Depth 100 -Compress)){throw 'Native report embeds a different frame capture from the encoded evidence'}
$clip=@($capture.clips)[0];$frames=@($clip.frames)
if($clip.id -cne $ExpectedClip -or $frames.Count -lt 31 -or $clip.capturedFrames -ne $frames.Count -or $clip.writtenFrames -ne $frames.Count -or
    $clip.sourceFps -lt 30 -or $clip.distinctSamplesWithinGameTick -lt 1 -or $clip.changedRigPairs -le 0){throw 'Native source does not establish genuine changing 30 Hz frames'}
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
$previousTime=-1.;$previousFrame=-1L;$previousRig=-1L;$submitted=0
for($i=0;$i -lt $frames.Count;$i++){
    $frame=$frames[$i];$pngRow=$pngValidation.frames[$i]
    $capturedAt=RequireFiniteValue $frame.capturedAtSeconds "frame $i capture time"
    if($frame.index -ne $i -or $capturedAt -le $previousTime -or $frame.renderFrame -le $previousFrame -or
        $frame.paused -ne $false -or $frame.levelRunningNormally -ne $true -or
        $frame.file -cne (('screenshots/render-frames/'+$ExpectedClip+'/frame-')+$i.ToString('D6')+'.png')){throw 'Native frames are duplicated, reordered, paused or misnamed'}
    if($i -gt 0 -and $capturedAt-$previousTime -gt .25){throw 'Native framebuffer capture contains an unverified stalled interval'}
    $pngPath=ChildPath $gameEvidence $frame.file
    AssertPng $pngPath
    if($pngRow.index -ne $i -or $pngRow.file -cne $frame.file -or $pngRow.verifiedPng -ne $true -or $pngRow.sha256 -cne (Hash $pngPath)){throw "Native PNG no longer matches its independently verified source: $i"}
    RequireNear $capturedAt $pngRow.capturedAtSeconds 'Independent PNG capture timestamp' .000000001
    if($frame.observation.PSObject.Properties.Name -contains 'submittedAvatar'){
        $avatar=$frame.observation.submittedAvatar
        if($avatar.nativePoseSnapshotStage -cne 'RenderLivingEvent.Post actual avatar submission' -or $avatar.nativePoseSnapshotRenderFrame -le $previousRig){throw 'A native rig was detached from its actual rendered player frame'}
        $previousRig=$avatar.nativePoseSnapshotRenderFrame;$submitted++
    }
    $previousTime=$capturedAt;$previousFrame=$frame.renderFrame
}
if($submitted -lt 31){throw 'Too few native actual-player rig submissions'}
# Review one or more original PNGs per scenario. An indexed sample must identify its exact recorded case;
# a separate Screenshot.grab PNG is allowed when that native scenario lies outside the movie interval.
$visualPath=ChildPath $gameEvidence 'visual-inspection.json'
$visual=ReadJson $visualPath
if($visual.passed -ne $true -or $visual.modJarSha256 -cne $sha -or $visual.nativeReportSha256 -cne (Hash $reviewPath) -or
    $visual.sourceCaptureSha256 -cne (Hash $capturePath) -or @($visual.samples).Count -lt $caseIds.Count){throw 'Original native-image visual review of the exact candidate and reports is missing'}
foreach($id in $caseIds){if(@($visual.samples | Where-Object {$_.id -ceq $id}).Count -lt 1){throw "No reviewed original native image for $id"}}
foreach($sample in $visual.samples){
    if($caseIds -notcontains $sample.id -or $sample.passed -ne $true -or [string]::IsNullOrWhiteSpace($sample.notes) -or
        $sample.file -notmatch '^screenshots/[a-zA-Z0-9_./-]+\.png$'){throw 'Visual sample lacks a passing scenario-specific inspection of an original native PNG'}
    $samplePath=ChildPath $gameEvidence $sample.file
    AssertPng $samplePath
    if($sample.sha256 -cne (Hash $samplePath)){throw 'Visual review original PNG changed after inspection'}
    if($sample.PSObject.Properties.Name -contains 'index'){
        $sampleIndex=RequireFiniteValue $sample.index 'visual sample index'
        if($sampleIndex -lt 0 -or $sampleIndex -ge $frames.Count -or $sampleIndex -ne [Math]::Truncate($sampleIndex)){throw 'Visual review sample index is outside the original frame inventory'}
        $frame=$frames[[int]$sampleIndex]
        if($sample.file -cne $frame.file -or $frame.observation.scenario -cne $sample.id){throw 'Visual review image differs from its actual native scenario/frame'}
    }elseif($sample.file.StartsWith('screenshots/render-frames/',[StringComparison]::Ordinal)){throw 'Recorded-frame visual samples must include their original frame index'}
}
return @{report=$reviewPath;reportSha256=(Hash $reviewPath);jarSha256=$review.modJarSha256;scenarios=$caseIds;scenarioCount=$caseIds.Count;
    captureSha256=(Hash $capturePath);encodedReviewSha256=(Hash (ChildPath $gameEvidence 'encoded-review.json'));visualReviewSha256=(Hash $visualPath);
    sourceFps=$clip.sourceFps;sourceFrames=$frames.Count;motionInterpolation=$false;encodingThreads=2}
}
$cameraReview=VerifyNative $evidence 'game/spear-orbit-review.json' 'FSRPG Spear Orbit Review 20261021 01' '.fsrpg-spear-orbit-test-world' 'spear-orbit' 'src/smoke/java/org/fsrpg/smoke/SpearOrbitReview.java' 10 'enter\(Stage\.[A-Z_]+,"(?<id>[a-z0-9-]+)"\)' 'clientConfigurationRestored'
$rapierReview=VerifyNative $rapierEvidence 'game/rapier-fencing-review.json' 'FSRPG Rapier Fencing Review 20261021 01' '.fsrpg-rapier-fencing-test-world' 'rapier-fencing' 'src/smoke/java/org/fsrpg/smoke/RapierFencingReview.java' 9 'new Scenario\("(?<id>[a-z0-9-]+)"\s*,' 'clientConfigurationRestored'
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
if(-not (IsFsrpg $jar) -or (ModToml $jar) -notmatch '(?m)^\s*version\s*=\s*"0\.8\.5"'){throw 'Candidate JAR metadata is not FSRPG 0.8.5'}
$sourceAssets=@{
    'legacy_dual_blade.fsvoxel'='7e4accde21d0d4b8a9249501e3df7a10b8a6e5c6e22bfb254ee5b1ec7017a2b0'
    'legacy_dual_blade_fp.fsvoxel'='ad74c113e8da9b741553690d4eb09218c723ed58ef3489892c69d58e88549f76'
}
$allAssetHashes=@{}
$archive=[IO.Compression.ZipFile]::OpenRead($jar)
try{
    if(@($archive.Entries | Where-Object {$_.FullName -match '(^org/fsrpg/smoke/)|MonitorAgent|forging|ArtContent|art/combat_arena'}).Count){throw 'Development code or excluded other-project materials are present in the release JAR'}
    if($null -ne $archive.GetEntry('org/fsrpg/mixin/BurstDashTravelMixin.class')){throw 'The old every-tick movement override is still packaged'}
    if(@($archive.Entries | Where-Object {$_.FullName -match '(^|/)SpearRunCarry[^/]*\.class$'}).Count){throw 'Removed SpearRunCarry code is still packaged'}
    foreach($name in @('org/fsrpg/client/AirInertia.class','org/fsrpg/client/SpearGrip.class','org/fsrpg/client/PlayerAnimations.class')){if($null -eq $archive.GetEntry($name)){throw "Required tested presentation layer is missing: $name"}}
    foreach($name in $sourceAssets.Keys){if((JarEntryHash $archive ('assets/fsrpg/fsvoxel/models/'+$name)) -cne $sourceAssets[$name]){throw "Original dual source asset changed: $name"}}
    foreach($entry in $archive.Entries){
        if($entry.FullName.StartsWith('assets/',[StringComparison]::Ordinal) -and -not $entry.FullName.EndsWith('/')){
            $allAssetHashes[$entry.FullName]=JarEntryHash $archive $entry.FullName
        }
    }
    if($allAssetHashes.Count -ne 148){throw 'Expected the same exact 148 asset names with only the two authorized rapier replacements'}
}finally{$archive.Dispose()}
$instance=(Resolve-Path -LiteralPath 'C:/Users/34003/AppData/Roaming/.minecraft/versions/1.21.11-NeoForge').Path
$versionText=Get-Content -LiteralPath (Join-Path $instance '1.21.11-NeoForge.json') -Raw
if(($versionText | ConvertFrom-Json).id -cne '1.21.11-NeoForge' -or -not $versionText.Contains('21.11.45')){throw 'Official Minecraft/NeoForge instance version changed'}
$latestLog=Get-Content -LiteralPath (Join-Path $instance 'logs/latest.log') -Raw
if(-not $latestLog.Contains("Game directory: $instance") -and -not $latestLog.Contains('Game directory: '+$instance.Replace('\','/'))){throw 'Actual official game directory was not confirmed by its log'}
function InstanceRunning{
    return [bool]@(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | Where-Object {$_.CommandLine -and
        ($_.CommandLine.IndexOf($instance,[StringComparison]::OrdinalIgnoreCase) -ge 0 -or $_.CommandLine.IndexOf($instance.Replace('\','/'),[StringComparison]::OrdinalIgnoreCase) -ge 0 -or $_.CommandLine -match '(?:^|\s)--version\s+"?1\.21\.11-NeoForge(?:"|\s|$)')}).Count
}
function AssertInstanceStopped{if(InstanceRunning){throw 'Official instance is running; no loaded JAR or configuration will be replaced. Exit it before deployment.'}}
$mods=(Resolve-Path -LiteralPath (Join-Path $instance 'mods')).Path
$configRoot=(Resolve-Path -LiteralPath (Join-Path $instance 'config')).Path
$config=(Resolve-Path -LiteralPath (Join-Path $configRoot 'fsrpg')).Path
AssertNoReparsePath $mods $instance
AssertNoReparsePath $config $instance
AssertNoReparsePath $stage $project
AssertNoReparsePath $stageReport $project
AssertNoReparsePath $assetManifest $project
$configurationBefore=TreeHashes $config
$allConfigBefore=TreeHashes $configRoot
$dependency=Join-Path $mods 'fsvoxel-renderer-neoforge-0.6.0.jar'
if((Hash $dependency) -cne (Hash (Join-Path $project 'libs/fsvoxel-renderer-neoforge-0.6.0.jar'))){throw 'Official required FSVOXEL 0.6.0 dependency differs from the tested dependency'}
$old=@(Get-ChildItem -LiteralPath $mods -File -Filter '*.jar' | Where-Object {IsFsrpg $_.FullName})
if($old.Count -ne 1 -or (ModToml $old[0].FullName) -notmatch '(?m)^\s*version\s*=\s*"0\.8\.4"'){throw 'Expected one installed FSRPG 0.8.4 JAR for this replacement'}
if((Hash $old[0].FullName) -cne 'ecee3a5904b0801d960406549af4b86395cbbbec8d16a550b29d711dc0a52de4'){throw 'Installed 0.8.4 JAR differs from the previously tested/deployed release'}
$helperOutput=& $Python -B (Join-Path $PSScriptRoot 'verify085.py') --config $config --stage $stage --report $stageReport --jar $jar --asset-manifest $assetManifest --previous-jar $old[0].FullName
if($LASTEXITCODE -ne 0){throw 'Independent protocol/configuration field and whole-tree verification failed'}
$stageVerification=($helperOutput -join "`n") | ConvertFrom-Json
if($stageVerification.passed -ne $true -or $stageVerification.jarSha256 -cne $sha -or $stageVerification.networkProtocol -ne 11){throw 'Independent stage verification did not bind the exact JAR'}
$expectedConfiguration=@{};foreach($entry in $stageVerification.afterSha256.PSObject.Properties){$expectedConfiguration[$entry.Name]=[string]$entry.Value}
$configurationWrites=@($stageVerification.configurationFilesToWrite)
if($configurationWrites.Count -gt 12 -or @($stageVerification.addedFiles).Count -lt 5 -or @($stageVerification.addedFiles).Count -gt 7){throw 'Unexpected configuration mutation inventory'}
$expectedAllConfig=@{};foreach($entry in $allConfigBefore.GetEnumerator()){$expectedAllConfig[$entry.Key]=$entry.Value}
foreach($entry in $expectedConfiguration.GetEnumerator()){$expectedAllConfig['fsrpg/'+$entry.Key]=$entry.Value}
$authorizedAssetChanges=@{};foreach($entry in $stageVerification.assetUpgrade){$authorizedAssetChanges[$entry.entry]=$entry}
$oldArchive=[IO.Compression.ZipFile]::OpenRead($old[0].FullName)
try{
    $oldAssets=@($oldArchive.Entries | Where-Object {$_.FullName.StartsWith('assets/',[StringComparison]::Ordinal) -and -not $_.FullName.EndsWith('/')})
    if($oldAssets.Count -ne $allAssetHashes.Count){throw 'Original asset inventory changed'}
    foreach($entry in $oldAssets){
        if(-not $allAssetHashes.ContainsKey($entry.FullName)){throw "Original asset was removed: $($entry.FullName)"}
        $oldHash=JarEntryHash $oldArchive $entry.FullName
        if($authorizedAssetChanges.ContainsKey($entry.FullName)){
            $authorized=$authorizedAssetChanges[$entry.FullName]
            if($oldHash -cne $authorized.beforeSha256 -or $allAssetHashes[$entry.FullName] -cne $authorized.afterSha256){throw "Authorized rapier replacement differs from its before/after manifest: $($entry.FullName)"}
        }elseif($oldHash -cne $allAssetHashes[$entry.FullName]){throw "Asset outside the two authorized rapier replacements changed: $($entry.FullName)"}
    }
}finally{$oldArchive.Dispose()}
$installed=ChildPath $mods 'fsrpg-neoforge-0.8.5.jar'
if(Test-Path -LiteralPath $installed){throw 'The 0.8.5 destination already exists; no unknown file will be overwritten'}
$modsBefore=TreeHashes $mods
$expectedMods=@{};foreach($entry in $modsBefore.GetEnumerator()){$expectedMods[$entry.Key]=$entry.Value}
[void]$expectedMods.Remove($old[0].Name);$expectedMods['fsrpg-neoforge-0.8.5.jar']=$sha
$preserved=@()
foreach($name in @('options.txt','config/fsrpg-client.toml')){$path=ChildPath $instance $name;if(Test-Path -LiteralPath $path){$preserved+=@{path=$path;sha256=(Hash $path)}}}
$saves=ChildPath $instance 'saves';$worlds=if(Test-Path -LiteralPath $saves -PathType Container){TreeHashes $saves}else{@{}}
$running=InstanceRunning
if($VerifyOnly){Write-Output "VERIFY ONLY PASSED: exact candidate $sha; protocol 11; 77 custom test tasks plus compileSmokeJava; spear orbit 10 and rapier fencing 9 exact-JAR DISPLAY5 scenes; all original PNG hashes and native >=30 Hz timestamps; two-thread 30 FPS videos without interpolation; exact 148 asset names, 146 byte-exact preserved assets and two manifest-bound rapier replacements; independently checked allowed configuration migration ($($configurationWrites.Count) files, all $($configurationBefore.Count) original preimages matched); other mods/TOML/options/world hashes retained. Official instance running=$running. No files were written; deployment requires it stopped.";return}
AssertInstanceStopped
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$backup=ChildPath $instance "fsrpg-backups/$stamp-before-0.8.5"
AssertNoReparsePath $backup $instance
if(Test-Path -LiteralPath $backup){throw 'Dated backup already exists'}
New-Item -ItemType Directory -Path (Join-Path $backup 'mods') -Force | Out-Null
Copy-Item -LiteralPath $configRoot -Destination (Join-Path $backup 'config') -Recurse -Force
SameTree $allConfigBefore (Join-Path $backup 'config') 'Full configuration backup'
if(Test-Path -LiteralPath (Join-Path $instance 'options.txt')){Copy-Item -LiteralPath (Join-Path $instance 'options.txt') -Destination (Join-Path $backup 'options.txt')}
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
SameTree $expectedConfiguration $stage 'Verified isolated configuration immediately before deployment'
if((Hash $jar) -cne $sha -or (Hash $stageReport) -cne $stageVerification.stageReportSha256 -or (Hash $assetManifest) -cne $stageVerification.assetManifestSha256){throw 'Candidate JAR, staged report or authorized asset manifest changed after verification'}
$removed=@();$jarWrite=@{committed=$false};$writtenConfigs=@()
try{
    foreach($file in $previous){
        if((Hash $file.path) -cne $file.sha256){throw 'Previous FSRPG JAR changed immediately before replacement'}
        Remove-Item -LiteralPath $file.path
        $removed+=,$file
    }
    WriteAtomicVerified $jar $installed $sha $null $jarWrite
    if((Hash $installed) -cne $sha){throw 'Installed JAR is not the exact tested candidate'}
    foreach($relative in $configurationWrites){
        $source=ChildPath $stage $relative;$destination=ChildPath $config $relative
        AssertNoReparsePath $destination $config
        if((Hash $source) -cne $expectedConfiguration[$relative]){throw "Staged source changed immediately before copy: $relative"}
        $existed=$configurationBefore.ContainsKey($relative)
        if($existed){if((Hash $destination) -cne $configurationBefore[$relative]){throw "Concurrent custom edit: $relative"}}
        elseif(Test-Path -LiteralPath $destination){throw "Concurrent file occupies new migration artifact: $relative"}
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
        $writeRecord=@{relative=$relative;path=$destination;existed=$existed;sha256=$expectedConfiguration[$relative];committed=$false;preimage=$null;preimageSha256=$null}
        $writtenConfigs+=,$writeRecord
        $previousSha=if($existed){$configurationBefore[$relative]}else{$null}
        WriteAtomicVerified $source $destination $expectedConfiguration[$relative] $previousSha $writeRecord
        if((Hash $destination) -cne $expectedConfiguration[$relative]){throw "Installed staged configuration SHA mismatch: $relative"}
    }
    CleanupAtomicArtifacts
    SameTree $expectedMods $mods 'Only this tested FSRPG JAR was replaced'
    SameTree $expectedAllConfig $configRoot 'Only allowed FSRPG configuration migration fields/artifacts changed'
    SameTree $expectedConfiguration $config 'Every FSRPG configuration file matches the validated candidate'
    foreach($file in $preserved){if((Hash $file.path) -cne $file.sha256){throw "Preserved file changed: $($file.path)"}}
    if(Test-Path -LiteralPath $saves -PathType Container){SameTree $worlds $saves 'Unchanged user worlds'}elseif($worlds.Count -gt 0){throw 'User worlds disappeared'}
    $record=[ordered]@{version='0.8.5';networkProtocol=11;installedJar=$installed;jarSha256=$sha;testedJarSha256=$cameraReview.jarSha256;backup=$backup;
        previousMods=$previous;configurationBeforeSha256=$configurationBefore;configurationAfterSha256=$expectedConfiguration;configurationFileCount=$configurationBefore.Count;
        allConfigurationBeforeSha256=$allConfigBefore;allConfigurationAfterSha256=$expectedAllConfig;configurationFilesWritten=$configurationWrites;configurationMigration=$true;
        migrationFieldAllowlist='Only the stock four-stage rapier chain with both compatible stock models gains the fifth fencing skill; unchanged baseline label/timing fields update, custom fields/chains/actions/models remain; root unlock list/description update only from baseline values; two stock matching rapier models upgrade from reviewed before to after';
        otherExistingConfigurationByteExactlyPreserved=$true;clientTomlByteExactlyPreserved=$true;stageReport=$stageReport;stageReportSha256=$stageVerification.stageReportSha256;
        dualSourceAssets=$sourceAssets;allAssetsByteExactlyPreserved=$false;otherAssetsByteExactlyPreserved=$true;unchangedAssetFileCount=146;authorizedRapierAssetChanges=$stageVerification.assetUpgrade;assetManifest=$assetManifest;assetManifestSha256=$stageVerification.assetManifestSha256;modelUpgrade=$stageVerification.modelUpgrade;customModelsPreserved=$true;assetFileCount=$allAssetHashes.Count;assetSha256=$allAssetHashes;
        nativeReviews=@($cameraReview,$rapierReview);nativeScenarioCount=($cameraReview.scenarioCount+$rapierReview.scenarioCount);nativeMotionInterpolation=$false;encodingThreads=2;
        customTestTasks=$testTasks;buildLogSha256=(Hash $buildLog);worldsModified=$false;worldFileCount=$worlds.Count;otherModsPreserved=$true;
        modsBeforeSha256=$modsBefore;modsAfterSha256=$expectedMods;preserved=$preserved;evidence=$evidence;rapierEvidence=$rapierEvidence;deployedAt=(Get-Date).ToString('o')}
    $record | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $backup 'deployment.json') -Encoding utf8
    $record | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $evidence 'deployment.json') -Encoding utf8
}catch{
    $originalFailure=$_;$rollbackErrors=@()
    for($i=$writtenConfigs.Count-1;$i -ge 0;$i--){$file=$writtenConfigs[$i];try{
        if(-not $file.committed){continue}
        if($file.existed){
            $original=if($null -ne $file.preimage){$file.preimage}else{ChildPath (Join-Path $backup 'config/fsrpg') $file.relative}
            $restoreSha=if($null -ne $file.preimage){$file.preimageSha256}else{$configurationBefore[$file.relative]}
            if($null -eq $restoreSha -or (Hash $original) -cne $restoreSha){throw "Atomic rollback preimage changed: $($file.relative)"}
            $current=if(Test-Path -LiteralPath $file.path){Hash $file.path}else{$null}
            if($current -cne $restoreSha){
                if($null -ne $current -and $current -cne $file.sha256){throw "Concurrent file blocks configuration rollback: $($file.relative)"}
                $rollbackWrite=@{committed=$false}
                WriteAtomicVerified $original $file.path $restoreSha $current $rollbackWrite
            }
            if((Hash $file.path) -cne $restoreSha){throw 'Configuration rollback SHA mismatch'}
            if($null -ne $file.preimageArtifact){$file.preimageArtifact.retain=$false}
        }elseif(Test-Path -LiteralPath $file.path){
            if((Hash $file.path) -cne $file.sha256){throw "Concurrent file blocks new-artifact rollback: $($file.relative)"}
            Remove-Item -LiteralPath $file.path
        }
    }catch{$rollbackErrors+=$_.ToString()}}
    try{if($jarWrite.committed -and (Test-Path -LiteralPath $installed)){if((Hash $installed) -cne $sha){throw 'Unknown candidate contents block rollback'};Remove-Item -LiteralPath $installed}}catch{$rollbackErrors+=$_.ToString()}
    foreach($file in $removed){try{
        if(Test-Path -LiteralPath $file.path){if((Hash $file.path) -cne $file.sha256){throw "Concurrent file occupies rollback destination: $($file.path)"}}
        else{$rollbackJarWrite=@{committed=$false};WriteAtomicVerified $file.backup $file.path $file.sha256 $null $rollbackJarWrite}
        if((Hash $file.path) -cne $file.sha256){throw 'Rolled-back JAR mismatch'}
    }catch{$rollbackErrors+=$_.ToString()}}
    try{CleanupAtomicArtifacts}catch{$rollbackErrors+=$_.ToString()}
    try{SameTree $allConfigBefore $configRoot 'Configuration after failed deployment rollback'}catch{$rollbackErrors+=$_.ToString()}
    try{SameTree $modsBefore $mods 'Mod inventory after rollback'}catch{$rollbackErrors+=$_.ToString()}
    @{deploymentFailure=$originalFailure.ToString();rollbackErrors=$rollbackErrors;backup=$backup;configurationFilesWritten=$writtenConfigs;worldsModified=$false} |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $backup 'rollback.json') -Encoding utf8
    if($rollbackErrors.Count -gt 0){throw "Deployment failed: $originalFailure; rollback needs attention: $($rollbackErrors -join ' | '); backup: $backup"}
    throw $originalFailure
}
Write-Output "Installed $installed; SHA-256 $sha; exact tested/deployed hashes match; $($configurationWrites.Count) independently allowlisted migration files copied with original-byte backups; every other configuration/TOML, other mod and world preserved; backup $backup"
