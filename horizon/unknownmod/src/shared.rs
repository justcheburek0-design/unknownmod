//! Snapshot shared by server gameplay and the networking crate. Never hold a
//! rules lock while sending packets: serialization reads this separate view.
use std::{collections::{BTreeMap, BTreeSet}, sync::{OnceLock, RwLock}, time::{Instant, SystemTime, UNIX_EPOCH}};
use crate::{Config, PlayerId, State};

#[derive(Default, Clone)]
pub struct View {
    pub stopped: bool,
    pub name: String,
    pub revealed: BTreeSet<PlayerId>,
    pub known: BTreeMap<PlayerId, String>,
}
fn view() -> &'static RwLock<View> { static VIEW: OnceLock<RwLock<View>> = OnceLock::new(); VIEW.get_or_init(Default::default) }
pub fn enabled() -> bool { std::env::var("UNKNOWNMOD_ENABLED").is_ok_and(|v| v == "1") }
fn activity() -> &'static RwLock<BTreeMap<PlayerId, u64>> {
    static ACTIVITY: OnceLock<RwLock<BTreeMap<PlayerId, u64>>> = OnceLock::new();
    ACTIVITY.get_or_init(Default::default)
}
pub fn record_activity(id: PlayerId) {
    if enabled() { activity().write().unwrap_or_else(|e| e.into_inner()).insert(id, combat_time()); }
}
pub fn take_activity() -> BTreeMap<PlayerId, u64> {
    std::mem::take(&mut *activity().write().unwrap_or_else(|e| e.into_inner()))
}
pub fn snapshot() -> View { view().read().unwrap_or_else(|e| e.into_inner()).clone() }
pub fn publish(state: &State, config: &Config) {
    let mut view = view().write().unwrap_or_else(|e| e.into_inner());
    view.name = config.anonymous_name.clone();
    view.stopped = state.stopped;
    view.revealed = state.reveals.keys().copied().collect();
    for (&id, player) in &state.players { view.known.insert(id, player.name.clone()); }
}
pub fn remember(id: PlayerId, name: &str) {
    if real_id(id).is_some(){return}
    view().write().unwrap_or_else(|e| e.into_inner()).known.entry(id).or_insert_with(|| name.into());
}

#[derive(Default)]
struct Aliases { forward: BTreeMap<PlayerId,PlayerId>, reverse: BTreeMap<PlayerId,PlayerId> }
fn aliases()->&'static RwLock<Aliases>{static ALIASES:OnceLock<RwLock<Aliases>>=OnceLock::new();ALIASES.get_or_init(Default::default)}
/// OS-random UUIDv4, stable across reconnect/reveal within this server process.
/// Account identifiers are never derived from a nickname or a public hash.
pub fn public_id(real:PlayerId)->PlayerId {
    if !enabled(){return real}
    if let Some(alias)=aliases().read().unwrap_or_else(|e|e.into_inner()).forward.get(&real).copied(){return alias}
    let known=snapshot().known;
    let mut map=aliases().write().unwrap_or_else(|e|e.into_inner());
    if let Some(id)=map.forward.get(&real){return *id}
    loop {
        let mut bytes=[0u8;16];getrandom::fill(&mut bytes).expect("UnknownMod requires OS randomness for anonymous UUIDs");
        bytes[6]=(bytes[6]&15)|0x40;bytes[8]=(bytes[8]&63)|0x80;
        let alias=u128::from_be_bytes(bytes);
        if alias==real||known.contains_key(&alias)||map.reverse.contains_key(&alias)||map.forward.contains_key(&alias){continue}
        map.forward.insert(real,alias);map.reverse.insert(alias,real);return alias
    }
}
pub fn real_id(alias:PlayerId)->Option<PlayerId>{aliases().read().unwrap_or_else(|e|e.into_inner()).reverse.get(&alias).copied()}
pub fn wire_id(real:PlayerId,viewer:PlayerId)->PlayerId {
    let registered=known_id(real)||aliases().read().unwrap_or_else(|e|e.into_inner()).forward.contains_key(&real);
    if real==viewer||!registered{real}else{public_id(real)}
}
pub fn player_wire_id(real:PlayerId,viewer:PlayerId)->PlayerId{if real==viewer||real_id(real).is_some(){real}else{public_id(real)}}
pub fn known_id(id:PlayerId)->bool{view().read().unwrap_or_else(|e|e.into_inner()).known.contains_key(&id)}
pub fn running() -> bool { enabled() && !snapshot().stopped }
pub fn hidden(id: PlayerId) -> bool { let view=snapshot(); enabled() && !view.stopped && !view.revealed.contains(&real_id(id).unwrap_or(id)) }
pub fn anonymous_name() -> String { let name = snapshot().name; if name.is_empty() { "JustPlayer".into() } else { name } }
pub fn wall_time() -> u64 { SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64 }
pub fn combat_time() -> u64 {
    static START: OnceLock<Instant> = OnceLock::new();
    START.get_or_init(Instant::now).elapsed().as_millis() as u64
}

fn client_masks() -> &'static RwLock<BTreeSet<PlayerId>> {
    static MASKS: OnceLock<RwLock<BTreeSet<PlayerId>>> = OnceLock::new();
    MASKS.get_or_init(Default::default)
}
pub fn set_client_mask(id: PlayerId, masked: bool) {
    let mut masks = client_masks().write().unwrap_or_else(|e| e.into_inner());
    if masked { masks.insert(id); } else { masks.remove(&id); }
}
pub fn masked_clients() -> BTreeSet<PlayerId> { client_masks().read().unwrap_or_else(|e| e.into_inner()).clone() }
pub fn clear_client_masks() {
    client_masks().write().unwrap_or_else(|e| e.into_inner()).clear();
    client_entities().write().unwrap_or_else(|e|e.into_inner()).clear();
}

fn client_entities()-> &'static RwLock<BTreeSet<u32>> {
    static ENTITIES:OnceLock<RwLock<BTreeSet<u32>>>=OnceLock::new();ENTITIES.get_or_init(Default::default)
}
pub fn set_masked_entity(id:u32,masked:bool){let mut entities=client_entities().write().unwrap_or_else(|e|e.into_inner());if masked{entities.insert(id);}else{entities.remove(&id);}}
pub fn masked_entity(id:u32)->bool{client_entities().read().unwrap_or_else(|e|e.into_inner()).contains(&id)}
