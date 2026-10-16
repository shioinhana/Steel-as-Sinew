$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$evidence=Join-Path $project 'verification/spear-performance-20261016'
function Hash([string]$path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
$runtime=Get-Content -LiteralPath (Join-Path $evidence 'optimized/game/spear-performance-review.json') -Raw | ConvertFrom-Json
$comparison=Get-Content -LiteralPath (Join-Path $evidence 'comparison.json') -Raw | ConvertFrom-Json
$migration=Get-Content -LiteralPath (Join-Path $evidence 'config-stage/report.json') -Raw | ConvertFrom-Json
$jar=Join-Path $project 'build/libs/fsrpg-neoforge-0.7.2.jar';$sha=Hash $jar
if(-not $runtime.passed -or $runtime.modJarSha256 -ne $sha -or $runtime.monitorAdapter -cne '\\.\DISPLAY5' -or $runtime.completedScenes -ne 5){throw 'Exact release has not passed DISPLAY5 review'}
if(-not $comparison.passed -or $comparison.optimizedJarSha256 -ne $sha){throw 'Performance comparison is not verified'}
if(-not $migration.passed -or -not $migration.allOtherExistingFieldsAndFilesPreserved -or $migration.afterStaminaRegenPerSecond -ne 3){throw 'Staged regeneration migration not verified'}
if((Get-Content -LiteralPath (Join-Path $evidence 'build.log') -Raw) -notmatch 'BUILD SUCCESSFUL'){throw 'Full build did not pass'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::OpenRead($jar)
try{if(@($zip.Entries | Where-Object {$_.FullName -match 'org/fsrpg/smoke|MonitorAgent|forging|ArtContent|art/combat_arena'}).Count){throw 'Excluded code or other-project material in release'}}finally{$zip.Dispose()}
$instance=(Resolve-Path -LiteralPath 'C:/Users/34003/AppData/Roaming/.minecraft/versions/1.21.11-NeoForge').Path
$versionText=Get-Content -LiteralPath (Join-Path $instance '1.21.11-NeoForge.json') -Raw
if(($versionText | ConvertFrom-Json).id -ne '1.21.11-NeoForge' -or -not $versionText.Contains('21.11.45')){throw 'Unexpected instance version'}
if(-not (Get-Content -LiteralPath (Join-Path $instance 'logs/latest.log') -Raw).Contains("Game directory: $instance")){throw 'Actual game directory not confirmed'}
if(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | Where-Object {$_.CommandLine -and ($_.CommandLine.Contains($instance) -or $_.CommandLine.Contains($instance.Replace('\','/')))}){throw 'Official instance is running; do not replace loaded JARs'}
$mods=(Resolve-Path -LiteralPath (Join-Path $instance 'mods')).Path
if((Hash (Join-Path $mods 'fsvoxel-renderer-neoforge-0.6.0.jar')) -ne (Hash (Join-Path $project 'libs/fsvoxel-renderer-neoforge-0.6.0.jar'))){throw 'Required dependency differs from tested dependency'}
function IsFsrpg([string]$path){
    $archive=[IO.Compression.ZipFile]::OpenRead($path)
    try{$entry=$archive.GetEntry('META-INF/neoforge.mods.toml');if($null -eq $entry){return $false}
        $reader=[IO.StreamReader]::new($entry.Open());try{$text=$reader.ReadToEnd()}finally{$reader.Dispose()}
        return [regex]::IsMatch(($text -split '\[\[dependencies\.')[0],'(?m)^\s*modId\s*=\s*"fsrpg"')
    }finally{$archive.Dispose()}
}
$old=@(Get-ChildItem -LiteralPath $mods -File -Filter '*.jar' | Where-Object {IsFsrpg $_.FullName})
$installed=Join-Path $mods 'fsrpg-neoforge-0.7.2.jar'
if((Test-Path -LiteralPath $installed) -and $old.FullName -notcontains $installed){throw 'Release filename belongs to unrelated content'}
$config=Join-Path $instance 'config/fsrpg';$stage=Join-Path $evidence 'config-stage/fsrpg'
foreach($entry in $migration.beforeSha256.PSObject.Properties){if((Hash (Join-Path $config $entry.Name)) -ne $entry.Value){throw "Existing config changed after validation: $($entry.Name)"}}
foreach($entry in $migration.afterSha256.PSObject.Properties){if((Hash (Join-Path $stage $entry.Name)) -ne $entry.Value){throw "Staged config changed after validation: $($entry.Name)"}}
$preserved=@(Get-ChildItem -LiteralPath $mods -File | Where-Object {$old.FullName -notcontains $_.FullName} | ForEach-Object {@{path=$_.FullName;sha256=(Hash $_.FullName)}})
$preserved+=@(Get-ChildItem -LiteralPath $config -Recurse -File | Where-Object {$_.FullName -ne (Join-Path $config 'combat.json')} | ForEach-Object {@{path=$_.FullName;sha256=(Hash $_.FullName)}})
foreach($name in @('options.txt','config/fsrpg-client.toml')){$path=Join-Path $instance $name;if(Test-Path -LiteralPath $path){$preserved+=@{path=$path;sha256=(Hash $path)}}}
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss';$backup=[IO.Path]::GetFullPath((Join-Path $instance "fsrpg-backups/$stamp-before-spear-performance-0.7.2"))
if(-not $backup.StartsWith($instance+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Backup outside instance'}
New-Item -ItemType Directory -Path (Join-Path $backup 'mods') -Force | Out-Null
Copy-Item -LiteralPath $config -Destination (Join-Path $backup 'fsrpg') -Recurse
$previous=@();$created=@()
foreach($file in $old){
    if($file.DirectoryName -ne $mods -or $file.Extension -ne '.jar'){throw 'Old JAR outside mods'}
    $saved=Join-Path $backup "mods/$($file.Name)";Copy-Item -LiteralPath $file.FullName -Destination $saved
    if((Hash $saved) -ne (Hash $file.FullName)){throw 'Old JAR backup mismatch'}
    $previous+=@{path=$file.FullName;backup=$saved;sha256=(Hash $saved)}
}
try{
    foreach($file in $previous){Remove-Item -LiteralPath $file.path}
    Copy-Item -LiteralPath $jar -Destination $installed
    Copy-Item -LiteralPath (Join-Path $stage 'combat.json') -Destination (Join-Path $config 'combat.json') -Force
    foreach($entry in $migration.afterSha256.PSObject.Properties){
        if($entry.Name -ne 'stamina-regen-migration-v1.json' -and -not $entry.Name.StartsWith('stamina-regen-backup-v1/')){continue}
        $target=Join-Path $config $entry.Name
        if(Test-Path -LiteralPath $target){if((Hash $target) -ne $entry.Value){throw 'Existing migration file differs'};continue}
        New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $stage $entry.Name) -Destination $target;$created+=,$target
    }
    if((Hash $installed) -ne $sha){throw 'Installed JAR differs from tested release'}
    foreach($entry in $migration.afterSha256.PSObject.Properties){if((Hash (Join-Path $config $entry.Name)) -ne $entry.Value){throw 'Installed config differs from tested migration'}}
    foreach($file in $preserved){if((Hash $file.path) -ne $file.sha256){throw "Preserved file changed: $($file.path)"}}
}catch{
    if(Test-Path -LiteralPath $installed){Remove-Item -LiteralPath $installed}
    foreach($file in $previous){Copy-Item -LiteralPath $file.backup -Destination $file.path}
    Copy-Item -LiteralPath (Join-Path $backup 'fsrpg/combat.json') -Destination (Join-Path $config 'combat.json') -Force
    foreach($path in $created){Remove-Item -LiteralPath $path}
    throw
}
$record=[ordered]@{version='0.7.2';installedJar=$installed;jarSha256=$sha;testedJarSha256=$runtime.modJarSha256;backup=$backup;previousMods=$previous;staminaRegenBefore=1;staminaRegenAfter=3;spiritRegen=1;resourceRegenDelayTicks=20;existingConfigFieldsPreserved=$true;preserved=$preserved;worldsModified=$false;comparison=(Join-Path $evidence 'comparison.json')}
$record | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $evidence 'deployment.json') -Encoding utf8
$record | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $backup 'deployment.json') -Encoding utf8
Write-Output "Installed $installed; SHA-256 $sha; backup $backup; stamina regeneration 1 -> 3 per second"
