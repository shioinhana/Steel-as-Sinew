param(
    [switch]$VerifyOnly,
    [string]$EvidenceDirectory='verification/dual-source-20261018'
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$project=(Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$evidence=if([IO.Path]::IsPathRooted($EvidenceDirectory)){[IO.Path]::GetFullPath($EvidenceDirectory)}else{[IO.Path]::GetFullPath((Join-Path $project $EvidenceDirectory))}
$jar=Join-Path $project 'build/libs/fsrpg-neoforge-0.7.6.jar'
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
function SameTree([hashtable]$expected,[string]$directory,[string]$description){
    $actual=TreeHashes $directory
    if($actual.Count -ne $expected.Count){throw "$description file inventory changed"}
    foreach($entry in $expected.GetEnumerator()){
        if(-not $actual.ContainsKey($entry.Key) -or $actual[$entry.Key] -cne $entry.Value){throw "$description hash mismatch: $($entry.Key)"}
    }
}
function RequireFiniteAtMost([double]$actual,[double]$limit,[string]$description){
    if([double]::IsNaN($actual) -or [double]::IsInfinity($actual) -or $actual -lt 0 -or $actual -gt $limit){throw "$description exceeds $limit : $actual"}
}
$sha=Hash $jar
$gameEvidence=Join-Path $evidence 'game'
$review=ReadJson (Join-Path $gameEvidence 'dual-source-review.json')
if($review.passed -ne $true -or $review.modJarSha256 -cne $sha -or $review.monitorAdapter -cne '\\.\DISPLAY5' -or
    $review.copiedWorld -cne 'FSRPG Dual Source Review 20261018 01' -or $review.windowWidth -ne 1000 -or $review.windowHeight -ne 680){
    throw 'The original dual animation review did not pass on the exact candidate JAR on DISPLAY5'
}
if((Get-Content -LiteralPath (Join-Path $evidence 'build.log') -Raw) -notmatch 'BUILD SUCCESSFUL'){throw 'The required complete 0.7.6 build did not pass'}
$source=$review.sourceComparison
if($source.frames -le 100 -or $source.boneMatrices -le 1000 -or $source.sourceActionChangesCompared -lt 10 -or
    $source.allTransitionFramesIncluded -ne $true -or $source.proceduralLowerFrames -ne 0 -or $source.movingFrames -le 30){
    throw 'Native original-source comparisons omitted actions, transitions or moving lower body'
}
RequireFiniteAtMost $source.maxWorldMatrixError .0001 'Original-source world FK error'
RequireFiniteAtMost $source.maxLinearClockErrorSeconds .00000001 'Original linear clip clock error'
foreach($gait in @('walk_hold','run_hold')){
    if(@($source.sourceGaits.PSObject.Properties | Where-Object {$_.Name.Contains($gait) -and $_.Value -gt 0}).Count -lt 1){throw "The original $gait gait was never actually submitted"}
}
foreach($action in @('legacy_dual_blade_attack_1_upper','legacy_dual_blade_attack2_upper','legacy_dual_blade_attack3_upper',
    'legacy_dual_blade_attack4_upper','legacy_dual_blade_attack_dual_upper','dual_guard')){
    if($source.sourceActions.PSObject.Properties.Name -cnotcontains $action -or $source.sourceActions.$action -lt 1){throw "The original source action was never actually submitted: $action"}
}
if($review.officialMainSubmissionsDuringCapture -lt 1 -or $review.officialOffhandSubmissionsDuringCapture -lt 1){throw 'Both real official ItemStack submissions are required'}
$expected=[ordered]@{
    idle=@();walk=@();run=@();stop=@();'left-four'=@('dual_ll','dual_rl','dual_ll','dual_rl')
    'right-four'=@('dual_rr','dual_lr','dual_rr','dual_lr');combined=@('dual_combined');'moving-attack'=@('dual_ll','dual_rl');guard=@()
}
if(@($review.scenarios).Count -ne $expected.Count){throw 'The nine original-motion native scenarios are required'}
foreach($id in $expected.Keys){
    $rows=@($review.scenarios | Where-Object {$_.id -ceq $id})
    if($rows.Count -ne 1 -or $rows[0].passed -ne $true){throw "Missing or failed original-motion native scenario: $id"}
    $row=$rows[0];$wanted=@($expected[$id]);$skills=@($row.skills)
    if($row.actualBladeCount -ne $wanted.Count -or $row.expectedBladeCount -ne $wanted.Count -or $skills.Count -ne $wanted.Count){throw "Wrong actual input/blade count: $id"}
    $starts=@($row.actualStarts)
    if($starts.Count -ne $wanted.Count){throw "Native actual blade starts are missing: $id"}
    for($i=0;$i -lt $wanted.Count;$i++){
        if($skills[$i] -cne $wanted[$i] -or $starts[$i].skillId -cne $wanted[$i]){throw "Retained dual hand order changed: $id"}
        RequireFiniteAtMost ([Math]::Abs(50-8*($i+1)-$starts[$i].stamina)) .003 "Actual retained per-blade stamina: $id"
        if($i -gt 0 -and ($starts[$i].swingSequence -ne $starts[$i-1].swingSequence+1 -or $starts[$i].worldGameTick -le $starts[$i-1].worldGameTick)){throw "Duplicate or reordered native blade start: $id"}
    }
    if($wanted.Count -gt 0 -and $row.staminaCostPerBlade -ne 8){throw "Configured dual stamina cost changed: $id"}
}
$capture=ReadJson (Join-Path $gameEvidence 'render-capture.json')
if($capture.captureEvent -cne 'RenderFrameEvent.Post' -or $capture.pendingWrites -ne 0 -or @($capture.clips).Count -ne 1){throw 'Native framebuffer recording was not completed'}
$clip=@($capture.clips)[0];$frames=@($clip.frames)
if($clip.id -cne 'dual-original-motion' -or $frames.Count -lt 30 -or $clip.capturedFrames -ne $frames.Count -or
    $clip.writtenFrames -ne $frames.Count -or $clip.sourceFps -lt 30 -or $clip.distinctSamplesWithinGameTick -lt 1 -or $clip.changedRigPairs -lt 1){
    throw 'Recording does not establish genuine native 30 Hz changing rig presentation'
}
$duration=$frames[-1].capturedAtSeconds-$frames[0].capturedAtSeconds
if($duration -le 0 -or ($frames.Count-1)/$duration -lt 30 -or [Math]::Abs(($frames.Count-1)/$duration-$clip.sourceFps) -gt .003){throw 'Raw native source frame timestamps do not establish 30 Hz'}
$previousTime=-1.;$previousFrame=-1L;$previousRigFrame=-1L
for($i=0;$i -lt $frames.Count;$i++){
    $frame=$frames[$i]
    if($frame.index -ne $i -or $frame.capturedAtSeconds -le $previousTime -or $frame.renderFrame -le $previousFrame -or
        $frame.paused -ne $false -or $frame.levelRunningNormally -ne $true -or
        $frame.file -cne ('screenshots/render-frames/dual-original-motion/frame-'+$i.ToString('D6')+'.png')){throw 'Native source frames are reordered, duplicated, paused or misnamed'}
    $image=ChildPath $gameEvidence $frame.file
    if(-not (Test-Path -LiteralPath $image -PathType Leaf) -or (Get-Item -LiteralPath $image).Length -le 0){throw 'A native framebuffer PNG is missing'}
    if($frame.observation.PSObject.Properties.Name -notcontains 'submittedAvatar' -or $frame.observation.PSObject.Properties.Name -notcontains 'rig' -or
        $frame.observation.submittedAvatar.nativePoseSnapshotStage -cne 'RenderLivingEvent.Post actual avatar submission' -or
        $frame.observation.submittedAvatar.nativePoseSnapshotRenderFrame -le $previousRigFrame -or @($frame.observation.rig.PSObject.Properties).Count -lt 1){
        throw 'A recorded source rig was not submitted by the actual native player in the current rendered frame'
    }
    $comparison=$frame.observation.sourceComparison
    if($frame.observation.actualCameraType -cne 'THIRD_PERSON_BACK' -or $comparison.allTransitionFramesIncluded -ne $true -or
        $comparison.comparedBones -lt 18 -or $comparison.nativeRenderFrame -lt 1){throw 'A native source frame omitted source joints, transitions or the real player camera'}
    RequireFiniteAtMost $comparison.worldMatrixError .0001 'Per-frame original world FK error'
    RequireFiniteAtMost $comparison.linearClockErrorSeconds .00000001 'Per-frame original linear clock error'
    $previousRigFrame=$frame.observation.submittedAvatar.nativePoseSnapshotRenderFrame
    $previousTime=$frame.capturedAtSeconds;$previousFrame=$frame.renderFrame
}
$encoded=ReadJson (Join-Path $gameEvidence 'encoded-review.json')
if($encoded.passed -ne $true -or $encoded.originalCaptureUnchanged -ne $true -or $encoded.motionInterpolation -ne $false -or
    $encoded.sourceCaptureSha256 -cne (Hash (Join-Path $gameEvidence 'render-capture.json')) -or $encoded.clipId -cne $clip.id -or
    $encoded.capturedFrames -ne $frames.Count -or $encoded.outputFps -ne 30 -or $encoded.independentPngValidation.passed -ne $true -or
    $encoded.independentPngValidation.verifiedPngFiles -ne $frames.Count -or
    $encoded.outputSha256 -cne (Hash (ChildPath $gameEvidence $encoded.output)) -or
    $encoded.independentPngValidation.reportSha256 -cne (Hash (ChildPath $gameEvidence $encoded.independentPngValidation.report))){
    throw 'Actual-frame review video or independently verified source PNG inventory is incomplete'
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
function ModToml([string]$path){
    $archive=[IO.Compression.ZipFile]::OpenRead($path)
    try{$entry=$archive.GetEntry('META-INF/neoforge.mods.toml');if($null -eq $entry){return ''}
        $reader=[IO.StreamReader]::new($entry.Open());try{return $reader.ReadToEnd()}finally{$reader.Dispose()}
    }finally{$archive.Dispose()}
}
function IsFsrpg([string]$path){$toml=ModToml $path;return [regex]::IsMatch(($toml -split '\[\[dependencies\.')[0],'(?m)^\s*modId\s*=\s*"fsrpg"')}
if(-not (IsFsrpg $jar) -or (ModToml $jar) -notmatch '(?m)^\s*version\s*=\s*"0\.7\.6"'){throw 'Candidate JAR metadata is not FSRPG 0.7.6'}
$sourceAssets=@{
    'legacy_dual_blade.fsvoxel'='7e4accde21d0d4b8a9249501e3df7a10b8a6e5c6e22bfb254ee5b1ec7017a2b0'
    'legacy_dual_blade_fp.fsvoxel'='ad74c113e8da9b741553690d4eb09218c723ed58ef3489892c69d58e88549f76'
}
$archive=[IO.Compression.ZipFile]::OpenRead($jar)
try{
    if(@($archive.Entries | Where-Object {$_.FullName -match '(^org/fsrpg/smoke/)|MonitorAgent|forging|ArtContent|art/combat_arena'}).Count){throw 'Development code or excluded other-project materials are present in the release JAR'}
    foreach($name in $sourceAssets.Keys){
        $entry=$archive.GetEntry('assets/fsrpg/fsvoxel/models/'+$name);if($null -eq $entry){throw "Original dual asset is missing: $name"}
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
        ($_.CommandLine.Contains($instance) -or $_.CommandLine.Contains($instance.Replace('\','/')))}){throw 'Official instance is running; loaded JARs will not be replaced'}
}
AssertInstanceStopped
$mods=(Resolve-Path -LiteralPath (Join-Path $instance 'mods')).Path
$config=(Resolve-Path -LiteralPath (Join-Path $instance 'config/fsrpg')).Path
$before=TreeHashes $config
if($before.Count -ne 47){throw 'Expected the existing 47-file official FSRPG configuration; this release must not migrate configuration'}
foreach($name in $sourceAssets.Keys){if((Hash (ChildPath $config ('models/'+$name))) -cne $sourceAssets[$name]){throw "Official original dual model differs from tested source: $name"}}
$dependency=Join-Path $mods 'fsvoxel-renderer-neoforge-0.6.0.jar'
if((Hash $dependency) -cne (Hash (Join-Path $project 'libs/fsvoxel-renderer-neoforge-0.6.0.jar'))){throw 'Official required dependency differs from the tested dependency'}
$old=@(Get-ChildItem -LiteralPath $mods -File -Filter '*.jar' | Where-Object {IsFsrpg $_.FullName})
if($old.Count -ne 1 -or (ModToml $old[0].FullName) -notmatch '(?m)^\s*version\s*=\s*"0\.7\.5"'){throw 'Expected one installed FSRPG 0.7.5 JAR for this replacement'}
$oldPaths=@($old | ForEach-Object {$_.FullName});$installed=ChildPath $mods 'fsrpg-neoforge-0.7.6.jar'
if(Test-Path -LiteralPath $installed){throw 'The 0.7.6 destination already exists; no unknown file will be overwritten'}
$preserved=@(Get-ChildItem -LiteralPath $mods -Recurse -File | Where-Object {$oldPaths -notcontains $_.FullName} | ForEach-Object {@{path=$_.FullName;sha256=(Hash $_.FullName)}})
foreach($name in @('options.txt','config/fsrpg-client.toml')){$path=ChildPath $instance $name;if(Test-Path -LiteralPath $path){$preserved+=@{path=$path;sha256=(Hash $path)}}}
$saves=ChildPath $instance 'saves';$worlds=if(Test-Path -LiteralPath $saves -PathType Container){TreeHashes $saves}else{@{}}
if($VerifyOnly){Write-Output "VERIFY ONLY PASSED: exact candidate $sha; DISPLAY5 original dual source FK and native 30 Hz; unchanged 47 config files; dependency hashes; official instance stopped. No files were written.";return}
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss-fff';$backup=ChildPath $instance "fsrpg-backups/$stamp-before-0.7.6"
if(Test-Path -LiteralPath $backup){throw 'Dated backup already exists'}
New-Item -ItemType Directory -Path (Join-Path $backup 'mods') -Force | Out-Null
Copy-Item -LiteralPath $config -Destination (Join-Path $backup 'fsrpg') -Recurse
SameTree $before (Join-Path $backup 'fsrpg') 'Full configuration backup'
$previous=@()
foreach($file in $old){
    if($file.DirectoryName -cne $mods -or $file.Extension -cne '.jar'){throw 'Previous mod is outside the exact mods directory'}
    $saved=ChildPath (Join-Path $backup 'mods') $file.Name;Copy-Item -LiteralPath $file.FullName -Destination $saved
    if((Hash $saved) -cne (Hash $file.FullName)){throw 'Previous FSRPG JAR backup hash mismatch'}
    $previous+=@{path=$file.FullName;backup=$saved;sha256=(Hash $saved)}
}
AssertInstanceStopped;SameTree $before $config 'Official configuration immediately before deployment'
$installedTouched=$false
try{
    foreach($file in $previous){Remove-Item -LiteralPath $file.path}
    $installedTouched=$true;Copy-Item -LiteralPath $jar -Destination $installed
    if((Hash $installed) -cne $sha){throw 'Installed JAR is not the exact tested candidate'}
    SameTree $before $config 'Unchanged official configuration'
    foreach($file in $preserved){if((Hash $file.path) -cne $file.sha256){throw "Preserved file changed: $($file.path)"}}
    if(Test-Path -LiteralPath $saves -PathType Container){SameTree $worlds $saves 'Unchanged user worlds'}elseif($worlds.Count -gt 0){throw 'User worlds disappeared'}
    $record=[ordered]@{version='0.7.6';installedJar=$installed;jarSha256=$sha;testedJarSha256=$review.modJarSha256;backup=$backup;
        previousMods=$previous;configurationSha256=$before;configurationFileCount=$before.Count;configurationMigration=$false;configurationByteExactlyPreserved=$true;
        dualSourceAssets=$sourceAssets;sourceWorldMatrixError=$source.maxWorldMatrixError;sourceLinearClockErrorSeconds=$source.maxLinearClockErrorSeconds;
        nativeSourceFps=$clip.sourceFps;nativeSourceFrames=$frames.Count;retainedDualStaminaCost=8;worldsModified=$false;worldFileCount=$worlds.Count;
        otherModsPreserved=$true;preserved=$preserved;evidence=$evidence;deployedAt=(Get-Date).ToString('o')}
    $record | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $backup 'deployment.json') -Encoding utf8
    $record | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $evidence 'deployment.json') -Encoding utf8
}catch{
    $originalFailure=$_;$rollbackErrors=@()
    try{if($installedTouched -and (Test-Path -LiteralPath $installed)){Remove-Item -LiteralPath $installed}}catch{$rollbackErrors+=$_.ToString()}
    foreach($file in $previous){try{Copy-Item -LiteralPath $file.backup -Destination $file.path -Force}catch{$rollbackErrors+=$_.ToString()}}
    try{SameTree $before $config 'Configuration after rollback';foreach($file in $previous){if((Hash $file.path) -cne $file.sha256){throw 'Rolled-back JAR mismatch'}}}catch{$rollbackErrors+=$_.ToString()}
    @{deploymentFailure=$originalFailure.ToString();rollbackErrors=$rollbackErrors;backup=$backup} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $backup 'rollback.json') -Encoding utf8
    if($rollbackErrors.Count -gt 0){throw "Deployment failed: $originalFailure; rollback needs attention: $($rollbackErrors -join ' | '); backup: $backup"};throw $originalFailure
}
Write-Output "Installed $installed; SHA-256 $sha; tested/deployed hashes match; all 47 config files preserved; backup $backup"
