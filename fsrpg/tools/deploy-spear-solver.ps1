param(
    [string]$Instance='C:/Users/34003/AppData/Roaming/.minecraft/versions/1.21.11-NeoForge',
    [string]$Evidence='verification/spear-blend-20261012'
)
$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$instanceRoot=(Resolve-Path -LiteralPath $Instance).Path
$evidenceRoot=if([IO.Path]::IsPathRooted($Evidence)){[IO.Path]::GetFullPath($Evidence)}else{[IO.Path]::GetFullPath((Join-Path $project $Evidence))}
function Hash([string]$path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
$metadata=Get-Content -LiteralPath (Join-Path $instanceRoot '1.21.11-NeoForge.json') -Raw | ConvertFrom-Json
$arguments=[object[]]$metadata.arguments.game
$neo=[array]::IndexOf($arguments,'--fml.neoForgeVersion');$mc=[array]::IndexOf($arguments,'--fml.mcVersion')
if($metadata.id -ne '1.21.11-NeoForge' -or $neo -lt 0 -or $mc -lt 0 -or $arguments[$neo+1] -ne '21.11.45' -or $arguments[$mc+1] -ne '1.21.11'){throw 'Unexpected target instance version'}
$version=([regex]::Match((Get-Content -LiteralPath (Join-Path $project 'gradle.properties') -Raw),'(?m)^mod_version=(.+)$')).Groups[1].Value.Trim()
if($version -notmatch '^\d+\.\d+\.\d+$'){throw 'Invalid release version'}
$jarName="fsrpg-neoforge-$version.jar";$jar=Join-Path $project "build/libs/$jarName";$jarSha=Hash $jar
$review=Get-Content -LiteralPath (Join-Path $evidenceRoot 'visual-review.json') -Raw | ConvertFrom-Json
if(-not $review.passed -or $review.jarSha256 -ne $jarSha){throw 'Exact packaged release needs the focused visual review'}
$runtime=Get-Content -LiteralPath $review.gameReport -Raw | ConvertFrom-Json
if(-not $runtime.passed -or $runtime.modJarSha256 -ne $jarSha -or $runtime.monitorAdapter -ne '\\.\DISPLAY5'){throw 'Exact packaged release needs a passed DISPLAY5 runtime review'}
if($null -eq $runtime.movementValidation -or -not $runtime.movementValidation.passed){throw 'Exact packaged release needs passed stock/slim live spear movement validation'}
foreach($profile in @('stock','slim')){
    foreach($segment in @('walk-start','forward-walk','forward-sprint','walk-stop','strafe-left','strafe-right','backward-walk','moving-thrust','moving-sweep')){
        $sample=$runtime.movementValidation.$profile.$segment
        if($null -eq $sample -or $sample.frames -lt 5 -or $sample.freshSamplingPairs -le 0){throw "Live spear movement coverage is missing: $profile $segment"}
    }
}
foreach($image in $review.views){if((Hash $image.path) -ne $image.sha256){throw 'Reviewed spear image changed'}}
$runRoot=[IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($review.gameReport))
$capturePath=Join-Path $runRoot 'render-capture.json'
$focused=Get-Content -LiteralPath $review.focusedFrameReport -Raw | ConvertFrom-Json
if($focused.sourceJarSha256 -ne $jarSha -or $focused.loadedSolverBytecodeSha256 -ne $runtime.loadedSolverBytecodeSha256 -or $focused.sourceCaptureSha256 -ne (Hash $capturePath) -or [IO.Path]::GetFullPath($focused.sourceRun) -ne $runRoot){throw 'Focused frame audit does not match the exact loaded release and source capture'}
if(-not $focused.passedFocusedGeometry -or $focused.requiresJointJumpReview -or @($focused.geometryViolations).Count -ne 0 -or @($focused.jumpAlerts).Count -ne 0){throw 'Focused spear geometry or rendered joint continuity failed'}
if($null -eq $focused.movementValidation -or -not $focused.movementValidation.passed -or @($focused.movementValidation.violations).Count -ne 0){throw 'Focused fixed-aim spear movement amplitude or freeze audit failed'}
$capture=Get-Content -LiteralPath $capturePath -Raw | ConvertFrom-Json
if(@($capture.clips).Count -ne 3 -or $capture.pendingWrites -ne 0 -or $capture.captureEvent -ne 'RenderFrameEvent.Post'){throw 'Actual spear render capture is incomplete'}
foreach($clipId in @('spear-ready-extremes','spear-thrust-sweep-return','spear-moving-extremes')){
    $clip=@($capture.clips | Where-Object id -eq $clipId)
    if($clip.Count -ne 1 -or $clip[0].sourceFps -lt 30 -or $clip[0].captureDurationSeconds -lt 9 -or $clip[0].capturedFrames -ne $clip[0].writtenFrames -or $clip[0].distinctSamplesWithinGameTick -le 0){throw "Spear capture lacks complete actual >=30 Hz frames: $clipId"}
}
$build=Get-Content -LiteralPath (Join-Path $evidenceRoot 'build.log') -Raw
$aim=Get-Content -LiteralPath (Join-Path $evidenceRoot 'spear-aim-test.log') -Raw
$pitch=Get-Content -LiteralPath (Join-Path $evidenceRoot 'pitch-continuity.json') -Raw | ConvertFrom-Json
$safety=Get-Content -LiteralPath (Join-Path $evidenceRoot 'final.json') -Raw | ConvertFrom-Json
if(-not $build.Contains('BUILD SUCCESSFUL') -or -not $aim.Contains('SpearAimPoseTest passed:') -or $pitch.failures -ne 0 -or $safety.solverErrors -ne 0 -or $safety.loadedSolverBytecodeSha256 -ne $runtime.loadedSolverBytecodeSha256){throw 'Focused spear evidence is missing or mismatched'}
$movementPath=Join-Path $evidenceRoot 'movement-continuity.json'
$movement=Get-Content -LiteralPath $movementPath -Raw | ConvertFrom-Json
if($movement.failures -ne 0 -or @($movement.failureExamples).Count -ne 0 -or $movement.sampleHz -lt 30 -or $movement.poses -le 0 -or $movement.checks -le 0 -or -not $movement.solverHasHistory -or -not $movement.solverTransportsAuthoredMotion){throw 'Focused offline spear movement continuity failed or lacks authored-motion transport'}
if($movement.loadedSolverBytecodeSha256 -ne $runtime.loadedSolverBytecodeSha256){throw 'Offline movement test loaded a different spear solver from the tested client'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$movementArchive=[IO.Compression.ZipFile]::OpenRead($jar)
try{
    foreach($class in @(
        @{entry='org/fsrpg/client/SpearAimPose.class';field='loadedSolverBytecodeSha256'},
        @{entry='org/fsrpg/client/SpearMotion.class';field='loadedMotionBytecodeSha256'},
        @{entry='org/fsrpg/client/SpearMotion$State.class';field='loadedMotionStateBytecodeSha256'},
        @{entry='org/fsrpg/client/SpearConstraints.class';field='loadedConstraintsBytecodeSha256'},
        @{entry='org/fsrpg/client/SpearConstraints$Space.class';field='loadedConstraintSpaceBytecodeSha256'}
    )){
        $entry=$movementArchive.GetEntry($class.entry)
        if($null -eq $entry){throw "Packaged release is missing tested spear class $($class.entry)"}
        $stream=$entry.Open();$digest=[Security.Cryptography.SHA256]::Create()
        try{$classSha=([BitConverter]::ToString($digest.ComputeHash($stream))).Replace('-','').ToLowerInvariant()}
        finally{$stream.Dispose();$digest.Dispose()}
        if($movement.($class.field) -ne $classSha){throw "Offline movement class differs from exact packaged release: $($class.entry)"}
    }
}finally{$movementArchive.Dispose()}
foreach($profile in @('Stock','Slim')){
    foreach($groupPrefix in @('translationControl','sourceGripControl','legalCommonGripControl','idle','walk','strafe','sprint')){
        $group=$groupPrefix+$profile
        if($null -eq $movement.groups.$group){throw "Offline spear movement evidence is missing group $group"}
    }
}
if($null -eq $movement.groups.stateTransport){throw 'Offline spear movement evidence lacks complete transform/state transport controls'}
# Recent actual client log confirms this instance is the game directory, not only a version library directory.
$actualLog=Get-Content -LiteralPath (Join-Path $instanceRoot 'logs/latest.log') -Raw
if(-not $actualLog.Contains((Join-Path $instanceRoot 'config\fsrpg'))){throw 'Actual game directory is not confirmed by the instance client log'}
if(Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" | Where-Object {$_.CommandLine -and ($_.CommandLine.Contains($instanceRoot) -or $_.CommandLine.Contains($instanceRoot.Replace('\','/')))} ) {throw 'Target instance is running; loaded files cannot be replaced'}
$mods=(Resolve-Path -LiteralPath (Join-Path $instanceRoot 'mods')).Path
$client=Join-Path $instanceRoot 'config/fsrpg-client.toml'
$oldConfig=if(Test-Path -LiteralPath $client){[IO.File]::ReadAllText($client)}else{''}
$defaults=[ordered]@{spearElbowLimitDegrees=135.0;spearWristLimitDegrees=120.0;spearBodyClearanceCm=1.5;spearNeckYawLimitDegrees=85.0;spearVerticalAimBankDegrees=18.0}
$newLines=@();$added=@()
foreach($name in $defaults.Keys){
    if(-not [regex]::IsMatch($oldConfig,'(?m)^\s*'+[regex]::Escape($name)+'\s*=')){
        $newLines+= "$name = $($defaults[$name].ToString('0.0',[Globalization.CultureInfo]::InvariantCulture))";$added+=$name
    }
}
$newConfig=$oldConfig
if($newLines.Count -gt 0){
    $extra="# FSRPG $version spear solver defaults; existing settings retained.`r`n"+($newLines -join "`r`n")+"`r`n"
    $section=[regex]::Match($oldConfig,'(?m)^\s*\[')
    if($section.Success){$newConfig=$oldConfig.Insert($section.Index,$extra+"`r`n")}
    else{$newConfig=$oldConfig+$(if($oldConfig.EndsWith("`n") -or $oldConfig.Length -eq 0){''}else{"`r`n"})+"`r`n"+$extra}
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
function IsFsrpg([string]$path){
    $zip=[IO.Compression.ZipFile]::OpenRead($path)
    try{$entry=$zip.GetEntry('META-INF/neoforge.mods.toml');if($null -eq $entry){return $false}
        $reader=[IO.StreamReader]::new($entry.Open());try{$text=$reader.ReadToEnd()}finally{$reader.Dispose()}
        return [regex]::IsMatch(($text -split '\[\[dependencies\.')[0],'(?m)^\s*modId\s*=\s*"fsrpg"')
    }finally{$zip.Dispose()}
}
$oldMods=@(Get-ChildItem -LiteralPath $mods -File -Filter '*.jar' | Where-Object {IsFsrpg $_.FullName})
$preserved=@(Get-ChildItem -LiteralPath $mods -File | Where-Object {$oldMods.FullName -notcontains $_.FullName} | ForEach-Object {@{path=$_.FullName;sha256=(Hash $_.FullName)}})
$models=Join-Path $instanceRoot 'config/fsrpg/models'
if(Test-Path -LiteralPath $models){$preserved+=@(Get-ChildItem -LiteralPath $models -File | ForEach-Object {@{path=$_.FullName;sha256=(Hash $_.FullName)}})}
$backup=[IO.Path]::GetFullPath((Join-Path $instanceRoot ("fsrpg-backups/"+(Get-Date -Format 'yyyyMMdd-HHmmss')+"-spear-before-$version")))
if(-not $backup.StartsWith($instanceRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Backup escaped the target instance'}
New-Item -ItemType Directory -Path (Join-Path $backup 'mods') -Force | Out-Null
$configBefore=if(Test-Path -LiteralPath $client){Hash $client}else{''}
if(Test-Path -LiteralPath $client){Copy-Item -LiteralPath $client -Destination (Join-Path $backup 'fsrpg-client.toml');if((Hash (Join-Path $backup 'fsrpg-client.toml')) -ne $configBefore){throw 'Config backup differs'}}
$previous=@()
foreach($old in $oldMods){
    $oldSha=Hash $old.FullName;$target=Join-Path (Join-Path $backup 'mods') $old.Name
    Copy-Item -LiteralPath $old.FullName -Destination $target
    if((Hash $target) -ne $oldSha){throw 'Old Mod backup differs'}
    $previous+=@{path=$old.FullName;sha256=$oldSha;backup=$target}
}
$installed=Join-Path $mods $jarName
try{
    foreach($old in $oldMods){Remove-Item -LiteralPath $old.FullName}
    Copy-Item -LiteralPath $jar -Destination $installed
    if((Hash $installed) -ne $jarSha){throw 'Installed JAR differs from tested release'}
    if($newConfig -ne $oldConfig){[IO.File]::WriteAllText($client,$newConfig,[Text.UTF8Encoding]::new($false))}
    foreach($file in $preserved){if((Hash $file.path) -ne $file.sha256){throw 'An unrelated Mod or custom model changed'}}
}catch{
    if(Test-Path -LiteralPath $installed){Remove-Item -LiteralPath $installed}
    foreach($entry in $previous){Copy-Item -LiteralPath $entry.backup -Destination $entry.path}
    if($configBefore){Copy-Item -LiteralPath (Join-Path $backup 'fsrpg-client.toml') -Destination $client}
    throw
}
$record=[ordered]@{version=$version;instance=$instanceRoot;installedAt=(Get-Date).ToString('o');installedJar=$installed;jarSha256=$jarSha;testedJarSha256=$runtime.modJarSha256;backup=$backup;previousMods=$previous;addedClientFields=$added;configBeforeSha256=$configBefore;configAfterSha256=(Hash $client);preserved=$preserved;worldsModified=$false;gameReport=$review.gameReport;focusedFrameReport=$review.focusedFrameReport;sourceCaptureSha256=$focused.sourceCaptureSha256;movementContinuityReport=$movementPath;movementContinuityReportSha256=(Hash $movementPath);movementContinuityPoses=$movement.poses;movementContinuityChecks=$movement.checks;loadedSolverBytecodeSha256=$movement.loadedSolverBytecodeSha256;loadedMotionBytecodeSha256=$movement.loadedMotionBytecodeSha256;loadedMotionStateBytecodeSha256=$movement.loadedMotionStateBytecodeSha256;monitor=$runtime.monitorAdapter;scope='Only FSRPG JAR and absent spear client fields; exact-release geometry and blended movement evidence required; existing models, config values, other Mods and user saves retained'}
$record | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $evidenceRoot 'deployment.json') -Encoding utf8
$record | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $backup 'deployment.json') -Encoding utf8
Write-Output "Installed $installed"
Write-Output "Verified SHA-256 $jarSha; backup $backup"
