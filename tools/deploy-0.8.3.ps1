param(
    [switch]$VerifyOnly,
    [string]$EvidenceDirectory='verification/weapon-clash-20261021',
    [string]$NativeReport='game/weapon-clash-review.json'
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$project=(Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$evidence=if([IO.Path]::IsPathRooted($EvidenceDirectory)){[IO.Path]::GetFullPath($EvidenceDirectory)}else{[IO.Path]::GetFullPath((Join-Path $project $EvidenceDirectory))}
$jar=Join-Path $project 'build/libs/fsrpg-neoforge-0.8.3.jar'
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
$reviewPath=ChildPath $evidence $NativeReport
$gameEvidence=Split-Path -Parent $reviewPath
$review=ReadJson $reviewPath
$buildLog=ChildPath $evidence 'build.log'
$buildText=Get-Content -LiteralPath $buildLog -Raw
$testTasks=@([regex]::Matches($buildText,'(?m)^> Task :(?<name>[A-Za-z][A-Za-z0-9]*Test)(?:[ \t\r]|$)') | ForEach-Object {$_.Groups['name'].Value} | Sort-Object -Unique)
if($buildText -notmatch '(?m)^> Task :build(?:\s|$)' -or $buildText -notmatch '(?m)^> Task :compileSmokeJava(?:\s|$)' -or
    $buildText -notmatch 'BUILD SUCCESSFUL' -or $buildText -match '(?m)^> Task :[^\r\n]+ FAILED' -or $testTasks.Count -ne 71 -or
    (Get-Item -LiteralPath $buildLog).LastWriteTimeUtc -lt (Get-Item -LiteralPath $jar).LastWriteTimeUtc){throw 'A complete successful build and compileSmokeJava with exactly 71 custom test tasks of this exact 0.8.3 JAR was not established'}
if($review.passed -ne $true -or $review.modJarSha256 -cne $sha -or $review.monitorAdapter -cne '\\.\DISPLAY5' -or
    $review.copiedWorld -cne 'FSRPG Weapon Clash Review 20261021 01' -or $review.windowWidth -ne 1000 -or $review.windowHeight -ne 680 -or
    $review.fixtureSkillConfigRestored -ne $true -or
    ($review.PSObject.Properties.Name -contains 'error' -and $null -ne $review.error)){throw 'Native weapon-clash report did not pass on the exact JAR, restored fixture, copied world and DISPLAY5'}
$centerX=(RequireFiniteValue $review.windowX 'windowX')+$review.windowWidth/2
$centerY=(RequireFiniteValue $review.windowY 'windowY')+$review.windowHeight/2
$monitorX=RequireFiniteValue $review.monitorBounds.x 'monitorBounds.x'
$monitorY=RequireFiniteValue $review.monitorBounds.y 'monitorBounds.y'
$monitorWidth=RequireFiniteValue $review.monitorBounds.width 'monitorBounds.width'
$monitorHeight=RequireFiniteValue $review.monitorBounds.height 'monitorBounds.height'
if($monitorWidth -le 0 -or $monitorHeight -le 0 -or $centerX -lt $monitorX -or $centerX -ge $monitorX+$monitorWidth -or
    $centerY -lt $monitorY -or $centerY -ge $monitorY+$monitorHeight){throw 'Actual native-window center is outside its reported DISPLAY5 monitor bounds'}
$copy=ChildPath $project ('run/client/saves/'+$review.copiedWorld)
if(-not (Test-Path -LiteralPath (ChildPath $copy '.fsrpg-weapon-clash-test-world') -PathType Leaf)){throw 'The named copied weapon-clash test-world marker is missing'}
$smokeSource=ChildPath $project 'src/smoke/java/org/fsrpg/smoke/WeaponClashReview.java'
$smokeText=Get-Content -LiteralPath $smokeSource -Raw
$caseIds=@([regex]::Matches($smokeText,'new Scenario\("(?<id>[a-z0-9-]+)"\s*,') | ForEach-Object {$_.Groups['id'].Value})
if($caseIds.Count -lt 15 -or @($caseIds | Sort-Object -Unique).Count -ne $caseIds.Count -or @($review.scenarios).Count -ne $caseIds.Count){throw 'Native scenario inventory differs from the required smoke source (at least fifteen unique scenarios)'}
foreach($id in $caseIds){
    $rows=@($review.scenarios | Where-Object {$_.id -ceq $id})
    if($rows.Count -ne 1 -or $rows[0].passed -ne $true){throw "Native scenario is absent, duplicated or failed: $id"}
}
$nativeChecks=@($review.checks)
if($nativeChecks.Count -lt $caseIds.Count -or @($nativeChecks | Where-Object {$_ -isnot [string] -or [string]::IsNullOrWhiteSpace($_)}).Count){throw 'Native review does not contain its completed passing check inventory'}
$dual=@($review.scenarios | Where-Object {$_.id -ceq 'dual-two-left-return-before-third'})
if($dual.Count -ne 1 -or $dual[0].thirdContinuousInputRejected -ne $true -or $dual[0].newLeftAcceptedAfterNaturalReturn -ne $true -or
    $dual[0].actualAcceptedBladeCount -ne 3){throw 'Actual dual third-input rejection and later natural hand return were not established'}

$capturePath=ChildPath $gameEvidence 'render-capture.json'
$capture=ReadJson $capturePath
if($capture.captureEvent -cne 'RenderFrameEvent.Post' -or $capture.pendingWrites -ne 0 -or @($capture.clips).Count -ne 1){throw 'Native framebuffer capture was not completed'}
if($review.renderCapture.captureEvent -cne $capture.captureEvent -or $review.renderCapture.pendingWrites -ne 0 -or
    (ConvertTo-Json -InputObject $review.renderCapture.clips -Depth 100 -Compress) -cne
    (ConvertTo-Json -InputObject $capture.clips -Depth 100 -Compress)){throw 'Native report embeds a different frame capture from the encoded evidence'}
$clip=@($capture.clips)[0];$frames=@($clip.frames)
if($clip.id -cne 'weapon-clashes' -or $frames.Count -lt 31 -or $clip.capturedFrames -ne $frames.Count -or $clip.writtenFrames -ne $frames.Count -or
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
        $frame.file -cne ('screenshots/render-frames/weapon-clashes/frame-'+$i.ToString('D6')+'.png')){throw 'Native frames are duplicated, reordered, paused or misnamed'}
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
if(-not (IsFsrpg $jar) -or (ModToml $jar) -notmatch '(?m)^\s*version\s*=\s*"0\.8\.3"'){throw 'Candidate JAR metadata is not FSRPG 0.8.3'}
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
    if($allAssetHashes.Count -ne 148){throw 'Expected exactly 148 unchanged original asset files'}
    $protocolClassSha=JarEntryHash $archive 'org/fsrpg/FsRpg.class'
}finally{$archive.Dispose()}
$instance=(Resolve-Path -LiteralPath 'C:/Users/34003/AppData/Roaming/.minecraft/versions/1.21.11-NeoForge').Path
$versionText=Get-Content -LiteralPath (Join-Path $instance '1.21.11-NeoForge.json') -Raw
if(($versionText | ConvertFrom-Json).id -cne '1.21.11-NeoForge' -or -not $versionText.Contains('21.11.45')){throw 'Official Minecraft/NeoForge instance version changed'}
$latestLog=Get-Content -LiteralPath (Join-Path $instance 'logs/latest.log') -Raw
if(-not $latestLog.Contains("Game directory: $instance") -and -not $latestLog.Contains('Game directory: '+$instance.Replace('\','/'))){throw 'Actual official game directory was not confirmed by its log'}
function AssertInstanceStopped{
    if(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | Where-Object {$_.CommandLine -and
        ($_.CommandLine.IndexOf($instance,[StringComparison]::OrdinalIgnoreCase) -ge 0 -or $_.CommandLine.IndexOf($instance.Replace('\','/'),[StringComparison]::OrdinalIgnoreCase) -ge 0 -or $_.CommandLine -match '(?:^|\s)--version\s+"?1\.21\.11-NeoForge(?:"|\s|$)')}){
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
if($configurationBefore.Count -lt 54){throw 'Expected the preserved 0.8.2 configuration inventory (at least 54 existing files); this release performs no migration'}
$dependency=Join-Path $mods 'fsvoxel-renderer-neoforge-0.6.0.jar'
if((Hash $dependency) -cne (Hash (Join-Path $project 'libs/fsvoxel-renderer-neoforge-0.6.0.jar'))){throw 'Official required dependency differs from the tested dependency'}
$old=@(Get-ChildItem -LiteralPath $mods -File -Filter '*.jar' | Where-Object {IsFsrpg $_.FullName})
if($old.Count -ne 1 -or (ModToml $old[0].FullName) -notmatch '(?m)^\s*version\s*=\s*"0\.8\.2"'){throw 'Expected one installed FSRPG 0.8.2 JAR for this replacement'}
if((Hash $old[0].FullName) -cne '71f4551ec6e910f2c60b853e31bc739cf90b55e0344dc5258f82b5ac0440c98c'){throw 'Installed 0.8.2 JAR differs from the previously tested/deployed release'}
$oldArchive=[IO.Compression.ZipFile]::OpenRead($old[0].FullName)
try{
    if((JarEntryHash $oldArchive 'org/fsrpg/FsRpg.class') -cne $protocolClassSha){throw 'The protocol-10 registration class changed in this protocol-compatible release'}
    $oldAssets=@($oldArchive.Entries | Where-Object {$_.FullName.StartsWith('assets/',[StringComparison]::Ordinal) -and -not $_.FullName.EndsWith('/')})
    if($oldAssets.Count -ne $allAssetHashes.Count){throw 'Original asset inventory changed'}
    foreach($entry in $oldAssets){
        if(-not $allAssetHashes.ContainsKey($entry.FullName) -or (JarEntryHash $oldArchive $entry.FullName) -cne $allAssetHashes[$entry.FullName]){throw "Original asset changed or was removed: $($entry.FullName)"}
    }
}finally{$oldArchive.Dispose()}
$installed=ChildPath $mods 'fsrpg-neoforge-0.8.3.jar'
if(Test-Path -LiteralPath $installed){throw 'The 0.8.3 destination already exists; no unknown file will be overwritten'}
$modsBefore=TreeHashes $mods
$expectedMods=@{};foreach($entry in $modsBefore.GetEnumerator()){$expectedMods[$entry.Key]=$entry.Value}
[void]$expectedMods.Remove($old[0].Name);$expectedMods['fsrpg-neoforge-0.8.3.jar']=$sha
$preserved=@()
foreach($name in @('options.txt','config/fsrpg-client.toml')){$path=ChildPath $instance $name;if(Test-Path -LiteralPath $path){$preserved+=@{path=$path;sha256=(Hash $path)}}}
$saves=ChildPath $instance 'saves';$worlds=if(Test-Path -LiteralPath $saves -PathType Container){TreeHashes $saves}else{@{}}
if($VerifyOnly){Write-Output "VERIFY ONLY PASSED: exact candidate $sha; 71 custom test tasks plus compileSmokeJava; $($caseIds.Count) DISPLAY5 weapon-clash/PvP/guard/dual scenes; all original PNGs and true timestamps; two-thread 30 FPS movie without interpolation; all $($configurationBefore.Count) FSRPG config files and full config tree will remain byte-exact; dependency hashes; official instance stopped. No files were written.";return}
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$backup=ChildPath $instance "fsrpg-backups/$stamp-before-0.8.3"
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
    $record=[ordered]@{version='0.8.3';networkProtocol=10;installedJar=$installed;jarSha256=$sha;testedJarSha256=$review.modJarSha256;backup=$backup;
        previousMods=$previous;configurationSha256=$configurationBefore;configurationFileCount=$configurationBefore.Count;allConfigurationSha256=$allConfigBefore;
        configurationFilesWritten=0;configurationMigration=$false;existingConfigurationByteExactlyPreserved=$true;clientTomlByteExactlyPreserved=$true;
        weaponClashCompatibility='Existing combat.json is not edited; absent weaponClash uses 2 ticks / 3 damage in memory';
        dualSourceAssets=$sourceAssets;allAssetsByteExactlyPreserved=$true;assetFileCount=$allAssetHashes.Count;assetSha256=$allAssetHashes;nativeReview=$reviewPath;nativeReviewSha256=(Hash $reviewPath);nativeScenarioCount=$caseIds.Count;
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
