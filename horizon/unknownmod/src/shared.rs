//! Snapshot shared by server gameplay and the networking crate. Never hold a
//! rules lock while sending packets: serialization reads this separate view.
use std::{collections::{BTreeMap, BTreeSet}, sync::{OnceLock, RwLock}, time::{Instant, SystemTime, UNIX_EPOCH}};
use crate::{Config, PlayerId, State};

#[derive(Default, Clone)]
pub struct View {
    pub name: String,
    pub revealed: BTreeSet<PlayerId>,
    pub known: BTreeMap<PlayerId, String>,
}
fn view() -> &'static RwLock<View> { static VIEW: OnceLock<RwLock<View>> = OnceLock::new(); VIEW.get_or_init(Default::default) }
pub fn enabled() -> bool { std::env::var("UNKNOWNMOD_ENABLED").is_ok_and(|v| v == "1") }
pub fn snapshot() -> View { view().read().unwrap_or_else(|e| e.into_inner()).clone() }
pub fn publish(state: &State, config: &Config) {
    let mut view = view().write().unwrap_or_else(|e| e.into_inner());
    view.name = config.anonymous_name.clone();
    view.revealed = state.reveals.keys().copied().collect();
    for (&id, player) in &state.players { view.known.insert(id, player.name.clone()); }
}
pub fn remember(id: PlayerId, name: &str) {
    view().write().unwrap_or_else(|e| e.into_inner()).known.entry(id).or_insert_with(|| name.into());
}
pub fn hidden(id: PlayerId) -> bool { enabled() && !snapshot().revealed.contains(&id) }
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
