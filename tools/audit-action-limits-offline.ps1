param([string]$BuildLog='build-revised.log')
$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$evidence=Join-Path $project 'verification/action-limits-20261013'
$jar=Join-Path $project 'build/libs/fsrpg-neoforge-0.5.3.jar'
$instance='C:/Users/34003/AppData/Roaming/.minecraft/versions/1.21.11-NeoForge'
function Hash([string]$path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
$buildLogPath=Join-Path $evidence $BuildLog
$buildLogText=Get-Content -LiteralPath $buildLogPath -Raw
$tests=[ordered]@{spearCursorFieldTest=1194189;spearForwardFieldPoseTest=307408;swordMotionTest=23938;staminaCostTest=1518;poseLayersTest=6037;presentationMotionTest=649}
if(-not $buildLogText.Contains('BUILD SUCCESSFUL')){throw 'Final focused build failed'}
foreach($name in $tests.Keys){if(-not $buildLogText.Contains("Task :$name") -or -not $buildLogText.Contains([string]$tests[$name])){throw "Missing focused result: $name"}}
$solverLogPath=Join-Path $evidence 'solver-expanded-final.log'
$solverLog=Get-Content -LiteralPath $solverLogPath -Raw
$solverResult=[regex]::Match($solverLog,'SpearAimPoseTest passed: (\d+) checks')
if(-not $solverLog.Contains('BUILD SUCCESSFUL') -or -not $solverResult.Success){throw 'Full solver continuity regression did not pass'}
$tests['spearAimPoseTest']=[int]$solverResult.Groups[1].Value
$poses=Get-Content -LiteralPath (Join-Path $evidence 'forward-field-poses.json') -Raw | ConvertFrom-Json
if($poses.failures -ne 0 -or $poses.diagnosticFieldOverride -or $poses.testedMaximumYawDegrees -ne 45 -or $poses.testedMaximumPitchDegrees -ne 40){throw 'Production forward field did not pass'}
$stage=Get-Content -LiteralPath (Join-Path $evidence 'stamina-stage.json') -Raw | ConvertFrom-Json
if(-not $stage.passed -or -not $stage.secondReloadIdempotent -or -not $stage.unrelatedConfigValuesPreserved){throw 'Official-config staging failed'}
foreach($field in $stage.beforeSha256.PSObject.Properties){if((Hash (Join-Path $instance "config/fsrpg/$($field.Name)")) -ne $field.Value){throw "Official config changed since staging: $($field.Name)"}}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::OpenRead($jar)
$classChecks=0
try{
    if(@($zip.Entries | Where-Object {$_.FullName -match 'org/fsrpg/(smoke|dev)/'}).Count -ne 0){throw 'Release JAR includes development classes'}
    foreach($entry in $zip.Entries | Where-Object {$_.FullName.StartsWith('org/fsrpg/') -and $_.FullName.EndsWith('.class')}){
        $class=Join-Path $project "build/classes/java/main/$($entry.FullName)"
        if(-not (Test-Path -LiteralPath $class -PathType Leaf)){throw "Packaged class has no build counterpart: $($entry.FullName)"}
        $stream=$entry.Open();$digest=[Security.Cryptography.SHA256]::Create()
        try{$sha=([BitConverter]::ToString($digest.ComputeHash($stream))).Replace('-','').ToLowerInvariant()}
        finally{$stream.Dispose();$digest.Dispose()}
        if($sha -ne (Hash $class)){throw "Packaged class differs from tested output: $($entry.FullName)"}
        if($entry.FullName -eq 'org/fsrpg/client/SpearAimPose.class' -and $sha -ne $poses.loadedSolverBytecodeSha256){throw 'Pose evidence solver differs from release JAR'}
        $classChecks++
    }
    $entry=$zip.GetEntry('META-INF/neoforge.mods.toml');$reader=[IO.StreamReader]::new($entry.Open())
    try{$metadata=$reader.ReadToEnd()}finally{$reader.Dispose()}
    if($metadata -notmatch 'version="0\.5\.3"' -or $metadata -notmatch 'modId="fsrpg"'){throw 'Unexpected release metadata'}
}finally{$zip.Dispose()}
Add-Type -AssemblyName System.Windows.Forms
$screens=@([System.Windows.Forms.Screen]::AllScreens | ForEach-Object {@{adapter=$_.DeviceName;bounds=@{x=$_.Bounds.X;y=$_.Bounds.Y;width=$_.Bounds.Width;height=$_.Bounds.Height}}})
$displayAvailable=@($screens | Where-Object adapter -eq '\\.\DISPLAY5').Count -eq 1
$installed=Join-Path $instance 'mods/fsrpg-neoforge-0.5.2.jar'
$report=[ordered]@{
    status='awaiting-required-real-client-validation';finalPassed=$false;offlineChecksPassed=$true;version='0.5.3';minecraft='1.21.11';neoForge='21.11.45'
    jar=$jar;jarSha256=(Hash $jar);packagedMainClassChecks=$classChecks;developmentClassesExcluded=$true
    tests=$tests;buildLog=$buildLogPath;buildLogSha256=(Hash $buildLogPath);solverRegressionLog=$solverLogPath;solverRegressionLogSha256=(Hash $solverLogPath);forwardFieldPoses=$poses.poses;forwardFieldFailures=$poses.failures;stagedStaminaFields=$stage.staminaFieldsValidated;officialConfigUnchanged=$true
    requiredDisplay='\\.\DISPLAY5';requiredDisplayAvailable=$displayAvailable;enumeratedDisplays=$screens
    gameValidation='pending final matching-JAR DISPLAY5 runtime review'
    deployment='not performed';officialInstalledJar=$installed;officialInstalledSha256=(Hash $installed)
    formalZip='not produced pending required real-client validation and deployment'
    nextCommand='powershell -NoProfile -File tools/run-action-limits-review.ps1'
    recordedAt=[DateTimeOffset]::Now.ToString('o')
}
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $evidence 'offline-pending.json') -Encoding UTF8
Write-Output "Offline audit passed: $classChecks packaged classes; DISPLAY5 available=$displayAvailable; release/deployment remain pending."
Write-Output "Candidate JAR SHA-256 $($report.jarSha256)"
