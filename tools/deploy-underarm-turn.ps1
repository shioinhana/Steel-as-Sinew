param([string]$EvidenceDirectory='verification/spear-underarm-turn-20261014')
$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$evidence=[IO.Path]::GetFullPath((Join-Path $project $EvidenceDirectory))
$review=Get-Content -LiteralPath (Join-Path $evidence 'final-report.json') -Raw | ConvertFrom-Json
if(-not $review.passed){throw 'Final underarm review is not passed'}
function Hash([string]$path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
$jar=Join-Path $project 'build/libs/fsrpg-neoforge-0.5.4.jar';$sha=Hash $jar
if($sha -ne $review.jarSha256){throw 'Release JAR differs from tested final review'}
$runtime=Get-Content -LiteralPath $review.gameReport -Raw | ConvertFrom-Json
if(-not $runtime.passed -or $runtime.modJarSha256 -ne $sha -or $runtime.monitorAdapter -ne '\\.\DISPLAY5'){throw 'Exact JAR was not accepted on DISPLAY5'}
foreach($test in @($review.poseReport,$review.motionReport)){
    $result=Get-Content -LiteralPath $test -Raw | ConvertFrom-Json
    if($result.failures -ne 0){throw 'Required pose/continuity regression failed'}
}
$contact=Get-Content -LiteralPath $review.contactReport -Raw | ConvertFrom-Json
if(-not $contact.passed){throw 'Actual complete-pole contact audit failed'}
$instance=(Resolve-Path -LiteralPath 'C:/Users/34003/AppData/Roaming/.minecraft/versions/1.21.11-NeoForge').Path
$versionText=Get-Content -LiteralPath (Join-Path $instance '1.21.11-NeoForge.json') -Raw
$metadata=$versionText | ConvertFrom-Json
if($metadata.id -ne '1.21.11-NeoForge' -or -not $versionText.Contains('21.11.45')){throw 'Target Minecraft/NeoForge version differs'}
$log=Get-Content -LiteralPath (Join-Path $instance 'logs/latest.log') -Raw
if(-not $log.Contains((Join-Path $instance 'config\fsrpg'))){throw 'Actual game directory is not confirmed by the client log'}
if(Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" | Where-Object {$_.CommandLine -and ($_.CommandLine.Contains($instance) -or $_.CommandLine.Contains($instance.Replace('\','/')))}){throw 'Official instance is running; do not replace loaded JARs'}
$mods=(Resolve-Path -LiteralPath (Join-Path $instance 'mods')).Path
Add-Type -AssemblyName System.IO.Compression.FileSystem
function IsFsrpg([string]$path){
    $zip=[IO.Compression.ZipFile]::OpenRead($path)
    try{$entry=$zip.GetEntry('META-INF/neoforge.mods.toml');if($null -eq $entry){return $false}
        $reader=[IO.StreamReader]::new($entry.Open());try{$text=$reader.ReadToEnd()}finally{$reader.Dispose()}
        return [regex]::IsMatch(($text -split '\[\[dependencies\.')[0],'(?m)^\s*modId\s*=\s*"fsrpg"')
    }finally{$zip.Dispose()}
}
$old=@(Get-ChildItem -LiteralPath $mods -File -Filter '*.jar' | Where-Object {IsFsrpg $_.FullName})
$installed=Join-Path $mods 'fsrpg-neoforge-0.5.4.jar'
if((Test-Path -LiteralPath $installed) -and $old.FullName -notcontains $installed){throw 'Release filename is occupied by an unrelated file'}
$dependency=Join-Path $mods 'fsvoxel-renderer-neoforge-0.6.0.jar'
if((Hash $dependency) -ne (Hash (Join-Path $project 'libs/fsvoxel-renderer-neoforge-0.6.0.jar'))){throw 'Required dependency differs from the tested build'}
$config=Join-Path $instance 'config/fsrpg';$client=Join-Path $instance 'config/fsrpg-client.toml'
$preserved=@(Get-ChildItem -LiteralPath $mods -File | Where-Object {$old.FullName -notcontains $_.FullName} | ForEach-Object {@{path=$_.FullName;sha256=(Hash $_.FullName)}})
$preserved+=@(Get-ChildItem -LiteralPath $config -Recurse -File | ForEach-Object {@{path=$_.FullName;sha256=(Hash $_.FullName)}})
$original=if(Test-Path -LiteralPath $client){[IO.File]::ReadAllText($client)}else{''}
$defaults=[ordered]@{spearCarryDownCm=-12.0;spearCarryBackCm=22.0;spearCarrySideCm=50.0;spearPelvisResponseTicks=3.2;spearTurnStepTicks=6.0;spearTurnStepDegrees=28.0;spearTurnStepLiftCm=5.625}
$added=@();$updated=$original
foreach($name in $defaults.Keys){if(-not [regex]::IsMatch($original,'(?m)^\s*'+[regex]::Escape($name)+'\s*=')){$added+=$name;$number=$defaults[$name].ToString([Globalization.CultureInfo]::InvariantCulture);if(-not $number.Contains('.')){$number+='.0'};$updated+="`r`n$name = $number`r`n"}}
$stamp=[TimeZoneInfo]::ConvertTimeFromUtc([DateTime]::UtcNow,[TimeZoneInfo]::FindSystemTimeZoneById('China Standard Time')).ToString('yyyyMMdd-HHmmss')
$backup=[IO.Path]::GetFullPath((Join-Path $instance "fsrpg-backups/$stamp-underarm-before-0.5.4"))
if(-not $backup.StartsWith($instance+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Backup escaped named instance'}
New-Item -ItemType Directory -Path (Join-Path $backup 'mods') -Force | Out-Null
Copy-Item -LiteralPath $config -Destination (Join-Path $backup 'fsrpg') -Recurse
if(Test-Path -LiteralPath $client){Copy-Item -LiteralPath $client -Destination (Join-Path $backup 'fsrpg-client.toml')}
$previous=@()
foreach($file in $old){$saved=Join-Path $backup "mods/$($file.Name)";Copy-Item -LiteralPath $file.FullName -Destination $saved;$before=Hash $file.FullName;if((Hash $saved) -ne $before){throw 'Old JAR backup mismatch'};$previous+=@{path=$file.FullName;backup=$saved;sha256=$before}}
try{
    foreach($file in $old){Remove-Item -LiteralPath $file.FullName}
    Copy-Item -LiteralPath $jar -Destination $installed
    if((Hash $installed) -ne $sha){throw 'Installed JAR differs from tested build'}
    if($added.Count -gt 0){[IO.File]::WriteAllText($client,$updated,[Text.UTF8Encoding]::new($false))}
    foreach($file in $preserved){if((Hash $file.path) -ne $file.sha256){throw "Preserved file changed: $($file.path)"}}
}catch{
    if(Test-Path -LiteralPath $installed){Remove-Item -LiteralPath $installed}
    foreach($file in $previous){Copy-Item -LiteralPath $file.backup -Destination $file.path}
    if(Test-Path -LiteralPath (Join-Path $backup 'fsrpg-client.toml')){Copy-Item -LiteralPath (Join-Path $backup 'fsrpg-client.toml') -Destination $client -Force}
    throw
}
$record=[ordered]@{version='0.5.4';instance=$instance;installedJar=$installed;jarSha256=$sha;testedJarSha256=$runtime.modJarSha256;dependencySha256=(Hash $dependency);backup=$backup;previousMods=$previous;addedClientFields=$added;existingClientValuesPreserved=$updated.StartsWith($original);preserved=$preserved;worldsModified=$false;gameReport=$review.gameReport;contactReport=$review.contactReport;finalReportSha256=(Hash (Join-Path $evidence 'final-report.json'))}
$record | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $evidence 'deployment.json') -Encoding utf8
$record | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $backup 'deployment.json') -Encoding utf8
Write-Output "Installed $installed; SHA-256 $sha"
Write-Output "Backup $backup; existing settings, models, resource costs, other Mods and worlds preserved"
