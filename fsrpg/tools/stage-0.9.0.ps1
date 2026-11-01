param(
    [string]$StageRoot = 'verification/legacy-branches-20261022',
    [string]$Instance = 'C:\Users\34003\AppData\Roaming\.minecraft\versions\1.21.11-NeoForge'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$project = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
function ProjectPath([string]$path) {
    if ([IO.Path]::IsPathRooted($path)) { return [IO.Path]::GetFullPath($path) }
    return [IO.Path]::GetFullPath((Join-Path $project $path))
}
$instance = [IO.Path]::GetFullPath($Instance).TrimEnd('\', '/')
$configRoot = Join-Path $instance 'config/fsrpg'
if (-not (Test-Path -LiteralPath $configRoot -PathType Container)) { throw "Official config/fsrpg not found: $configRoot" }
if ($instance -notmatch '1\.21\.11-NeoForge$') { throw "Unexpected official instance: $instance" }
$root = ProjectPath $StageRoot
if (-not $root.Contains('verification')) { throw "Stage root must live under verification/: $root" }
if (Test-Path -LiteralPath $root) { throw "Stage root already exists; move it aside first: $root" }
$stage = Join-Path $root 'fsrpg'
New-Item -ItemType Directory -Path $stage -Force | Out-Null
Copy-Item -Path (Join-Path $configRoot '*') -Destination $stage -Recurse -Force
$before = @{}
$stagePrefix = $stage.TrimEnd('\', '/') + '\'
foreach ($file in (Get-ChildItem -LiteralPath $stage -Recurse -Force -File)) {
    if (-not $file.FullName.StartsWith($stagePrefix, [StringComparison]::OrdinalIgnoreCase)) { throw "Staged file escaped: $($file.FullName)" }
    $relative = $file.FullName.Substring($stagePrefix.Length).Replace('\', '/')
    $before[$relative] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
}
$report = Join-Path $root 'stage-config.json'
Push-Location $project
try {
    & .\gradlew.bat stage090Config --offline --console=plain "-PreleaseStageDirectory=$stage" "-PreleaseStageReport=$report"
    if ($LASTEXITCODE -ne 0) { throw "stage090Config failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}
if (-not (Test-Path -LiteralPath $report -PathType Leaf)) { throw "Stage report missing: $report" }
Write-Output "Staged official 0.8.x configuration at $stage; report $report; $($before.Count) source files inspected."
