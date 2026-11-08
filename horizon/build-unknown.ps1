$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$horizonBuild = Join-Path $projectRoot 'build/horizon'
$env:CARGO_HOME = Join-Path $horizonBuild 'cargo'
$env:RUSTUP_HOME = Join-Path $horizonBuild 'rustup'
$rustBin = Join-Path $env:RUSTUP_HOME 'toolchains/stable-x86_64-pc-windows-msvc/bin'
$env:PATH = $rustBin + ';' + $env:PATH
$python = $env:UNKNOWNMOD_PYTHON
if (-not $python) { $python = (Get-Command python -ErrorAction SilentlyContinue).Source }
if (-not $python) { throw 'Install Python 3.11+ with Pillow, or set UNKNOWNMOD_PYTHON to its executable.' }
& $python (Join-Path $PSScriptRoot 'prepare-unknown.py')
if ($LASTEXITCODE -ne 0) { throw 'Preparing UnknownMod failed.' }
& (Join-Path $rustBin 'cargo.exe') test --offline --manifest-path (Join-Path $PSScriptRoot 'unknownmod/Cargo.toml') -j 2
if ($LASTEXITCODE -ne 0) { throw 'UnknownMod rule scenarios failed.' }
& (Join-Path $rustBin 'cargo.exe') build --offline --manifest-path (Join-Path $horizonBuild 'voice-game/Cargo.toml') --locked --profile fast -p horizon-client --bin horizon-client -j 2
if ($LASTEXITCODE -ne 0) { throw 'Building UnknownMod for Horizon failed.' }
$destination = Join-Path $horizonBuild 'unknown-bin'
New-Item -ItemType Directory -Force -Path $destination | Out-Null
$compiledEngine = Join-Path $horizonBuild 'voice-game/target/fast/horizon-client.exe'
$packagedEngine = Join-Path $destination 'horizon-client.exe'
# An unchanged, running Windows executable need not be replaced.
$sameEngine = (Test-Path -LiteralPath $packagedEngine) -and ((Get-FileHash -LiteralPath $compiledEngine -Algorithm SHA256).Hash -eq (Get-FileHash -LiteralPath $packagedEngine -Algorithm SHA256).Hash)
if (-not $sameEngine) { Copy-Item -LiteralPath $compiledEngine -Destination $packagedEngine }
Copy-Item -LiteralPath (Join-Path $projectRoot 'LICENSE') -Destination (Join-Path $destination 'LICENSE-UnknownMod')
Copy-Item -Path (Join-Path $horizonBuild 'voice-bin/LICENSE*') -Destination $destination
Get-FileHash -LiteralPath (Join-Path $destination 'horizon-client.exe') -Algorithm SHA256
