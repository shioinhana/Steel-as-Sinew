param(
    [Parameter(Mandatory=$true)][string]$World,
    [ValidateSet('weapons','rapier','pose')][string]$Suite='weapons',
    [string]$PoseSamples='',
    [string]$EvidenceDirectory=''
)
$ErrorActionPreference='Stop'
$posePlan=$null
if($PoseSamples){
    if($Suite -ne 'pose'){throw '-PoseSamples requires -Suite pose'}
    if(-not (Test-Path -LiteralPath $PoseSamples -PathType Leaf)){throw "Pose sample JSON does not exist: $PoseSamples"}
    $posePlan=(Resolve-Path -LiteralPath $PoseSamples).ProviderPath
}
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
$reviewEvidence=$null
if($EvidenceDirectory){
    if($Suite -ne 'weapons'){throw '-EvidenceDirectory requires -Suite weapons'}
    $reviewEvidence=[IO.Path]::GetFullPath($EvidenceDirectory)
}
$jdk='C:\Users\34003\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
$launcher=Join-Path $jdk 'bin\java.exe'
$gpuKey='HKCU:\Software\Microsoft\DirectX\UserGpuPreferences'
$previousJava=$env:JAVA_HOME
if(-not (Test-Path -LiteralPath $gpuKey)){New-Item -Path $gpuKey | Out-Null}
$registry=Get-Item -LiteralPath $gpuKey
$hadPreference=$registry.GetValueNames() -contains $launcher
$previousPreference=if($hadPreference){$registry.GetValue($launcher)}else{$null}
$previousKind=if($hadPreference){$registry.GetValueKind($launcher)}else{$null}
# Only this development JDK's app preference changes, and its prior value is restored.
try {
    New-ItemProperty -LiteralPath $gpuKey -Name $launcher -Value 'GpuPreference=2;' -PropertyType String -Force | Out-Null
    $env:JAVA_HOME=$jdk
    $reviewArguments=@('runClient','-PrpgJarSmoke',"-PsmokeWorld=$World",'--console=plain')
    if($Suite -eq 'weapons'){$reviewArguments+='-PweaponSmoke'}
    if($reviewEvidence){$reviewArguments+="-PsmokeEvidence=$reviewEvidence"}
    if($Suite -eq 'pose'){$reviewArguments+='-PposeSmoke'}
    if($posePlan){$reviewArguments+="-PposeSamples=$posePlan"}
    $startedAt=[DateTime]::UtcNow
    & .\gradlew.bat @reviewArguments
    if($LASTEXITCODE -ne 0){throw "Game review exited $LASTEXITCODE"}
    # Closing a client also exits Gradle successfully; require this run's completed report.
    $reportPath=switch($Suite){
        'weapons' {if($reviewEvidence){Join-Path $reviewEvidence 'game-smoke.json'}else{'verification/weapons-r2/game-smoke.json'}}
        'rapier' {'verification/rapier-r6/game-smoke.json'}
        'pose' {'verification/pose-preview/latest.json'}
    }
    if(-not (Test-Path -LiteralPath $reportPath -PathType Leaf) -or (Get-Item -LiteralPath $reportPath).LastWriteTimeUtc -lt $startedAt){
        throw 'The client closed before a fresh review report was completed; this run is incomplete.'
    }
    $report=Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
    $completed=if($Suite -eq 'pose'){$report.capturePassed}else{$report.passed}
    if(-not $completed){throw "Review reported a failure; inspect $reportPath"}
} finally {
    if($hadPreference){$registry.SetValue($launcher,$previousPreference,$previousKind)}
    else{Remove-ItemProperty -LiteralPath $gpuKey -Name $launcher -ErrorAction SilentlyContinue}
    $registry.Dispose()
    $env:JAVA_HOME=$previousJava
}
