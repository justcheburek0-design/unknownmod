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
& $python (Join-Path $PSScriptRoot 'prepare-voice.py')
if ($LASTEXITCODE -ne 0) { throw 'Preparing the voice adapters failed.' }
$source = Join-Path $horizonBuild 'verified-source'
$expected = (Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'sources.json') | ConvertFrom-Json).horizon.commit
$actual = git -C $source rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $actual -ne $expected) { throw 'Horizon checkout does not match sources.json.' }
$mixins = Join-Path $horizonBuild 'mixins-source/horizon-mixins-e8cd0aa38cffef49cb2e088ef716baa107148c02/target/release/horizon-mixins.exe'
$stage = Join-Path $horizonBuild 'voice-game'
# Windows cannot atomically replace the mixins stage. Retain the old source and
# move its compilation cache back only after applying successfully to a fresh path.
$oldStage = $null
if (Test-Path -LiteralPath $stage) {
    $oldStage = Join-Path $horizonBuild ('voice-previous-' + [guid]::NewGuid().ToString('N'))
    $allowedRoot = [IO.Path]::GetFullPath($horizonBuild).TrimEnd('\') + '\'
    foreach ($movePath in @($stage, $oldStage)) {
        if (-not [IO.Path]::GetFullPath($movePath).StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Stage move escaped the Horizon build directory.'
        }
    }
    Move-Item -LiteralPath $stage -Destination $oldStage
}
& $mixins apply --workspace $source --mods (Join-Path $horizonBuild 'voice-bundles') --out $stage
if ($LASTEXITCODE -ne 0) { throw "Applying the native voice bundles failed. Previous source retained at $oldStage" }
$env:CARGO_TARGET_DIR = Join-Path $stage 'target'
if ($oldStage -and (Test-Path -LiteralPath (Join-Path $oldStage 'target'))) {
    $oldTarget = [IO.Path]::GetFullPath((Join-Path $oldStage 'target'))
    $newTarget = [IO.Path]::GetFullPath($env:CARGO_TARGET_DIR)
    foreach ($movePath in @($oldTarget, $newTarget)) {
        if (-not $movePath.StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Cache move escaped the Horizon build directory.'
        }
    }
    Move-Item -LiteralPath $oldTarget -Destination $newTarget
}
& (Join-Path $rustBin 'cargo.exe') build --manifest-path (Join-Path $stage 'Cargo.toml') --locked --profile fast -p horizon-client --bin horizon-client -j 2
if ($LASTEXITCODE -ne 0) { throw 'Building the voice-enabled Horizon engine failed.' }
$voiceBin = Join-Path $horizonBuild 'voice-bin'
New-Item -ItemType Directory -Force -Path $voiceBin | Out-Null
Copy-Item -LiteralPath (Join-Path $env:CARGO_TARGET_DIR 'fast/horizon-client.exe') -Destination (Join-Path $voiceBin 'horizon-client.exe')
Remove-Item Env:CARGO_TARGET_DIR
& (Join-Path $rustBin 'cargo.exe') build --release --manifest-path (Join-Path $PSScriptRoot 'voice-proof/Cargo.toml') --locked -j 2
if ($LASTEXITCODE -ne 0) { throw 'Building the audio proof failed.' }
