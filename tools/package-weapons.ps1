$ErrorActionPreference='Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
$version=((Get-Content gradle.properties | Where-Object {$_ -match '^mod_version='}) -replace '^mod_version=','')
if($version -notmatch '^\d+\.\d+\.\d+$'){throw 'Invalid version'}
$evidence='verification/weapons-r2'
$build=Get-Content "$evidence/build.log" -Raw
if($build -notmatch 'BUILD SUCCESSFUL'){throw 'Build must pass'}
$checks=[ordered]@{}
foreach($suite in @('CombatTest','ConfigTest','QuestTest','RapierAssetTest','ArmDeformationTest','HeldItemTransformTest','SpearAimPoseTest','PoseLayersTest','PresentationMotionTest','LegDeformationTest','IdleInspectionTest','WeaponControlsTest','WeaponAssetTest')){
    $match=[regex]::Match($build,"$suite passed: (\d+) checks")
    if(-not $match.Success){throw "Missing suite $suite"};$checks[$suite]=[int]$match.Groups[1].Value
}
$jar="fsrpg-neoforge-$version.jar"
& "$PSScriptRoot/verify-weapon-authoring.ps1" -JarPath "build/libs/$jar"
$game=Get-Content "$evidence/game-smoke.json" -Raw | ConvertFrom-Json
if(-not $game.passed -or $game.jarSha256 -ne (Get-FileHash "build/libs/$jar" -Algorithm SHA256).Hash -or $game.monitorAdapter -cne '\\.\DISPLAY5' -or @($game.checks).Count -lt 25){throw 'Exact JAR must pass screen-two DISPLAY5 smoke'}
$capture=Get-Content "$evidence/render-capture.json" -Raw | ConvertFrom-Json
$encoded=Get-Content "$evidence/encoded-review.json" -Raw | ConvertFrom-Json
$motion=Get-Content "$evidence/motion-review.json" -Raw | ConvertFrom-Json
$independent=Get-Content "$evidence/independent-motion-review.json" -Raw | ConvertFrom-Json
$visual=Get-Content "$evidence/visual-inspection.json" -Raw | ConvertFrom-Json
if(-not $independent.passed -or $independent.jarSha256 -ne $game.jarSha256 -or @($independent.failures).Count -ne 0){throw 'Independent frame review must pass for this exact JAR'}
if(-not $visual.passed -or $visual.jarSha256 -ne $game.jarSha256 -or @($visual.views).Count -lt 10){throw 'Inspect real bare-arm and armored grips before packaging'}
if(-not $motion.passed -or @($motion.clips).Count -ne 6 -or @($motion.failures).Count -ne 0){throw 'Real render motion presentation must pass'}
if($capture.captureEvent -ne 'RenderFrameEvent.Post' -or $capture.pendingWrites -ne 0 -or @($capture.clips).Count -ne 10 -or @($encoded.clips).Count -ne 10){throw 'Capture and encode all ten control and presentation clips'}
foreach($clip in $capture.clips){if($clip.sourceFps -lt 30 -or $clip.capturedFrames -ne $clip.writtenFrames -or $clip.distinctSamplesWithinGameTick -le 0 -or $clip.changedRigPairs -le 0){throw 'Invalid motion capture'}}
foreach($clip in $encoded.clips){if($clip.outputFps -ne 30 -or $clip.uniqueFramebufferImages -lt 30){throw 'Invalid encoded evidence'}}
$rapierEvidence='verification/rapier-r6'
$rapierGame=Get-Content "$rapierEvidence/game-smoke.json" -Raw | ConvertFrom-Json
$rapierCapture=Get-Content "$rapierEvidence/render-capture.json" -Raw | ConvertFrom-Json
$rapierEncoded=Get-Content "$rapierEvidence/encoded-review.json" -Raw | ConvertFrom-Json
$rapierIndependent=Get-Content "$rapierEvidence/independent-motion-review.json" -Raw | ConvertFrom-Json
$rapierVisual=Get-Content "$rapierEvidence/visual-inspection.json" -Raw | ConvertFrom-Json
if(-not $rapierGame.passed -or $rapierGame.modJarSha256 -ne $game.jarSha256 -or $rapierGame.monitorAdapter -cne '\\.\DISPLAY5' -or -not $rapierGame.fixturePoseFrozen){throw 'Revised rapier must pass the same exact JAR on screen two DISPLAY5'}
if(-not $rapierIndependent.passed -or $rapierIndependent.jarSha256 -ne $game.jarSha256 -or @($rapierIndependent.failures).Count -ne 0){throw 'Independent rapier frame review must pass for this exact JAR'}
if(-not $rapierVisual.passed -or $rapierVisual.jarSha256 -ne $game.jarSha256 -or @($rapierVisual.views).Count -lt 10){throw 'Inspect all ten revised rapier views before packaging this exact JAR'}
if(@($rapierCapture.clips).Count -ne 10 -or $rapierCapture.pendingWrites -ne 0 -or @($rapierEncoded.clips).Count -ne 10){throw 'Complete rapier render capture is required'}
foreach($clip in $rapierCapture.clips){if($clip.sourceFps -lt 30 -or $clip.capturedFrames -ne $clip.writtenFrames){throw 'Invalid rapier motion capture'}}
foreach($clip in $rapierEncoded.clips){if($clip.outputFps -ne 30 -or $clip.uniqueFramebufferImages -lt 30){throw 'Invalid rapier encoded evidence'}}
$name="FSRPG-$version-NeoForge-1.21.11";$release="dist/$name"
foreach($folder in @('mods','config/fsrpg/models','schemas','docs','verification/screenshots','verification/visual-contact-sheets','verification/authoring-sources','verification/rapier/screenshots/review','verification/rapier/visual-contact-sheets')){New-Item -ItemType Directory -Path "$release/$folder" -Force | Out-Null}
Copy-Item -LiteralPath "build/libs/$jar",'libs/fsvoxel-renderer-neoforge-0.6.0.jar' -Destination "$release/mods"
Get-ChildItem src/main/resources/defaults -Filter '*.json' -File | Copy-Item -Destination "$release/config/fsrpg"
Get-ChildItem src/main/resources/assets/fsrpg/fsvoxel/models -Filter '*.fsvoxel' -File | Copy-Item -Destination "$release/config/fsrpg/models"
Get-ChildItem schemas -File | Copy-Item -Destination "$release/schemas"
Get-ChildItem docs -File | Copy-Item -Destination "$release/docs"
Copy-Item README.md -Destination $release
Copy-Item run/client/config/fsrpg-client.toml -Destination "$release/config"
foreach($file in @('build.log','game-smoke.json','render-capture.json','encoded-review.json','workbench-authoring.json','workbench-provenance.json','sword-path.json','motion-review.json','independent-motion-review.json','visual-inspection.json','visual-review.mp4')){Copy-Item -LiteralPath "$evidence/$file" -Destination "$release/verification"}
foreach($file in @('dual-workbench-authoring.json','spear-workbench-authoring.json')){Copy-Item -LiteralPath "$evidence/authoring-sources/$file" -Destination "$release/verification/authoring-sources"}
Get-ChildItem "$evidence/screenshots" -Filter '*.png' -File | Copy-Item -Destination "$release/verification/screenshots"
Get-ChildItem -LiteralPath "$evidence/visual-contact-sheets" | Copy-Item -Destination "$release/verification/visual-contact-sheets" -Recurse -Force
foreach($file in @('game-smoke.json','render-capture.json','encoded-review.json','independent-motion-review.json','visual-inspection.json','idle-inspection-timeline.json','visual-review.mp4')){Copy-Item -LiteralPath "$rapierEvidence/$file" -Destination "$release/verification/rapier"}
Get-ChildItem -LiteralPath "$rapierEvidence/visual-contact-sheets" | Copy-Item -Destination "$release/verification/rapier/visual-contact-sheets" -Recurse -Force
foreach($file in @('rapier-anatomy.json','workbench-authoring.json')){Copy-Item -LiteralPath "verification/rapier-r7/$file" -Destination "$release/verification/rapier"}
Get-ChildItem "$rapierEvidence/screenshots/review" -Filter '*.png' -File | Copy-Item -Destination "$release/verification/rapier/screenshots/review"
Copy-Item -LiteralPath 'tools/weapon-choreography.json' -Destination "$release/verification"
$artifacts=@()
foreach($file in @("mods/$jar",'mods/fsvoxel-renderer-neoforge-0.6.0.jar','config/fsrpg/models/rapier.fsvoxel','config/fsrpg/models/dual_blade.fsvoxel','config/fsrpg/models/spear.fsvoxel')){$artifacts+=@{file=$file;sha256=(Get-FileHash "$release/$file" -Algorithm SHA256).Hash.ToLower()}}
[ordered]@{version=$version;minecraft='1.21.11';neoforge='21.11.45';java=21;checks=$checks;packagedGameChecks=@($game.checks).Count;artifacts=$artifacts;enabledStyles=@('rapier','dual_blade','spear');networkProtocol='2';authoringSampleRate=60;videoOutputFps=30} | ConvertTo-Json -Depth 8 | Set-Content "$release/交付清单.json" -Encoding utf8
Compress-Archive -LiteralPath $release -DestinationPath "dist/$name.zip" -Force
$source='src','schemas','gradle','libs','tools','docs','build.gradle','settings.gradle','gradle.properties','gradlew','gradlew.bat','Build.cmd','Run-Client.cmd','README.md','AGENTS.md','.gitignore'
Compress-Archive -LiteralPath $source -DestinationPath "dist/FSRPG-$version-source.zip" -Force
Get-FileHash -Algorithm SHA256 -LiteralPath "dist/$name.zip","dist/FSRPG-$version-source.zip" | ForEach-Object {"$($_.Hash.ToLower())  $([IO.Path]::GetFileName($_.Path))"} | Set-Content "dist/SHA256SUMS-$version.txt" -Encoding utf8
Write-Output "Packaged $name.zip and FSRPG-$version-source.zip"
