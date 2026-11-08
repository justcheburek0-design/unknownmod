"""Package the prepared source bundles for Horizon's native-mod installer."""
import hashlib
import json
from pathlib import Path
import tomllib
import zipfile

root = Path(__file__).resolve().parent.parent
build = root / "build" / "horizon"
sources = json.loads((root / "horizon" / "sources.json").read_text(encoding="utf-8"))
output = build / "packages"
output.mkdir(parents=True, exist_ok=True)
for name in ("horizon_voice", "voicechat_server"):
    bundle = build / "voice-bundles" / name
    mod = tomllib.loads((bundle / "horizon-mixin.toml").read_text(encoding="utf-8"))["mod"]
    metadata = {"schema": 1, "game": "26.3", "id": name, "version": mod["version"],
                "horizon": sources["horizon"]["commit"], "mixins": sources["horizon_mixins"]["commit"]}
    entries = {"horizon-native.json": (json.dumps(metadata, indent=2) + "\n").encode()}
    for file in sorted(bundle.rglob("*")):
        if not file.is_file() or "target" in file.relative_to(bundle).parts:
            continue
        if file.suffix in (".rs", ".toml") or file.name.startswith("LICENSE"):
            entries[f"mixins/{name}/{file.relative_to(bundle).as_posix()}"] = file.read_bytes()
    archive = output / f"{name}-{mod['version']}-unknown.hnmod"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as packed:
        for filename, content in sorted(entries.items()):
            info = zipfile.ZipInfo(filename, (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            packed.writestr(info, content)
    print(json.dumps({"file": str(archive), "sha256": hashlib.sha256(archive.read_bytes()).hexdigest()}))
