"""Copy native bundles into a private compatibility sandbox; never installs mods."""
from pathlib import Path,PurePosixPath
import zipfile,json,shutil,re
ROOT=Path(__file__).resolve().parent.parent
DEST=ROOT/'build/horizon/client-compat-bundles'
SOURCE=Path.home()/'AppData/Roaming/horizon/horizon-native-mods'
DEST.mkdir(parents=True,exist_ok=True)
for source in (ROOT/'build/horizon/client-bundles').iterdir():
    if source.is_dir():shutil.copytree(source,DEST/source.name,dirs_exist_ok=True)
found=[]
for archive in sorted(SOURCE.glob('*.hnmod')):
    with zipfile.ZipFile(archive) as z:
        meta=json.loads(z.read('horizon-native.json'))
        for entry in z.infolist():
            name=PurePosixPath(entry.filename)
            if name.is_absolute() or '..' in name.parts or '\\' in entry.filename:raise ValueError('Unsafe native mod path')
            if len(name.parts)<3 or name.parts[0]!='mixins' or entry.is_dir():continue
            target=DEST/Path(*name.parts[1:]);target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(z.read(entry))
        # The launcher rebases hook-only registry mods onto the selected base.
        # Reproduce that in the sandbox; installed archives remain unchanged.
        manifest=DEST/meta['id']/'horizon-mixin.toml'
        if manifest.exists():
            text=manifest.read_text(encoding='utf-8')
            text=re.sub(r'horizon_revision\s*=\s*"[0-9a-f]{40}"','horizon_revision = "2ebf00a0281c592f79191e76fce0e7d522c6f425"',text)
            manifest.write_text(text,encoding='utf-8')
        found.append({'id':meta['id'],'version':meta['version'],'hooks':meta.get('hooks')})
print(json.dumps({'copied_for_check':found,'sandbox':str(DEST)}))
