"""Build the installable client-only hnmod. Never installs into the user's launcher."""
from pathlib import Path
import hashlib,json,zipfile,shutil
import re
from PIL import Image

ROOT=Path(__file__).resolve().parent.parent
BUILD=ROOT/'build/horizon'
PIN='2ebf00a0281c592f79191e76fce0e7d522c6f425'
MIXINS='e8cd0aa38cffef49cb2e088ef716baa107148c02'
MOD='unknownmod_client'
DEST=BUILD/'client-bundles'/MOD
DEST.mkdir(parents=True,exist_ok=True)
for file in (ROOT/'horizon/client-mod').glob('*.rs'):shutil.copyfile(file,DEST/file.name)
shutil.copyfile(ROOT/'LICENSE',DEST/'LICENSE')
shutil.copyfile(BUILD/'client-mod-source/LICENSE',DEST/'LICENSE-Horizon')
image=Image.open(ROOT/'src/main/resources/assets/unknownmod/textures/entity/player/anonymous.png').convert('RGBA')
assert image.size==(64,64)
runtime=(DEST/'runtime.rs').read_text(encoding='utf-8')
runtime+='\npub(super) const ANONYMOUS_RGBA:&[u8]=&['+','.join(map(str,image.tobytes()))+'];\n'
(DEST/'runtime.rs').write_text(runtime,encoding='utf-8')

# A cancellable HEAD keeps the target's nested const items intact. The mixin
# engine cannot attach RETURN to this function. Preserve the pinned vanilla
# implementation, adding only the already-tested anonymous skin resolution.
remote=(BUILD/'client-mod-source/crates/horizon-client/src/game/remote.rs').read_text(encoding='utf-8')
start=remote.index('    pub(super) fn update_remote_skins(&mut self) {')
end=remote.index('    /// Puts a downloaded skin',start)
function=remote[start:end].rstrip()
body=function[function.index('{')+1:function.rfind('}')]
anchor='        let skin_of = |skins: &HashMap<u128, String>, uuid: u128| match skins.get(&uuid)'
assert body.count(anchor)==1
body=body.replace(anchor,'''        let masks=crate::game::unknownmod_client::players();
        if !masks.is_empty() {
            const ANON:&str="skins/unknownmod_anonymous";
            if !self.skin_slots.contains(ANON) || !self.gui.atlas.sprites.contains_key(ANON) {
                self.store_skin(ANON.into(),crate::game::unknownmod_client::ANONYMOUS_RGBA.to_vec());
            }
            for uuid in &masks {
                resolved.retain(|(id,_)|id!=uuid);resolved.push((*uuid,ANON.into()));
                slim.remove(uuid);capes.remove(uuid);
            }
        }
'''+anchor)
body=body.replace('        for (id, uuid) in level {','        for (id, uuid) in level {\n            crate::game::unknownmod_client::entity(id,masks.contains(&uuid));',1)
body=re.sub(r'\bself\b','game',body)
(DEST/'skins.rs').write_text("fn unknownmod_skins(game:&mut crate::game::Game<'_>)->core::ops::ControlFlow<()> {\n    let run=|game:&mut crate::game::Game<'_>| {"+body+"\n    };\n    run(game);\n    core::ops::ControlFlow::Break(())\n}\n",encoding='utf-8')
manifest=f'''[mod]
id = "{MOD}"
version = "6.0.0"
priority = 10

[compatibility]
horizon_revision = "{PIN}"

[[module]]
id = "state"
file = "crates/horizon-client/src/game.rs"
name = "unknownmod_client"
source = "runtime.rs"
'''
def injection(name,kind,target,bindings,cancellable=False):
    global manifest
    manifest+=f'\n[[injection]]\nid = "{name}"\nkind = "{kind}"\ntarget = "{target}"\nhelper = "{name}.rs"\n'
    if cancellable:manifest+='cancellable = true\n'
    manifest+='bindings = [\n'
    for name,ty,value in bindings:manifest+='  { name = '+json.dumps(name)+', ty = '+json.dumps(ty)+', value = '+json.dumps(value)+' },\n'
    manifest+=']\n'
game=('game',"&mut crate::game::Game<'_>",'&mut *self')
injection('packet','HEAD',"horizon_client::game::remote::Game<'_>::handle_packet",[game,('packet','&horizon_protocol::packets::play::Clientbound','&packet')])
injection('skins','HEAD',"horizon_client::game::remote::Game<'_>::update_remote_skins",[game],cancellable=True)
injection('name','HEAD',"horizon_client::game::native_hooks::Game<'_>::native_render_name_tag",[('entity_id','u32','entity_id'),('kind','&str','kind'),('visible','&mut bool','&mut *visible')])
injection('list','HEAD',"horizon_client::game::native_hooks::Game<'_>::native_mods",[('mods','&mut Vec<crate::game::native_hooks::NativeMod>','&mut *mods')])
(DEST/'horizon-mixin.toml').write_text(manifest,encoding='utf-8')
metadata={'schema':1,'id':MOD,'version':'6.0.0','game':'26.3','horizon':PIN,'mixins':MIXINS}
output=BUILD/'packages/UnknownMod-6.0.0-client.hnmod'
output.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as archive:
    entries={'horizon-native.json':(json.dumps(metadata,indent=2)+'\n').encode()}
    for file in sorted(DEST.iterdir()):
        if file.is_file():entries[f'mixins/{MOD}/{file.name}']=file.read_bytes()
    for name,data in sorted(entries.items()):
        info=zipfile.ZipInfo(name,(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16;archive.writestr(info,data)
print(json.dumps({'file':str(output),'sha256':hashlib.sha256(output.read_bytes()).hexdigest(),'horizon':PIN}))

# The native voice client is distributed separately so the launcher can list it
# and players can use its normal microphone/device settings page.
voice=BUILD/'client-bundles/horizon_voice'
shutil.copytree(BUILD/'voice-bundles/horizon_voice',voice,dirs_exist_ok=True)
voice_manifest=voice/'horizon-mixin.toml'
voice_manifest.write_text(voice_manifest.read_text(encoding='utf-8').replace('66f3981f522b24c250b21523a70080965b940424',PIN),encoding='utf-8')
voice_meta={'schema':1,'id':'horizon_voice','version':'0.3.3','game':'26.3','horizon':PIN,'mixins':MIXINS}
voice_output=BUILD/'packages/HorizonVoice-0.3.3-UnknownMod.hnmod'
with zipfile.ZipFile(voice_output,'w',zipfile.ZIP_DEFLATED) as archive:
    entries={'horizon-native.json':(json.dumps(voice_meta,indent=2)+'\n').encode()}
    for file in sorted(voice.rglob('*')):
        if file.is_file() and 'target' not in file.relative_to(voice).parts and file.name!='Cargo.lock':
            entries[f'mixins/horizon_voice/{file.relative_to(voice).as_posix()}']=file.read_bytes()
    for name,data in sorted(entries.items()):
        info=zipfile.ZipInfo(name,(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16;archive.writestr(info,data)
print(json.dumps({'file':str(voice_output),'sha256':hashlib.sha256(voice_output.read_bytes()).hexdigest(),'horizon':PIN}))
