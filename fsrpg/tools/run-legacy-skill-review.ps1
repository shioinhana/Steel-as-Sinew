param(
    [Parameter(Mandatory=$true)][string]$World,
    [string]$EvidenceDirectory='verification/legacy-skills-20261012-001758/game'
)
$ErrorActionPreference='Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
if(-not $World.StartsWith('FSRPG Legacy Skill Review ')){throw 'Expected an explicitly named world copy'}
$worldRoot=Join-Path (Get-Location) "run/client/saves/$World"
if(-not (Test-Path -LiteralPath (Join-Path $worldRoot '.fsrpg-legacy-skill-test-world'))){throw 'Missing copied-world marker'}
$jdk='C:/Users/34003/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2'
$launcher=(Join-Path $jdk 'bin/java.exe').Replace('/','\')
$gpuKey='HKCU:/Software/Microsoft/DirectX/UserGpuPreferences'
$previousJava=$env:JAVA_HOME
if(-not (Test-Path -LiteralPath $gpuKey)){New-Item -Path $gpuKey | Out-Null}
$registry=Get-Item -LiteralPath $gpuKey
$had=$registry.GetValueNames() -contains $launcher
$previous=if($had){$registry.GetValue($launcher)}else{$null}
$kind=if($had){$registry.GetValueKind($launcher)}else{$null}
try{
    New-ItemProperty -LiteralPath $gpuKey -Name $launcher -Value 'GpuPreference=2;' -PropertyType String -Force | Out-Null
    $env:JAVA_HOME=$jdk
    $started=[DateTime]::UtcNow
    $arguments=@('runClient','-PrpgJarSmoke','-PlegacySkillSmoke',"-PsmokeWorld=$World","-PsmokeEvidence=$EvidenceDirectory",'--console=plain')
    $proxy=Get-ItemProperty 'HKCU:/Software/Microsoft/Windows/CurrentVersion/Internet Settings'
    if($proxy.ProxyEnable -eq 1 -and $proxy.ProxyServer -match '^(?:http://)?127\.0\.0\.1:(\d+)$'){
        $port=$Matches[1];$arguments+=@('-Dhttp.proxyHost=127.0.0.1',"-Dhttp.proxyPort=$port",'-Dhttps.proxyHost=127.0.0.1',"-Dhttps.proxyPort=$port",'-Dhttp.nonProxyHosts=localhost')
    }
    & .\gradlew.bat @arguments
    if($LASTEXITCODE -ne 0){throw "Client process exited $LASTEXITCODE"}
    $report=Get-ChildItem -LiteralPath $EvidenceDirectory -Recurse -File -Filter 'legacy-skill-smoke.json' | Where-Object LastWriteTimeUtc -gt $started | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if($null -eq $report){throw 'Client closed without completing a fresh report'}
    if(-not (Get-Content -LiteralPath $report.FullName -Raw | ConvertFrom-Json).passed){throw "Client review failed; inspect $($report.FullName)"}
}finally{
    if($had){$registry.SetValue($launcher,$previous,$kind)}else{Remove-ItemProperty -LiteralPath $gpuKey -Name $launcher -ErrorAction SilentlyContinue}
    $registry.Dispose();$env:JAVA_HOME=$previousJava
}
