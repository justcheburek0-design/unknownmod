"""Package the verified server engine; Minecraft resources and worlds stay local."""
from pathlib import Path
import hashlib
import json
import zipfile

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'build/horizon'
VERSION = '6.1.1'


def main():
    engine = BUILD / 'unknown-bin/horizon-client.exe'
    verification = BUILD / f'verification-{VERSION}.json'
    report = json.loads(verification.read_text(encoding='utf-8'))
    digest = hashlib.sha256(engine.read_bytes()).hexdigest()
    if report['version'] != VERSION or report['server_sha256'] != digest:
        raise SystemExit('Verification does not match this server engine; run the checks first.')
    output = BUILD / f'packages/UnknownMod-{VERSION}-Server-Engine-Windows-x64.zip'
    output.parent.mkdir(parents=True, exist_ok=True)
    instructions = f'''UnknownMod {VERSION} server engine / Minecraft 26.3 / Windows x64

Compatible client: UnknownMod 6.1.0 and HorizonVoice 0.3.3.
This package updates the engine of an existing prepared Horizon server.
It does not contain Minecraft resources, a world, or the Horizon supervisor.

Stop the server, back up its world, then extract this archive to a separate
engine directory. In server.toml set [engine].path to the absolute path of
this horizon-client.exe. Start with UNKNOWNMOD_ENABLED=1 and the existing
Horizon supervisor. Keep the previous engine for rollback. Never replace
a running executable. No client reinstall or rebuild is required.

The development workspace uses horizon/start-server.ps1, which selects
build/horizon/unknown-bin/horizon-client.exe after build-unknown.ps1.
Real UUIDs remain server-side; each other player uses an OS-random UUIDv4.
The aliases persist across reconnects and reveals until the server restarts.
The player's own connection retains its real profile and signed skin.
Other revealed skins contain visual URLs without account metadata.
Automatic events use current world borders and save progress across restarts.

SHA256 horizon-client.exe: {digest}
'''
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.write(engine, 'horizon-client.exe')
        for license_file in sorted((BUILD / 'unknown-bin').glob('LICENSE*')):
            archive.write(license_file, license_file.name)
        archive.write(verification, 'VERIFICATION.json')
        archive.write(ROOT / 'horizon/PRIVACY-CHECKS.md', 'PRIVACY-CHECKS.md')
        archive.writestr('INSTALL.txt', instructions)
    checksum = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix('.zip.sha256').write_text(f'{checksum}  {output.name}\n', encoding='utf-8')
    print(output)
    print(f'SHA256 {checksum}')


if __name__ == '__main__':
    main()
