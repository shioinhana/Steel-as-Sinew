param([switch]$VerifyOnly)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$project=(Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$evidence=Join-Path $project 'verification/level-creature-combat-20261016'
$jar=Join-Path $project 'build/libs/fsrpg-neoforge-0.7.4.jar'
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
$sha=Hash $jar
$review=ReadJson (Join-Path $evidence 'game/creature-combat-review.json')
$migration=ReadJson (Join-Path $evidence 'config-stage/report.json')
if($review.passed -ne $true -or $review.modJarSha256 -cne $sha -or $review.monitorAdapter -cne '\\.\DISPLAY5'){
    throw 'The real DISPLAY5 creature/level review did not pass on the exact candidate JAR'
}
if((Get-Content -LiteralPath (Join-Path $evidence 'build.log') -Raw) -notmatch 'BUILD SUCCESSFUL'){
    throw 'Required full 0.7.4 build did not pass'
}
function RequireNear([double]$expected,[double]$actual,[string]$description){
    if([double]::IsNaN($actual) -or [double]::IsInfinity($actual) -or [Math]::Abs($expected-$actual) -ge .002){throw "$description differs: expected $expected, actual $actual"}
}
function CheckCreatureReview($report,[string]$gameEvidence,[string]$stagedConfig){
    if($report.levelNoRefillAndClampPassed -ne $true -or $report.counterMultiplier -ne 1.5 -or
        $report.serverTicks -lt 1 -or $report.nativeFrames -lt 30 -or $report.screenshots -lt 5 -or @($report.checks).Count -lt 1){throw 'Actual resource, counter, server tick or screenshot review is incomplete'}
    $levels=@($report.levelScaling)
    if($levels.Count -ne 4){throw 'Four actual vanilla experience levels are required'}
    foreach($level in @(0,1,10,100)){
        $rows=@($levels | Where-Object {$_.playerExperienceLevel -eq $level})
        if($rows.Count -ne 1){throw "Missing or duplicate actual experience level $level"}
        $row=$rows[0]
        if($row.actorPlayerLevel -ne $level -or $row.clientPlayerLevel -ne $level){throw "Server/client level disagrees at $level"}
        $maximum=if($level -eq 0){50}else{50+[Math]::Pow($level,.8)+3*[Math]::Log($level)}
        $regen=if($level -eq 0){5}else{[Math]::Floor(5+[Math]::Sqrt($level)+[Math]::Log($level))}
        RequireNear $maximum $row.maxStamina "Actor max stamina at $level"
        RequireNear $maximum $row.clientMaxStamina "Received max stamina at $level"
        RequireNear $regen $row.staminaRegenPerSecond "Actor regeneration at $level"
        RequireNear $regen $row.clientStaminaRegenPerSecond "Received regeneration at $level"
        RequireNear ($regen/20) $row.observedTickRegen "Actual one-tick regeneration at $level"
        if($row.clientStamina -lt 0 -or $row.clientStamina -gt $maximum -or [string]::IsNullOrWhiteSpace($row.hudReadoutLabel) -or $row.hudReadoutLabel -ceq '--/--'){throw "Actual HUD values are missing at level $level"}
    }
    $expected=[ordered]@{
        'zombie-ai-auto'=@('minecraft:zombie','AUTO');'zombie-contact'=@('minecraft:zombie','ENCHANTED')
        'move-out-of-range'=@('minecraft:zombie','RANGE');'occluded-before-contact'=@('minecraft:zombie','WALL')
        'ordinary-c-guard'=@('minecraft:zombie','GUARD');'perfect-c-guard-counter'=@('minecraft:zombie','PERFECT')
        'dead-attacker-cancels'=@('minecraft:zombie','DEAD');'changed-weapon-cancels'=@('minecraft:zombie','WEAPON')
        'changed-target-cancels'=@('minecraft:zombie','TARGET');'removed-target-cancels'=@('minecraft:zombie','REMOVED')
        'iron-golem-independent-override'=@('minecraft:iron_golem','GOLEM');'cave-spider-poison-contact'=@('minecraft:cave_spider','POISON')
        'unprofiled-hoglin-native'=@('minecraft:hoglin','GENERIC');'creaking-frozen-cancels'=@('minecraft:creaking','FROZEN')
        'warden-sonic-cancels'=@('minecraft:warden','SONIC');'enderman-stare-cancels'=@('minecraft:enderman','STARE')
    }
    $cancellations=@('dead-attacker-cancels','changed-weapon-cancels','changed-target-cancels','removed-target-cancels','creaking-frozen-cancels','warden-sonic-cancels','enderman-stare-cancels')
    $misses=@('move-out-of-range','occluded-before-contact')
    if(@($report.scenarios).Count -ne $expected.Count){throw 'Exact sixteen-scenario native creature inventory is required'}
    foreach($id in $expected.Keys){
        $rows=@($report.scenarios | Where-Object {$_.id -ceq $id})
        if($rows.Count -ne 1){throw "Missing or duplicate creature scenario $id"}
        $row=$rows[0]
        if($row.entityType -cne $expected[$id][0] -or $row.outcome -cne $expected[$id][1]){throw "Wrong native creature scenario type/mode: $id"}
        if($row.preContactDamageEvents -ne 0 -or $row.preContactSideEffects -ne 0){throw "Damage/status effect occurred before contact: $id"}
        if($id -ceq 'unprofiled-hoglin-native'){
            if($row.immediateHealthLoss -le 0 -or $row.totalHealthLoss -le 0 -or $row.contactClaims -ne 0){throw 'Unprofiled native attack compatibility failed'}
            continue
        }
        if($row.immediateHealthLoss -ne 0 -or $row.sequence -le 0 -or $row.startGameTick -lt 0 -or
            $row.contactGameTick -le $row.startGameTick -or $row.endGameTick -le $row.contactGameTick -or
            $row.observedContactGameTick -ne $row.contactGameTick){throw "Actual server windup/contact/recovery ticks are invalid: $id"}
        $trace=@($report.serverTickTrace | Where-Object {$_.scenario -ceq $id})
        $windup=@($trace | Where-Object {$_.gameTick -gt $row.startGameTick -and $_.gameTick -lt $row.contactGameTick})
        $incoming=@($trace | Where-Object {$_.PSObject.Properties.Name -contains 'kind' -and $_.kind -ceq 'native-incoming-damage'})
        if($windup.Count -lt 1 -or $incoming.Count -ne $row.damageEvents){throw "Actual tick/native-event trace is incomplete: $id"}
        foreach($event in $incoming){if($event.gameTick -ne $row.contactGameTick -or $event.amount -le 0){throw "Native damage event is outside the actual contact tick: $id"}}
        if($cancellations -contains $id){
            if($row.contactClaims -ne 0 -or $row.damageEvents -ne 0 -or $row.totalHealthLoss -ne 0 -or $row.canceledAttacks -ne 1 -or $row.completedAttacks -ne 0){throw "Invalid pending attack was not actually canceled: $id"}
            if($id -ceq 'creaking-frozen-cancels' -and [string]::IsNullOrWhiteSpace($row.freezeFixture)){throw 'Native Creaking gaze-freeze fixture evidence is absent'}
            if(@('warden-sonic-cancels','enderman-stare-cancels') -contains $id -and [string]::IsNullOrWhiteSpace($row.aiStateFixture)){throw "Original AI state fixture evidence is absent: $id"}
        }else{
            if($row.contactClaims -ne 1){throw "Contact was claimed more or less than once: $id"}
            if($misses -contains $id){
                if($row.damageEvents -ne 0 -or $row.totalHealthLoss -ne 0){throw "Revalidated range/occlusion did not miss: $id"}
            }else{
                if($row.damageEvents -ne 1){throw "Original native incoming event was not invoked exactly once: $id"}
                if($id -ceq 'perfect-c-guard-counter'){
                    if($row.totalHealthLoss -ne 0 -or $incoming[0].canceled -ne $true){throw 'Perfect guard did not cancel the native contact'}
                }elseif($row.totalHealthLoss -le 0){throw "Actual native contact had no health effect: $id"}
            }
            if($id -cne 'perfect-c-guard-counter' -and $row.completedAttacks -ne 1){throw "Native attack did not finish exactly one recovery: $id"}
        }
    }
    $auto=@($report.scenarios | Where-Object {$_.id -ceq 'zombie-ai-auto'})[0]
    if($auto.startedByVanillaAi -ne $true){throw 'A genuinely running vanilla melee AI did not initiate its own attack'}
    $guard=@($report.scenarios | Where-Object {$_.id -ceq 'ordinary-c-guard'})[0]
    if($guard.totalHealthLoss -ge $auto.totalHealthLoss){throw 'Held ordinary C guard did not reduce actual contact damage'}
    if(@($report.stunTicks).Count -ne 5){throw 'Five exact stun boundary observations are required'}
    $stunBase=0
    foreach($elapsed in 0..4){
        $rows=@($report.stunTicks | Where-Object {$_.elapsed -eq $elapsed})
        if($rows.Count -ne 1 -or $rows[0].remaining -ne (4-$elapsed)){throw "Actual NPC stun boundary differs at T+$elapsed"}
        if($elapsed -eq 0){$stunBase=$rows[0].completedNativeTicks}
        elseif($elapsed -lt 4 -and $rows[0].completedNativeTicks -ne $stunBase){throw 'Stunned NPC continued actual entity work'}
        elseif($elapsed -eq 4 -and $rows[0].completedNativeTicks -le $stunBase){throw 'NPC did not resume at the exact fourth tick'}
    }
    $render=$report.renderEvidence
    if($render.recording -cne 'render-capture.json' -or $render.source -cne 'SERVER' -or $render.nativeSourceFps -lt 30 -or
        $render.windupFrames -lt 1 -or $render.contactFrames -lt 1 -or $render.recoveryFrames -lt 1 -or
        $render.invalidRenderedSamples -ne 0 -or $render.distinctPoseHashes -le 10 -or $render.changedRigPairs -le 10 -or
        $render.actualRenderFrames -lt 30 -or $render.distinctSamplesWithinGameTick -lt 1){throw 'Real submitted server-clocked creature quality did not pass'}
    $capture=ReadJson (ChildPath $gameEvidence $render.recording)
    if($capture.captureEvent -cne 'RenderFrameEvent.Post' -or $capture.pendingWrites -ne 0 -or @($capture.clips).Count -ne 1){throw 'Native recording is incomplete'}
    $clip=@($capture.clips)[0];$frames=@($clip.frames)
    if($clip.id -cne 'zombie-native-contact' -or $frames.Count -lt 30 -or $clip.capturedFrames -ne $frames.Count -or $clip.writtenFrames -ne $frames.Count -or $render.actualRenderFrames -ne $frames.Count){throw 'Native PNG frame inventory is incomplete'}
    $duration=$frames[-1].capturedAtSeconds-$frames[0].capturedAtSeconds
    if($duration -le 0){throw 'Native source time span is empty'}
    $fps=($frames.Count-1)/$duration
    RequireNear $fps $clip.sourceFps 'Raw native FPS'
    RequireNear $fps $render.nativeSourceFps 'Reported native FPS'
    if($fps -lt 30){throw 'Source timestamps do not sustain genuine 30 Hz'}
    $video=@($report.scenarios | Where-Object {$_.id -ceq 'zombie-contact'})[0]
    $catalog=ReadJson (Join-Path $stagedConfig 'creature_combat.json')
    $definitions=@($catalog.attacks | Where-Object {$_.entityType -ceq 'minecraft:zombie' -and $_.clip -ceq $video.clip})
    if($definitions.Count -ne 1){throw 'Recording has no unambiguous staged source-clip definition'}
    $definition=$definitions[0]
    if($video.contactGameTick-$video.startGameTick -ne $definition.windupTicks -or $video.endGameTick-$video.contactGameTick -ne $definition.recoveryTicks -or $render.serverContactGameTick -ne $video.contactGameTick){throw 'Recording clock differs from the actual configured server attack'}
    $previousTime=-1.;$previousFrame=-1L;$previousElapsed=-1.;$poses=@{};$phases=@{};$serverFrames=0;$changed=0;$subticks=0;$animatedSubticks=0;$previousRig='';$previousGameTime=[double]::NaN;$entityUUID=''
    for($i=0;$i -lt $frames.Count;$i++){
        $frame=$frames[$i]
        if($frame.index -ne $i -or $frame.capturedAtSeconds -le $previousTime -or $frame.renderFrame -le $previousFrame -or $frame.paused -ne $false -or $frame.levelRunningNormally -ne $true){throw 'Native frames were duplicated, paused or reordered'}
        if($frame.file -cne ('screenshots/render-frames/'+$clip.id+'/frame-'+$i.ToString('D6')+'.png')){throw 'Native source PNG filenames do not match unique frame indices'}
        $image=ChildPath $gameEvidence $frame.file
        if(-not (Test-Path -LiteralPath $image -PathType Leaf) -or (Get-Item -LiteralPath $image).Length -le 0){throw 'A genuine captured source PNG is missing'}
        if(-not [double]::IsNaN($previousGameTime) -and [Math]::Floor($previousGameTime) -eq [Math]::Floor($frame.gameTimeTicks) -and [Math]::Abs($previousGameTime-$frame.gameTimeTicks) -gt .00001){$subticks++}
        $previousGameTime=$frame.gameTimeTicks;$previousTime=$frame.capturedAtSeconds;$previousFrame=$frame.renderFrame
        $observation=$frame.observation
        if($observation.PSObject.Properties.Name -notcontains 'source' -or $observation.source -cne 'SERVER'){continue}
        $serverFrames++
        if($entityUUID -ceq ''){$entityUUID=$observation.entityUUID}
        if($observation.sequence -ne $video.sequence -or $observation.clip -cne $video.clip -or
            $observation.startedGameTime -ne $video.startGameTick -or $observation.contactGameTime -ne $video.contactGameTick -or
            $observation.endedGameTime -ne $video.endGameTick -or $observation.elapsedTicks -lt $previousElapsed -or
            $observation.poseHash -cnotmatch '^[0-9a-f]{64}$' -or $observation.rig -cne $observation.poseHash -or
            $observation.entityUUID -cne $entityUUID -or $entityUUID -notmatch '^[0-9a-fA-F-]{36}$' -or
            $observation.nativePoseSnapshotStage -cne 'CreatureArtRenderer.submit actual mesh submissions'){throw 'Actual rendered payload origin, sequence or FK source is inconsistent'}
        $elapsed=[double]$observation.elapsedTicks
        if($elapsed -lt 0 -or $elapsed -ge ($definition.windupTicks+$definition.recoveryTicks)){throw 'Actual rendered server elapsed time is outside its attack'}
        $phase=if($elapsed -lt $definition.windupTicks){'WINDUP'}elseif($elapsed -lt ($definition.windupTicks+1)){'CONTACT'}else{'RECOVERY'}
        $seconds=if($elapsed -le $definition.windupTicks){$definition.contactSeconds*$elapsed/$definition.windupTicks}else{$definition.contactSeconds+($definition.durationSeconds-$definition.contactSeconds)*($elapsed-$definition.windupTicks)/$definition.recoveryTicks}
        if($observation.phase -cne $phase){throw 'Rendered phase disagrees with the actual server tick interval'}
        RequireNear $seconds $observation.sampleSeconds 'Actual piecewise server clip clock'
        if($previousElapsed -ge 0 -and [Math]::Floor($previousElapsed) -eq [Math]::Floor($elapsed) -and
            [Math]::Abs($previousElapsed-$elapsed) -gt .00001 -and $previousRig -cne $observation.poseHash){$animatedSubticks++}
        if($previousRig -ne '' -and $previousRig -cne $observation.poseHash){$changed++}
        $previousRig=$observation.poseHash;$previousElapsed=$elapsed;$poses[$observation.poseHash]=$true;$phases[$phase]=$true
    }
    if($serverFrames -lt 30 -or $poses.Count -le 10 -or $changed -le 10 -or $subticks -lt 1 -or $animatedSubticks -lt 1 -or
        -not $phases.ContainsKey('WINDUP') -or -not $phases.ContainsKey('CONTACT') -or -not $phases.ContainsKey('RECOVERY')){throw 'Raw native recording does not prove changing sub-tick server-clocked FK in all three phases'}
}
CheckCreatureReview $review (Join-Path $evidence 'game') $stage
if($migration.passed -ne $true -or $migration.version -cne '0.7.4' -or $migration.sourceCopiedByteExactly -ne $true -or
    $migration.officialSourceUntouched -ne $true -or $migration.allOtherExistingFieldsAndFilesPreserved -ne $true -or
    $migration.exactOriginalBackups -ne $true -or $migration.secondReloadIdempotent -ne $true -or
    $migration.baseMaxStamina -ne 50 -or $migration.baseStaminaRegenPerSecond -ne 5 -or
    $migration.levelSource -cne 'vanilla experienceLevel' -or $migration.creatureCatalogDefinitionCount -lt 1 -or
    $migration.creatureCatalogValidatedAgainstSuppliedArt -ne $true){throw 'Staged 0.7.4 configuration failed exact field/backup validation'}
if($migration.staminaLevelScaling.regenSqrtCoefficient -ne 1 -or $migration.staminaLevelScaling.regenLogCoefficient -ne 1 -or
    $migration.staminaLevelScaling.maxPowerCoefficient -ne 1 -or $migration.staminaLevelScaling.maxPowerExponent -ne .8 -or
    $migration.staminaLevelScaling.maxLogCoefficient -ne 3){throw 'Staged level formula does not match the requested coefficients'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
function ModToml([string]$path){
    $archive=[IO.Compression.ZipFile]::OpenRead($path)
    try{
        $entry=$archive.GetEntry('META-INF/neoforge.mods.toml');if($null -eq $entry){return ''}
        $reader=[IO.StreamReader]::new($entry.Open());try{return $reader.ReadToEnd()}finally{$reader.Dispose()}
    }finally{$archive.Dispose()}
}
function IsFsrpg([string]$path){
    $toml=ModToml $path
    return [regex]::IsMatch(($toml -split '\[\[dependencies\.')[0],'(?m)^\s*modId\s*=\s*"fsrpg"')
}
$archive=[IO.Compression.ZipFile]::OpenRead($jar)
try{
    if(@($archive.Entries | Where-Object {$_.FullName -match '(^org/fsrpg/smoke/)|MonitorAgent|forging|ArtContent|art/combat_arena'}).Count){throw 'Smoke code or excluded other-project materials found in release JAR'}
}finally{$archive.Dispose()}
if(-not (IsFsrpg $jar) -or (ModToml $jar) -notmatch '(?m)^\s*version\s*=\s*"0\.7\.4"'){throw 'Candidate JAR metadata is not FSRPG 0.7.4'}
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
if((Hash (Join-Path $mods 'geckolib-neoforge-1.21.11-5.4.5.jar')) -cne (Hash (Join-Path $project 'run/client/mods/geckolib-neoforge-1.21.11-5.4.5.jar'))){throw 'Official creature rendering library differs from the tested library'}
$before=HashMap $migration.beforeSha256
$after=HashMap $migration.afterSha256
SameTree $before $config 'Official configuration after staging'
SameTree $after $stage 'Staged configuration after validation'
$edited=@('combat.json')
$allowedNew=@('stamina-level-migration-v1.json','stamina-level-backup-v1/combat.json','creature_combat.json')
$newFiles=@()
foreach($name in $before.Keys){
    if(-not $after.ContainsKey($name)){throw "Staged migration removed existing file: $name"}
    if($edited -notcontains $name -and $before[$name] -cne $after[$name]){throw "Staged migration changed unrelated existing file: $name"}
}
foreach($name in $after.Keys){
    [void](ChildPath $stage $name);[void](ChildPath $config $name)
    if($before.ContainsKey($name)){continue}
    if($allowedNew -notcontains $name){throw "Unapproved new staged file: $name"}
    $newFiles+=,$name
}
foreach($name in $edited){if(-not $before.ContainsKey($name) -or -not $after.ContainsKey($name)){throw "Missing edited configuration: $name"}}
if(-not $newFiles.Contains('stamina-level-migration-v1.json') -or -not $newFiles.Contains('stamina-level-backup-v1/combat.json') -or
    $after['stamina-level-backup-v1/combat.json'] -cne $before['combat.json']){throw 'Exact original stamina migration backup/marker is missing'}
if($newFiles.Contains('creature_combat.json')){
    $archive=[IO.Compression.ZipFile]::OpenRead($jar)
    try{
        $entry=$archive.GetEntry('defaults/creature_combat.json');if($null -eq $entry){throw 'Candidate has no creature combat seed'}
        $stream=$entry.Open();try{$seedSha=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)).ToLowerInvariant()}finally{$stream.Dispose()}
        if($after['creature_combat.json'] -cne $seedSha){throw 'Staged creature seed differs from the exact tested JAR'}
    }finally{$archive.Dispose()}
}
$old=@(Get-ChildItem -LiteralPath $mods -File -Filter '*.jar' | Where-Object {IsFsrpg $_.FullName})
$oldPaths=@($old | ForEach-Object {$_.FullName})
$installed=ChildPath $mods 'fsrpg-neoforge-0.7.4.jar'
if((Test-Path -LiteralPath $installed) -and $oldPaths -notcontains $installed){throw 'Candidate release filename belongs to an unrelated mod'}
$preserved=@(Get-ChildItem -LiteralPath $mods -Recurse -File | Where-Object {$oldPaths -notcontains $_.FullName} | ForEach-Object {@{path=$_.FullName;sha256=(Hash $_.FullName)}})
foreach($name in $before.Keys){if($edited -notcontains $name){$preserved+=@{path=(ChildPath $config $name);sha256=$before[$name]}}}
foreach($name in @('options.txt','config/fsrpg-client.toml')){
    $path=ChildPath $instance $name;if(Test-Path -LiteralPath $path){$preserved+=@{path=$path;sha256=(Hash $path)}}
}
if($VerifyOnly){
    Write-Output "VERIFY ONLY PASSED: exact candidate $sha; real DISPLAY5 report and native clock/quality evidence; staged config hashes and dependency hashes; official instance stopped. No files were written."
    return
}
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$backup=ChildPath $instance "fsrpg-backups/$stamp-before-0.7.4"
if(Test-Path -LiteralPath $backup){throw 'Dated backup already exists'}
New-Item -ItemType Directory -Path (Join-Path $backup 'mods') -Force | Out-Null
Copy-Item -LiteralPath $config -Destination (Join-Path $backup 'fsrpg') -Recurse
SameTree $before (Join-Path $backup 'fsrpg') 'Full configuration backup'
$previous=@()
foreach($file in $old){
    if($file.DirectoryName -cne $mods -or $file.Extension -cne '.jar'){throw 'Previous mod is outside the exact mods directory'}
    $saved=ChildPath (Join-Path $backup 'mods') $file.Name
    Copy-Item -LiteralPath $file.FullName -Destination $saved
    if((Hash $saved) -cne (Hash $file.FullName)){throw 'Previous FSRPG JAR backup hash mismatch'}
    $previous+=@{path=$file.FullName;backup=$saved;sha256=(Hash $saved)}
}
# Recheck immediately before mutation: never overwrite newer user edits or newly loaded files.
AssertInstanceStopped
SameTree $before $config 'Official configuration before deployment'
$created=@();$installedTouched=$false
try{
    foreach($file in $previous){Remove-Item -LiteralPath $file.path}
    $installedTouched=$true;Copy-Item -LiteralPath $jar -Destination $installed
    foreach($name in $edited){Copy-Item -LiteralPath (ChildPath $stage $name) -Destination (ChildPath $config $name) -Force}
    foreach($name in ($newFiles | Sort-Object {$_ -notmatch '-backup-v1/'},{$_})){
        $target=ChildPath $config $name
        if(Test-Path -LiteralPath $target){throw "Unexpected concurrent new migration file: $name"}
        New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
        $created+=,$target;Copy-Item -LiteralPath (ChildPath $stage $name) -Destination $target
    }
    if((Hash $installed) -cne $sha){throw 'Installed JAR is not the exact tested candidate'}
    SameTree $after $config 'Deployed configuration'
    foreach($file in $preserved){if((Hash $file.path) -cne $file.sha256){throw "Preserved file changed: $($file.path)"}}
    $record=[ordered]@{version='0.7.4';installedJar=$installed;jarSha256=$sha;testedJarSha256=$review.modJarSha256;backup=$backup;
        previousMods=$previous;editedConfiguration=$edited;newMigrationFiles=$newFiles;configurationSha256=$after;
        baseMaxStamina=50;staminaRegenPerSecond=5;staminaLevelScaling=$migration.staminaLevelScaling;thrustDurationSeconds=.2;perfectGuardTicks=2;counterWindowTicks=4;counterDamageMultiplier=1.5;parryStunTicks=4;
        singleSwordCost=5;dualBladeCost=8;spearThrustCost=10;rapidCost=5;snipeCost=15;partialRapidCost=3.5;partialSnipeCost=10.5;
        existingConfigurationFieldsPreserved=$true;worldsModified=$false;otherModsPreserved=$true;preserved=$preserved;
        evidence=$evidence;deployedAt=(Get-Date).ToString('o')}
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
    if($rollbackErrors.Count -gt 0){throw "Deployment failed: $originalFailure; rollback needs attention: $($rollbackErrors -join ' | '); backup: $backup"}
    throw $originalFailure
}
Write-Output "Installed $installed; SHA-256 $sha; tested and deployed hashes match; backup $backup"
