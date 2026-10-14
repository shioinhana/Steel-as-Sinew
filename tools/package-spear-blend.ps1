$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$workspace=Split-Path -Parent $project
$evidence=Join-Path $project 'verification/spear-blend-20261012'
$deployment=Get-Content -LiteralPath (Join-Path $evidence 'deployment.json') -Raw | ConvertFrom-Json
$review=Get-Content -LiteralPath (Join-Path $evidence 'visual-review.json') -Raw | ConvertFrom-Json
$focused=Get-Content -LiteralPath $review.focusedFrameReport -Raw | ConvertFrom-Json
$movement=Get-Content -LiteralPath (Join-Path $evidence 'movement-continuity.json') -Raw | ConvertFrom-Json
$runtime=Get-Content -LiteralPath $review.gameReport -Raw | ConvertFrom-Json
$version=$deployment.version
if($version -notmatch '^\d+\.\d+\.\d+$'){throw 'Invalid tested release version'}
$modName="fsrpg-neoforge-$version.jar"
$mod=Join-Path $project "build/libs/$modName"
$dependency=Join-Path $project 'libs/fsvoxel-renderer-neoforge-0.6.0.jar'
function Hash([string]$path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
$modSha=Hash $mod
if(-not $review.passed -or -not $runtime.passed -or $focused.requiresJointJumpReview -or $movement.failures -ne 0){throw 'Final focused release evidence failed'}
if($modSha -ne $deployment.jarSha256 -or $modSha -ne $runtime.modJarSha256 -or $modSha -ne (Hash $deployment.installedJar)){throw 'Package must use the exact tested and installed JAR'}
$releaseName="FSRPG-$version-NeoForge-1.21.11"
$staging=Join-Path $project "delivery/$releaseName"
$mods=Join-Path $staging 'mods'
New-Item -ItemType Directory -Path $mods -Force | Out-Null
Copy-Item -LiteralPath $mod -Destination (Join-Path $mods $modName)
Copy-Item -LiteralPath $dependency -Destination $mods
$instructions=@"
FSRPG $version 动作系统融合版
适用：Minecraft Java 1.21.11 / NeoForge 21.11.45 / Java 21。

安装：
1. 关闭游戏，将压缩包 mods 文件夹中的两个 JAR 放到目标实例的 mods 文件夹。
2. 更新时先备份并移走旧版 fsrpg-neoforge JAR，只保留一个 FSRPG 版本。
3. 保留已有配置、自定义模型、其他 Mod 和存档。首次运行会使用 JAR 内置默认内容。
4. FSVOXEL renderer 已内嵌 engine 与 OpenGL 运行库，不需要另放 engine JAR。

本机已安装到：$($deployment.installedJar)
旧版和配置已备份到：$($deployment.backup)

本版融合走跑、侧移、呼吸、刺扫动作与双手 IK、关节限位、连续分支。
最终动作专项和 DISPLAY5 真实客户端验证通过；极端角度仍有局部贴身与软碰撞残余。
压缩包内 FSRPG JAR 与已测试、已安装版本 SHA-256 一致：
$modSha
"@
[IO.File]::WriteAllText((Join-Path $staging '安装说明.txt'),$instructions,[Text.UTF8Encoding]::new($false))
$manifest=[ordered]@{
    version=$version;minecraft='1.21.11';neoforge='21.11.45';java=21;packagedAt=(Get-Date).ToString('o')
    artifacts=@(
        @{file="mods/$modName";sha256=$modSha},
        @{file='mods/fsvoxel-renderer-neoforge-0.6.0.jar';sha256=(Hash $dependency)}
    )
    verified=@{movementPoses=$movement.poses;movementChecks=$movement.checks;movementFailures=$movement.failures;actualRenderFrames=$focused.capturedFrames;geometryViolations=@($focused.geometryViolations).Count;jumpAlerts=@($focused.jumpAlerts).Count;liveMovementPassed=$focused.movementValidation.passed;clips=$focused.clips}
    testedAndInstalledJarSha256=$modSha
    limitations='Extreme poses retain local body/head proximity and soft collision residuals.'
}
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $staging '交付清单.json') -Encoding utf8
$zip=Join-Path $workspace "$releaseName.zip"
Compress-Archive -LiteralPath $staging -DestinationPath $zip -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::OpenRead($zip)
try{
    foreach($artifact in $manifest.artifacts){
        $entry=$archive.GetEntry("$releaseName/"+$artifact.file)
        if($null -eq $entry){throw "Missing ZIP artifact: $($artifact.file)"}
        $stream=$entry.Open();$digest=[Security.Cryptography.SHA256]::Create()
        try{$actual=([BitConverter]::ToString($digest.ComputeHash($stream))).Replace('-','').ToLowerInvariant()}
        finally{$stream.Dispose();$digest.Dispose()}
        if($actual -ne $artifact.sha256){throw "ZIP JAR hash differs: $($artifact.file)"}
    }
}finally{$archive.Dispose()}
$zipSha=Hash $zip
"$zipSha  $releaseName.zip" | Set-Content -LiteralPath "$zip.sha256" -Encoding utf8
Write-Output "Packaged $zip"
Write-Output "ZIP SHA-256 $zipSha"
Write-Output "Both JAR entries verified; exact tested FSRPG SHA-256 $modSha"
