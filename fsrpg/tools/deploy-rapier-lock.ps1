$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$evidence=Join-Path $project 'verification/rapier-lock-20261016'
function Hash([string]$path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
$runtime=Get-Content -LiteralPath (Join-Path $evidence 'game/rapier-lock-review.json') -Raw | ConvertFrom-Json
$visual=Get-Content -LiteralPath (Join-Path $evidence 'visual-review.json') -Raw | ConvertFrom-Json
$migration=Get-Content -LiteralPath (Join-Path $evidence 'config-stage/report.json') -Raw | ConvertFrom-Json
$jar=Join-Path $project 'build/libs/fsrpg-neoforge-0.7.1.jar';$sha=Hash $jar
if(-not $runtime.passed -or $runtime.modJarSha256 -ne $sha -or $runtime.monitorAdapter -cne '\\.\DISPLAY5' -or $runtime.completedTick -ne 625){throw 'Exact release JAR has not passed the completed DISPLAY5 review'}
if(-not $visual.passed -or $visual.jarSha256 -ne $sha){throw 'Visual review differs from tested JAR'}
if(-not $migration.passed -or -not $migration.existingFieldsPreserved -or $migration.range -ne 10){throw 'Staged migration is not verified'}
if((Get-Content -LiteralPath (Join-Path $evidence 'build-final.log') -Raw) -notmatch 'BUILD SUCCESSFUL'){throw 'Final full build did not pass'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::OpenRead($jar)
try{if(@($archive.Entries | Where-Object {$_.FullName -match 'org/fsrpg/smoke|MonitorAgent|forging|ArtContent|art/combat_arena'}).Count){throw 'Excluded code or other-project material in release'}}finally{$archive.Dispose()}
$instance=(Resolve-Path -LiteralPath 'C:/Users/34003/AppData/Roaming/.minecraft/versions/1.21.11-NeoForge').Path
$versionText=Get-Content -LiteralPath (Join-Path $instance '1.21.11-NeoForge.json') -Raw
if(($versionText | ConvertFrom-Json).id -ne '1.21.11-NeoForge' -or -not $versionText.Contains('21.11.45')){throw 'Unexpected Minecraft/NeoForge target'}
if(-not (Get-Content -LiteralPath (Join-Path $instance 'logs/latest.log') -Raw).Contains((Join-Path $instance 'config\fsrpg'))){throw 'Actual game directory is not confirmed'}
if(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | Where-Object {$_.CommandLine -and ($_.CommandLine.Contains($instance) -or $_.CommandLine.Contains($instance.Replace('\','/')))}){throw 'Official instance is running; do not replace loaded JARs'}
$mods=(Resolve-Path -LiteralPath (Join-Path $instance 'mods')).Path
$dependency=Join-Path $mods 'fsvoxel-renderer-neoforge-0.6.0.jar'
if((Hash $dependency) -ne (Hash (Join-Path $project 'libs/fsvoxel-renderer-neoforge-0.6.0.jar'))){throw 'Required dependency differs from tested dependency'}
function IsFsrpg([string]$path){
    $zip=[IO.Compression.ZipFile]::OpenRead($path)
    try{$entry=$zip.GetEntry('META-INF/neoforge.mods.toml');if($null -eq $entry){return $false}
        $reader=[IO.StreamReader]::new($entry.Open());try{$text=$reader.ReadToEnd()}finally{$reader.Dispose()}
        return [regex]::IsMatch(($text -split '\[\[dependencies\.')[0],'(?m)^\s*modId\s*=\s*"fsrpg"')
    }finally{$zip.Dispose()}
}
$old=@(Get-ChildItem -LiteralPath $mods -File -Filter '*.jar' | Where-Object {IsFsrpg $_.FullName})
$installed=Join-Path $mods 'fsrpg-neoforge-0.7.1.jar'
if((Test-Path -LiteralPath $installed) -and $old.FullName -notcontains $installed){throw 'Release filename belongs to unrelated content'}
$config=Join-Path $instance 'config/fsrpg';$combat=Join-Path $config 'combat.json';$options=Join-Path $instance 'options.txt'
$stagedCombat=Join-Path $evidence 'config-stage/fsrpg/combat.json'
if((Hash $combat) -ne $migration.oldCombatSha256 -or (Hash $stagedCombat) -ne $migration.newCombatSha256){throw 'Current or staged configuration changed after validation'}
$originalOptions=[IO.File]::ReadAllBytes($options);$optionsText=[Text.Encoding]::UTF8.GetString($originalOptions)
$keyPattern='(?m)^key_key\.fsrpg\.guard:[^\r\n]*'
$matches=[regex]::Matches($optionsText,$keyPattern)
if($matches.Count -gt 1){throw 'Duplicate saved guard mapping'}
$updatedOptions=if($matches.Count -eq 1){[regex]::Replace($optionsText,$keyPattern,'key_key.fsrpg.guard:key.keyboard.c')}else{$optionsText+"`r`nkey_key.fsrpg.guard:key.keyboard.c`r`n"}
$preserved=@(Get-ChildItem -LiteralPath $mods -File | Where-Object {$old.FullName -notcontains $_.FullName} | ForEach-Object {@{path=$_.FullName;sha256=(Hash $_.FullName)}})
$preserved+=@(Get-ChildItem -LiteralPath $config -Recurse -File | Where-Object {$_.FullName -ne $combat} | ForEach-Object {@{path=$_.FullName;sha256=(Hash $_.FullName)}})
$client=Join-Path $instance 'config/fsrpg-client.toml';if(Test-Path -LiteralPath $client){$preserved+=@{path=$client;sha256=(Hash $client)}}
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss';$backup=[IO.Path]::GetFullPath((Join-Path $instance "fsrpg-backups/$stamp-before-rapier-lock-0.7.1"))
if(-not $backup.StartsWith($instance+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Backup path outside verified instance'}
New-Item -ItemType Directory -Path (Join-Path $backup 'mods') -Force | Out-Null
Copy-Item -LiteralPath $config -Destination (Join-Path $backup 'fsrpg') -Recurse
[IO.File]::WriteAllBytes((Join-Path $backup 'options.txt'),$originalOptions)
if(Test-Path -LiteralPath $client){Copy-Item -LiteralPath $client -Destination $backup}
$previous=@()
foreach($file in $old){
    if($file.DirectoryName -ne $mods -or $file.Extension -ne '.jar'){throw 'Old JAR outside verified mods directory'}
    $saved=Join-Path $backup "mods/$($file.Name)";Copy-Item -LiteralPath $file.FullName -Destination $saved
    if((Hash $saved) -ne (Hash $file.FullName)){throw 'Old JAR backup mismatch'}
    $previous+=@{path=$file.FullName;backup=$saved;sha256=(Hash $saved)}
}
try{
    foreach($file in $previous){Remove-Item -LiteralPath $file.path}
    Copy-Item -LiteralPath $jar -Destination $installed
    Copy-Item -LiteralPath $stagedCombat -Destination $combat -Force
    $canonicalBackup=Join-Path $config 'rapier-target-lock-backup-v1'
    if(-not(Test-Path -LiteralPath $canonicalBackup)){Copy-Item -LiteralPath (Join-Path $evidence 'config-stage/fsrpg/rapier-target-lock-backup-v1') -Destination $canonicalBackup -Recurse}
    [IO.File]::WriteAllBytes($options,[Text.Encoding]::UTF8.GetBytes($updatedOptions))
    if((Hash $installed) -ne $sha -or (Hash $combat) -ne $migration.newCombatSha256){throw 'Installed artifact differs from tested staging'}
    if([IO.File]::ReadAllText($options) -ne $updatedOptions){throw 'Saved key mapping differs'}
    foreach($file in $preserved){if((Hash $file.path) -ne $file.sha256){throw "Preserved file changed: $($file.path)"}}
}catch{
    if(Test-Path -LiteralPath $installed){Remove-Item -LiteralPath $installed}
    foreach($file in $previous){Copy-Item -LiteralPath $file.backup -Destination $file.path}
    Copy-Item -LiteralPath (Join-Path $backup 'fsrpg/combat.json') -Destination $combat -Force
    [IO.File]::WriteAllBytes($options,$originalOptions)
    throw
}
$record=[ordered]@{version='0.7.1';installedJar=$installed;jarSha256=$sha;testedJarSha256=$runtime.modJarSha256;backup=$backup;previousMods=$previous;guardKey='C';optionsBeforeSha256=(Hash (Join-Path $backup 'options.txt'));optionsAfterSha256=(Hash $options);oldCombatSha256=$migration.oldCombatSha256;newCombatSha256=$migration.newCombatSha256;existingConfigFieldsPreserved=$true;preserved=$preserved;worldsModified=$false}
$record | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $evidence 'deployment.json') -Encoding utf8
$record | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $backup 'deployment.json') -Encoding utf8
Write-Output "Installed $installed; SHA-256 $sha; backup $backup; guard C; range 10; existing tuning preserved"
