param([string]$SkinTarget = '')
# Invisible QA client only. Never selects or opens the user's launcher profile.
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object Text.UTF8Encoding($false)
$OutputEncoding = [Console]::OutputEncoding
$root = Split-Path $PSScriptRoot -Parent
$build = Join-Path $root 'build/horizon'
$qa = Join-Path $build 'ui-qa-6.1'
$control = Join-Path $qa 'control.json'
New-Item -ItemType Directory -Force -Path $qa | Out-Null
if (Test-Path -LiteralPath $control) { Remove-Item -LiteralPath $control }
$env:HORIZON_HOME = Join-Path $qa 'home'
$env:XDG_DATA_HOME = Join-Path $qa 'data'
$env:XDG_CONFIG_HOME = Join-Path $qa 'config'
$env:HORIZON_NO_UPDATE_CHECK = '1'
$env:HORIZON_NO_AUDIO = '1'
$env:UNKNOWNMOD_ENABLED = '0'
$engine = Join-Path $build 'client-6.1-target/fast/horizon-client.exe'
$ctl = Join-Path $build 'runtime/horizon-windows-x64/horizon-ctl.exe'
& (Join-Path $build 'runtime/horizon-windows-x64/horizon-server.exe') cmd 'deop MenuVisualQA' --dir (Join-Path $build 'unknown-test-server') | Out-Null
$arguments = @('--offscreen','--width','1280','--height','720','--resources',('"'+(Join-Path $build 'resources/26.3')+'"'),'--game-dir',('"'+$qa+'"'),'--home',('"'+$env:HORIZON_HOME+'"'),'--name','MenuVisualQA','--server','127.0.0.1:25569','--render-distance','2','--control','--control-socket',('"'+$control+'"'))
$client = Start-Process -FilePath $engine -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $qa 'client.log') -RedirectStandardError (Join-Path $qa 'client.err.log')
function Invoke-Control([string[]]$Words) {
    $output = & $ctl --socket $control --timeout 40 @Words
    if ($LASTEXITCODE -ne 0) { throw "QA control failed: $Words $output" }
    $output -join "`n"
}
try {
    $deadline = (Get-Date).AddSeconds(120)
    while (-not (Test-Path -LiteralPath $control)) {
        if ($client.HasExited -or (Get-Date) -gt $deadline) { throw 'Invisible QA client did not open its control socket.' }
        Start-Sleep -Milliseconds 500
    }
    Invoke-Control @('wait-ready') | Out-Null
    if ($SkinTarget) {
        $server = Join-Path $build 'runtime/horizon-windows-x64/horizon-server.exe'
        $directory = Join-Path $build 'unknown-test-server'
        foreach ($command in @('tp MenuVisualQA 0 -60 0',"tp $SkinTarget 4 -60 0 90 0","unknown hide $SkinTarget")) { & $server cmd $command --dir $directory | Out-Null }
        Start-Sleep -Seconds 1
        Invoke-Control @('look-at','4.5','-58.5','0.5') | Out-Null
        Start-Sleep -Seconds 2
        Invoke-Control @('screenshot',(Join-Path $build 'alias-skin-hidden.png')) | Out-Null
        & $server cmd "unknown reveal $SkinTarget 1" --dir $directory | Out-Null
        Start-Sleep -Seconds 4
        Invoke-Control @('screenshot',(Join-Path $build 'alias-skin-revealed.png')) | Out-Null
        & $server cmd "unknown hide $SkinTarget" --dir $directory | Out-Null
        Start-Sleep -Seconds 2
        Invoke-Control @('screenshot',(Join-Path $build 'alias-skin-restored.png')) | Out-Null
    }
    Invoke-Control @('key','u','tap') | Out-Null
    Start-Sleep -Seconds 2
    $public = Invoke-Control @('ui')
    [IO.File]::WriteAllText((Join-Path $qa 'player-ui.json'),$public,(New-Object Text.UTF8Encoding($false)))
    Invoke-Control @('screenshot',(Join-Path $build 'menu-player.png')) | Out-Null
    $view = $public | ConvertFrom-Json
    if ($view.screen -ne 'options' -or -not ($view.widgets | Where-Object { $_.id -like '*-unknownmod' })) { throw 'U did not open the UnknownMod menu.' }
    if ($public -match '\u0420\u0430\u0441\u043A\u0440\u044B\u0442\u044C') { throw 'Ordinary QA client received administrative controls.' }
    Write-Output 'PASS: U opens the menu; ordinary player has no admin controls.'
    & (Join-Path $build 'runtime/horizon-windows-x64/horizon-server.exe') cmd 'op MenuVisualQA' --dir (Join-Path $build 'unknown-test-server') | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not grant isolated QA permissions.' }
    Start-Sleep -Seconds 2
    $admin = Invoke-Control @('ui')
    if ($admin -notmatch '\u0420\u0430\u0441\u043A\u0440\u044B\u0442\u044C') { throw 'Operator menu did not update after permission change.' }
    Invoke-Control @('screenshot',(Join-Path $build 'menu-operator.png')) | Out-Null
    Write-Output 'PASS: operator controls appear after the server grants permission.'
} finally {
    try { Invoke-Control @('quit') | Out-Null } catch { if (-not $client.HasExited) { Stop-Process -Id $client.Id } }
}
