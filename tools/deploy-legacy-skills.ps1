param(
    [string]$Instance='C:/Users/34003/AppData/Roaming/.minecraft/versions/1.21.11-NeoForge',
    [string]$Evidence='verification/legacy-skills-20261012-001758',
    [switch]$PackageWithoutFurtherTests
)
$ErrorActionPreference='Stop'
$project=Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $project
$instanceRoot=(Resolve-Path -LiteralPath $Instance).Path
$versionJson=Get-Content -LiteralPath (Join-Path $instanceRoot '1.21.11-NeoForge.json') -Raw | ConvertFrom-Json
$neoIndex=[array]::IndexOf([object[]]$versionJson.arguments.game,'--fml.neoForgeVersion')
$mcIndex=[array]::IndexOf([object[]]$versionJson.arguments.game,'--fml.mcVersion')
if($versionJson.id -ne '1.21.11-NeoForge' -or $neoIndex -lt 0 -or $mcIndex -lt 0 -or $versionJson.arguments.game[$neoIndex+1] -ne '21.11.45' -or $versionJson.arguments.game[$mcIndex+1] -ne '1.21.11'){throw 'Unexpected instance version'}
$version=((Get-Content -LiteralPath gradle.properties | Where-Object {$_ -match '^mod_version='}) -replace '^mod_version=','')
if($version -notmatch '^\d+\.\d+\.\d+$'){throw 'Invalid mod version'}
$jarName="fsrpg-neoforge-$version.jar"
$jarPath=Join-Path $project "build/libs/$jarName"
function Hash([string]$path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLower()}
$jarHash=Hash $jarPath
$build=Get-Content -LiteralPath "$Evidence/build-offline.log" -Raw
foreach($text in @('BUILD SUCCESSFUL','ArcheryTest: 53 checks passed','ProgressionTest passed:','LegacyMeleeTest passed:','LegacyAssetTest passed:','CollectionQuestTest passed:','WeaponDrawRulesTest passed:')){if(-not $build.Contains($text)){throw "Missing successful validation: $text"}}
$gameReportPath=$null
$report=[pscustomobject]@{checks=@();passed=$false}
if(-not $PackageWithoutFurtherTests){
$visual=Get-Content -LiteralPath "$Evidence/visual-review.json" -Raw | ConvertFrom-Json
if(-not $visual.passed -or $visual.jarSha256 -ne $jarHash){throw 'The exact release must have a visual review'}
$report=Get-Content -LiteralPath $visual.gameReport -Raw | ConvertFrom-Json
if(-not $report.passed -or $report.modJarSha256 -ne $jarHash -or $report.monitorAdapter -cne '\\.\DISPLAY5'){throw 'Exact packaged release must pass real client review on DISPLAY5'}
$capture=Get-Content -LiteralPath (Join-Path (Split-Path -Parent $visual.gameReport) 'render-capture.json') -Raw | ConvertFrom-Json
if(@($capture.clips).Count -lt 7 -or $capture.pendingWrites -ne 0){throw 'Actual frame capture is incomplete'}
foreach($clip in $capture.clips){if($clip.sourceFps -lt 30 -or $clip.writtenFrames -ne $clip.capturedFrames){throw 'Capture must be actual >=30 fps, without missing images'}}
foreach($view in $visual.views){if((Hash $view.path) -ne $view.sha256){throw 'Reviewed screenshots changed'}}
$gameReportPath=$visual.gameReport
}else{
    # Explicit user instruction on 2026-10-12: stop further testing and package immediately.
    $lastReport=Get-ChildItem -LiteralPath "$Evidence/game" -Recurse -File -Filter 'legacy-skill-smoke.json' | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if($null -ne $lastReport){$gameReportPath=$lastReport.FullName;$report=Get-Content -LiteralPath $gameReportPath -Raw | ConvertFrom-Json}
}
foreach($asset in (Get-Content -LiteralPath "$Evidence/assets-preserved.json" -Raw | ConvertFrom-Json)){if((Hash $asset.path) -ne $asset.sha256){throw 'Existing model or HUD asset changed'}}
if(Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" | Where-Object {$_.CommandLine -and $_.CommandLine.Contains($instanceRoot)}){throw 'Official instance is running; its files must not be replaced while loaded'}
$configDir=Join-Path $instanceRoot 'config/fsrpg'
foreach($before in (Get-Content -LiteralPath "$Evidence/staged-before-hashes.json" -Raw | ConvertFrom-Json)){if((Hash (Join-Path $configDir $before.name)) -ne $before.sha256){throw 'Official config changed after staged validation'}}
Add-Type -AssemblyName System.IO.Compression.FileSystem
function ModIds([string]$path){
    $zip=[IO.Compression.ZipFile]::OpenRead($path)
    try{
        $entry=$zip.GetEntry('META-INF/neoforge.mods.toml');if($null -eq $entry){return @()}
        $reader=[IO.StreamReader]::new($entry.Open());try{$metadata=$reader.ReadToEnd()}finally{$reader.Dispose()}
        $section=($metadata -split '\[\[dependencies\.')[0]
        return @([regex]::Matches($section,'(?m)^\s*modId\s*=\s*"([^"]+)"') | ForEach-Object {$_.Groups[1].Value})
    }finally{$zip.Dispose()}
}
$mods=Join-Path $instanceRoot 'mods'
$backup=Join-Path $instanceRoot ("fsrpg-backups/"+(Get-Date -Format 'yyyyMMdd-HHmmss')+"-before-$version")
New-Item -ItemType Directory -Path (Join-Path $backup 'mods') -Force | Out-Null
if(Test-Path -LiteralPath $configDir){Copy-Item -LiteralPath $configDir -Destination (Join-Path $backup 'config-fsrpg') -Recurse}
$clientConfig=Join-Path $instanceRoot 'config/fsrpg-client.toml'
if(Test-Path -LiteralPath $clientConfig){Copy-Item -LiteralPath $clientConfig -Destination $backup}
$previous=@()
foreach($old in (Get-ChildItem -LiteralPath $mods -File -Filter '*.jar')){
    $ids=@(ModIds $old.FullName)
    if($ids -contains 'fsrpg' -or $ids -contains 'fsvoxel' -or $ids -contains 'actioncombat'){
        $target=Join-Path (Join-Path $backup 'mods') $old.Name;$oldHash=Hash $old.FullName
        Copy-Item -LiteralPath $old.FullName -Destination $target
        if((Hash $target) -ne $oldHash){throw 'Backup verification failed'}
        Remove-Item -LiteralPath $old.FullName
        $previous+=@{name=$old.Name;sha256=$oldHash;backup=$target}
    }
}
Copy-Item -LiteralPath $jarPath,(Join-Path $project 'libs/fsvoxel-renderer-neoforge-0.6.0.jar') -Destination $mods
New-Item -ItemType Directory -Path $configDir,(Join-Path $configDir 'models') -Force | Out-Null
$migrated=@()
foreach($source in (Get-ChildItem -LiteralPath "$Evidence/official-config-staged" -File)){
    $target=Join-Path $configDir $source.Name
    if(-not(Test-Path -LiteralPath $target) -or (Hash $target) -ne (Hash $source.FullName)){
        Copy-Item -LiteralPath $source.FullName -Destination $target;$migrated+=$source.Name
    }
}
$seeded=@()
$migrationBackup=Join-Path "$Evidence/official-config-staged" 'legacy-skill-backup-v1'
$installedMigrationBackup=Join-Path $configDir 'legacy-skill-backup-v1'
if((Test-Path -LiteralPath $migrationBackup) -and -not (Test-Path -LiteralPath $installedMigrationBackup)){
    Copy-Item -LiteralPath $migrationBackup -Destination $installedMigrationBackup -Recurse
    foreach($source in (Get-ChildItem -LiteralPath $migrationBackup -File)){
        if((Hash (Join-Path $installedMigrationBackup $source.Name)) -ne (Hash $source.FullName)){throw 'Migration backup verification failed'}
    }
}
foreach($source in (Get-ChildItem -LiteralPath src/main/resources/assets/fsrpg/fsvoxel/models -File -Filter '*.fsvoxel')){
    $target=Join-Path (Join-Path $configDir 'models') $source.Name
    if(-not(Test-Path -LiteralPath $target)){Copy-Item -LiteralPath $source.FullName -Destination $target;$seeded+=$target}
}
$installed=@()
foreach($name in @($jarName,'fsvoxel-renderer-neoforge-0.6.0.jar')){
    $target=Join-Path $mods $name;$source=if($name -eq $jarName){$jarPath}else{Join-Path $project "libs/$name"}
    if((Hash $target) -ne (Hash $source)){throw 'Installed release differs from tested release'}
    $installed+=@{path=$target;sha256=(Hash $target)}
}
$record=[ordered]@{version=$version;instance=$instanceRoot;installedAt=(Get-Date).ToString('o');backup=$backup;installed=$installed;previousMods=$previous;jarSha256=$jarHash;migratedFiles=$migrated;seededModels=$seeded;checks=@($report.checks).Count;monitor=$report.monitorAdapter;worldsPreserved=$true;spearPreserved=$true;biologicalActionsDeferred=$true;gameReport=$gameReportPath;furtherTestingStoppedByUser=[bool]$PackageWithoutFurtherTests;fullGameReviewPassed=[bool]$report.passed;finalIdleFallbackNotRetested=[bool]$PackageWithoutFurtherTests}
$record | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath "$Evidence/deployment.json" -Encoding utf8
$record | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $backup 'deployment.json') -Encoding utf8
$record | ConvertTo-Json -Depth 8
