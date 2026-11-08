$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$build = Join-Path $projectRoot 'build/horizon'
$python = $env:UNKNOWNMOD_PYTHON
if (-not $python) { $python = (Get-Command python -ErrorAction SilentlyContinue).Source }
if (-not $python) { throw 'Install Python 3.11+ with Pillow, or set UNKNOWNMOD_PYTHON.' }
$env:CARGO_HOME = Join-Path $build 'cargo'
$env:RUSTUP_HOME = Join-Path $build 'rustup'
$rustBin = Join-Path $env:RUSTUP_HOME 'toolchains/stable-x86_64-pc-windows-msvc/bin'
$env:PATH = $rustBin + ';' + $env:PATH
$source = Join-Path $build 'client-mod-source'
$expected = '2ebf00a0281c592f79191e76fce0e7d522c6f425'
if ((git -C $source rev-parse HEAD) -ne $expected) { throw 'Prepare the pinned client-mod-source checkout first.' }
& $python (Join-Path $PSScriptRoot 'package-client.py')
if ($LASTEXITCODE -ne 0) { throw 'Client packaging failed.' }
$stage = Join-Path $build 'client-6.1-source'
$mixins = Join-Path $build 'mixins-source/horizon-mixins-e8cd0aa38cffef49cb2e088ef716baa107148c02/target/release/horizon-mixins.exe'
& $mixins apply --workspace $source --mods (Join-Path $build 'client-bundles') --out $stage
if ($LASTEXITCODE -ne 0) { throw 'Applying client bundles failed.' }
$manifest = Join-Path $stage 'Cargo.toml'
$content = [IO.File]::ReadAllText($manifest)
if ($content -notmatch '\[profile.fast.package.horizon-client\]') {
    [IO.File]::WriteAllText($manifest, ($content + "`n[profile.fast.package.horizon-client]`nopt-level = 0`n"), (New-Object Text.UTF8Encoding($false)))
}
$previousTarget = $env:CARGO_TARGET_DIR
try {
    $env:CARGO_TARGET_DIR = Join-Path $build 'client-6.1-target'
    & (Join-Path $rustBin 'cargo.exe') build --locked --profile fast --manifest-path $manifest -p horizon-client --bin horizon-client -j 2
    if ($LASTEXITCODE -ne 0) { throw 'Building the ready client failed.' }
} finally { $env:CARGO_TARGET_DIR = $previousTarget }
& $python (Join-Path $PSScriptRoot 'package-ready-client.py')
if ($LASTEXITCODE -ne 0) { throw 'Packaging the ready client failed.' }
