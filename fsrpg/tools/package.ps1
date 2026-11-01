$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ((Get-Content (Join-Path $projectRoot 'gradle.properties') -Raw) -match 'mod_version=0\.3\.') {
    & (Join-Path $PSScriptRoot 'package-weapons.ps1')
    return
}
Set-Location -LiteralPath $projectRoot
$version = (Get-Content -LiteralPath 'gradle.properties' | Where-Object { $_ -match '^mod_version=' }) -replace '^mod_version=', ''
if ($version -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid project version' }
$releaseName = "FSRPG-$version-NeoForge-1.21.11"
$releaseRoot = Join-Path $projectRoot "dist\$releaseName"
$modName = "fsrpg-neoforge-$version.jar"
foreach ($subdirectory in @('mods','config\fsrpg\models','schemas','docs','verification')) {
    New-Item -ItemType Directory -Path (Join-Path $releaseRoot $subdirectory) -Force | Out-Null
}
Copy-Item -LiteralPath "build\libs\$modName" -Destination "$releaseRoot\mods\$modName"
Copy-Item -LiteralPath 'libs\fsvoxel-renderer-neoforge-0.6.0.jar' -Destination "$releaseRoot\mods"
Get-ChildItem -LiteralPath 'src\main\resources\defaults' -Filter '*.json' -File | Copy-Item -Destination "$releaseRoot\config\fsrpg"
Get-ChildItem -LiteralPath 'src\main\resources\assets\fsrpg\fsvoxel\models' -Filter '*.fsvoxel' -File | Copy-Item -Destination "$releaseRoot\config\fsrpg\models"
Get-ChildItem -LiteralPath 'schemas' -File | Copy-Item -Destination "$releaseRoot\schemas"
Copy-Item -LiteralPath 'README.md' -Destination $releaseRoot
Get-ChildItem -LiteralPath 'docs' -File | Copy-Item -Destination "$releaseRoot\docs"
if (Test-Path -LiteralPath 'run\client\config\fsrpg-client.toml') {
    Copy-Item -LiteralPath 'run\client\config\fsrpg-client.toml' -Destination "$releaseRoot\config"
}
$evidenceRoot = Join-Path $projectRoot 'verification\rapier-r6'
$buildLog = Get-Content -LiteralPath (Join-Path $evidenceRoot 'build.log') -Raw
if ($buildLog -notmatch 'BUILD SUCCESSFUL') { throw 'Complete a successful build before packaging' }
$checks = [ordered]@{}
foreach ($suite in @('CombatTest','ConfigTest','QuestTest','RapierAssetTest','ArmDeformationTest','HeldItemTransformTest','PoseLayersTest','PresentationMotionTest','LegDeformationTest','IdleInspectionTest')) {
    $match = [regex]::Match($buildLog, "$suite passed: (\d+) checks")
    if (-not $match.Success) { throw "Missing successful check: $suite" }
    $checks[$suite] = [int]$match.Groups[1].Value
}
$gameEvidence = Get-Content -LiteralPath (Join-Path $evidenceRoot 'game-smoke.json') -Raw | ConvertFrom-Json
if (-not $gameEvidence.passed -or $gameEvidence.checks -lt 60 -or $gameEvidence.skinnedArmSegments -le 0) { throw 'Complete the packaged rapier client checks before packaging' }
if ($gameEvidence.reviewImages -lt 112 -or $gameEvidence.reviewPosePairChecks -lt 56) { throw 'Complete all matched front/back native pose reviews before packaging' }
foreach ($item in @('minecraft:diamond_sword','minecraft:iron_sword','minecraft:trident')) {
    if ($item -notin $gameEvidence.officialItemIds) { throw "Missing actual official item rendering: $item" }
}
if ($gameEvidence.officialItemSubmissions -le 0 -or $gameEvidence.officialFoilSubmissions -le 0 -or $gameEvidence.wristAttachedOffhandSubmissions -le 0) { throw 'Verify actual mainhand, enchantment glint and wrist-bound offhand rendering' }
if ($gameEvidence.movingReviewImages -ne 4 -or $gameEvidence.movingLegChecks -ne 2) { throw 'Verify both moving upper-body and full-body action scenes' }
if (-not $gameEvidence.refinementMechanicsPassed -or $gameEvidence.ambientMotionChecks -ne 13 -or $gameEvidence.ambientImages -ne 6) { throw 'Verify light/heavy guards, conditional techniques, directional lean and breathing' }
if (-not $gameEvidence.actualInputMovementPassed -or $gameEvidence.actualInputMovementChecks -ne 15) { throw 'Verify actual local-player run/strafe/reversal/stop input before packaging' }
if (-not $gameEvidence.idleInspectionPassed -or $gameEvidence.idleInspectionChecks -lt 17) { throw 'Verify both default idle inspections and real input interruption before packaging' }
if (-not $gameEvidence.cancelReviewPassed -or $gameEvidence.cancelReviewChecks -ne 9) { throw 'Verify live windup cancels, active protection and recovery follow-up' }
$capture = Get-Content -LiteralPath (Join-Path $evidenceRoot 'render-capture.json') -Raw | ConvertFrom-Json
if ($capture.captureEvent -ne 'RenderFrameEvent.Post' -or $capture.pendingWrites -ne 0 -or @($capture.clips).Count -ne 10) { throw 'Complete ten actual render-frame capture clips before packaging' }
foreach ($clip in $capture.clips) {
    if ($clip.sourceFps -lt 30 -or $clip.capturedFrames -lt 31 -or $clip.writtenFrames -ne $clip.capturedFrames -or $clip.distinctSamplesWithinGameTick -le 0 -or $clip.changedRigPairs -le 0) { throw "Actual capture is incomplete or below 30 fps: $($clip.id)" }
}
$encoded = Get-Content -LiteralPath (Join-Path $evidenceRoot 'encoded-review.json') -Raw | ConvertFrom-Json
if ($encoded.outputFps -ne 30 -or @($encoded.clips).Count -ne 10) { throw 'Encode all ten verified capture clips at 30 fps before packaging' }
foreach ($clip in $encoded.clips) {
    if ($clip.sourceFps -lt 30 -or $clip.outputFps -ne 30 -or $clip.uniqueFramebufferImages -lt 30 -or $clip.id -notin $capture.clips.id) { throw "Encoded capture lacks distinct real rendered frames: $($clip.id)" }
}
if (-not (Test-Path -LiteralPath (Join-Path $evidenceRoot 'visual-review.mp4') -PathType Leaf)) { throw 'Missing verified gameplay review video' }
if ($gameEvidence.monitorAdapter -notmatch 'DISPLAY5$' -or $gameEvidence.windowY -ge 0) { throw 'Verify that the test window stayed on screen two before packaging' }
$checks['packagedGame'] = $gameEvidence.checks
if ($gameEvidence.modJarSha256 -ne (Get-FileHash -Algorithm SHA256 -LiteralPath "build\libs\$modName").Hash) { throw 'Packaged smoke did not validate this exact JAR' }
Copy-Item -LiteralPath (Join-Path $evidenceRoot 'game-smoke.json') -Destination "$releaseRoot\verification"
Copy-Item -LiteralPath (Join-Path $evidenceRoot 'build.log'),(Join-Path $evidenceRoot 'workbench-authoring.json'),(Join-Path $evidenceRoot 'packaged-client.log'),(Join-Path $evidenceRoot 'render-capture.json'),(Join-Path $evidenceRoot 'encoded-review.json'),(Join-Path $evidenceRoot 'visual-review.mp4') -Destination "$releaseRoot\verification"
Copy-Item -LiteralPath (Join-Path $evidenceRoot 'screenshots\review') -Destination "$releaseRoot\verification" -Recurse -Force
Copy-Item -LiteralPath (Join-Path $evidenceRoot 'screenshots\moving') -Destination "$releaseRoot\verification" -Recurse -Force
$manifest = [ordered]@{
    schemaVersion = 1
    name = 'FSRPG Adventure - Rapier'
    version = $version
    date = Get-Date -Format 'yyyy-MM-dd'
    minecraft = '1.21.11'
    neoforge = '21.11.45'
    java = 21
    checks = $checks
    scope = 'Server quests and configurable rapier combat; Workbench 60 Hz actions, separated upper/lower locomotion, official skin, armor and actual ItemStack rendering'
    animationRevision = 6
    pending = @('First-person custom animations','Dual blades, spear and unarmed actions','Vanilla mob AI stun','Attribute growth, monster levels and adventure content')
    artifacts = @(
        @{file="mods/$modName";sha256=(Get-FileHash -Algorithm SHA256 -LiteralPath "$releaseRoot\mods\$modName").Hash},
        @{file='mods/fsvoxel-renderer-neoforge-0.6.0.jar';sha256=(Get-FileHash -Algorithm SHA256 -LiteralPath "$releaseRoot\mods\fsvoxel-renderer-neoforge-0.6.0.jar").Hash},
        @{file='config/fsrpg/models/rapier.fsvoxel';sha256=(Get-FileHash -Algorithm SHA256 -LiteralPath "$releaseRoot\config\fsrpg\models\rapier.fsvoxel").Hash}
    )
}
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath "$releaseRoot\交付清单.json" -Encoding utf8
Compress-Archive -LiteralPath $releaseRoot -DestinationPath "dist\$releaseName.zip" -Force
$sourceEntries = 'src','schemas','gradle','libs','tools','docs','build.gradle','settings.gradle','gradle.properties','gradlew','gradlew.bat','Build.cmd','Run-Client.cmd','README.md','AGENTS.md','.gitignore'
Compress-Archive -LiteralPath $sourceEntries -DestinationPath "dist\FSRPG-$version-source.zip" -Force
Get-FileHash -Algorithm SHA256 -LiteralPath "dist\$releaseName.zip","dist\FSRPG-$version-source.zip" |
    ForEach-Object { "$($_.Hash.ToLower())  $([IO.Path]::GetFileName($_.Path))" } |
    Set-Content -LiteralPath "dist\SHA256SUMS-$version.txt" -Encoding utf8
Write-Output "Packaged $releaseName.zip and FSRPG-$version-source.zip"
