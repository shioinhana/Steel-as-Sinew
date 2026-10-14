param([string]$Entry,[switch]$Neo)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$sourceJar = if ($Neo) { 'C:\Users\34003\.gradle\caches\modules-2\files-2.1\net.neoforged\neoforge\21.11.45\d9fabd450a07bc163aa8c632ab2373e0d9fef3a7\neoforge-21.11.45-sources.jar' } else { 'C:\Users\34003\Desktop\fsmod\FSVOXEL-Renderer-0.6.0-Windows\FSVOXEL-Renderer-0.6.0-Windows\源码\FSVOXEL\fsvoxel-minecraft\build\moddev\artifacts\neoforge-21.11.45-sources.jar' }
$sourceZip=[IO.Compression.ZipFile]::OpenRead($sourceJar)
try {
    $sourceEntry=$sourceZip.GetEntry($Entry)
    if (!$sourceEntry) { throw "Source not found: $Entry" }
    $sourceReader=[IO.StreamReader]::new($sourceEntry.Open())
    try { $sourceReader.ReadToEnd() -split "`n" } finally { $sourceReader.Dispose() }
} finally { $sourceZip.Dispose() }
