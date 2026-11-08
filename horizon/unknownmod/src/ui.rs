use serde::{Serialize, Deserialize};
use crate::{Config, PlayerId, State};

#[derive(Debug, Deserialize)]
#[serde(tag="action", rename_all="snake_case", deny_unknown_fields)]
pub enum Request {
    Status {},
    Reveal { target: String },
    Hide { target: String },
    Restore { target: String },
    Setting { field: Setting, value: u32 },
}
#[derive(Debug, Deserialize)]
#[serde(rename_all="snake_case")]
pub enum Setting { Duration, Interval, Minimum }

#[derive(Serialize)]
pub struct Snapshot {
    pub status: String,
    pub combat_seconds: u64,
    pub reveal_seconds: u64,
    pub last_decision: Option<String>,
    pub recent: Vec<String>,
    pub inactive: Vec<String>,
    #[serde(skip_serializing_if="Option::is_none")]
    pub admin: Option<Admin>,
}
#[derive(Serialize)]
pub struct Admin {
    pub duration: u32,
    pub interval: u32,
    pub minimum: usize,
    pub players: Vec<AdminPlayer>,
}
#[derive(Serialize)]
pub struct AdminPlayer { pub id: String, pub name: String, pub ghost: bool, pub online: bool }

pub fn snapshot(state: &State, config: &Config, viewer: PlayerId, privileged: bool, now: u64, combat: u64) -> Snapshot {
    let player=state.players.get(&viewer);
    let (recent,inactive)=state.participant_groups(config,now);
    Snapshot {
        status: if player.is_some_and(|p| p.ghost) {"Ты наблюдатель"} else if state.reveals.contains_key(&viewer) {"Твоя личность раскрыта"} else {"Твоя личность скрыта"}.into(),
        combat_seconds: state.remaining_combat_seconds(viewer,combat),
        reveal_seconds: state.reveals.get(&viewer).map_or(0,|r|r.remaining(now).div_ceil(1000)),
        last_decision: player.and_then(|p|p.last_decision.as_ref()).map(|d|d.explanation()),
        recent: recent.into_iter().take(200).collect(), inactive: inactive.into_iter().take(200).collect(),
        admin: privileged.then(||Admin {duration: config.reveal_duration_minutes, interval: config.reveal_interval_hours, minimum: config.minimum_players,
            players: state.players.iter().take(200).map(|(id,p)|AdminPlayer {id:format!("{id:032x}"),name:p.name.clone(),ghost:p.ghost,online:p.online}).collect()}),
    }
}
