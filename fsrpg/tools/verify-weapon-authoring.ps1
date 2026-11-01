param([Parameter(Mandatory=$true)][string]$JarPath)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskEvidence=Join-Path $taskRoot 'verification/weapons-r2'
$taskProvenance=Get-Content -LiteralPath (Join-Path $taskEvidence 'workbench-provenance.json') -Raw | ConvertFrom-Json
function FileSha([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLower() }
if($taskProvenance.schemaVersion -ne 1 -or @($taskProvenance.resources).Count -ne 2){throw 'Two current weapon authoring resources are required'}
foreach($record in @($taskProvenance.mergedLog,$taskProvenance.dualDiagnostics)) {
    if($record.file -notin @('workbench-authoring.json','sword-path.json') -or (FileSha (Join-Path $taskEvidence $record.file)) -cne $record.sha256){throw 'Weapon authoring log or diagnostics identity mismatch'}
}
$taskMerged=@(Get-Content -LiteralPath (Join-Path $taskEvidence 'workbench-authoring.json') -Raw | ConvertFrom-Json)
$taskCreates=@($taskMerged | Where-Object {$_.tool -eq 'create_resource'})
if($taskCreates.Count -ne 2 -or @($taskCreates.args.id | Sort-Object -Unique).Count -ne 2){throw 'Merged Workbench log must contain exactly one export session per current weapon'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskJar=[System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $JarPath))
try {
    foreach($resource in $taskProvenance.resources) {
        $expectedModel=switch -Exact ($resource.id) {'fsrpg.dual_blade' {'dual_blade.fsvoxel'} 'fsrpg.spear' {'spear.fsvoxel'} default {throw 'Unknown authoring resource'}}
        $expectedProof=if($resource.id -eq 'fsrpg.dual_blade'){'authoring-sources/dual-workbench-authoring.json'}else{'authoring-sources/spear-workbench-authoring.json'}
        if($resource.model -cne $expectedModel -or $resource.source.proofFile -cne $expectedProof){throw 'Authoring model or proof path mismatch'}
        $modelPath=Join-Path $taskRoot "src/main/resources/assets/fsrpg/fsvoxel/models/$expectedModel"
        if((FileSha $modelPath) -cne $resource.modelSha256 -or $resource.exportSha256 -cne $resource.modelSha256){throw "Workbench export does not match final source model $expectedModel"}
        $proofPath=Join-Path $taskEvidence $expectedProof
        if((FileSha $proofPath) -cne $resource.source.proofSha256){throw "Workbench source proof changed for $expectedModel"}
        $proof=@(Get-Content -LiteralPath $proofPath -Raw | ConvertFrom-Json)
        $proofCreates=@($proof | Where-Object {$_.tool -eq 'create_resource'})
        $proofExports=@($proof | Where-Object {$_.tool -eq 'export_resource'})
        $proofSaves=@($proof | Where-Object {$_.tool -eq 'save_copy'})
        if($proofCreates.Count -ne 1 -or $proofCreates[0].args.id -cne $resource.id -or $proofExports.Count -ne 1 -or $proofSaves.Count -ne 1){throw "Incomplete independent Workbench proof for $expectedModel"}
        if($proofExports[0].result.sha256 -cne $resource.modelSha256 -or $proofSaves[0].result.hash -cne $resource.modelSha256 -or $proofSaves[0].result.copyId -cne $resource.copyId -or $proofExports[0].result.bytes -ne (Get-Item -LiteralPath $modelPath).Length){throw "Workbench save/export/model identity mismatch for $expectedModel"}
        if(@($resource.source.proofRecordIndices).Count -ne @($resource.mergedRecordIndices).Count){throw 'Merged evidence record mapping is incomplete'}
        for($i=0;$i -lt @($resource.mergedRecordIndices).Count;$i++) {
            $sourceIndex=[int]$resource.source.proofRecordIndices[$i];$mergedIndex=[int]$resource.mergedRecordIndices[$i]
            if($sourceIndex -lt 0 -or $sourceIndex -ge $proof.Count -or $mergedIndex -lt 0 -or $mergedIndex -ge $taskMerged.Count){throw 'Invalid source proof record index'}
            if(($proof[$sourceIndex] | ConvertTo-Json -Depth 100 -Compress) -cne ($taskMerged[$mergedIndex] | ConvertTo-Json -Depth 100 -Compress)){throw "Merged Workbench evidence differs from the actual source session for $expectedModel"}
        }
        $entry=$taskJar.GetEntry("assets/fsrpg/fsvoxel/models/$expectedModel")
        if($null -eq $entry){throw "Built JAR is missing $expectedModel"}
        $stream=$entry.Open();$sha=[System.Security.Cryptography.SHA256]::Create()
        try {$entrySha=[BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','').ToLower()}finally{$stream.Dispose();$sha.Dispose()}
        if($entrySha -cne $resource.modelSha256){throw "Built JAR contains a different $expectedModel than the proven Workbench export"}
    }
} finally {$taskJar.Dispose()}
Write-Output 'Verified current dual-blade and spear Workbench source proofs, merged records, source models and built JAR model hashes.'
