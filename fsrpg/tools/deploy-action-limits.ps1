param(
    [string]$Instance='C:/Users/34003/AppData/Roaming/.minecraft/versions/1.21.11-NeoForge',
    [string]$Evidence='verification/action-limits-20261013'
)
$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$instanceRoot=(Resolve-Path -LiteralPath $Instance).Path
$evidenceRoot=[IO.Path]::GetFullPath((Join-Path $project $Evidence))
function Hash([string]$path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
$metadata=Get-Content -LiteralPath (Join-Path $instanceRoot '1.21.11-NeoForge.json') -Raw | ConvertFrom-Json
$arguments=[object[]]$metadata.arguments.game
$neo=[array]::IndexOf($arguments,'--fml.neoForgeVersion');$mc=[array]::IndexOf($arguments,'--fml.mcVersion')
if($metadata.id -ne '1.21.11-NeoForge' -or $neo -lt 0 -or $mc -lt 0 -or $arguments[$neo+1] -ne '21.11.45' -or $arguments[$mc+1] -ne '1.21.11'){throw 'Unexpected target instance version'}
$actualLog=Get-Content -LiteralPath (Join-Path $instanceRoot 'logs/latest.log') -Raw
if(-not $actualLog.Contains((Join-Path $instanceRoot 'config\fsrpg'))){throw 'Actual game directory is not confirmed by its client log'}
if(Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" | Where-Object {$_.CommandLine -and ($_.CommandLine.Contains($instanceRoot) -or $_.CommandLine.Contains($instanceRoot.Replace('\','/')))} ){throw 'Target instance is running; loaded files cannot be replaced'}
$version=([regex]::Match((Get-Content -LiteralPath (Join-Path $project 'gradle.properties') -Raw),'(?m)^mod_version=(.+)$')).Groups[1].Value.Trim()
$jarName="fsrpg-neoforge-$version.jar";$jar=Join-Path $project "build/libs/$jarName";$sha=Hash $jar
$report=Get-Content -LiteralPath (Join-Path $evidenceRoot 'final-report.json') -Raw | ConvertFrom-Json
$runtime=Get-Content -LiteralPath $report.gameReport -Raw | ConvertFrom-Json
$frames=Get-Content -LiteralPath $report.frameReport -Raw | ConvertFrom-Json
$stage=Get-Content -LiteralPath (Join-Path $evidenceRoot 'stamina-stage.json') -Raw | ConvertFrom-Json
if(-not $report.passed -or $report.jarSha256 -ne $sha -or -not $runtime.passed -or $runtime.modJarSha256 -ne $sha -or $runtime.monitorAdapter -ne '\\.\DISPLAY5' -or -not $frames.passed -or $frames.jarSha256 -ne $sha){throw 'Final focused report must match exact DISPLAY5 tested JAR'}
if(-not $stage.passed -or -not $stage.secondReloadIdempotent -or $stage.staminaCostMultiplier -ne .2 -or -not $stage.allExistingStaminaCostsScaledExactlyOnce -or -not $stage.unrelatedConfigValuesPreserved){throw 'Staged production cost migration failed'}
$config=Join-Path $instanceRoot 'config/fsrpg'
$definitionFiles=@('combat.json','skills.json','styles.json','archery.json','quests.json','weapons.json','collections.json','skill_trees.json')
if(@($stage.beforeSha256.PSObject.Properties).Count -ne $definitionFiles.Count -or @($stage.afterSha256.PSObject.Properties).Count -ne $definitionFiles.Count){throw 'Staging config hash catalogs differ from the expected definitions'}
$stageDirectory=(Resolve-Path -LiteralPath $stage.stageDirectory).Path
$expectedConfigHashes=@{}
foreach($name in $definitionFiles){
    $before=$stage.beforeSha256.PSObject.Properties[$name];$after=$stage.afterSha256.PSObject.Properties[$name]
    if($null -eq $before -or $null -eq $after){throw "Staging has no complete config hashes: $name"}
    if((Hash (Join-Path $config $name)) -ne $before.Value){throw "Official config changed since staging: $name"}
    if((Hash (Join-Path $stageDirectory $name)) -ne $after.Value){throw "Staged config changed after validation: $name"}
    $expectedConfigHashes[$name]=$after.Value
}
$changed=@($stage.changedFiles)
foreach($name in $changed){if($name -notin @('combat.json','skills.json','styles.json','archery.json')){throw "Staging changed unrelated config $name"}}
if(@($changed | Select-Object -Unique).Count -ne $changed.Count){throw 'Staging repeats a changed config file'}
foreach($name in $definitionFiles){
    $different=$stage.beforeSha256.PSObject.Properties[$name].Value -ne $stage.afterSha256.PSObject.Properties[$name].Value
    if($different -ne ($changed -contains $name)){throw "Changed config list differs from validated hashes: $name"}
}
$changed+='stamina-cost-migration-v1.json'
$marker=Join-Path $stageDirectory 'stamina-cost-migration-v1.json'
$migration=Get-Content -LiteralPath $marker -Raw | ConvertFrom-Json
if($migration.schemaVersion -ne 1 -or $migration.releaseVersion -ne $version -or $migration.staminaCostMultiplier -ne .2 -or -not $migration.customFieldsPreserved -or @($migration.changedFiles).Count -ne ($changed.Count - 1)){throw 'Staged migration marker differs from validated upgrade'}
if(@($migration.changedFiles | Select-Object -Unique).Count -ne @($migration.changedFiles).Count){throw 'Staged migration marker repeats a changed file'}
foreach($name in $migration.changedFiles){if($changed -notcontains $name){throw 'Staged migration marker changed-file list differs'}}
$expectedConfigHashes['stamina-cost-migration-v1.json']=Hash $marker
$migrationBackup=Join-Path $stageDirectory 'stamina-cost-backup-v1'
if(-not (Test-Path -LiteralPath $migrationBackup -PathType Container)){throw 'Staged original-cost backup is missing'}
$expectedMigrationBackupHashes=@{}
foreach($name in @('combat.json','skills.json','styles.json','archery.json')){
    $snapshot=Join-Path $migrationBackup $name
    if((Hash $snapshot) -ne $stage.beforeSha256.PSObject.Properties[$name].Value){throw "Staged original-cost backup changed: $name"}
    $expectedMigrationBackupHashes[$name]=Hash $snapshot
}
$sourceFiles=Join-Path $migrationBackup 'source-files.json'
$snapshotNames=@(Get-Content -LiteralPath $sourceFiles -Raw | ConvertFrom-Json)
if($snapshotNames.Count -ne 4 -or @($snapshotNames | Select-Object -Unique).Count -ne 4){throw 'Staged original-cost manifest is incomplete'}
foreach($name in $snapshotNames){if($expectedMigrationBackupHashes.Keys -notcontains $name){throw 'Staged original-cost manifest contains an unrelated file'}}
$expectedMigrationBackupHashes['source-files.json']=Hash $sourceFiles
if(@(Get-ChildItem -LiteralPath $migrationBackup).Count -ne $expectedMigrationBackupHashes.Count){throw 'Staged original-cost backup contains unrelated files'}
$installedMigrationBackup=Join-Path $config 'stamina-cost-backup-v1'
if(Test-Path -LiteralPath $installedMigrationBackup){throw 'Migration backup already exists; reconcile interrupted upgrade before deployment'}
$mods=(Resolve-Path -LiteralPath (Join-Path $instanceRoot 'mods')).Path
Add-Type -AssemblyName System.IO.Compression.FileSystem
function IsFsrpg([string]$path){
    $zip=[IO.Compression.ZipFile]::OpenRead($path)
    try{$entry=$zip.GetEntry('META-INF/neoforge.mods.toml');if($null -eq $entry){return $false}
        $reader=[IO.StreamReader]::new($entry.Open());try{$text=$reader.ReadToEnd()}finally{$reader.Dispose()}
        return [regex]::IsMatch(($text -split '\[\[dependencies\.')[0],'(?m)^\s*modId\s*=\s*"fsrpg"')
    }finally{$zip.Dispose()}
}
$oldMods=@(Get-ChildItem -LiteralPath $mods -File -Filter '*.jar' | Where-Object {IsFsrpg $_.FullName})
$dependency=Join-Path $project 'libs/fsvoxel-renderer-neoforge-0.6.0.jar'
$installedDependency=Join-Path $mods 'fsvoxel-renderer-neoforge-0.6.0.jar'
$dependencySha=Hash $dependency
if(-not (Test-Path -LiteralPath $installedDependency) -or $dependencySha -ne (Hash $installedDependency)){throw 'Required FSVOXEL dependency needs a separate backed-up replacement'}
$installed=Join-Path $mods $jarName
if((Test-Path -LiteralPath $installed) -and $oldMods.FullName -notcontains $installed){throw 'Release filename is occupied by an unrelated file'}
$preserved=@(Get-ChildItem -LiteralPath $mods -File | Where-Object {$oldMods.FullName -notcontains $_.FullName} | ForEach-Object {@{path=$_.FullName;sha256=(Hash $_.FullName)}})
$preserved+=@(Get-ChildItem -LiteralPath $config -Recurse -File | Where-Object {$_.DirectoryName -ne $config -or $changed -notcontains $_.Name} | ForEach-Object {@{path=$_.FullName;sha256=(Hash $_.FullName)}})
$client=Join-Path $instanceRoot 'config/fsrpg-client.toml'
if(Test-Path -LiteralPath $client){$preserved+=@{path=$client;sha256=(Hash $client)}}
$stamp=[TimeZoneInfo]::ConvertTimeFromUtc([DateTime]::UtcNow,[TimeZoneInfo]::FindSystemTimeZoneById('China Standard Time')).ToString('yyyyMMdd-HHmmss')
$backup=[IO.Path]::GetFullPath((Join-Path $instanceRoot "fsrpg-backups/$stamp-action-before-$version"))
if(-not $backup.StartsWith($instanceRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Backup escaped target instance'}
New-Item -ItemType Directory -Path (Join-Path $backup 'mods') -Force | Out-Null
Copy-Item -LiteralPath $config -Destination (Join-Path $backup 'fsrpg') -Recurse
if(Test-Path -LiteralPath $client){Copy-Item -LiteralPath $client -Destination (Join-Path $backup 'fsrpg-client.toml')}
$previous=@()
foreach($old in $oldMods){$oldSha=Hash $old.FullName;$target=Join-Path $backup "mods/$($old.Name)";Copy-Item -LiteralPath $old.FullName -Destination $target;if((Hash $target) -ne $oldSha){throw 'Old Mod backup differs'};$previous+=@{path=$old.FullName;sha256=$oldSha;backup=$target}}
foreach($field in $stage.beforeSha256.PSObject.Properties){if((Hash (Join-Path $backup "fsrpg/$($field.Name)")) -ne $field.Value){throw 'Config backup differs from staging source'}}
try{
    foreach($old in $oldMods){Remove-Item -LiteralPath $old.FullName}
    Copy-Item -LiteralPath $jar -Destination $installed
    if((Hash $installed) -ne $sha){throw 'Installed JAR differs from tested build'}
    foreach($name in $changed){$source=Join-Path $stageDirectory $name;$destination=Join-Path $config $name;Copy-Item -LiteralPath $source -Destination $destination -Force;if((Hash $destination) -ne $expectedConfigHashes[$name]){throw "Migrated config differs from validated staging $name"}}
    Copy-Item -LiteralPath $migrationBackup -Destination $installedMigrationBackup -Recurse
    foreach($name in $expectedMigrationBackupHashes.Keys){if($expectedMigrationBackupHashes[$name] -ne (Hash (Join-Path $installedMigrationBackup $name))){throw 'Installed original-cost backup differs'}}
    foreach($file in $preserved){if((Hash $file.path) -ne $file.sha256){throw "Preserved file changed: $($file.path)"}}
}catch{
    if(Test-Path -LiteralPath $installed){Remove-Item -LiteralPath $installed}
    foreach($old in $previous){Copy-Item -LiteralPath $old.backup -Destination $old.path}
    foreach($name in $changed){$prior=Join-Path $backup "fsrpg/$name";$destination=Join-Path $config $name;if(Test-Path -LiteralPath $prior){Copy-Item -LiteralPath $prior -Destination $destination -Force}else{if(Test-Path -LiteralPath $destination){Remove-Item -LiteralPath $destination}}}
    if(Test-Path -LiteralPath $installedMigrationBackup){
        if([IO.Path]::GetFullPath($installedMigrationBackup) -ne [IO.Path]::GetFullPath((Join-Path $config 'stamina-cost-backup-v1'))){throw 'Rollback path escaped migration backup'}
        Get-ChildItem -LiteralPath $installedMigrationBackup -File | ForEach-Object {Remove-Item -LiteralPath $_.FullName}
        Remove-Item -LiteralPath $installedMigrationBackup
    }
    throw
}
$record=[ordered]@{version=$version;installedJar=$installed;jarSha256=$sha;testedJarSha256=$runtime.modJarSha256;installedDependency=$installedDependency;dependencySha256=$dependencySha;instance=$instanceRoot;backup=$backup;previousMods=$previous;changedConfigFiles=$changed;staminaMultiplier=.2;preserved=$preserved;worldsModified=$false;monitor=$runtime.monitorAdapter;gameReport=$report.gameReport;frameReport=$report.frameReport;finalReportSha256=(Hash (Join-Path $evidenceRoot 'final-report.json'))}
$record | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $evidenceRoot 'deployment.json') -Encoding utf8
$record | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $backup 'deployment.json') -Encoding utf8
Write-Output "Installed $installed; SHA-256 $sha"
Write-Output "Backed up $backup; migrated only action stamina costs once; other files retained"
