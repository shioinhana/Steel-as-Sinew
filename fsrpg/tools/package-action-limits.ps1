$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$workspace=Split-Path -Parent $project
$evidence=Join-Path $project 'verification/action-limits-20261013'
$deployment=Get-Content -LiteralPath (Join-Path $evidence 'deployment.json') -Raw | ConvertFrom-Json
$report=Get-Content -LiteralPath (Join-Path $evidence 'final-report.json') -Raw | ConvertFrom-Json
$version=$deployment.version
if($version -notmatch '^\d+\.\d+\.\d+$'){throw 'Invalid release version'}
function Hash([string]$path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
$modName="fsrpg-neoforge-$version.jar";$mod=Join-Path $project "build/libs/$modName"
$dependency=Join-Path $project 'libs/fsvoxel-renderer-neoforge-0.6.0.jar'
$sha=Hash $mod
if(-not $report.passed -or $sha -ne $report.jarSha256 -or $sha -ne $deployment.jarSha256 -or $sha -ne (Hash $deployment.installedJar)){throw 'Package must match final tested and installed JAR'}
if((Hash (Join-Path $evidence 'final-report.json')) -ne $deployment.finalReportSha256){throw 'Final evidence changed after deployment'}
$dependencySha=Hash $dependency
if($dependencySha -ne $deployment.dependencySha256 -or $dependencySha -ne (Hash $deployment.installedDependency)){throw 'Packaged FSVOXEL dependency must match deployment'}
$releaseName="FSRPG-$version-NeoForge-1.21.11";$staging=Join-Path $project "delivery/$releaseName"
$expectedFiles=@("mods/$modName",'mods/fsvoxel-renderer-neoforge-0.6.0.jar','安装说明.txt','最终验证报告.json','交付清单.json')
if(Test-Path -LiteralPath $staging){
    foreach($file in Get-ChildItem -LiteralPath $staging -Recurse -File){
        $relative=$file.FullName.Substring($staging.Length+1).Replace('\','/')
        if($expectedFiles -notcontains $relative){throw "Stale package staging contains an unrelated file: $relative"}
    }
}
New-Item -ItemType Directory -Path (Join-Path $staging 'mods') -Force | Out-Null
Copy-Item -LiteralPath $mod -Destination (Join-Path $staging "mods/$modName")
Copy-Item -LiteralPath $dependency -Destination (Join-Path $staging 'mods/fsvoxel-renderer-neoforge-0.6.0.jar')
$instructions=@"
FSRPG $version 动作限位与剑融合
Minecraft Java 1.21.11 / NeoForge 21.11.45 / Java 21。

安装：
1. 关闭游戏，备份并移走旧版 FSRPG JAR。
2. 将 mods 文件夹中的两个 JAR 放入目标实例 mods，只保留一个 FSRPG 版本。
3. 保留已有配置、其他 Mod、自定义模型和存档。首次启动会按稳定技能/档位 ID
   将已有动作体力消耗降低至此前的20%；其他数值保留，并生成原配置备份。
4. 迁移只运行一次，完成标记为 stamina-cost-migration-v1.json；之后编辑成本
   写入的数值就是实际成本。请勿删除标记以免再次迁移。
5. FSVOXEL renderer 已包含所需运行库，无需额外 engine JAR。

修改：长枪瞄准框与实际射线共用前方限位，左右各45°、上下各40°。
框外继续移动可以转向。方向合法的近敌照常拾取，侧后方相机遮挡不作为目标。
近处伤害保留真实眼睛瞄准方向，动画目标延长至安全深度，枪轴仍有近处视差。
剑上半身沿用最早0.1.1动作；下半身、支撑脚和动作交接使用新版长枪流水线。
额外移动倾斜至多4°，剑骨盆偏移至多1.5cm；原始攻击倾斜保留。
全部动作体力消耗为升级前的1/5，灵力、恢复和伤害不变。

本机已安装：$($deployment.installedJar)
旧版本和完整配置备份：$($deployment.backup)
本轮专项与 DISPLAY5 实际渲染验证通过。验证详情在随附最终报告中。
已测试、已安装、压缩包中的 FSRPG JAR SHA-256：
$sha
"@
[IO.File]::WriteAllText((Join-Path $staging '安装说明.txt'),$instructions,[Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath (Join-Path $evidence 'final-report.json') -Destination (Join-Path $staging '最终验证报告.json')
$manifest=[ordered]@{version=$version;minecraft='1.21.11';neoforge='21.11.45';java=21;artifacts=@(@{file="mods/$modName";sha256=$sha},@{file='mods/fsvoxel-renderer-neoforge-0.6.0.jar';sha256=$dependencySha});testedAndInstalledJarSha256=$sha;finalReportSha256=$deployment.finalReportSha256;scope='Spear field and arms, old sword upper/modern lower, mild lean and action stamina costs'}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $staging '交付清单.json') -Encoding utf8
$packageHashes=@{}
foreach($name in $expectedFiles){$packageHashes[$name]=Hash (Join-Path $staging $name)}
if($packageHashes["mods/$modName"] -ne $sha -or $packageHashes['mods/fsvoxel-renderer-neoforge-0.6.0.jar'] -ne $dependencySha -or $packageHashes['最终验证报告.json'] -ne $deployment.finalReportSha256){throw 'Package staging differs from validated release'}
$zip=Join-Path $workspace "$releaseName.zip"
Compress-Archive -LiteralPath $staging -DestinationPath $zip -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::OpenRead($zip)
try{
    $files=@($archive.Entries | Where-Object {$_.Name -ne ''})
    if($files.Count -ne $expectedFiles.Count){throw 'ZIP includes unexpected files'}
    foreach($name in $expectedFiles){
        $entry=$archive.GetEntry("$releaseName/"+$name);if($null -eq $entry){throw "ZIP missing $name"}
        $stream=$entry.Open();$digest=[Security.Cryptography.SHA256]::Create()
        try{$actual=([BitConverter]::ToString($digest.ComputeHash($stream))).Replace('-','').ToLowerInvariant()}finally{$stream.Dispose();$digest.Dispose()}
        if($actual -ne $packageHashes[$name]){throw "ZIP hash mismatch $name"}
    }
}finally{$archive.Dispose()}
$zipSha=Hash $zip
"$zipSha  $releaseName.zip" | Set-Content -LiteralPath "$zip.sha256" -Encoding utf8
Write-Output "Packaged $zip; verified both JAR hashes"
Write-Output "ZIP SHA-256 $zipSha"
