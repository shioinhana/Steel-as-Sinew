param(
    [Parameter(Mandatory=$true)][string]$Run,
    [string]$BuildLog='build-revised.log'
)
$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$evidence=Join-Path $project 'verification/action-limits-20261013'
$runRoot=(Resolve-Path -LiteralPath $Run).Path
$gamePath=Join-Path $runRoot 'action-limits-smoke.json'
$framePath=Join-Path $runRoot 'focused-analysis/focused-summary.json'
$capturePath=Join-Path $runRoot 'render-capture.json'
$visualPath=Join-Path $runRoot 'focused-analysis/visual-review.json'
function Hash([string]$path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
$jar=Join-Path $project 'build/libs/fsrpg-neoforge-0.5.3.jar';$sha=Hash $jar
$game=Get-Content -LiteralPath $gamePath -Raw | ConvertFrom-Json
$frames=Get-Content -LiteralPath $framePath -Raw | ConvertFrom-Json
$visual=Get-Content -LiteralPath $visualPath -Raw | ConvertFrom-Json
if(-not $game.passed -or $game.modJarSha256 -ne $sha -or $game.monitorAdapter -ne '\\.\DISPLAY5'){throw 'Required exact-JAR real-client review did not pass on DISPLAY5'}
if(-not $frames.passed -or $frames.jarSha256 -ne $sha -or $frames.sourceCaptureSha256 -ne (Hash $capturePath) -or $frames.sourceRuntimeReportSha256 -ne (Hash $gamePath)){throw 'Frame analysis does not match current runtime evidence'}
if(-not $visual.passed -or $visual.jarSha256 -ne $sha -or $visual.runtimeReportSha256 -ne (Hash $gamePath) -or $visual.captureReportSha256 -ne (Hash $capturePath)){throw 'Visual review does not match current runtime evidence'}
if(@($visual.reviewedImages).Count -lt 2){throw 'Actual captured image review is incomplete'}
foreach($path in $visual.reviewedImages){if(-not (Test-Path -LiteralPath $path -PathType Leaf)){throw "Missing reviewed actual image: $path"}}
$logPath=Join-Path $evidence $BuildLog
$log=Get-Content -LiteralPath $logPath -Raw
if(-not $log.Contains('BUILD SUCCESSFUL')){throw 'Focused build failed'}
$offline=Get-Content -LiteralPath (Join-Path $evidence 'offline-pending.json') -Raw | ConvertFrom-Json
if(-not $offline.offlineChecksPassed -or $offline.jarSha256 -ne $sha){throw 'Packaged class audit does not match tested build'}
if($offline.buildLogSha256 -ne (Hash $logPath) -or $offline.solverRegressionLogSha256 -ne (Hash $offline.solverRegressionLog)){throw 'Focused build/regression log changed after packaged class audit'}
$posePath=Join-Path $evidence 'forward-field-poses.json'
$poses=Get-Content -LiteralPath $posePath -Raw | ConvertFrom-Json
if($poses.failures -ne 0 -or $poses.diagnosticFieldOverride -or $poses.testedMaximumYawDegrees -ne $game.aimField.maxYawDegrees -or $poses.testedMaximumPitchDegrees -ne $game.aimField.maxPitchDegrees){throw 'Offline production field geometry failed'}
$stagePath=Join-Path $evidence 'stamina-stage.json'
$stage=Get-Content -LiteralPath $stagePath -Raw | ConvertFrom-Json
if(-not $stage.passed -or -not $stage.secondReloadIdempotent -or -not $stage.allExistingStaminaCostsScaledExactlyOnce -or -not $stage.unrelatedConfigValuesPreserved){throw 'Existing custom cost migration failed'}
$report=[ordered]@{
    passed=$true;version='0.5.3';minecraft='1.21.11';neoForge='21.11.45';java=21
    scope='Spear field and arms, original sword upper with modern lower/support/transition, mild movement lean and action stamina costs'
    jar=$jar;jarSha256=$sha;gameReport=$gamePath;frameReport=$framePath;captureReport=$capturePath;visualReview=$visualPath
    evidenceHashes=@{gameReport=(Hash $gamePath);frameReport=(Hash $framePath);captureReport=(Hash $capturePath);visualReview=(Hash $visualPath);buildLog=(Hash $logPath);solverRegressionLog=(Hash $offline.solverRegressionLog);forwardFieldPoses=(Hash $posePath);staminaStage=(Hash $stagePath)}
    buildLog=$logPath;offlineChecks=$offline.tests;packagedMainClassChecks=$offline.packagedMainClassChecks;developmentClassesExcluded=$offline.developmentClassesExcluded
    forwardField=@{poses=$poses.poses;checks=$poses.checks;failures=$poses.failures;diagnosticOverride=$poses.diagnosticFieldOverride};aimField=$game.aimField
    monitor=@{adapter=$game.monitorAdapter;bounds=$game.monitorBounds;window=@{x=$game.windowX;y=$game.windowY;width=$game.windowWidth;height=$game.windowHeight}}
    actualRenderedFrames=$frames.actualRenderedFrames;renderClips=$frames.clips;numericChecks=$frames.numericChecks
    cursorChecks=$game.cursorChecks;nearEntityChecks=$game.nearEntityChecks;maximumCursorBodyYawDegrees=$game.maximumCursorBodyYawDegrees
    swordSourceComparisons=$game.swordSourceComparisons;maximumSwordSourceArmDifferenceDegrees=$game.maximumSwordSourceArmDifferenceDegrees;maximumSwordReadyTiltDegrees=$game.maximumSwordReadyTiltDegrees
    serverStamina=$game.serverStamina;stagedStaminaFields=$stage.staminaFieldsValidated;staminaCostMultiplier=.2;customValuesPreserved=$stage.unrelatedConfigValuesPreserved;secondReloadIdempotent=$stage.secondReloadIdempotent
    limitation='Near hits retain exact eye-to-hit combat direction; the animation target extends along that direction to safe forward depth. The rendered pole has hand/eye parallax and need not pass exactly through the original near hit.'
    visualNotes=$visual.notes;recordedAt=[DateTimeOffset]::Now.ToString('o')
}
$report | ConvertTo-Json -Depth 18 | Set-Content -LiteralPath (Join-Path $evidence 'final-report.json') -Encoding UTF8
Write-Output "Final focused evidence saved for SHA-256 $sha; $($frames.actualRenderedFrames) actual frames."
