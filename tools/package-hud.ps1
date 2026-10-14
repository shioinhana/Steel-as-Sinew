$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
$evidence = 'verification/hud-20261011'
$version = ((Get-Content gradle.properties | Where-Object { $_ -match '^mod_version=' }) -replace '^mod_version=', '')
$jar = "fsrpg-neoforge-$version.jar"
$build = Get-Content "$evidence/build-offline.log" -Raw
if ($build -notmatch 'BUILD SUCCESSFUL' -or $build -notmatch 'HudPresentationTest passed: 408 checks') { throw 'Build and HUD layout checks must pass' }
$game = Get-Content "$evidence/game/latest.json" -Raw | ConvertFrom-Json
$jarHash = (Get-FileHash "build/libs/$jar" -Algorithm SHA256).Hash.ToLower()
if (-not $game.capturePassed -or $game.jarSha256 -ne $jarHash -or $game.monitorAdapter -cne '\\.\DISPLAY5' -or @($game.views).Count -ne 9) { throw 'Exact released JAR must pass all nine HUD views on DISPLAY5' }
$visual = Get-Content "$evidence/visual-review.json" -Raw | ConvertFrom-Json
if (-not $visual.passed -or $visual.jarSha256 -ne $jarHash) { throw 'Inspect the real screenshots for this JAR before packaging' }
$before = Get-Content "$evidence/animation-hashes-before.json" -Raw | ConvertFrom-Json
foreach ($model in $before) {
    if ((Get-FileHash -LiteralPath $model.Path -Algorithm SHA256).Hash -ne $model.Hash) { throw "Animation asset changed: $($model.Path)" }
}
$release = "delivery/FSRPG-HUD-update-2026-10-11"
foreach ($folder in @('mods', 'docs', 'verification/screenshots', 'verification/assets', 'source')) {
    New-Item -ItemType Directory -Path "$release/$folder" -Force | Out-Null
}
Copy-Item -LiteralPath "build/libs/$jar", 'libs/fsvoxel-renderer-neoforge-0.6.0.jar' -Destination "$release/mods" -Force
Copy-Item -LiteralPath "build/libs/fsrpg-neoforge-$version-sources.jar" -Destination "$release/source" -Force
Copy-Item -LiteralPath 'docs/素材HUD.md' -Destination "$release/docs" -Force
Copy-Item -LiteralPath "$evidence/build-offline.log", "$evidence/game/latest.json", "$evidence/visual-review.json", "$evidence/animation-hashes-before.json" -Destination "$release/verification" -Force
foreach ($view in $game.views) {
    $screenshot = Join-Path $game.directory $view.file
    if ((Get-FileHash -LiteralPath $screenshot -Algorithm SHA256).Hash.ToLower() -ne $view.sha256) { throw 'Screenshot evidence changed' }
    Copy-Item -LiteralPath $screenshot -Destination "$release/verification/screenshots" -Force
}
foreach ($file in @('source-manifest.json', 'layer-manifest.json', 'output-verification.json', 'layer-progress-contact-sheet.png')) {
    Copy-Item -LiteralPath "$evidence/assets/$file" -Destination "$release/verification/assets" -Force
}
Copy-Item -LiteralPath "$evidence/hud-preview.png" -Destination $release -Force
@'
FSRPG 0.3.2 素材 HUD 更新（Minecraft Java 1.21.11 / NeoForge 21.11.45）

关闭游戏，将旧 FSRPG JAR 移出 mods，复制 mods/fsrpg-neoforge-0.3.2.jar 到实际游戏的 mods 目录。
FSVOXEL 0.6.0 已有依赖继续使用；若尚未安装，可使用随附 mods/fsvoxel-renderer-neoforge-0.6.0.jar。
多人游戏客户端和服务端使用同一版 FSRPG。

新排版：左上生命、左下经验；中央原版九格快捷栏；右上灵力、右下体力。
使用新素材的原色与质感，经验绿色、体力玉绿。GUI 缩放较大/有副手时自动上移。
数值沿用原版生命、原版经验、服务端灵力与体力。创造模式也显示四条。
F1、旁观模式、打开窗口时隐藏。config/fsrpg-client.toml 中 materialHud=false 可关闭新 HUD。

本次搁置生物动作素材，模型与动作资产 SHA-256 已核对一致。
升级无需复制或覆盖外部 config/fsrpg/models，现有模型与数值配置继续使用。
hud-preview.png 为实际游戏截图裁剪。docs/素材HUD.md 有详细说明，verification/ 有构建与实机证据。
'@ | Set-Content -LiteralPath "$release/安装说明.txt" -Encoding utf8
$manifest = [ordered]@{
    version = $version; minecraft = '1.21.11'; neoforge = '21.11.45'; java = 21
    jarSha256 = $jarHash; hudLayoutChecks = 408; gameViews = 9; gameCapturePassed = $game.capturePassed
    monitorAdapter = $game.monitorAdapter; visualReviewPassed = $visual.passed
    animationModelsUnchanged = $true; biologicalMotionAssetsDeferred = $true
    assets = @('health', 'experience', 'spirit', 'stamina'); palette = 'Supplied original colors'
    install = 'Replace only FSRPG JAR; keep existing external models/config'
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath "$release/交付清单.json" -Encoding utf8
$archive = "$release.zip"
Compress-Archive -LiteralPath $release -DestinationPath $archive -Force
"$((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLower())  $([IO.Path]::GetFileName($archive))" |
    Set-Content -LiteralPath "$archive.sha256" -Encoding utf8
Get-Item -LiteralPath $archive | Select-Object FullName, Length
