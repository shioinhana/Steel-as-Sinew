param([switch]$VerifyOnly)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$project=(Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$instance='C:\Users\34003\AppData\Roaming\.minecraft\versions\1.21.11-NeoForge'
$minecraft=Split-Path -Parent (Split-Path -Parent $instance)
$evidence=Join-Path $project 'verification\quest-system-20261101'
$stage=Join-Path $evidence 'fsrpg'
$config=Join-Path $instance 'config\fsrpg'
$mods=Join-Path $instance 'mods'
$jar=Join-Path $project 'build\libs\fsrpg-neoforge-0.13.0.jar'
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Inside([string]$Path,[string]$Root) {
    $full=[IO.Path]::GetFullPath($Path); $base=[IO.Path]::GetFullPath($Root).TrimEnd('\')
    if(-not $full.StartsWith($base+'\',[StringComparison]::OrdinalIgnoreCase)){throw "Path escaped root: $full"}
    $cursor=$full
    while($cursor){
        if(Test-Path -LiteralPath $cursor){if((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw "Reparse point: $cursor"}}
        $cursor=[IO.Path]::GetDirectoryName($cursor)
    }
    return $full
}
function Tree([string]$Root) {
    $result=[ordered]@{}
    foreach($file in Get-ChildItem -LiteralPath $Root -File -Recurse -Force){
        $path=Inside $file.FullName $Root
        $result[$path.Substring($Root.TrimEnd('\').Length+1).Replace('\','/')]=Hash $path
    }
    return $result
}
function AssertTree($Expected,[string]$Root) {
    $actual=Tree $Root
    if($Expected.Count -ne $actual.Count){throw "File count changed under $Root"}
    foreach($key in $Expected.Keys){if(-not $actual.Contains($key) -or $actual[$key] -cne $Expected[$key]){throw "Hash changed: $Root/$key"}}
}
function Versions([string]$Id) {
    if($Id -notmatch '^[a-zA-Z0-9._-]+$'){throw 'Invalid version inheritance'}
    $path=Inside (Join-Path $minecraft "versions\$Id\$Id.json") $minecraft
    $json=Get-Content -LiteralPath $path -Raw|ConvertFrom-Json
    if($json.PSObject.Properties.Name -contains 'inheritsFrom'){Versions $json.inheritsFrom}
    $json
}
function NoGame {
    foreach($process in Get-CimInstance Win32_Process){
        if($process.Name -match '^javaw?\.exe$' -and $process.CommandLine -and
          ($process.CommandLine.Contains($instance) -or $process.CommandLine.Contains('1.21.11-NeoForge'))){throw "Game PID $($process.ProcessId) is using the target instance"}
    }
}
NoGame
$metadata=@(Versions '1.21.11-NeoForge')
$argsList=@(foreach($version in $metadata){if($version.PSObject.Properties.Name -contains 'arguments'){$version.arguments.game}})
foreach($pair in @(@('--fml.mcVersion','1.21.11'),@('--fml.neoForgeVersion','21.11.45'))){
    $index=[Array]::IndexOf($argsList,$pair[0]);if($index -lt 0 -or $argsList[$index+1] -cne $pair[1]){throw "Version mismatch: $($pair[0])"}
}
$latest=Get-Content -LiteralPath (Join-Path $instance 'logs\latest.log') -Raw
$directoryMatches=[regex]::Matches($latest,'(?m)Game directory:\s*([^\r\n]+)')
if($directoryMatches.Count -lt 1 -or [IO.Path]::GetFullPath($directoryMatches[$directoryMatches.Count-1].Groups[1].Value.Trim().Trim('"')).TrimEnd('\') -ine $instance){throw 'Actual game directory is not verified'}
$sha=Hash $jar
$report=Get-Content -LiteralPath (Join-Path $evidence 'game-final\quest-system-review.json') -Raw|ConvertFrom-Json
if(-not $report.passed -or $report.modJarSha256 -cne $sha -or $report.monitorAdapter -cne '\\.\DISPLAY5'){throw 'Same-JAR DISPLAY5 acceptance missing'}
$staging=Get-Content -LiteralPath (Join-Path $evidence 'stage-config.json') -Raw|ConvertFrom-Json -AsHashtable
if(-not $staging.passed -or $staging.modJarSha256 -cne $sha -or -not $staging.originalTasksPreserved -or -not $staging.secondReloadIdempotent){throw 'Configuration stage is not verified'}
$before=Tree $config
AssertTree $staging.beforeSha256 $config
AssertTree $staging.afterSha256 $stage
$modsBefore=Tree $mods
$old=@(Get-ChildItem -LiteralPath $mods -Filter 'fsrpg-neoforge-*.jar' -File)
if($old.Count -ne 1){throw 'Expected one installed FSRPG JAR'}
$installed=Inside (Join-Path $mods 'fsrpg-neoforge-0.13.0.jar') $mods
if(Test-Path -LiteralPath $installed){throw 'New version already installed'}
$options=Join-Path $instance 'options.txt';$optionsSha=Hash $options
if($VerifyOnly){Write-Output "Verified 0.13.0 $sha, 1.21.11/21.11.45 actual game directory, same-JAR DISPLAY5 smoke and configuration preservation. No files changed.";return}
$stamp=[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTimeOffset]::UtcNow,'China Standard Time').ToString('yyyyMMdd-HHmmss-fff')
$backup=Inside (Join-Path $instance "fsrpg-backups\$stamp-before-0.13.0") $instance
New-Item -ItemType Directory -Path (Join-Path $backup 'mods') -Force|Out-Null
Copy-Item -LiteralPath $config -Destination (Join-Path $backup 'config') -Recurse
Copy-Item -LiteralPath $old[0].FullName -Destination (Join-Path $backup ('mods\'+$old[0].Name))
Copy-Item -LiteralPath $options -Destination (Join-Path $backup 'options.txt')
AssertTree $before (Join-Path $backup 'config')
if((Hash (Join-Path $backup ('mods\'+$old[0].Name))) -cne $modsBefore[$old[0].Name]){throw 'Old JAR backup hash mismatch'}
$changed=[Collections.Generic.List[string]]::new()
try {
    NoGame
    foreach($key in $staging.afterSha256.Keys){
        if($before.Contains($key) -and $before[$key] -ceq $staging.afterSha256[$key]){continue}
        $target=Inside (Join-Path $config $key) $config
        New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force|Out-Null
        $changed.Add($key); Copy-Item -LiteralPath (Join-Path $stage $key) -Destination $target -Force
        if((Hash $target) -cne $staging.afterSha256[$key]){throw "Config copy mismatch: $key"}
    }
    Copy-Item -LiteralPath $jar -Destination $installed
    if((Hash $installed) -cne $sha){throw 'Installed JAR differs from tested JAR'}
    $oldPath=Inside $old[0].FullName $mods
    Remove-Item -LiteralPath $oldPath
    $expected=[ordered]@{};foreach($key in $modsBefore.Keys){if($key -cne $old[0].Name){$expected[$key]=$modsBefore[$key]}};$expected['fsrpg-neoforge-0.13.0.jar']=$sha
    AssertTree $expected $mods;AssertTree $staging.afterSha256 $config
    if((Hash $options) -cne $optionsSha){throw 'options.txt changed'}
    $record=[ordered]@{version='0.13.0';networkProtocol=14;installedJar=$installed;jarSha256=$sha;testedJar=$jar;backup=$backup;configChanges=@($changed);originalTasksPreserved=$true;otherModsPreserved=$true;optionsPreserved=$true;worldsModified=$false;monitorAdapter='\\.\DISPLAY5';minecraftVersion='1.21.11';neoForgeVersion='21.11.45';actualGameDirectory=$instance;deployedAt=$stamp}
    $record|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $evidence 'deployment.json') -Encoding utf8
    $record|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $backup 'deployment.json') -Encoding utf8
    Write-Output "Installed $installed SHA-256 $sha; backup $backup"
} catch {
    $failure=$_
    if(Test-Path -LiteralPath $installed){Remove-Item -LiteralPath $installed}
    Copy-Item -LiteralPath (Join-Path $backup ('mods\'+$old[0].Name)) -Destination $old[0].FullName -Force
    foreach($key in $changed){$target=Inside (Join-Path $config $key) $config;if($before.Contains($key)){Copy-Item -LiteralPath (Join-Path $backup "config\$key") -Destination $target -Force}else{if(Test-Path -LiteralPath $target){Remove-Item -LiteralPath $target}}}
    AssertTree $modsBefore $mods;AssertTree $before $config
    throw "Deployment rolled back: $failure"
}
