"""Apply the version-pinned UnknownMod adapter to an already staged voice engine.

Original stage files are retained separately; repeated runs produce identical source.
Never touches the running executable or the player's world.
"""
from pathlib import Path
import re
import shutil
import tempfile

ROOT = Path(__file__).resolve().parent.parent
STAGE = ROOT / 'build/horizon/voice-game'
BASE = ROOT / 'build/horizon/unknown-originals'
WORK = Path(tempfile.mkdtemp(prefix='unknown-patch-', dir=ROOT/'build/horizon'))
SRC = WORK / 'crates/horizon-client/src'

def patch(relative, transform):
    path = WORK / relative
    original = BASE / relative
    if not original.exists():
        original.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(STAGE / relative, original)
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(transform(original.read_text(encoding='utf-8')), encoding='utf-8')

def replace(text, old, new):
    if text.count(old) != 1:
        raise RuntimeError(f'Pinned source mismatch ({text.count(old)} matches): {old[:90]}')
    return text.replace(old, new, 1)

def hook(text, function, body):
    pattern = rf'(    pub\(super\) fn {function}\([^{{}}]*?\)\s*\{{)'
    text, count = re.subn(pattern, lambda m: m[0]+'\n        '+body, text, count=1)
    if count != 1: raise RuntimeError(f'Hook not found: {function}')
    return text

def main():
    (SRC/'game').mkdir(parents=True,exist_ok=True)
    patch('Cargo.toml',lambda t:t+'\n# UnknownMod native development profile; world/render dependencies stay optimized.\n[profile.fast.package.horizon-client]\nopt-level = 0\n[profile.fast.package.horizon-client-lib]\nopt-level = 0\n')
    core = (ROOT/'horizon/unknownmod').as_posix()
    for crate in ['horizon-client','horizon-client-lib']:
        patch(f'crates/{crate}/Cargo.toml', lambda t:replace(t,'[dependencies]',f'[dependencies]\nunknownmod_rules = {{ path = "{core}" }}'))
    patch('crates/horizon-client/src/client_lib.rs',lambda t:t+'\npub mod unknown_wire;\n')
    patch('crates/horizon-client/src/game.rs',lambda t:t+'\nmod unknownmod;\n')
    (SRC/'game/unknownmod.rs').write_text((ROOT/'horizon/unknown-game.rs').read_text(encoding='utf-8')+'\n'+(ROOT/'horizon/unknown-ui.rs').read_text(encoding='utf-8'),encoding='utf-8')
    shutil.copyfile(ROOT/'horizon/unknown-wire.rs',SRC/'unknown_wire.rs')
    shutil.copyfile(ROOT/'src/main/resources/assets/unknownmod/textures/entity/player/anonymous.png',SRC/'game/unknown-anonymous.png')
    from PIL import Image
    with Image.open(SRC/'game/unknown-anonymous.png') as image:
        assert image.size==(64,64)
        (SRC/'game/unknown-anonymous.rgba').write_bytes(image.convert('RGBA').tobytes())
    patch('crates/horizon-client/src/lan/guest.rs',lambda t:replace(t,'        self.sender.send(packet.encode());',
        '''        let filtered = crate::unknown_wire::masked(packet, self.permission >= 2, self.uuid);
        self.sender.send(filtered.as_ref().unwrap_or(packet).encode());'''))
    patch('crates/horizon-client/src/lan/hub.rs',lambda t:replace(replace(t,'    fn player_info(&self,','    pub fn player_info(&self,'),'        let body = packet.encode();\n        for g in self.guests.iter().filter(|g| g.placed) {',
        '''        if unknownmod_rules::shared::enabled() {
            for g in self.guests.iter().filter(|g| g.placed) { g.send(packet); }
            return;
        }
        let body = packet.encode();
        for g in self.guests.iter().filter(|g| g.placed) {'''))
    hub=SRC/'lan/hub.rs'
    t=hub.read_text(encoding='utf-8')
    t=replace(t,'    pub fn player_display_name(&self, uuid: u128, name: &str) -> Tag {',
        '''    pub fn player_display_name(&self, uuid: u128, name: &str) -> Tag {
        if unknownmod_rules::shared::hidden(uuid) {return literal(&unknownmod_rules::shared::anonymous_name());}''')
    # Joining notifications and the newcomer profile bypass Hub.broadcast in
    # vanilla. Route these packets through the same per-viewer filter too.
    for binding in ['joined','newcomer','left']:
        t,count=re.subn(rf'(let {binding} = C::[\s\S]*?\}}\))\s*\.encode\(\);',r'\1;',t,count=1)
        if count!=1: raise RuntimeError('Pinned packet path mismatch: '+binding)
        t=t.replace(f'g.sender.send({binding}.clone());',f'g.send(&{binding});')
        t=t.replace(f'o.sender.send({binding}.clone());',f'o.send(&{binding});')
    for function in ['system_message_to','system_message_to_player']:
        start=t.index(f'    pub fn {function}(')
        end=t.index('\n    }',start)+6
        body=t[start:end]
        body=body.replace('        .encode();',';').replace('g.sender.send(body.clone());','g.send(&body);')
        t=t[:start]+body+t[end:]
    hub.write_text(t,encoding='utf-8')
    patch('crates/horizon-client/src/lan/mod.rs',lambda t:replace(t,
        '                        .map(|(u, n)| {',
        '''                        .map(|(u, n)| {
                            let n=if unknownmod_rules::shared::hidden(u) {unknownmod_rules::shared::anonymous_name()} else {n};'''))
    def hooks(t):
        for fn, body in [
            ('native_net_from_client','self.unknownmod_ui(player,channel,data);'),
            ('native_server_tick','if matches!(phase, ServerTickPhase::ServerTickEnd { .. }) { self.unknownmod_tick(); }'),
            ('native_server_stopping','self.unknownmod_stopping();'),
            ('native_player_logged_in','self.unknownmod_join(player,name);'),
            ('native_player_respawned','self.unknownmod_respawn(player);'),
            ('native_register_commands','self.unknownmod_commands(commands);'),
            ('native_perform_command','self.unknownmod_command(player.permission,command,ran);'),
            ('native_perform_console_command','self.unknownmod_command(4,command,ran);'),
            ('native_player_commands_sent','if unknownmod_rules::shared::enabled() { names.retain(|n| !matches!(n.as_str(),"msg"|"tell"|"w"|"me"|"teammsg"|"tm"|"list")); }'),
            ('native_render_name_tag','if kind=="minecraft:player" && unknownmod_rules::shared::masked_entity(entity_id) { *visible=false; }'),
            ('native_player_command','if unknownmod_rules::shared::enabled() {\n            let root=command.split_whitespace().next().unwrap_or("");\n            if matches!(root,"msg"|"tell"|"w"|"me"|"teammsg"|"tm"|"list") { *canceled=true; replies.push(Text::literal("Общение в эксперименте — только голосом. Список участников: /players")); }\n        }'),
        ]: t=hook(t,fn,body)
        return t
    patch('crates/horizon-client/src/game/native_hooks.rs',hooks)
    patch('crates/horizon-client/src/game/combat.rs',lambda t:replace(replace(t,
        '            self.combat.entries.push(Entry {','            if let Some((id, _)) = &attacker { self.unknownmod_hit(*id,item.as_ref()); }\n            self.combat.entries.push(Entry {'),
        'struct Entry {','''impl CombatTracker {
    pub(super) fn unknownmod_blow(&self)->Option<(u32,Option<String>)> {
        let e=self.entries.last()?;
        Some((e.attacker.as_ref()?.0,e.item.as_ref().map(Text::plain)))
    }
}
struct Entry {'''))
    # Preserve the actual fired bow/crossbow or thrown trident, even if the
    # shooter changes their held item before impact.
    patch('crates/horizon-client/src/game/lan_pvp.rs',lambda t:replace(t,
        '        let shooter = self.player_attacker(&host, p.guest);',
        '''        let shooter = self.player_attacker(&host, p.guest);
        let weapon=p.arrow.as_ref().and_then(|a|a.fired_from_weapon.as_ref()).or(p.item.as_ref())
            .and_then(|s|s.custom_name()).map(Text::literal);''').replace(
        '                g.combat.projectile_attacker = shooter;',
        '                g.combat.projectile_attacker = shooter;\n                g.combat.unknownmod_projectile_item = weapon;'))
    patch('crates/horizon-client/src/game/creatures.rs',lambda t:replace(t,
        '            self.note_named_attacker(damage_type, id, name);',
        '            let item=self.combat.unknownmod_projectile_item.take();\n            self.note_player_attacker(damage_type, id, name,item);'))
    # Extend combat's ephemeral projectile metadata without changing world saves.
    combat=SRC/'game/combat.rs'
    combat.write_text(replace(combat.read_text(encoding='utf-8'),
        '    pub(super) projectile_attacker: Option<(u32, Text)>,',
        '    pub(super) projectile_attacker: Option<(u32, Text)>,\n    pub(super) unknownmod_projectile_item: Option<Text>,'),encoding='utf-8')
    patch('crates/horizon-client/src/game/lan_actions.rs',lambda t:replace(replace(t,
        '    fn guest_die(&mut self, host: &mut LanHost, gi: usize, conn: u32) {','    pub(super) fn guest_die(&mut self, host: &mut LanHost, gi: usize, conn: u32) {'),
        '        let here = host.hub.here(&host.hub.guests[gi]);\n        let g = &mut host.hub.guests[gi];',
        '        self.unknownmod_death(host,gi);\n        let here = host.hub.here(&host.hub.guests[gi]);\n        let g = &mut host.hub.guests[gi];'))
    actions=SRC/'game/lan_actions.rs'
    actions.write_text(actions.read_text(encoding='utf-8').replace('    death_cause: Option<String>,','    pub(super) death_cause: Option<String>,'),encoding='utf-8')
    patch('crates/horizon-client/src/game/lan.rs',lambda t:replace(t,'                            host.hub.guests.push(*guest);',
        '                            host.hub.guests.push(*guest);\n                            self.unknownmod_leave(&mut host,at);'))
    # Preserve real names for operator command feedback; the wire filter hides
    # UUID-bearing player components from ordinary viewers.
    patch('crates/horizon-client/src/game/commands/players.rs',lambda t:t)
    patch('crates/horizon-client/src/game/dragon.rs',lambda t:replace(t,'        self.award_stat("killed", "minecraft:ender_dragon", 1);',
        '        self.unknownmod_dragon_win();\n        self.award_stat("killed", "minecraft:ender_dragon", 1);'))
    def chat(t):
        return hook_chat(t)
    patch('crates/horizon-client/src/lan/hub/chat.rs',chat)
    def remote(t):
        t=replace(t,'            C::Login(p) => {','            C::Login(p) => {\n                unknownmod_rules::shared::clear_client_masks();')
        t=replace(t,'                        for e in &p.entries {\n                            if let Some(name) = &e.name {',
            '''                        for e in &p.entries {
                            let masked=e.properties.iter().any(|p|p.name=="unknownmod_anonymous" && p.value=="1");
                            unknownmod_rules::shared::set_client_mask(e.uuid,masked);
                            r.skin_textures.remove(&e.uuid);
                            r.level_player_textures.clear();
                            r.tab_players.remove(&e.uuid);
                            if let Some(name) = &e.name {''')
        t=replace(t,'        let skin_of = |skins: &HashMap<u128, String>, uuid: u128| match skins.get(&uuid)',
            '''        let masks=unknownmod_rules::shared::masked_clients();
        if !masks.is_empty() {
            const ANON:&str="skins/unknownmod_anonymous";
            if !self.skin_slots.contains(ANON) || !self.gui.atlas.sprites.contains_key(ANON) {
                let rgba=include_bytes!("unknown-anonymous.rgba").to_vec();
                self.store_skin(ANON.into(),rgba);
            }
            for uuid in masks {
                resolved.retain(|(id,_)|*id!=uuid);resolved.push((uuid,ANON.into()));
                slim.remove(&uuid);capes.remove(&uuid);
            }
        }
        let skin_of = |skins: &HashMap<u128, String>, uuid: u128| match skins.get(&uuid)''')
        t=replace(t,'        for (id, uuid) in level {', '        for (id, uuid) in level {\n            unknownmod_rules::shared::set_masked_entity(id,unknownmod_rules::shared::masked_clients().contains(&uuid));')
        return t
    patch('crates/horizon-client/src/game/remote.rs',remote)
    changed=0
    for source in WORK.rglob('*'):
        if not source.is_file(): continue
        target=STAGE/source.relative_to(WORK)
        if target.exists() and target.read_bytes()==source.read_bytes(): continue
        target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(source,target)
        changed+=1
    print(f'UnknownMod adapters staged: {changed} changed files (running binaries unchanged).')

def hook_chat(t):
    # This free hook is in the networking library; enforce even with no typed mods.
    start=t.index('pub fn native_player_chat(')
    body=t.index(') {',start)+3
    return t[:body]+'''\n    if unknownmod_rules::shared::enabled() { *cancel = true; return; }
'''+t[body:]

if __name__=='__main__': main()
