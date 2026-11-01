param([switch]$VerifyOnly)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$project=(Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$evidence=Join-Path $project 'verification/guard-combo-20261018'
$jar=Join-Path $project 'build/libs/fsrpg-neoforge-0.7.5.jar'
$stage=(Resolve-Path -LiteralPath (Join-Path $evidence 'config-stage/fsrpg')).Path
function Hash([string]$path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
function ReadJson([string]$path){Get-Content -LiteralPath $path -Raw | ConvertFrom-Json}
function ChildPath([string]$directory,[string]$relative){
    if([string]::IsNullOrWhiteSpace($relative) -or [IO.Path]::IsPathRooted($relative) -or $relative -match '(^|[\\/])\.\.([\\/]|$)'){throw "Invalid relative path: $relative"}
    $parent=[IO.Path]::GetFullPath($directory).TrimEnd('\','/')
    $result=[IO.Path]::GetFullPath((Join-Path $parent $relative))
    if(-not $result.StartsWith($parent+'\',[StringComparison]::OrdinalIgnoreCase)){throw "Path outside intended directory: $result"}
    return $result
}
function TreeHashes([string]$directory){
    $result=@{}
    foreach($file in Get-ChildItem -LiteralPath $directory -Recurse -File){
        $relative=[IO.Path]::GetRelativePath($directory,$file.FullName).Replace('\','/')
        $result[$relative]=Hash $file.FullName
    }
    return $result
}
function HashMap($json){
    $result=@{}
    foreach($entry in $json.PSObject.Properties){
        if($entry.Value -cnotmatch '^[0-9a-f]{64}$'){throw "Invalid expected SHA-256 for $($entry.Name)"}
        $result[$entry.Name]=$entry.Value
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
function RequireNear([double]$expected,[double]$actual,[string]$description){
    if([double]::IsNaN($actual) -or [double]::IsInfinity($actual) -or [Math]::Abs($expected-$actual) -ge .003){throw "$description differs: expected $expected, actual $actual"}
}
$sha=Hash $jar
$review=ReadJson (Join-Path $evidence 'game/guard-combo-review.json')
$migration=ReadJson (Join-Path $evidence 'config-stage/report.json')
if($review.passed -ne $true -or $review.modJarSha256 -cne $sha -or $review.monitorAdapter -cne '\\.\DISPLAY5' -or
    $review.windowWidth -ne 1000 -or $review.windowHeight -ne 680){throw 'The real DISPLAY5 guard/combo review did not pass on the exact candidate JAR at the reviewed viewport'}
if((Get-Content -LiteralPath (Join-Path $evidence 'build.log') -Raw) -notmatch 'BUILD SUCCESSFUL'){throw 'The required complete 0.7.5 build did not pass'}

function CheckGuardComboReview($report,[string]$gameEvidence){
    if($report.serverTicks -lt 1 -or $report.nativeFrames -lt 30 -or $report.screenshots -lt 1 -or @($report.checks).Count -lt 1 -or
        $report.officialMainSubmissionsDuringCapture -lt 1 -or $report.officialOffhandSubmissionsDuringCapture -lt 1 -or
        $report.copiedWorld -cne 'FSRPG Guard Combo Review 20261018 01' -or $report.visibleFlashes -lt 1 -or
        $report.visibleFlashes -ne $report.distinctVisibleSequences){throw 'Native server/input/flash/screenshot evidence is incomplete'}
    $guardIds=@('guard-early','guard-held_renewal','guard-suspend_resume','guard-window','guard-release_repress','guard-late_press','guard-active_interrupt','guard-later_attack','guard-cancel','guard-dead')
    $perfect=@('guard-window','guard-release_repress','guard-active_interrupt','guard-later_attack')
    if(@($report.guardScenarios).Count -ne $guardIds.Count){throw 'The exact ten native guard scenarios are required'}
    foreach($id in $guardIds){
        $rows=@($report.guardScenarios | Where-Object {$_.id -ceq $id})
        if($rows.Count -ne 1 -or $rows[0].passed -ne $true){throw "Missing or failed actual guard scenario: $id"}
        $row=$rows[0];$s=$row.schedule
        if($s.sequence -lt 1 -or $s.startedGameTick -lt 0 -or $s.flashGameTick -le $s.startedGameTick -or
            $s.contactGameTick-$s.flashGameTick -ne 6 -or $s.closeGameTick-$s.openGameTick -ne 6 -or
            $s.flashGameTick -ne $s.openGameTick -or $s.closeGameTick -ne $s.contactGameTick -or
            $row.observedContactGameTick -ne $s.contactGameTick){throw "Actual flash/window/contact schedule is invalid: $id"}
        if($perfect -contains $id){
            if($row.freshPressGameTick -lt $s.openGameTick -or $row.freshPressGameTick -ge $s.closeGameTick -or
                $row.firstHealthLoss -ne 0 -or $row.observedParryStunTicks -ne 4 -or $row.observedCounterRemainingTicks -lt 1){throw "Fresh C did not authorize a real perfect guard inside the exclusive window: $id"}
        }elseif(@('guard-early','guard-held_renewal','guard-suspend_resume') -contains $id){
            if($row.firstHealthLoss -le 0 -or $row.firstHealthLoss -ge 4 -or $row.observedParryStunTicks -ne 0 -or $row.observedCounterRemainingTicks -ne 0){throw "Early/held C incorrectly produced a perfect block: $id"}
        }elseif($id -ceq 'guard-late_press'){
            if($row.freshPressGameTick -ne $s.closeGameTick -or $row.observedParryStunTicks -ne 0){throw 'Exclusive closing edge was not tested with a genuinely late C'}
            RequireNear 4 $row.firstHealthLoss 'Actual late C damage'
        }elseif($row.firstHealthLoss -ne 0){throw "Canceled/dead attack still caused damage: $id"}
        if($id -ceq 'guard-later_attack' -and ($row.laterAttack.sequence -eq $s.sequence -or $row.laterHealthLoss -le 0 -or $row.laterObservedParryStunTicks -ne 0)){throw 'One fresh C incorrectly rebound to a later enemy sequence'}
        if($id -ceq 'guard-suspend_resume'){
            if($row.suspendedGameTick -gt $s.startedGameTick -or $row.resumedGameTick -lt $s.openGameTick -or $row.resumedGameTick -ge $s.closeGameTick){throw 'Held C suspend/resume was not tested across a genuine in-window usability transition'}
            RequireNear 1.8 $row.firstHealthLoss 'Resumed held C remains an ordinary block'
        }
    }
    $expected=[ordered]@{
        'combo-rapier_windup'=@('sword_left','rapier_downcut');'combo-rapier_active'=@('sword_left','rapier_downcut');'combo-rapier_resume'=@('sword_left','rapier_downcut')
        'combo-dual_left'=@('dual_ll','dual_rl','dual_ll','dual_rl');'combo-dual_right'=@('dual_rr','dual_lr','dual_rr','dual_lr')
        'combo-rapier_guard_cancel'=@('sword_left');'combo-dual_guard_cancel'=@('dual_ll')
    }
    if(@($report.comboScenarios).Count -ne $expected.Count){throw 'The exact seven actual sword input scenarios are required'}
    foreach($id in $expected.Keys){
        $rows=@($report.comboScenarios | Where-Object {$_.id -ceq $id})
        if($rows.Count -ne 1 -or $rows[0].passed -ne $true){throw "Missing or failed actual sword scenario: $id"}
        $row=$rows[0];$skills=@($row.skills);$starts=@($row.actualStarts);$wanted=@($expected[$id]);$cost=if($id.Contains('dual')){8}else{5}
        if($row.actualBladeCount -ne $wanted.Count -or $row.expectedBladeCount -ne $wanted.Count -or $skills.Count -ne $wanted.Count -or $starts.Count -ne $wanted.Count){throw "Accepted point inputs caused missing or extra sword blades: $id"}
        for($i=0;$i -lt $wanted.Count;$i++){
            if($skills[$i] -cne $wanted[$i] -or $starts[$i].skillId -cne $wanted[$i]){throw "Wrong original skill/hand order in actual blade $i : $id"}
            if($i -gt 0 -and ($starts[$i].swingSequence -ne $starts[$i-1].swingSequence+1 -or $starts[$i].worldGameTick -le $starts[$i-1].worldGameTick)){throw "Native sword continuations have inconsistent sequences/ticks: $id"}
            RequireNear ($row.initialStamina-$cost*($i+1)) $starts[$i].stamina "One configured resource charge per started blade $i : $id"
        }
        if($row.firstStartedGameTick -ne $starts[0].worldGameTick){throw "First blade did not preserve its real input tick: $id"}
        if($id -cne 'combo-rapier_resume'){
            $edges=@($row.queueEdges)
            $phase=if($id -ceq 'combo-rapier_active'){'ACTIVE'}else{'WINDUP'}
            if($edges.Count -lt 1 -or $edges[0].queued -ne $true -or $edges[0].phase -cne $phase -or
                $edges[0].currentSwingSequence -ne $starts[0].swingSequence){throw "Real early/active buffered click evidence is absent: $id"}
            RequireNear ($row.initialStamina-$cost) $edges[0].stamina "Buffer acceptance must not charge the next blade early: $id"
        }else{
            $skillsConfig=@(ReadJson (Join-Path $stage 'skills.json'))
            $firstSkill=@($skillsConfig | Where-Object {$_.id -ceq 'sword_left'})[0]
            $duration=$firstSkill.windupTicks+$firstSkill.activeTicks+$firstSkill.recoveryTicks
            if($row.secondStartedGameTick-$row.firstStartedGameTick -lt $duration+6){throw 'Relaxed rapier continuation was not tested after complete recovery plus a 0.3 second pause'}
        }
    }
    $packets=@($report.receivedCuePackets);$cues=@($report.nativeWorldProjectedCueFrames)
    if($packets.Count -lt 1 -or $cues.Count -lt 1){throw 'No received schedule/native world-projected flash evidence'}
    $future=@($packets | Where-Object {$_.phase -ceq 'BEGIN' -and $_.flashGameTime -gt $_.serverGameTime})
    if($future.Count -lt 1 -or @($packets | Where-Object {$_.phase -ceq 'CANCEL'}).Count -lt 1){throw 'A future announced flash and actual cancellation packet are required'}
    if(@($future | Group-Object sequence | Where-Object {$_.Count -ge 2}).Count -lt 1){throw 'Actual duplicate BEGIN packets did not exercise one-flash deduplication'}
    $target=$packets[0].targetId
    if($target -notmatch '^[0-9a-fA-F-]{36}$' -or @($packets | Where-Object {$_.targetId -cne $target}).Count -gt 0){throw 'Received warning packets do not address one current target player'}
    $seen=@{};$previousProgress=@{}
    foreach($cue in $cues){
        $key=[string]$cue.sequence;$matching=@($packets | Where-Object {$_.sequence -eq $cue.sequence -and $_.entityId -ceq $cue.entityId})
        if($matching.Count -lt 1){throw 'A native flash has no real accepted authoritative schedule'}
        $p=$matching[0]
        if($cue.flashGameTime -ne $p.flashGameTime -or $cue.openGameTime -ne $p.openGameTime -or $cue.closeGameTime -ne $p.closeGameTime -or
            $cue.contactGameTime -ne $p.contactGameTime -or $cue.serverGameTime -lt $cue.flashGameTime -or
            $cue.serverGameTime -ge ($cue.flashGameTime+2.4) -or $cue.serverGameTime -ge $cue.closeGameTime -or
            $cue.screenX -lt 4 -or $cue.screenX -gt 496 -or $cue.screenY -lt 4 -or $cue.screenY -gt 336){throw 'Actual gold flash escaped its source schedule, visual duration or visible viewport'}
        if($previousProgress.ContainsKey($key) -and $cue.progress -lt $previousProgress[$key]){throw 'Duplicate payload restarted a native flash'}
        RequireNear (($cue.serverGameTime-$cue.flashGameTime)/2.4) $cue.progress 'Native flash progress'
        foreach($terminal in @($matching | Where-Object {$_.phase -ceq 'CANCEL' -or $_.phase -ceq 'END'})){
            if($cue.nativeNanoTime -gt $terminal.receiptNativeNanoTime){throw 'A canceled or ended attack produced a ghost flash'}
        }
        $previousProgress[$key]=$cue.progress;$seen[$key]=$true
        if(-not (Test-Path -LiteralPath (Join-Path $gameEvidence ('screenshots/gold-flash-sequence-'+$key+'.png')) -PathType Leaf)){throw 'Actual flash screenshot is missing'}
    }
    if($seen.Count -ne $report.visibleFlashes){throw 'Native sequence inventory disagrees with one-flash-per-attack counter'}
    foreach($row in @($report.guardScenarios)){
        $key=[string]$row.schedule.sequence
        if($row.id -ceq 'guard-cancel'){
            if($seen.ContainsKey($key)){throw 'An attack canceled before its flash still displayed a warning'}
        }elseif(-not $seen.ContainsKey($key)){throw "A visible live test attack never produced its scheduled single warning: $($row.id)"}
        if($row.id -ceq 'guard-later_attack' -and -not $seen.ContainsKey([string]$row.laterAttack.sequence)){throw 'The later independent attack did not produce its own warning'}
    }
    $capture=ReadJson (Join-Path $gameEvidence 'render-capture.json')
    if($capture.captureEvent -cne 'RenderFrameEvent.Post' -or $capture.pendingWrites -ne 0 -or @($capture.clips).Count -ne 1){throw 'Native frame recording was not completed'}
    $clip=@($capture.clips)[0];$frames=@($clip.frames)
    if($clip.id -cne 'guard-and-combo' -or $frames.Count -lt 30 -or $clip.capturedFrames -ne $frames.Count -or $clip.writtenFrames -ne $frames.Count -or
        $clip.distinctSamplesWithinGameTick -lt 1 -or $clip.changedRigPairs -lt 1 -or $clip.sourceFps -lt 30){throw 'Recording does not establish genuine native 30 Hz sub-tick changing rig presentation'}
    $duration=$frames[-1].capturedAtSeconds-$frames[0].capturedAtSeconds
    if($duration -le 0){throw 'Native frame clock has no positive span'}
    $fps=($frames.Count-1)/$duration;RequireNear $fps $clip.sourceFps 'Independent native source FPS'
    if($fps -lt 30){throw 'Raw source timestamps are below 30 Hz'}
    $previousTime=-1.;$previousFrame=-1L;$previousRigFrame=-1L;$renderedSequences=@{};$submittedRigFrames=0
    for($i=0;$i -lt $frames.Count;$i++){
        $frame=$frames[$i]
        if($frame.index -ne $i -or $frame.capturedAtSeconds -le $previousTime -or $frame.renderFrame -le $previousFrame -or
            $frame.paused -ne $false -or $frame.levelRunningNormally -ne $true -or
            $frame.file -cne ('screenshots/render-frames/guard-and-combo/frame-'+$i.ToString('D6')+'.png')){throw 'Native source frames are reordered, duplicated, paused or misnamed'}
        $image=ChildPath $gameEvidence $frame.file
        if(-not (Test-Path -LiteralPath $image -PathType Leaf) -or (Get-Item -LiteralPath $image).Length -le 0){throw 'A real framebuffer source PNG is missing'}
        if($frame.observation.PSObject.Properties.Name -notcontains 'submittedAvatar' -or $frame.observation.PSObject.Properties.Name -notcontains 'rig' -or
            $frame.observation.submittedAvatar.nativePoseSnapshotStage -cne 'RenderLivingEvent.Post actual avatar submission' -or
            $frame.observation.submittedAvatar.nativePoseSnapshotRenderFrame -le $previousRigFrame){throw 'A captured rig does not come from an actual current-frame native player submission'}
        if(@($frame.observation.rig.PSObject.Properties).Count -gt 0){$submittedRigFrames++}
        $previousRigFrame=$frame.observation.submittedAvatar.nativePoseSnapshotRenderFrame
        if($frame.observation.PSObject.Properties.Name -contains 'visibleWorldProjectedGuardCue'){$renderedSequences[[string]$frame.observation.visibleWorldProjectedGuardCue.sequence]=$true}
        $previousTime=$frame.capturedAtSeconds;$previousFrame=$frame.renderFrame
    }
    if($submittedRigFrames -le 30){throw 'Recording has fewer than thirty-one actual nonempty submitted player rigs'}
    foreach($key in $seen.Keys){if(-not $renderedSequences.ContainsKey($key)){throw 'A projected flash has no corresponding native captured framebuffer frame'}}
}
CheckGuardComboReview $review (Join-Path $evidence 'game')
if($migration.passed -ne $true -or $migration.version -cne '0.7.5' -or $migration.sourceCopiedByteExactly -ne $true -or
    $migration.officialSourceUntouched -ne $true -or $migration.allOtherExistingFieldsAndFilesPreserved -ne $true -or
    $migration.disabledGuardWindowsPreserved -ne $true -or $migration.exactOriginalBackups -ne $true -or $migration.secondReloadIdempotent -ne $true -or
    $migration.existingFileCount -ne 43 -or $migration.stagedFileCount -ne 47 -or $migration.perfectGuardTicks -ne 6 -or
    $migration.baseMaxStamina -ne 50 -or $migration.baseStaminaRegenPerSecond -ne 5 -or $migration.parryStunTicks -ne 4 -or
    $migration.counterWindowTicks -ne 4 -or $migration.counterDamageMultiplier -ne 1.5){throw 'The exact guard-only staged migration or preserved prior mechanics failed validation'}
if($migration.guardCueSettings.enabled -ne $true -or $migration.guardCueSettings.flashLeadTicks -ne 6 -or $migration.guardCueSettings.inputWindowTicks -ne 6 -or
    $migration.guardCueSettings.flashDurationMillis -ne 120 -or $migration.guardCueSettings.guardCancelsActive -ne $true){throw 'Staged cue timing differs from the actually tested project calibration'}
if($migration.staminaLevelScaling.regenSqrtCoefficient -ne 1 -or $migration.staminaLevelScaling.regenLogCoefficient -ne 1 -or
    $migration.staminaLevelScaling.maxPowerCoefficient -ne 1 -or $migration.staminaLevelScaling.maxPowerExponent -ne .8 -or
    $migration.staminaLevelScaling.maxLogCoefficient -ne 3){throw 'Existing requested level scaling coefficients changed'}

Add-Type -AssemblyName System.IO.Compression.FileSystem
function ModToml([string]$path){
    $archive=[IO.Compression.ZipFile]::OpenRead($path)
    try{$entry=$archive.GetEntry('META-INF/neoforge.mods.toml');if($null -eq $entry){return ''}
        $reader=[IO.StreamReader]::new($entry.Open());try{return $reader.ReadToEnd()}finally{$reader.Dispose()}
    }finally{$archive.Dispose()}
}
function IsFsrpg([string]$path){$toml=ModToml $path;return [regex]::IsMatch(($toml -split '\[\[dependencies\.')[0],'(?m)^\s*modId\s*=\s*"fsrpg"')}
$archive=[IO.Compression.ZipFile]::OpenRead($jar)
try{if(@($archive.Entries | Where-Object {$_.FullName -match '(^org/fsrpg/smoke/)|MonitorAgent|forging|ArtContent|art/combat_arena'}).Count){throw 'Development code or excluded other-project materials are present in the release JAR'}}finally{$archive.Dispose()}
if(-not (IsFsrpg $jar) -or (ModToml $jar) -notmatch '(?m)^\s*version\s*=\s*"0\.7\.5"'){throw 'Candidate JAR metadata is not FSRPG 0.7.5'}
$instance=(Resolve-Path -LiteralPath 'C:/Users/34003/AppData/Roaming/.minecraft/versions/1.21.11-NeoForge').Path
$versionText=Get-Content -LiteralPath (Join-Path $instance '1.21.11-NeoForge.json') -Raw
if(($versionText | ConvertFrom-Json).id -cne '1.21.11-NeoForge' -or -not $versionText.Contains('21.11.45')){throw 'Official Minecraft/NeoForge instance version changed'}
$latestLog=Get-Content -LiteralPath (Join-Path $instance 'logs/latest.log') -Raw
if(-not $latestLog.Contains("Game directory: $instance") -and -not $latestLog.Contains('Game directory: '+$instance.Replace('\','/'))){throw 'Actual official game directory was not confirmed by its log'}
function AssertInstanceStopped{
    if(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | Where-Object {$_.CommandLine -and
        ($_.CommandLine.Contains($instance) -or $_.CommandLine.Contains($instance.Replace('\','/')))}){throw 'Official instance is running; loaded JARs will not be replaced'}
}
AssertInstanceStopped
$mods=(Resolve-Path -LiteralPath (Join-Path $instance 'mods')).Path
$config=(Resolve-Path -LiteralPath (Join-Path $instance 'config/fsrpg')).Path
if(-not [string]::Equals([IO.Path]::GetFullPath($migration.sourceDirectory),$config,[StringComparison]::OrdinalIgnoreCase) -or
    -not [string]::Equals([IO.Path]::GetFullPath($migration.stageDirectory),$stage,[StringComparison]::OrdinalIgnoreCase)){throw 'Staging report refers to different source/destination directories'}
if((Hash (Join-Path $mods 'fsvoxel-renderer-neoforge-0.6.0.jar')) -cne (Hash (Join-Path $project 'libs/fsvoxel-renderer-neoforge-0.6.0.jar'))){throw 'Official required dependency differs from the tested dependency'}
$before=HashMap $migration.beforeSha256;$after=HashMap $migration.afterSha256
SameTree $before $config 'Official configuration after staging';SameTree $after $stage 'Staged configuration after validation'
$edited=@('combat.json','styles.json')
$allowedNew=@('guard-cue-migration-v1.json','guard-cue-backup-v1/combat.json','guard-cue-backup-v1/styles.json','guard_cues.json')
$newFiles=@()
foreach($name in $before.Keys){
    if(-not $after.ContainsKey($name)){throw "Staged migration removed existing file: $name"}
    if($edited -notcontains $name -and $before[$name] -cne $after[$name]){throw "Staged migration changed unrelated existing file: $name"}
}
foreach($name in $after.Keys){
    [void](ChildPath $stage $name);[void](ChildPath $config $name)
    if($before.ContainsKey($name)){continue};if($allowedNew -notcontains $name){throw "Unapproved new staged file: $name"};$newFiles+=,$name
}
if($newFiles.Count -ne 4){throw 'Expected exactly four approved new configuration files'}
foreach($name in $edited){
    if(-not $before.ContainsKey($name) -or -not $after.ContainsKey($name) -or $after['guard-cue-backup-v1/'+$name] -cne $before[$name]){throw "Missing guard configuration or exact original backup: $name"}
}
$archive=[IO.Compression.ZipFile]::OpenRead($jar)
try{
    $entry=$archive.GetEntry('defaults/guard_cues.json');if($null -eq $entry){throw 'Candidate has no guard cue seed'}
    $stream=$entry.Open();try{$seedSha=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)).ToLowerInvariant()}finally{$stream.Dispose()}
    if($after['guard_cues.json'] -cne $seedSha){throw 'Staged cue seed differs from the exact tested JAR'}
}finally{$archive.Dispose()}
$old=@(Get-ChildItem -LiteralPath $mods -File -Filter '*.jar' | Where-Object {IsFsrpg $_.FullName})
$oldPaths=@($old | ForEach-Object {$_.FullName});$installed=ChildPath $mods 'fsrpg-neoforge-0.7.5.jar'
if((Test-Path -LiteralPath $installed) -and $oldPaths -notcontains $installed){throw 'Candidate release filename belongs to an unrelated mod'}
$preserved=@(Get-ChildItem -LiteralPath $mods -Recurse -File | Where-Object {$oldPaths -notcontains $_.FullName} | ForEach-Object {@{path=$_.FullName;sha256=(Hash $_.FullName)}})
foreach($name in $before.Keys){if($edited -notcontains $name){$preserved+=@{path=(ChildPath $config $name);sha256=$before[$name]}}}
foreach($name in @('options.txt','config/fsrpg-client.toml')){$path=ChildPath $instance $name;if(Test-Path -LiteralPath $path){$preserved+=@{path=$path;sha256=(Hash $path)}}}
if($VerifyOnly){Write-Output "VERIFY ONLY PASSED: exact candidate $sha; DISPLAY5 gold-flash/guard/combo checks and genuine native 30 Hz frames; exact 43-to-47 staged config; dependency hashes; official instance stopped. No files were written.";return}
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss-fff';$backup=ChildPath $instance "fsrpg-backups/$stamp-before-0.7.5"
if(Test-Path -LiteralPath $backup){throw 'Dated backup already exists'}
New-Item -ItemType Directory -Path (Join-Path $backup 'mods') -Force | Out-Null
Copy-Item -LiteralPath $config -Destination (Join-Path $backup 'fsrpg') -Recurse
SameTree $before (Join-Path $backup 'fsrpg') 'Full configuration backup'
$previous=@()
foreach($file in $old){
    if($file.DirectoryName -cne $mods -or $file.Extension -cne '.jar'){throw 'Previous mod is outside the exact mods directory'}
    $saved=ChildPath (Join-Path $backup 'mods') $file.Name;Copy-Item -LiteralPath $file.FullName -Destination $saved
    if((Hash $saved) -cne (Hash $file.FullName)){throw 'Previous FSRPG JAR backup hash mismatch'}
    $previous+=@{path=$file.FullName;backup=$saved;sha256=(Hash $saved)}
}
AssertInstanceStopped;SameTree $before $config 'Official configuration immediately before deployment'
$created=@();$installedTouched=$false
try{
    foreach($file in $previous){Remove-Item -LiteralPath $file.path}
    $installedTouched=$true;Copy-Item -LiteralPath $jar -Destination $installed
    foreach($name in $edited){Copy-Item -LiteralPath (ChildPath $stage $name) -Destination (ChildPath $config $name) -Force}
    foreach($name in ($newFiles | Sort-Object {$_ -notmatch '-backup-v1/'},{$_})){
        $target=ChildPath $config $name;if(Test-Path -LiteralPath $target){throw "Unexpected concurrent new migration file: $name"}
        New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
        $created+=,$target;Copy-Item -LiteralPath (ChildPath $stage $name) -Destination $target
    }
    if((Hash $installed) -cne $sha){throw 'Installed JAR is not the exact tested candidate'}
    SameTree $after $config 'Deployed configuration'
    foreach($file in $preserved){if((Hash $file.path) -cne $file.sha256){throw "Preserved file changed: $($file.path)"}}
    $record=[ordered]@{version='0.7.5';installedJar=$installed;jarSha256=$sha;testedJarSha256=$review.modJarSha256;backup=$backup;
        previousMods=$previous;editedConfiguration=$edited;newMigrationFiles=$newFiles;configurationSha256=$after;guardCueSettings=$migration.guardCueSettings;
        baseMaxStamina=50;staminaRegenPerSecond=5;staminaLevelScaling=$migration.staminaLevelScaling;perfectGuardTicks=6;counterWindowTicks=4;counterDamageMultiplier=1.5;parryStunTicks=4;
        singleSwordCost=5;dualBladeCost=8;singleFlashPerAttack=$true;relaxedComboBuffer=$true;existingConfigurationFieldsPreserved=$true;worldsModified=$false;otherModsPreserved=$true;
        preserved=$preserved;evidence=$evidence;deployedAt=(Get-Date).ToString('o')}
    $record | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $backup 'deployment.json') -Encoding utf8
    $record | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $evidence 'deployment.json') -Encoding utf8
}catch{
    $originalFailure=$_;$rollbackErrors=@()
    try{if($installedTouched -and (Test-Path -LiteralPath $installed)){Remove-Item -LiteralPath $installed}}catch{$rollbackErrors+=$_.ToString()}
    foreach($file in $previous){try{Copy-Item -LiteralPath $file.backup -Destination $file.path -Force}catch{$rollbackErrors+=$_.ToString()}}
    foreach($name in $edited){try{Copy-Item -LiteralPath (ChildPath (Join-Path $backup 'fsrpg') $name) -Destination (ChildPath $config $name) -Force}catch{$rollbackErrors+=$_.ToString()}}
    foreach($path in $created){try{if(Test-Path -LiteralPath $path){Remove-Item -LiteralPath $path}}catch{$rollbackErrors+=$_.ToString()}}
    try{SameTree $before $config 'Rolled-back configuration';foreach($file in $previous){if((Hash $file.path) -cne $file.sha256){throw 'Rolled-back JAR mismatch'}}}catch{$rollbackErrors+=$_.ToString()}
    @{deploymentFailure=$originalFailure.ToString();rollbackErrors=$rollbackErrors;backup=$backup} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $backup 'rollback.json') -Encoding utf8
    if($rollbackErrors.Count -gt 0){throw "Deployment failed: $originalFailure; rollback needs attention: $($rollbackErrors -join ' | '); backup: $backup"};throw $originalFailure
}
Write-Output "Installed $installed; SHA-256 $sha; tested/deployed hashes match; backup $backup"
