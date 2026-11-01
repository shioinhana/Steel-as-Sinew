param(
    [string]$World='FSRPG Spear Solver Review 20261012 01',
    [string]$EvidenceDirectory='verification/spear-blend-20261012/game'
)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$savesRoot=(Resolve-Path -LiteralPath (Join-Path $projectRoot 'run/client/saves')).Path
if(-not $World.StartsWith('FSRPG Spear Solver Review ') -or [IO.Path]::GetFileName($World) -ne $World){throw 'Expected a named spear test world copy, without path components'}
$worldRoot=(Resolve-Path -LiteralPath (Join-Path $savesRoot $World)).Path
if([IO.Path]::GetDirectoryName($worldRoot) -ne $savesRoot){throw 'Spear test world must be directly inside the development saves directory'}
if(-not (Test-Path -LiteralPath (Join-Path $worldRoot '.fsrpg-spear-solver-test-world') -PathType Leaf)){throw 'Missing spear-only copied-world marker'}
$evidenceRoot=if([IO.Path]::IsPathRooted($EvidenceDirectory)){[IO.Path]::GetFullPath($EvidenceDirectory)}else{[IO.Path]::GetFullPath((Join-Path $projectRoot $EvidenceDirectory))}
$version=([regex]::Match((Get-Content -LiteralPath (Join-Path $projectRoot 'gradle.properties') -Raw),'(?m)^mod_version=(.+)$')).Groups[1].Value.Trim()
if([string]::IsNullOrWhiteSpace($version)){throw 'Missing Mod version for packaged spear review'}
$modJar=Join-Path $projectRoot "build/libs/fsrpg-neoforge-$version.jar"
if(-not (Test-Path -LiteralPath $modJar -PathType Leaf)){throw 'Build the release JAR before running the packaged spear review'}
$testedJarSha=(Get-FileHash -LiteralPath $modJar -Algorithm SHA256).Hash.ToLowerInvariant()
$jdk='C:/Users/34003/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2'
$launcher=(Join-Path $jdk 'bin/java.exe').Replace('/','\')
if(-not (Test-Path -LiteralPath $launcher -PathType Leaf)){throw 'Configured Java 21 runtime is unavailable'}
$gpuKey='HKCU:/Software/Microsoft/DirectX/UserGpuPreferences'
$previousJava=$env:JAVA_HOME
if(-not (Test-Path -LiteralPath $gpuKey)){New-Item -Path $gpuKey | Out-Null}
$registry=Get-Item -LiteralPath $gpuKey
$had=$registry.GetValueNames() -contains $launcher
$previous=if($had){$registry.GetValue($launcher)}else{$null}
$kind=if($had){$registry.GetValueKind($launcher)}else{$null}
Push-Location -LiteralPath $projectRoot
try {
    New-ItemProperty -LiteralPath $gpuKey -Name $launcher -Value 'GpuPreference=2;' -PropertyType String -Force | Out-Null
    $env:JAVA_HOME=$jdk
    $started=[DateTime]::UtcNow
    # runClient depends on the development monitor agent. The agent selects DISPLAY5
    # before the GLFW window exists and disables automatic focus; it is outside the Mod JAR.
    $arguments=@('runClient','-PrpgJarSmoke','-PspearSolverSmoke',"-PsmokeWorld=$World","-PsmokeEvidence=$evidenceRoot",'--console=plain')
    $proxy=Get-ItemProperty 'HKCU:/Software/Microsoft/Windows/CurrentVersion/Internet Settings'
    if($proxy.ProxyEnable -eq 1 -and $proxy.ProxyServer -match '^(?:http://)?127\.0\.0\.1:(\d+)$') {
        $port=$Matches[1];$arguments+=@('-Dhttp.proxyHost=127.0.0.1',"-Dhttp.proxyPort=$port",'-Dhttps.proxyHost=127.0.0.1',"-Dhttps.proxyPort=$port",'-Dhttp.nonProxyHosts=localhost')
    }
    & .\gradlew.bat @arguments
    if($LASTEXITCODE -ne 0){throw "Spear client process exited $LASTEXITCODE"}
    $report=Get-ChildItem -LiteralPath $evidenceRoot -Recurse -File -Filter 'spear-solver-smoke.json' | Where-Object LastWriteTimeUtc -gt $started | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if($null -eq $report){throw 'Spear client closed without a fresh runtime report'}
    $result=Get-Content -LiteralPath $report.FullName -Raw | ConvertFrom-Json
    if(-not $result.passed){throw "Spear runtime review failed: $($result.error); report $($report.FullName)"}
    if($result.monitorAdapter -ne '\\.\DISPLAY5'){throw 'Actual spear test window was outside DISPLAY5'}
    $centerX=$result.windowX+$result.windowWidth/2;$centerY=$result.windowY+$result.windowHeight/2;$bounds=$result.monitorBounds
    if($null -eq $bounds -or $centerX -lt $bounds.x -or $centerX -ge $bounds.x+$bounds.width -or $centerY -lt $bounds.y -or $centerY -ge $bounds.y+$bounds.height){throw 'Reported spear test window center is outside its recorded monitor bounds'}
    if([IO.Path]::GetFullPath($result.worldCopy) -ne [IO.Path]::GetFullPath($worldRoot)){throw 'Runtime report names a different world from the authorized spear copy'}
    $currentJarSha=(Get-FileHash -LiteralPath $modJar -Algorithm SHA256).Hash.ToLowerInvariant()
    if($result.modJarSha256 -ne $testedJarSha -or $currentJarSha -ne $testedJarSha){throw 'Loaded spear test JAR hash differs from the prebuilt release JAR'}
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive=[IO.Compression.ZipFile]::OpenRead($modJar)
    try {
        $entry=$archive.GetEntry('org/fsrpg/client/SpearAimPose.class')
        if($null -eq $entry){throw 'Packaged release JAR contains no spear solver bytecode'}
        $stream=$entry.Open();$digest=[Security.Cryptography.SHA256]::Create()
        try {$solverSha=([BitConverter]::ToString($digest.ComputeHash($stream))).Replace('-','').ToLowerInvariant()}
        finally {$stream.Dispose();$digest.Dispose()}
    } finally {$archive.Dispose()}
    if($result.loadedSolverBytecodeSha256 -ne $solverSha){throw 'Captured client loaded different spear solver bytecode from the tested release JAR'}
    foreach($name in @('SPEAR_ELBOW_LIMIT','SPEAR_WRIST_LIMIT','SPEAR_CLEARANCE','SPEAR_NECK_LIMIT','SPEAR_VERTICAL_BANK')) {
        if($null -eq $result.liveSolverLimits.$name){throw "Runtime report is missing live spear solver option $name"}
    }
    if($null -eq $result.movementValidation -or -not $result.movementValidation.passed){throw 'Spear review has no passed live movement/gait validation'}
    foreach($profile in @('stock','slim')) {
        foreach($segment in @('walk-start','forward-walk','forward-sprint','walk-stop','strafe-left','strafe-right','backward-walk','moving-thrust','moving-sweep')) {
            $segmentResult=$result.movementValidation.$profile.$segment
            if($null -eq $segmentResult -or $segmentResult.frames -lt 5 -or $segmentResult.freshSamplingPairs -le 0){throw "Missing live $profile movement segment: $segment"}
        }
    }
    $capturePath=Join-Path $report.DirectoryName 'render-capture.json'
    if(-not (Test-Path -LiteralPath $capturePath -PathType Leaf)){throw 'Passed spear review has no actual render-frame capture report'}
    $capture=Get-Content -LiteralPath $capturePath -Raw | ConvertFrom-Json
    $expected=@('spear-ready-extremes','spear-thrust-sweep-return','spear-moving-extremes')
    if(@($capture.clips).Count -ne $expected.Count -or $capture.pendingWrites -ne 0){throw 'Spear render capture is incomplete'}
    foreach($id in $expected) {
        $clip=@($capture.clips | Where-Object id -eq $id)
        if($clip.Count -ne 1 -or $clip[0].sourceFps -lt 30 -or $clip[0].captureDurationSeconds -lt 9 -or $clip[0].writtenFrames -ne $clip[0].capturedFrames -or $clip[0].distinctSamplesWithinGameTick -le 0){throw "Spear capture must contain actual complete >=30 FPS rendered frames: $id"}
    }
    Write-Output "Spear runtime review passed: $($report.FullName)"
    Write-Output "DISPLAY5; tested release JAR SHA-256 $testedJarSha"
    Write-Output "Live solver limits: $($result.liveSolverLimits | ConvertTo-Json -Compress)"
    Write-Output 'Live stock/slim movement covers walk start/stop, sprint, left/right strafe, backward, moving thrust and moving sweep.'
} finally {
    if($had){$registry.SetValue($launcher,$previous,$kind)}else{Remove-ItemProperty -LiteralPath $gpuKey -Name $launcher -ErrorAction SilentlyContinue}
    $registry.Dispose();$env:JAVA_HOME=$previousJava;Pop-Location
}
