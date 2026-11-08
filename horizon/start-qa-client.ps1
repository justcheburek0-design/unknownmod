# Isolated, invisible renderer for automated skin verification. Never uses a player's profile.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$horizonBuild = Join-Path $projectRoot 'build/horizon'
$qaDir = Join-Path $horizonBuild 'qa-client'
New-Item -ItemType Directory -Force -Path $qaDir | Out-Null
$env:HORIZON_HOME = Join-Path $qaDir 'home'
$env:XDG_DATA_HOME = Join-Path $qaDir 'data'
$env:XDG_CONFIG_HOME = Join-Path $qaDir 'config'
$env:HORIZON_NO_UPDATE_CHECK = '1'
$env:HORIZON_NO_AUDIO = '1'
$env:UNKNOWNMOD_ENABLED = '0'
& (Join-Path $horizonBuild 'unknown-bin/horizon-client.exe') --offscreen --width 800 --height 600 --resources (Join-Path $horizonBuild 'resources/26.3') --game-dir $qaDir --home $env:HORIZON_HOME --name UnknownQA --server 127.0.0.1:25569 --render-distance 2 --control --control-socket (Join-Path $qaDir 'control.json')
