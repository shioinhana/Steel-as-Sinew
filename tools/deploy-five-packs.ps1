$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$evidence=Join-Path $project 'verification/five-packs-20261016'
function Hash([string]$path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
$review=Get-Content -LiteralPath (Join-Path $evidence 'game/art-review.json') -Raw | ConvertFrom-Json
$visual=Get-Content -LiteralPath (Join-Path $evidence 'visual-review.json') -Raw | ConvertFrom-Json
$jar=Join-Path $project 'build/libs/fsrpg-neoforge-0.7.0.jar';$sha=Hash $jar
if(-not $review.passed -or $review.completedViews -ne $review.expectedViews -or $review.jarSha256 -ne $sha -or $review.monitorAdapter -cne '\\.\DISPLAY5'){throw 'Exact JAR has not passed DISPLAY5 runtime review'}
if(-not $visual.passed -or $visual.jarSha256 -ne $sha){throw 'Visual review does not match the tested JAR'}
if((Get-Content -LiteralPath (Join-Path $evidence 'build.log') -Raw) -notmatch 'BUILD SUCCESSFUL'){throw 'Full clean build did not pass'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::OpenRead($jar)
try{
    if(@($zip.Entries | Where-Object {$_.FullName -match 'forging|ArtContent|org/fsrpg/smoke|MonitorAgent|art/combat_arena|sounds/art_'}).Count){throw 'Excluded project resources or development classes in release'}
    if(@($zip.Entries | Where-Object {$_.FullName -like 'assets/fsrpg/art/creatures/*.json'}).Count -ne 30){throw 'Wrong creature model count'}
}finally{$zip.Dispose()}
$instance=(Resolve-Path -LiteralPath 'C:/Users/34003/AppData/Roaming/.minecraft/versions/1.21.11-NeoForge').Path
$metadata=Get-Content -LiteralPath (Join-Path $instance '1.21.11-NeoForge.json') -Raw
if(($metadata | ConvertFrom-Json).id -ne '1.21.11-NeoForge' -or -not $metadata.Contains('21.11.45')){throw 'Target version differs'}
$runtimeLog=Get-Content -LiteralPath (Join-Path $instance 'logs/latest.log') -Raw
if(-not $runtimeLog.Contains((Join-Path $instance 'config\fsrpg'))){throw 'Actual game directory has not been verified'}
if(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | Where-Object {$_.CommandLine -and ($_.CommandLine.Contains($instance) -or $_.CommandLine.Contains($instance.Replace('\','/')))}){throw 'Official instance is running; do not replace loaded JARs'}
$mods=(Resolve-Path -LiteralPath (Join-Path $instance 'mods')).Path
$dependency=Join-Path $mods 'fsvoxel-renderer-neoforge-0.6.0.jar'
if((Hash $dependency) -ne (Hash (Join-Path $project 'libs/fsvoxel-renderer-neoforge-0.6.0.jar'))){throw 'FSVOXEL dependency differs from tested dependency'}
function IsFsrpg([string]$path){
    $archive=[IO.Compression.ZipFile]::OpenRead($path)
    try{$entry=$archive.GetEntry('META-INF/neoforge.mods.toml');if($null -eq $entry){return $false}
        $reader=[IO.StreamReader]::new($entry.Open());try{$text=$reader.ReadToEnd()}finally{$reader.Dispose()}
        return [regex]::IsMatch(($text -split '\[\[dependencies\.')[0],'(?m)^\s*modId\s*=\s*"fsrpg"')
    }finally{$archive.Dispose()}
}
$old=@(Get-ChildItem -LiteralPath $mods -File -Filter '*.jar' | Where-Object {IsFsrpg $_.FullName})
$installed=Join-Path $mods 'fsrpg-neoforge-0.7.0.jar'
if((Test-Path -LiteralPath $installed) -and $old.FullName -notcontains $installed){throw 'Release filename belongs to an unrelated file'}
$preserved=@(Get-ChildItem -LiteralPath $mods -File | Where-Object {$old.FullName -notcontains $_.FullName} | ForEach-Object {@{path=$_.FullName;sha256=(Hash $_.FullName)}})
$config=Join-Path $instance 'config/fsrpg';$client=Join-Path $instance 'config/fsrpg-client.toml'
if(Test-Path -LiteralPath $config){$preserved+=@(Get-ChildItem -LiteralPath $config -File -Recurse | ForEach-Object {@{path=$_.FullName;sha256=(Hash $_.FullName)}})}
if(Test-Path -LiteralPath $client){$preserved+=@{path=$client;sha256=(Hash $client)}}
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss';$backup=[IO.Path]::GetFullPath((Join-Path $instance "fsrpg-backups/$stamp-before-five-packs-0.7.0"))
if(-not $backup.StartsWith($instance+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Backup outside named instance'}
New-Item -ItemType Directory -Path (Join-Path $backup 'mods') -Force | Out-Null
if(Test-Path -LiteralPath $config){Copy-Item -LiteralPath $config -Destination (Join-Path $backup 'fsrpg') -Recurse}
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
    if((Hash $installed) -ne $sha){throw 'Installed JAR differs from tested JAR'}
    foreach($file in $preserved){if((Hash $file.path) -ne $file.sha256){throw "Preserved file changed: $($file.path)"}}
}catch{
    if(Test-Path -LiteralPath $installed){Remove-Item -LiteralPath $installed}
    foreach($file in $previous){Copy-Item -LiteralPath $file.backup -Destination $file.path}
    throw
}
$record=[ordered]@{version='0.7.0';installedJar=$installed;jarSha256=$sha;testedJarSha256=$review.jarSha256;backup=$backup;previousMods=$previous;preserved=$preserved;worldsModified=$false;scope='Only five creature/HUD packs; other-project forging, sounds and platform excluded'}
$record | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $evidence 'deployment.json') -Encoding utf8
$record | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $backup 'deployment.json') -Encoding utf8
Write-Output "Installed $installed; SHA-256 $sha; backup $backup"
