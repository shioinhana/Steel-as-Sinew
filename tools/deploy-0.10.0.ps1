# Requires PowerShell 7; creates no files when -VerifyOnly is used.
param(
    [switch]$VerifyOnly,
    [string]$BuildJar = 'build/libs/fsrpg-neoforge-0.10.0.jar',
    [string]$StageDirectory = 'verification/rapier-renewal-20261024/fsrpg',
    [string]$StageReport = 'verification/rapier-renewal-20261024/stage-config.json',
    [string]$EvidenceDirectory = 'verification/rapier-renewal-20261024',
    [string]$ExpectedPreviousJarSha256 = '',
    [string]$Instance = 'C:\Users\34003\AppData\Roaming\.minecraft\versions\1.21.11-NeoForge'
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if($PSVersionTable.PSVersion.Major -lt 7){throw 'Run this deployment script with PowerShell 7 (pwsh).'}
$releaseZone=[TimeZoneInfo]::FindSystemTimeZoneById('China Standard Time')
function ReleaseTime { [TimeZoneInfo]::ConvertTime([DateTimeOffset]::UtcNow,$releaseZone) }
$project=(Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
function ProjectPath([string]$path){if([IO.Path]::IsPathRooted($path)){return [IO.Path]::GetFullPath($path)};return [IO.Path]::GetFullPath((Join-Path $project $path))}
function Hash([string]$path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
$script:atomicArtifacts=@()
function RemoveAtomicArtifact([hashtable]$artifact){
    if(-not (Test-Path -LiteralPath $artifact.path)){return}
    if($artifact.ContainsKey('retain') -and $artifact.retain){throw "Atomic preimage retained until its exact bytes are restored: $($artifact.path)"}
    if(((Get-Item -LiteralPath $artifact.path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
        $null -eq $artifact.sha256 -or (Hash $artifact.path) -cne $artifact.sha256){throw "Unknown/concurrent temporary contents retained for review: $($artifact.path)"}
    Remove-Item -LiteralPath $artifact.path
}
function CleanupAtomicArtifacts{
    $failures=@()
    for($i=$script:atomicArtifacts.Count-1;$i -ge 0;$i--){
        try{RemoveAtomicArtifact $script:atomicArtifacts[$i]}catch{$failures+=$_.ToString()}
    }
    if($failures.Count){throw ($failures -join ' | ')}
}
function WriteAtomicVerified([string]$source,[string]$destination,[string]$expectedSha,[AllowNull()]$previousSha,[hashtable]$writeRecord){
    $parent=Split-Path -Parent $destination
    $nonce=[Guid]::NewGuid().ToString('N')
    $staged=ChildPath $parent ('.fsrpg-0100-'+$nonce+'-stage.tmp')
    $preimage=ChildPath $parent ('.fsrpg-0100-'+$nonce+'-preimage.tmp')
    $stagedArtifact=@{path=$staged;sha256=$null}
    $preimageArtifact=$null
    $writeRecord.committed=$false;$writeRecord.preimage=$null;$writeRecord.preimageSha256=$null;$writeRecord.preimageArtifact=$null
    try{
        $destinationStream=$null;$sourceStream=$null
        try{
            $destinationStream=[IO.File]::Open($staged,[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
            $script:atomicArtifacts+=,$stagedArtifact
            $sourceStream=[IO.File]::OpenRead($source)
            $sourceStream.CopyTo($destinationStream)
            $destinationStream.Flush($true)
        }finally{
            if($null -ne $sourceStream){$sourceStream.Dispose()}
            if($null -ne $destinationStream){
                try{$destinationStream.Position=0;$stagedArtifact.sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($destinationStream)).ToLowerInvariant()}
                finally{$destinationStream.Dispose()}
            }
        }
        if($stagedArtifact.sha256 -cne $expectedSha -or (Hash $staged) -cne $expectedSha){throw "Atomic staging SHA mismatch: $destination"}
        if($null -eq $previousSha){
            # A racing new file is never overwritten. A partial stage never has a .jar/.json name.
            [IO.File]::Move($staged,$destination,$false)
            $writeRecord.committed=$true
        }else{
            if(-not (Test-Path -LiteralPath $destination -PathType Leaf) -or (Hash $destination) -cne $previousSha){throw "Concurrent edit before atomic replacement: $destination"}
            if(Test-Path -LiteralPath $preimage){throw "Atomic preimage destination already exists: $preimage"}
            $preimageArtifact=@{path=$preimage;sha256=$null;retain=$true}
            $script:atomicArtifacts+=,$preimageArtifact
            $writeRecord.preimage=$preimage;$writeRecord.preimageArtifact=$preimageArtifact
            # Replace preserves the exact displaced bytes, including an edit racing the last hash.
            [IO.File]::Replace($staged,$destination,$preimage,$false)
            $writeRecord.committed=$true
            $preimageArtifact.sha256=Hash $preimage
            $writeRecord.preimageSha256=$preimageArtifact.sha256
            if($preimageArtifact.sha256 -cne $previousSha){throw "Concurrent edit captured intact by atomic replacement: $preimage"}
            $preimageArtifact.retain=$false
            RemoveAtomicArtifact $preimageArtifact
            $writeRecord.preimage=$null
        }
        if((Hash $destination) -cne $expectedSha){throw "Atomic destination changed after commit: $destination"}
    }catch{
        # File.Replace may have completed before a later I/O failure. Its unique preimage is
        # authoritative for rollback; never replace it with an older dated-backup version.
        if($null -ne $preimageArtifact -and (Test-Path -LiteralPath $preimage)){
            $writeRecord.committed=$true
            if($null -eq $preimageArtifact.sha256){$preimageArtifact.sha256=Hash $preimage}
            $writeRecord.preimageSha256=$preimageArtifact.sha256
        }
        throw
    }finally{
        RemoveAtomicArtifact $stagedArtifact
    }
}
function ReadJson([string]$path){Get-Content -LiteralPath $path -Raw | ConvertFrom-Json}
function ChildPath([string]$directory,[string]$relative){
    if([string]::IsNullOrWhiteSpace($relative) -or [IO.Path]::IsPathRooted($relative) -or $relative -match '(^|[\\/])\.\.([\\/]|$)'){throw "Invalid relative path: $relative"}
    $parent=[IO.Path]::GetFullPath($directory).TrimEnd('\','/')
    $result=[IO.Path]::GetFullPath((Join-Path $parent $relative))
    if(-not $result.StartsWith($parent+'\',[StringComparison]::OrdinalIgnoreCase)){throw "Path outside intended directory: $result"}
    return $result
}
function AssertNoReparsePath([string]$path,[string]$boundary){
    $limit=[IO.Path]::GetFullPath($boundary).TrimEnd('\','/')
    $current=[IO.Path]::GetFullPath($path)
    if(-not [string]::Equals($current,$limit,[StringComparison]::OrdinalIgnoreCase) -and
        -not $current.StartsWith($limit+'\',[StringComparison]::OrdinalIgnoreCase)){throw "Path outside checked boundary: $current"}
    while($true){
        if(Test-Path -LiteralPath $current){if(((Get-Item -LiteralPath $current -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw "Linked deployment path is not supported: $current"}}
        if([string]::Equals($current,$limit,[StringComparison]::OrdinalIgnoreCase)){break}
        $current=Split-Path -Parent $current
    }
}
function TreeHashes([string]$directory){
    if(((Get-Item -LiteralPath $directory -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw "Linked preserved tree is not supported: $directory"}
    $result=@{}
    $entries=@(Get-ChildItem -LiteralPath $directory -Recurse -Force)
    if(@($entries | Where-Object {($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0}).Count){throw "Links inside preserved configuration/mod/world trees are not supported: $directory"}
    foreach($file in ($entries | Where-Object {-not $_.PSIsContainer})){
        $relative=[IO.Path]::GetRelativePath($directory,$file.FullName).Replace('\','/')
        $result[$relative]=Hash $file.FullName
    }
    return $result
}
function SameTree([hashtable]$expected,[string]$directory,[string]$description){
    $actual=TreeHashes $directory
    if($actual.Count -ne $expected.Count){throw "$description file inventory changed"}
    foreach($entry in $expected.GetEnumerator()){
        if(-not $actual.ContainsKey($entry.Key) -or $actual[$entry.Key] -cne $entry.Value){throw "$description hash mismatch: $($entry.Key)"}
    }
}
function JsonSame($a,$b){
    if($null -eq $a -or $null -eq $b){return $null -eq $a -and $null -eq $b}
    if($a -is [System.Collections.IDictionary]){
        if($b -isnot [System.Collections.IDictionary] -or $a.Count -ne $b.Count){return $false}
        foreach($key in $a.Keys){if(-not $b.Contains($key) -or -not (JsonSame $a[$key] $b[$key])){return $false}}
        return $true
    }
    if($a -is [array]){
        if($b -isnot [array] -or $a.Count -ne $b.Count){return $false}
        for($i=0;$i -lt $a.Count;$i++){if(-not (JsonSame $a[$i] $b[$i])){return $false}}
        return $true
    }
    if(($a -is [string]) -ne ($b -is [string]) -or ($a -is [bool]) -ne ($b -is [bool])){return $false}
    return $a -ceq $b
}
function JsonMap([string]$file){[IO.File]::ReadAllText($file,[Text.Encoding]::UTF8)|ConvertFrom-Json -AsHashtable}
function JarBytes([string]$entryName){
    $entry=$script:archive.GetEntry($entryName);if($null -eq $entry){throw "Missing bundled entry: $entryName"}
    $stream=$entry.Open();$buffer=[IO.MemoryStream]::new()
    try{$stream.CopyTo($buffer);return ,$buffer.ToArray()}finally{$buffer.Dispose();$stream.Dispose()}
}
function JarJson([string]$name){[Text.Encoding]::UTF8.GetString((JarBytes $name))|ConvertFrom-Json -AsHashtable}
function BytesHash([byte[]]$bytes){[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()}
function CheckCoinDefaultInstructions([string[]]$disassembly){
    # Read actual <clinit> instructions, not a coincidental string/double constant elsewhere.
    if($disassembly.Count -eq 0){throw 'ClientOptions disassembly is empty'}
    $initializers=@(0..($disassembly.Count-1)|Where-Object {$disassembly[$_] -cmatch '^\s*static \{\};\s*$'})
    if($initializers.Count -ne 1 -or $initializers[0]+1 -ge $disassembly.Count){throw 'ClientOptions must have exactly one static initializer with code'}
    $instructions=@()
    foreach($line in $disassembly[($initializers[0]+1)..($disassembly.Count-1)]){
        if($line -cmatch '^\s*(\d+):\s+(\w+)(?:\s+(.*?))?\s*$'){
            $instructions+=,@{offset=[int]$Matches[1];opcode=$Matches[2];operand=[string]$Matches[3]}
        }
    }
    if($instructions.Count -lt 6){throw 'ClientOptions initializer has no complete coin default assignment'}
    $keys=@(0..($instructions.Count-1)|Where-Object {$instructions[$_].opcode -cin @('ldc','ldc_w') -and $instructions[$_].operand -cmatch '^#\d+\s+// String importedCoinEquipSeconds$'})
    if($keys.Count -ne 1 -or $keys[0]+5 -ge $instructions.Count){throw 'Missing/ambiguous importedCoinEquipSeconds bytecode assignment'}
    $first=$keys[0];$values=@();$expectedOffset=$instructions[$first].offset
    for($i=0;$i -lt 6;$i++){
        $instruction=$instructions[$first+$i]
        if($instruction.offset -ne $expectedOffset){throw 'Coin default instructions are not contiguous'}
        $expectedOffset+=if($instruction.opcode -ceq 'ldc'){2}else{3}
        if($i -ge 1 -and $i -le 3){
            if($instruction.opcode -cne 'ldc2_w' -or $instruction.operand -cnotmatch '^#\d+\s+// double ([0-9.Ee+\-]+)d$'){throw 'Coin duration/default bounds are not literal double bytecode arguments'}
            $values+=,[double]::Parse($Matches[1],[Globalization.CultureInfo]::InvariantCulture)
        }
    }
    $method='Method net/neoforged/neoforge/common/ModConfigSpec$Builder.defineInRange:(Ljava/lang/String;DDD)Lnet/neoforged/neoforge/common/ModConfigSpec$DoubleValue;'
    $field='Field IMPORTED_COIN_SECONDS:Lnet/neoforged/neoforge/common/ModConfigSpec$DoubleValue;'
    $call=$instructions[$first+4];$assignment=$instructions[$first+5]
    if($call.opcode -cne 'invokevirtual' -or $call.operand -cnotmatch ('^#\d+\s+// '+[regex]::Escape($method)+'$') -or
        $assignment.opcode -cne 'putstatic' -or $assignment.operand -cnotmatch ('^#\d+\s+// '+[regex]::Escape($field)+'$')){throw 'Coin duration is not assigned to the production DoubleValue field'}
    $assignments=@($instructions|Where-Object {$_.opcode -ceq 'putstatic' -and $_.operand -cmatch ('^#\d+\s+// '+[regex]::Escape($field)+'$')})
    if($assignments.Count -ne 1 -or $values[0] -ne .55 -or $values[1] -ne .45 -or $values[2] -ne 2.0){throw "Tested JAR coin duration must define 0.55 seconds with unchanged 0.45..2.0 bounds; found $($values -join ', ')"}
    return [ordered]@{class='org.fsrpg.client.ClientOptions';method='<clinit>';field='IMPORTED_COIN_SECONDS';default=.55;minimum=.45;maximum=2.0;instructionOffset=$instructions[$first].offset}
}
function CheckCoinDefaultBytecode([string]$jarPath){
    $javap=Get-Command javap.exe -ErrorAction SilentlyContinue
    if($null -eq $javap){throw 'A JDK javap.exe is required to verify the exact tested JAR coin default'}
    $text=@(& $javap.Source -classpath $jarPath -c -p 'org.fsrpg.client.ClientOptions' 2>&1|ForEach-Object {$_.ToString()})
    if($LASTEXITCODE -ne 0){throw 'Could not disassemble ClientOptions from the tested JAR'}
    return CheckCoinDefaultInstructions $text
}
function CoinConfigCandidate([byte[]]$original){
    # Strict UTF-8 round trip retains the BOM, comments, line endings and every unrelated byte.
    $utf8=[Text.UTF8Encoding]::new($false,$true);$text=$utf8.GetString($original)
    if((BytesHash ($utf8.GetBytes($text))) -cne (BytesHash $original)){throw 'Client TOML is not losslessly readable UTF-8'}
    $fields=@();$state='';$root=$true
    $keyPattern=@'
^[\uFEFF \t]*(?:importedCoinEquipSeconds|"importedCoinEquipSeconds"|'importedCoinEquipSeconds')[ \t]*=[ \t]*
'@
    foreach($lineMatch in [regex]::Matches($text,'[^\r\n]+')){
        $line=$lineMatch.Value;$normalStart=$state -ceq '';$code=[Text.StringBuilder]::new()
        for($i=0;$i -lt $line.Length;$i++){
            $char=$line[$i]
            if($state -ceq ''){
                if($char -ceq '#'){break}
                if($char -ceq '"' -or $char -ceq "'"){
                    $quote=[string]$char
                    if($i+2 -lt $line.Length -and $line.Substring($i,3) -ceq ($quote*3)){$state=if($char -ceq '"'){'multiBasic'}else{'multiLiteral'};[void]$code.Append('   ');$i+=2;continue}
                    $state=if($char -ceq '"'){'basic'}else{'literal'};[void]$code.Append(' ');continue
                }
                [void]$code.Append($char);continue
            }
            [void]$code.Append(' ')
            if($state -cin @('basic','multiBasic') -and $char -ceq '\' -and $i+1 -lt $line.Length){[void]$code.Append(' ');$i++;continue}
            $quote=if($state -cin @('basic','multiBasic')){'"'}else{"'"}
            if([string]$char -cne $quote){continue}
            if($state -cin @('basic','literal')){$state='';continue}
            $run=1;while($i+$run -lt $line.Length -and [string]$line[$i+$run] -ceq $quote){$run++}
            if($run -ge 3){[void]$code.Append(' '*($run-1));$i+=$run-1;$state=''}
        }
        if($state -cin @('basic','literal')){throw 'Unterminated single-line TOML string; client configuration was not changed'}
        if(-not $normalStart){continue}
        if($code.ToString().TrimStart([char]0xFEFF,' ',[char]9).StartsWith('[')){$root=$false}
        if(-not $root){continue}
        $key=[regex]::Match($line,$keyPattern)
        if($key.Success){
            $length=$code.Length-$key.Length
            if($length -lt 0){throw 'Ambiguous coin duration TOML assignment'}
            $value=$line.Substring($key.Length,$length).TrimEnd()
            $fields+=,@{value=$value;offset=$lineMatch.Index+$key.Length;length=$value.Length}
        }
    }
    if($state -cne ''){throw 'Unterminated multiline TOML string; client configuration was not changed'}
    if($fields.Count -gt 1){throw 'Duplicate root importedCoinEquipSeconds assignments; client configuration was not changed'}
    $before=BytesHash $original;$result=@{changed=$false;bytes=$original;beforeSha256=$before;afterSha256=$before;oldValue=$null;newValue=$null;reason='field-absent'}
    if($fields.Count -eq 0){return $result}
    $field=$fields[0];$result.oldValue=$field.value;$result.newValue=$field.value;$result.reason='custom-value-preserved'
    # Only the known numeric old default is eligible; strings and custom values stay intact.
    $numeric=0.0
    if($field.value -cnotmatch '^[+-]?(?:0|[1-9](?:_?[0-9])*)(?:\.[0-9](?:_?[0-9])*)?(?:[eE][+-]?[0-9](?:_?[0-9])*)?$' -or
        -not [double]::TryParse($field.value.Replace('_',''),[Globalization.NumberStyles]::Float,[Globalization.CultureInfo]::InvariantCulture,[ref]$numeric) -or $numeric -ne 1.05){return $result}
    $candidate=$text.Substring(0,$field.offset)+'0.55'+$text.Substring($field.offset+$field.length)
    # Reinsert the precise original token: no other character may differ from the source.
    if(($candidate.Substring(0,$field.offset)+$field.value+$candidate.Substring($field.offset+4)) -cne $text){throw 'Coin migration changed bytes outside its single numeric value'}
    $result.changed=$true;$result.bytes=$utf8.GetBytes($candidate);$result.afterSha256=BytesHash $result.bytes
    $result.oldValue=1.05;$result.newValue=.55;$result.reason='known-old-default'
    return $result
}
function FindId($rows,[string]$id){$found=@($rows|Where-Object {$_['id'] -ceq $id});if($found.Count -ne 1){throw "Expected exactly one ID: $id"};return $found[0]}
function NoRunningInstance {
    $running=@(Get-CimInstance Win32_Process|Where-Object {$_.Name -in @('java.exe','javaw.exe')}|
        Where-Object {$_.CommandLine -and ($_.CommandLine.Contains($instance,[StringComparison]::OrdinalIgnoreCase) -or $_.CommandLine.Contains('1.21.11-NeoForge',[StringComparison]::OrdinalIgnoreCase))})
    if($running.Count){throw "Official instance is running in PID $($running[0].ProcessId); loaded JAR/config files cannot be replaced"}
}
function CheckVisual([string]$file,[string]$reportHash,[string]$jarHash){
    $visual=JsonMap $file
    if($visual['passed'] -ne $true -or $visual['modJarSha256'] -cne $jarHash -or $visual['nativeReportSha256'] -cne $reportHash){throw 'Visual acceptance must bind the exact tested JAR and native report'}
    $samples=@($visual['samples']);if($samples.Count -lt 5){throw 'Original native visual review needs at least all five attack phrases'}
    foreach($sample in $samples){
        if($sample['passed'] -ne $true -or [string]::IsNullOrWhiteSpace($sample['notes'])){throw 'A visual sample is unreviewed or failed'}
        $path=ChildPath $evidence $sample['file'];AssertNoReparsePath $path $evidence
        if((Hash $path) -cne $sample['sha256']){throw "Reviewed original image changed: $($sample['file'])"}
    }
}

$instance=[IO.Path]::GetFullPath($Instance).TrimEnd('\','/')
if($instance -notmatch '1\.21\.11-NeoForge$' -or -not (Test-Path -LiteralPath $instance -PathType Container)){throw "Unexpected/missing official instance: $instance"}
$mods=ChildPath $instance 'mods';$configRoot=ChildPath $instance 'config/fsrpg';$allConfig=ChildPath $instance 'config'
AssertNoReparsePath $instance (Split-Path -Parent $instance)
$jar=ProjectPath $BuildJar;$stage=ProjectPath $StageDirectory;$stageReport=ProjectPath $StageReport;$evidence=ProjectPath $EvidenceDirectory
if(-not $stage.StartsWith((Join-Path $project 'verification')+'\',[StringComparison]::OrdinalIgnoreCase) -or
    [IO.Path]::GetFileName($stage) -cne 'fsrpg'){throw "Stage must be an isolated project verification/fsrpg directory: $stage"}
AssertNoReparsePath $stage $project;AssertNoReparsePath $configRoot $instance;AssertNoReparsePath $mods $instance
$version=JsonMap (ChildPath $instance '1.21.11-NeoForge.json')
if($version['id'] -cne '1.21.11-NeoForge' -or $version['mainClass'] -cne 'net.neoforged.fml.startup.Client'){throw 'Official launcher metadata mismatch'}
$arguments=@($version['arguments']['game']);$mc=[Array]::IndexOf($arguments,'--fml.mcVersion');$neo=[Array]::IndexOf($arguments,'--fml.neoForgeVersion')
if($mc -lt 0 -or $neo -lt 0 -or $arguments[$mc+1] -cne '1.21.11' -or $arguments[$neo+1] -cne '21.11.45'){throw 'Official instance version mismatch'}
if(-not (Select-String -LiteralPath (ChildPath $instance 'logs/latest.log') -SimpleMatch "Game directory: $instance" -Quiet)){throw 'Latest actual game directory differs from official instance'}
NoRunningInstance
$sha=Hash $jar;$stageReportHash=Hash $stageReport;$report=JsonMap $stageReport
if($report['passed'] -ne $true -or $report['version'] -cne '0.10.0' -or [IO.Path]::GetFullPath($report['directory']) -ine $stage){throw 'Passed stage report must identify this exact 0.10.0 candidate directory'}
$stageBefore=@{};foreach($key in $report['beforeSha256'].Keys){$stageBefore[$key]=$report['beforeSha256'][$key]}
$stageAfter=@{};foreach($key in $report['afterSha256'].Keys){$stageAfter[$key]=$report['afterSha256'][$key]}
SameTree $stageBefore $configRoot 'Every live configuration preimage must match the staged source'
SameTree $stageAfter $stage 'Every staged configuration result must match its report'
foreach($key in $stageBefore.Keys){if(-not $stageAfter.ContainsKey($key)){throw "Stage deletes a user file: $key"}}
$changed=@($stageAfter.Keys|Where-Object {-not $stageBefore.ContainsKey($_) -or $stageBefore[$_] -cne $stageAfter[$_]}|Sort-Object)
$allowed='^(skills\.json|skill_trees\.json|models/legacy_rapier(_fp)?\.fsvoxel|rapier-renewal-migration-v1\.json|rapier-renewal-backup-v1/(plan\.json|skills\.json|skill_trees\.json|models/legacy_rapier(_fp)?\.fsvoxel))$'
foreach($relative in $changed){
    [void](ChildPath $configRoot $relative)
    if($relative -cnotmatch $allowed){throw "Migration changes a path outside its explicit allowlist: $relative"}
}
$marker=JsonMap (ChildPath $stage 'rapier-renewal-migration-v1.json')
if($marker['releaseVersion'] -cne '0.10.0' -or $marker['customFieldsPreserved'] -ne $true -or $marker['customModelsPreserved'] -ne $true -or -not (JsonSame $marker $report['migration'])){throw 'Stage report/marker mismatch'}

# Validate every changed field against the old/new defaults inside the same tested JAR.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$script:archive=[IO.Compression.ZipFile]::OpenRead($jar)
try{
    if(@($script:archive.Entries|Where-Object {$_.FullName.StartsWith('org/fsrpg/smoke/')} ).Count){throw 'Production JAR contains smoke classes'}
    $coinBytecode=CheckCoinDefaultBytecode $jar
    $coinBytecode['classSha256']=BytesHash (JarBytes 'org/fsrpg/client/ClientOptions.class')
    $baseline=JarJson 'defaults/rapier-renewal-0.9.5.json';$defaults=JarJson 'defaults/skills.json';$defaultTrees=JarJson 'defaults/skill_trees.json'
    $oldSkills=JsonMap (ChildPath $configRoot 'skills.json');$newSkills=JsonMap (ChildPath $stage 'skills.json')
    if($oldSkills.Count -ne $newSkills.Count){throw 'Rapier renewal must not add/remove stable skill IDs'}
    for($i=0;$i -lt $oldSkills.Count;$i++){if($oldSkills[$i]['id'] -cne $newSkills[$i]['id']){throw 'Rapier renewal must preserve the existing skill order'}}
    foreach($old in $oldSkills){
        $next=FindId $newSkills $old['id'];if($old.Count -ne $next.Count){throw "Skill fields added/removed: $($old['id'])"}
        foreach($field in $old.Keys){
            if(-not $next.Contains($field)){throw "Skill field removed: $($old['id']).$field"}
            if(JsonSame $old[$field] $next[$field]){continue}
            if($field -cnotin @('label','windupTicks','activeTicks','recoveryTicks','model','action')){throw "User skill tuning changed: $($old['id']).$field"}
            $stock=FindId $baseline['skills'] $old['id'];$fresh=FindId $defaults $old['id']
            if(-not (JsonSame $old[$field] $stock[$field]) -or -not (JsonSame $next[$field] $fresh[$field])){throw "Changed skill field was not untouched released default: $($old['id']).$field"}
            if($field -cin @('model','action')){
                foreach($relative in @('models/legacy_rapier.fsvoxel','models/legacy_rapier_fp.fsvoxel')){
                    if($stageAfter[$relative] -cne (BytesHash (JarBytes ('assets/fsrpg/fsvoxel/'+$relative)))){throw "Changed action does not have its reviewed paired assets: $($old['id'])"}
                }
                if($old['model'] -ceq 'rapier.fsvoxel' -and $stageBefore['models/rapier.fsvoxel'] -cne $baseline['models']['models/rapier.fsvoxel']){throw 'A custom legacy conditional model would be bypassed'}
            }
        }
    }
    $oldTrees=JsonMap (ChildPath $configRoot 'skill_trees.json');$newTrees=JsonMap (ChildPath $stage 'skill_trees.json')
    $allowedTrees=$oldTrees|ConvertTo-Json -Depth 100|ConvertFrom-Json -AsHashtable
    foreach($node in $baseline['nodes']){
        $id=$node['id'];$old=@($allowedTrees['nodes']|Where-Object {$_['id'] -ceq $id});if($old.Count -eq 0){continue}
        $next=FindId $newTrees['nodes'] $id
        if(-not (JsonSame $old[0]['description'] $next['description'])){
            $fresh=FindId $defaultTrees['nodes'] $id
            if(-not (JsonSame $old[0]['description'] $node['description']) -or -not (JsonSame $next['description'] $fresh['description'])){throw "Custom cultivation description changed: $id"}
            $old[0]['description']=$next['description']
        }
    }
    if(-not (JsonSame $allowedTrees $newTrees)){throw 'Cultivation progress, learned IDs, rules or other user tree fields changed'}
    foreach($relative in @('models/legacy_rapier.fsvoxel','models/legacy_rapier_fp.fsvoxel')){
        if($changed -ccontains $relative){
            if($stageBefore[$relative] -cne $baseline['models'][$relative]){throw "Custom model would be replaced: $relative"}
            if($stageAfter[$relative] -cne (BytesHash (JarBytes ('assets/fsrpg/fsvoxel/'+$relative)))){throw "Stage model differs from the tested JAR: $relative"}
        }
    }
}finally{$script:archive.Dispose()}
$plan=JsonMap (ChildPath $stage 'rapier-renewal-backup-v1/plan.json')
if($plan['releaseVersion'] -cne '0.10.0'){throw 'Unexpected durable migration plan version'}
foreach($relative in @('skills.json','skill_trees.json')){
    if(-not (JsonSame $plan['candidates'][$relative] (JsonMap (ChildPath $stage $relative)))){throw "Durable plan/candidate mismatch: $relative"}
}
foreach($relative in $changed){
    if($relative.StartsWith('rapier-renewal-backup-v1/') -and $relative -cne 'rapier-renewal-backup-v1/plan.json'){
        $original=$relative.Substring('rapier-renewal-backup-v1/'.Length)
        if(-not $stageBefore.ContainsKey($original) -or $stageAfter[$relative] -cne $stageBefore[$original]){throw "Migration backup does not preserve exact original bytes: $relative"}
    }
}
$nativeFile=ChildPath $evidence 'game/rapier-fencing-review.json';$nativeHash=Hash $nativeFile;$native=JsonMap $nativeFile
if($native['passed'] -ne $true -or $native['modJarSha256'] -cne $sha -or $native['monitorAdapter'] -cne '\\.\DISPLAY5' -or $native['clientConfigurationRestored'] -ne $true){throw 'Exact-JAR DISPLAY5 native review must pass and restore client settings'}
$centerX=[double]$native['windowX']+[double]$native['windowWidth']/2;$centerY=[double]$native['windowY']+[double]$native['windowHeight']/2;$bounds=$native['monitorBounds']
if($centerX -lt $bounds['x'] -or $centerX -ge $bounds['x']+$bounds['width'] -or $centerY -lt $bounds['y'] -or $centerY -ge $bounds['y']+$bounds['height']){throw 'Actual native-window center is outside DISPLAY5 bounds'}
$visualFile=ChildPath $evidence 'visual-review.json';CheckVisual $visualFile $nativeHash $sha;$visualHash=Hash $visualFile
$oldJars=@(Get-ChildItem -LiteralPath $mods -Filter 'fsrpg-neoforge-*.jar' -File)
if($oldJars.Count -ne 1){throw "Expected exactly one currently installed FSRPG JAR; found $($oldJars.Count)"}
$oldJar=$oldJars[0];$modsBefore=TreeHashes $mods;$oldSha=$modsBefore[$oldJar.Name]
if($ExpectedPreviousJarSha256 -and $ExpectedPreviousJarSha256.ToLowerInvariant() -cne $oldSha){throw 'Installed previous JAR changed since the latest verified 0.9.5 handoff; re-read the active version before deploying'}
if(-not $VerifyOnly -and $ExpectedPreviousJarSha256 -cnotmatch '^[0-9a-fA-F]{64}$'){throw 'Real deployment requires -ExpectedPreviousJarSha256 from the latest VerifyOnly result; this binds the concurrent 0.9.5 handoff'}
$allConfigBefore=TreeHashes $allConfig;$expectedAllConfig=@{};foreach($key in $allConfigBefore.Keys){$expectedAllConfig[$key]=$allConfigBefore[$key]}
foreach($relative in $changed){$expectedAllConfig['fsrpg/'+$relative]=$stageAfter[$relative]}
$coinConfig=ChildPath $allConfig 'fsrpg-client.toml'
$coinCandidate=@{changed=$false;bytes=$null;beforeSha256=$null;afterSha256=$null;oldValue=$null;newValue=$null;reason='file-absent'}
if($allConfigBefore.ContainsKey('fsrpg-client.toml')){
    AssertNoReparsePath $coinConfig $instance
    $coinCandidate=CoinConfigCandidate ([IO.File]::ReadAllBytes($coinConfig))
    if($coinCandidate.beforeSha256 -cne $allConfigBefore['fsrpg-client.toml']){throw 'Client TOML changed while its coin migration was being prepared'}
    $expectedAllConfig['fsrpg-client.toml']=$coinCandidate.afterSha256
}
$coinRecord=[ordered]@{file=$coinConfig;field='importedCoinEquipSeconds';changed=$coinCandidate.changed;reason=$coinCandidate.reason;
    oldValue=$coinCandidate.oldValue;newValue=$coinCandidate.newValue;beforeSha256=$coinCandidate.beforeSha256;afterSha256=$coinCandidate.afterSha256;
    customFieldsPreserved=$true;testedJarDefault=$coinBytecode}
$options=ChildPath $instance 'options.txt';$optionsBefore=if(Test-Path -LiteralPath $options -PathType Leaf){Hash $options}else{$null}
$targetName='fsrpg-neoforge-0.10.0.jar';$installed=ChildPath $mods $targetName
if($oldJar.Name -ceq $targetName){throw '0.10.0 is already installed; this script only upgrades a previous release'}
$expectedMods=@{};foreach($key in $modsBefore.Keys){$expectedMods[$key]=$modsBefore[$key]};$expectedMods.Remove($oldJar.Name);$expectedMods[$targetName]=$sha
if($VerifyOnly){Write-Output "VERIFY ONLY PASSED: candidate $sha; current installed $($oldJar.Name) SHA-256 $oldSha; exact staged preimages $($stageBefore.Count) files; allowlisted updates $($changed.Count); coin duration $($coinCandidate.reason) [$($coinCandidate.oldValue) -> $($coinCandidate.newValue)], TOML SHA-256 [$($coinCandidate.beforeSha256) -> $($coinCandidate.afterSha256)]; compiled coin default 0.55 verified; same-JAR DISPLAY5/visual evidence; no files written. Pass -ExpectedPreviousJarSha256 $oldSha for actual deployment.";return}

$stamp=(ReleaseTime).ToString('yyyyMMdd-HHmmss');$backup=ChildPath $instance ("fsrpg-backups/$stamp-before-0.10.0")
if(Test-Path -LiteralPath $backup){throw "Backup already exists: $backup"}
New-Item -ItemType Directory -Path (ChildPath $backup 'mods') -Force|Out-Null
Copy-Item -LiteralPath $allConfig -Destination (ChildPath $backup 'config') -Recurse
SameTree $allConfigBefore (ChildPath $backup 'config') 'Complete configuration backup including custom TOMLs'
$coinSource=$null
if($coinCandidate.changed){
    $coinSource=ChildPath $backup 'candidates/fsrpg-client.toml';AssertNoReparsePath $coinSource $instance
    New-Item -ItemType Directory -Path (Split-Path -Parent $coinSource) -Force|Out-Null
    $stream=[IO.File]::Open($coinSource,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try{$stream.Write($coinCandidate.bytes,0,$coinCandidate.bytes.Length);$stream.Flush($true)}finally{$stream.Dispose()}
    if((Hash $coinSource) -cne $coinCandidate.afterSha256){throw 'Single-field client TOML candidate hash mismatch'}
}
if($null -ne $optionsBefore){Copy-Item -LiteralPath $options -Destination (ChildPath $backup 'options.txt');if((Hash (ChildPath $backup 'options.txt')) -cne $optionsBefore){throw 'Options backup mismatch'}}
$oldCopy=ChildPath $backup ('verified-old/'+$oldJar.Name);New-Item -ItemType Directory -Path (Split-Path -Parent $oldCopy) -Force|Out-Null
Copy-Item -LiteralPath $oldJar.FullName -Destination $oldCopy
if((Hash $oldCopy) -cne $oldSha){throw 'Previous JAR changed while its backup was being made'}
NoRunningInstance;SameTree $modsBefore $mods 'Mods immediately before commit';SameTree $allConfigBefore $allConfig 'Configuration immediately before commit'
if((Hash $jar) -cne $sha -or (Hash $stageReport) -cne $stageReportHash -or (Hash $nativeFile) -cne $nativeHash -or (Hash $visualFile) -cne $visualHash){throw 'Tested build or evidence changed during deployment preparation'}
SameTree $stageAfter $stage 'Stage immediately before commit'
$oldMoved=ChildPath $backup ('mods/'+$oldJar.Name);$movedOld=$false;$jarWrite=@{committed=$false};$writes=@()
try{
    [IO.File]::Move($oldJar.FullName,$oldMoved,$false);$movedOld=$true
    if((Hash $oldMoved) -cne $oldSha){throw 'Previous JAR raced deployment; its exact latest bytes are retained for restoration'}
    WriteAtomicVerified $jar $installed $sha $null $jarWrite
    foreach($relative in $changed){
        $destination=ChildPath $configRoot $relative;AssertNoReparsePath $destination $instance
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force|Out-Null
        $previous=if($stageBefore.ContainsKey($relative)){$stageBefore[$relative]}else{$null}
        $write=@{relative=$relative;path=$destination;backupRelative=('config/fsrpg/'+$relative);sha256=$stageAfter[$relative];previous=$previous;committed=$false};$writes+=,$write
        WriteAtomicVerified (ChildPath $stage $relative) $destination $write.sha256 $previous $write
    }
    if($coinCandidate.changed){
        AssertNoReparsePath $coinConfig $instance
        $write=@{relative='fsrpg-client.toml';path=$coinConfig;backupRelative='config/fsrpg-client.toml';sha256=$coinCandidate.afterSha256;previous=$coinCandidate.beforeSha256;committed=$false};$writes+=,$write
        WriteAtomicVerified $coinSource $coinConfig $write.sha256 $write.previous $write
    }
    SameTree $expectedMods $mods 'Only the tested FSRPG JAR was replaced';SameTree $expectedAllConfig $allConfig 'Only allowlisted migration files changed'
    if($null -ne $optionsBefore -and (Hash $options) -cne $optionsBefore){throw 'Options changed during deployment'}
    CleanupAtomicArtifacts
    $record=[ordered]@{version='0.10.0';networkProtocol=12;installedJar=$installed;jarSha256=$sha;testedJar=$jar;backup=$backup;
        previousJar=[ordered]@{name=$oldJar.Name;sha256=$oldSha};configurationBeforeSha256=$stageBefore;configurationAfterSha256=$stageAfter;
        configurationFilesWritten=$changed;allConfigurationBeforeSha256=$allConfigBefore;allConfigurationAfterSha256=$expectedAllConfig;
        coinEquipDurationMigration=$coinRecord;
        stageDirectory=$stage;stageReport=$stageReport;stageReportSha256=$stageReportHash;nativeReportSha256=$nativeHash;visualReviewSha256=$visualHash;
        otherModsPreserved=$true;clientConfigPreserved=(-not $coinCandidate.changed);clientConfigCustomFieldsPreserved=$true;optionsPreserved=$true;worldsModified=$false;deployedAt=(ReleaseTime).ToString("yyyy-MM-ddTHH:mm:sszzz")}
    $record|ConvertTo-Json -Depth 20|Set-Content -LiteralPath (ChildPath $backup 'deployment.json') -Encoding utf8
    $record|ConvertTo-Json -Depth 20|Set-Content -LiteralPath (ChildPath $evidence 'deployment.json') -Encoding utf8
    Write-Output "Installed $installed; SHA-256 $sha; $($changed.Count) allowlisted rapier migration files; coin duration $($coinCandidate.reason); latest prior JAR $oldSha retained in $backup; other mods/custom settings/options/worlds preserved."
}catch{
    $failure=$_.ToString()+"`nSTACK:`n"+$_.ScriptStackTrace;$errors=@()
    for($i=$writes.Count-1;$i -ge 0;$i--){$write=$writes[$i];if(-not $write.committed){continue}
        try{
            if((Hash $write.path) -cne $write.sha256){throw "Concurrent file blocks automatic rollback: $($write.relative)"}
            if($write.preimage){$source=$write.preimage;$restoreHash=$write.preimageSha256}
            elseif($null -ne $write.previous){$source=ChildPath $backup $write.backupRelative;$restoreHash=$write.previous}
            else{Remove-Item -LiteralPath $write.path;continue}
            $restore=@{committed=$false};WriteAtomicVerified $source $write.path $restoreHash $write.sha256 $restore
            if($write.preimage){$write.preimageArtifact.retain=$false;RemoveAtomicArtifact $write.preimageArtifact}
        }catch{$errors+=$_.ToString()}
    }
    try{if($jarWrite.committed -and (Test-Path -LiteralPath $installed)){if((Hash $installed) -cne $sha){throw 'Concurrent candidate JAR blocks rollback'};Remove-Item -LiteralPath $installed}}catch{$errors+=$_.ToString()}
    try{if($movedOld){if(Test-Path -LiteralPath $oldJar.FullName){throw 'Another deployment occupies the previous JAR path; original preserved in backup'};[IO.File]::Move($oldMoved,$oldJar.FullName,$false)}}catch{$errors+=$_.ToString()}
    try{CleanupAtomicArtifacts;SameTree $allConfigBefore $allConfig 'Configuration rollback';SameTree $modsBefore $mods 'Mods rollback'}catch{$errors+=$_.ToString()}
    if($errors.Count){throw "Deployment failed: $failure; rollback needs attention: $($errors -join ' | '); backups: $backup"}
    throw "Deployment failed and rolled back: $failure; backup: $backup"
}
