param([string]$Launcher = '', [string]$LauncherHome = '')
$ErrorActionPreference = 'Stop'
$manifest = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'release.json') | ConvertFrom-Json
$engine = Join-Path $PSScriptRoot 'horizon-client.exe'
if ((Get-FileHash -LiteralPath $engine -Algorithm SHA256).Hash.ToLower() -ne $manifest.sha256) {
    throw 'The game file checksum does not match release.json. Download the archive again.'
}
if (-not $Launcher) {
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA 'Programs/Horizon Launcher/horizon-launcher.exe'),
        (Join-Path $env:ProgramFiles 'Horizon Launcher/horizon-launcher.exe')
    )
    $Launcher = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
}
if (-not $Launcher) { throw 'Install the official Horizon launcher first: https://playhorizon.dev/docs/install' }
if ($LauncherHome) { $env:HORIZON_LAUNCHER_HOME = [IO.Path]::GetFullPath($LauncherHome) }
$logDir = Join-Path ([IO.Path]::GetTempPath()) ('unknownmod-install-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $logDir | Out-Null
$stdout = Join-Path $logDir 'import.log'
$stderr = Join-Path $logDir 'import.err.log'
# --version names the prepared official base, not the new imported version.
$arguments = @('import-build', ('"' + $engine + '"'), '--version', $manifest.base_version, '--trust-unsigned', '--json')
$process = Start-Process -FilePath $Launcher -ArgumentList $arguments -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
if ($process.ExitCode -ne 0) {
    $detail = Get-Content -Raw -LiteralPath $stderr -ErrorAction SilentlyContinue
    throw "Import failed. Prepare Horizon $($manifest.base_version) in the launcher first. $detail"
}
$result = Get-Content -Raw -LiteralPath $stdout | ConvertFrom-Json
if (-not $result.imported) { throw "The launcher did not confirm the import. Log: $stdout" }
Write-Host "UnknownMod $($manifest.version) installed as $($result.imported)."
Write-Host 'Restart the launcher and choose the imported version. Native compilation is not required.'
if (-not $result.prepared) { Write-Host 'Prepare the imported version in the launcher before playing.' }
