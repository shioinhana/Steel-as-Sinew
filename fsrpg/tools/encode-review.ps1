param([string]$EvidenceDirectory = (Join-Path (Split-Path -Parent $PSScriptRoot) 'verification\rapier-r6'))
$ErrorActionPreference = 'Stop'
$encoder = (Get-Command ffmpeg -ErrorAction Stop).Source
$probe = (Get-Command ffprobe -ErrorAction Stop).Source
$evidencePath = (Resolve-Path -LiteralPath $EvidenceDirectory).Path
$capture = Get-Content -LiteralPath (Join-Path $evidencePath 'render-capture.json') -Raw | ConvertFrom-Json
if ($capture.captureEvent -ne 'RenderFrameEvent.Post' -or $capture.pendingWrites -ne 0) {
    throw 'Capture must contain completed real render frames; the old tick-based PNG sequence is unsupported'
}
$clips = @($capture.clips)
if ($clips.Count -ne 10) { throw 'Capture gameplay combo, actual player input, moving attacks, combined thrust, sprint lean and idle breathing before encoding' }
foreach ($required in @('actual-input-movement','sprint-directional-lean','idle-breathing','idle-inspection-1','idle-inspection-2','idle-inspection-3','combat-cancels')) {
    if ($required -notin $clips.id) { throw "Missing capture: $required" }
}
$invariant = [Globalization.CultureInfo]::InvariantCulture
$utf8 = [Text.UTF8Encoding]::new($false)
$encoded = [Collections.Generic.List[object]]::new()
$movieEntries = [Collections.Generic.List[string]]::new()
$movieEntries.Add('ffconcat version 1.0')
foreach ($clip in $clips) {
    if ($clip.id -notmatch '^[a-z0-9-]+$') { throw 'Invalid capture ID' }
    $frames = @($clip.frames)
    if ($frames.Count -lt 31 -or $clip.writtenFrames -ne $frames.Count) { throw "Incomplete captured clip: $($clip.id)" }
    $duration = [double]$frames[-1].capturedAtSeconds - [double]$frames[0].capturedAtSeconds
    if ($duration -le 0) { throw 'Capture timestamps must advance' }
    $sourceFps = ($frames.Count - 1) / $duration
    if ($sourceFps -lt 30 -or $clip.distinctSamplesWithinGameTick -lt 1 -or $clip.changedRigPairs -lt 1) {
        throw "Clip $($clip.id) has no verified >=30 fps moving render samples (source=$sourceFps fps). Recapture; do not change playback speed."
    }
    $lines = [Collections.Generic.List[string]]::new()
    $lines.Add('ffconcat version 1.0')
    $imageHashes = [Collections.Generic.HashSet[string]]::new()
    for ($frame = 0; $frame -lt $frames.Count; $frame++) {
        $sample = $frames[$frame]
        $expectedFile = "screenshots/render-frames/$($clip.id)/frame-$('{0:000000}' -f $frame).png"
        if ($sample.index -ne $frame -or $sample.file -ne $expectedFile) { throw 'Unexpected capture sequence path/index' }
        $imageFile = Join-Path $evidencePath $expectedFile
        if (-not (Test-Path -LiteralPath $imageFile -PathType Leaf)) { throw "Missing actual game frame: $imageFile" }
        [void]$imageHashes.Add((Get-FileHash -Algorithm SHA256 -LiteralPath $imageFile).Hash)
        $gap = if ($frame + 1 -lt $frames.Count) {
            [double]$frames[$frame + 1].capturedAtSeconds - [double]$sample.capturedAtSeconds
        } else { 1.0 / $sourceFps }
        if ($gap -le 0 -or $gap -gt 0.25) { throw "Non-monotonic or stalled actual capture in $($clip.id): $gap seconds" }
        $lines.Add("file '$expectedFile'")
        # The per-image demuxer time base must represent sub-tick timestamps, rather than its default 25 fps.
        $lines.Add('option framerate 1000')
        $lines.Add('duration ' + $gap.ToString('0.000000000', $invariant))
    }
    if ($imageHashes.Count -lt 30) { throw "Clip $($clip.id) has fewer than 30 different real framebuffer images" }
    $lines.Add("file '$($frames[-1].file)'")
    $lines.Add('option framerate 1000')
    $timeline = Join-Path $evidencePath "$($clip.id)-timestamps.ffconcat"
    [IO.File]::WriteAllLines($timeline, $lines, $utf8)
    $movieName = "$($clip.id)-30fps.mp4"
    $movie = Join-Path $evidencePath $movieName
    & $encoder -hide_banner -loglevel warning -y -f concat -safe 0 -i $timeline -vf 'fps=30' -c:v libx264 -preset fast -crf 20 -pix_fmt yuv420p -movflags +faststart $movie
    if ($LASTEXITCODE -ne 0) { throw "Video encoding failed: $($clip.id)" }
    $video = & $probe -v error -select_streams v:0 -show_entries 'stream=avg_frame_rate,nb_frames,duration' -of json $movie | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0 -or $video.streams[0].avg_frame_rate -ne '30/1') { throw 'Encoded video did not retain 30 fps' }
    $movieEntries.Add("file '$movieName'")
    $encoded.Add([ordered]@{ id=$clip.id; source=$clip.source; capturedFrames=$frames.Count; uniqueFramebufferImages=$imageHashes.Count; sourceFps=$sourceFps; captureDurationSeconds=$duration; outputFps=30; output=$movieName; encodedFrames=[int]$video.streams[0].nb_frames })
    Write-Output "Encoded $($clip.id): $($frames.Count) real render frames at $sourceFps source fps -> 30 fps video using actual timestamps"
}
$combinedTimeline = Join-Path $evidencePath 'visual-review.ffconcat'
[IO.File]::WriteAllLines($combinedTimeline, $movieEntries, $utf8)
$output = Join-Path $evidencePath 'visual-review.mp4'
& $encoder -hide_banner -loglevel warning -y -f concat -safe 0 -i $combinedTimeline -vf fps=30 -c:v libx264 -preset fast -crf 20 -pix_fmt yuv420p -movflags +faststart $output
if ($LASTEXITCODE -ne 0) { throw 'Combined video encoding failed' }
$combinedProbe = & $probe -v error -select_streams v:0 -show_entries stream=avg_frame_rate -of json $output | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or $combinedProbe.streams[0].avg_frame_rate -ne '30/1') { throw 'Combined timeline must retain exact 30 fps' }
[ordered]@{ schemaVersion=1; captureEvent=$capture.captureEvent; outputFps=30; clips=$encoded; output='visual-review.mp4' } |
    ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $evidencePath 'encoded-review.json') -Encoding utf8
Write-Output "Encoded actual gameplay and moving pose review: $output"
