#!/usr/bin/env python3
"""Check the final client .hmod against the native source tree before testing it in Horizon."""

import argparse
import hashlib
from pathlib import Path
import stat
import sys
import zipfile

CLIENT = Path(__file__).resolve().parent / 'client'
ROOT_FILES = {'Cargo.lock', 'Cargo.toml', 'horizon-mod.toml', 'LICENSE', 'LICENSE-Horizon'}


def verify(archive: Path) -> tuple[int, str]:
    expected = ROOT_FILES | {
        path.relative_to(CLIENT).as_posix()
        for path in (CLIENT / 'src').rglob('*')
        if path.is_file() and path.suffix in {'.rs', '.toml'}
    }
    with archive.open('rb') as source:
        with zipfile.ZipFile(source) as package:
            entries = package.infolist()
            names = {entry.filename for entry in entries}
            if len(names) != len(entries):
                raise ValueError('duplicate archive entry')
            if names != expected:
                raise ValueError(f'unexpected entries: {sorted(names - expected)}; missing entries: {sorted(expected - names)}')
            for entry in entries:
                if stat.S_ISLNK(entry.external_attr >> 16):
                    raise ValueError(f'symbolic link: {entry.filename}')
                original = (CLIENT / entry.filename).read_bytes()
                if entry.file_size != len(original) or package.read(entry) != original:
                    raise ValueError(f'archive entry differs from source: {entry.filename}')
        source.seek(0)
        return len(expected), hashlib.file_digest(source, 'sha256').hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    args = parser.parse_args()
    try:
        count, digest = verify(args.archive)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f'{args.archive}: {error}', file=sys.stderr)
        return 1
    print(f'{count} files verified; SHA-256 {digest}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
