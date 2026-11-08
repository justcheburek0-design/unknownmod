param([string]$Directory = '', [switch]$VoiceOnly)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$horizonBuild = Join-Path $projectRoot 'build/horizon'
$serverDir = Join-Path $horizonBuild 'server'
if ($Directory) { $serverDir = [IO.Path]::GetFullPath($Directory) }
$supervisor = Join-Path $horizonBuild 'runtime/horizon-windows-x64/horizon-server.exe'
$engine = Join-Path $horizonBuild 'voice-bin/horizon-client.exe'
if (-not $VoiceOnly -and (Test-Path -LiteralPath (Join-Path $horizonBuild 'unknown-bin/horizon-client.exe'))) {
    $engine = Join-Path $horizonBuild 'unknown-bin/horizon-client.exe'
    $env:UNKNOWNMOD_ENABLED = '1'
} else { $env:UNKNOWNMOD_ENABLED = '0' }
if (-not (Test-Path -LiteralPath $engine)) { throw 'The Horizon voice build is not ready yet.' }
$configPath = Join-Path $serverDir 'server.toml'
$config = Get-Content -Raw -LiteralPath $configPath
$engineTomlPath = $engine.Replace('\', '/')
$config = [regex]::Replace($config, '(?m)^path = "[^"]*"', ('path = "' + $engineTomlPath + '"'))
[IO.File]::WriteAllText($configPath, $config, (New-Object Text.UTF8Encoding($false)))
$voiceConfigDir = Join-Path $serverDir 'config'
New-Item -ItemType Directory -Force -Path $voiceConfigDir | Out-Null
$voiceConfigPath = Join-Path $voiceConfigDir 'voicechat-server.json'
if (-not (Test-Path -LiteralPath $voiceConfigPath)) {
    [IO.File]::WriteAllText($voiceConfigPath, '{"enabled":true,"bind":"127.0.0.1","port":24455,"distance":48.0,"whisper_distance":24.0,"groups":true}')
}
$env:HORIZON_HOME = Join-Path $serverDir '.horizon/unknown-home'
$env:XDG_DATA_HOME = Join-Path $serverDir '.horizon/unknown-data'
$env:XDG_CONFIG_HOME = Join-Path $serverDir '.horizon/unknown-config'
$env:HORIZON_NO_UPDATE_CHECK = '1'
& $supervisor run --dir $serverDir
