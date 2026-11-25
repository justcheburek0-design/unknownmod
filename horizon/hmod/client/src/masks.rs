// Client side of UnknownMod's anonymous-profile protocol. Rules stay on the server.
use std::{collections::BTreeSet,sync::{OnceLock,RwLock}};

#[derive(Default)]
struct Masks { players:BTreeSet<u128>, entities:BTreeSet<u32> }
fn masks()->&'static RwLock<Masks>{static MASKS:OnceLock<RwLock<Masks>>=OnceLock::new();MASKS.get_or_init(Default::default)}
pub fn clear(){*masks().write().unwrap_or_else(|e|e.into_inner())=Masks::default();}
pub fn player(id:u128,masked:bool){let mut m=masks().write().unwrap_or_else(|e|e.into_inner());if masked{m.players.insert(id);}else{m.players.remove(&id);}}
pub fn players()->BTreeSet<u128>{masks().read().unwrap_or_else(|e|e.into_inner()).players.clone()}
pub fn entity(id:u32,masked:bool){let mut m=masks().write().unwrap_or_else(|e|e.into_inner());if masked{m.entities.insert(id);}else{m.entities.remove(&id);}}
pub fn hidden_entity(id:u32)->bool{masks().read().unwrap_or_else(|e|e.into_inner()).entities.contains(&id)}
