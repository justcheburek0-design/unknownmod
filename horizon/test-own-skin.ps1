$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$rustBin = Join-Path $projectRoot 'build/horizon/rustup/toolchains/stable-x86_64-pc-windows-msvc/bin'
$deps = Join-Path $projectRoot 'build/horizon/voice-game/target/fast/deps'
$output = Join-Path $projectRoot 'build/horizon/test-own-skin.exe'
$arguments = @('--edition=2024', (Join-Path $PSScriptRoot 'test-own-skin.rs'), '-L', "dependency=$deps", '-o', $output)
foreach ($dependency in @('horizon_protocol', 'horizon_world', 'unknownmod_rules')) {
    $library = Get-ChildItem -LiteralPath $deps -Filter "lib$dependency-*.rlib" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $library) { throw "Build the server before this probe: missing $dependency" }
    $arguments += @('--extern', "$dependency=$($library.FullName)")
}
& (Join-Path $rustBin 'rustc.exe') @arguments
if ($LASTEXITCODE -ne 0) { throw 'Skin regression probe compilation failed.' }
& $output
if ($LASTEXITCODE -ne 0) { throw 'Skin regression probe failed.' }
