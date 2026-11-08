//! Authoritative experiment rules, independent of Horizon's rendering and networking.
//! Persisted timers use wall time; combat receives a process-monotonic timestamp.
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
pub mod shared;
pub mod audit;
pub mod ui;

pub type PlayerId = u128;
pub const COMBAT_MILLIS: u64 = 30_000;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct Config {
    pub reveal_duration_minutes: u32,
    pub reveal_interval_hours: u32,
    pub minimum_players: usize,
    pub roster: Vec<String>,
    pub anonymous_name: String,
}
impl Default for Config {
    fn default() -> Self {
        Self { reveal_duration_minutes: 90, reveal_interval_hours: 3, minimum_players: 3,
               roster: Vec::new(), anonymous_name: "JustPlayer".into() }
    }
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Player {
    pub name: String,
    pub ghost: bool,
    pub previous_mode: Option<i32>,
    pub last_seen: u64,
    pub last_decision: Option<audit::Decision>,
    #[serde(skip)] pub online: bool,
    #[serde(skip)] pub mode: i32,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub enum Reveal {
    Running { ends_at: u64 },
    Paused { remaining: u64 },
}
impl Reveal {
    pub fn remaining(&self, now: u64) -> u64 {
        match self { Self::Running { ends_at } => ends_at.saturating_sub(now),
                     Self::Paused { remaining } => *remaining }
    }
}

#[derive(Clone, Debug)]
struct IncomingHit { attacker: PlayerId, weapon: Option<String>, expires_at: u64 }
#[derive(Clone, Debug)]
struct Combat { expires_at: u64, incoming: Option<IncomingHit> }

#[derive(Default, Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct State {
    pub players: BTreeMap<PlayerId, Player>,
    pub reveals: BTreeMap<PlayerId, Reveal>,
    pub next_reveal_at: u64,
    #[serde(skip)] pub pending_audit: Vec<audit::Decision>,
    #[serde(skip)] combat: BTreeMap<PlayerId, Combat>,
}

#[derive(Debug, PartialEq, Eq)]
pub enum Logout { Safe, Death, Eliminated }

impl State {
    pub fn join(&mut self, id: PlayerId, name: &str, mode: i32, now: u64) {
        let player = self.players.entry(id).or_default();
        player.name = name.to_owned();
        player.online = true;
        player.mode = mode;
        player.last_seen = now;
        if let Some(Reveal::Paused { remaining }) = self.reveals.get(&id) {
            if *remaining == 0 { self.reveals.remove(&id); }
            else { self.reveals.insert(id, Reveal::Running { ends_at: now.saturating_add(*remaining) }); }
        }
    }

    pub fn is_active(&self, id: PlayerId) -> bool {
        self.players.get(&id).is_some_and(|p| p.online && !p.ghost && p.mode != 3)
    }

    pub fn reveal(&mut self, id: PlayerId, config: &Config, now: u64) -> bool {
        if !self.is_active(id) || self.reveals.contains_key(&id) { return false; }
        let duration = u64::from(config.reveal_duration_minutes.max(1)) * 60_000;
        self.reveals.insert(id, Reveal::Running { ends_at: now.saturating_add(duration) });
        true
    }

    /// A settings reload preserves current hunts and the existing schedule.
    pub fn configure(&mut self, config: &Config, now: u64, reset_interval: bool) {
        if reset_interval || self.next_reveal_at == 0 {
            self.next_reveal_at = now.saturating_add(u64::from(config.reveal_interval_hours.max(1)) * 3_600_000);
        }
    }

    pub fn expire_reveals(&mut self, now: u64) -> Vec<PlayerId> {
        let expired: Vec<_> = self.reveals.iter().filter_map(|(&id, r)|
            matches!(r, Reveal::Running { ends_at } if *ends_at <= now).then_some(id)).collect();
        for id in &expired { self.reveals.remove(id); }
        expired
    }

    /// Selection entropy is supplied by the game adapter. Paused/revealed targets
    /// remain participants but cannot be selected for a second simultaneous hunt.
    pub fn automatic_reveal(&mut self, config: &Config, now: u64, selection: usize) -> Option<PlayerId> {
        self.configure(config, now, false);
        if now < self.next_reveal_at { return None; }
        self.configure(config, now, true);
        let active: Vec<_> = self.players.keys().copied().filter(|&id| self.is_active(id)).collect();
        if active.len() < config.minimum_players.max(1) { return None; }
        let eligible: Vec<_> = active.into_iter().filter(|id| !self.reveals.contains_key(id)).collect();
        if eligible.is_empty() { return None; }
        let target = eligible[selection % eligible.len()];
        self.reveal(target, config, now).then_some(target)
    }

    pub fn hit(&mut self, attacker: PlayerId, victim: PlayerId, weapon: Option<&str>, now: u64) {
        if attacker == victim || !self.is_active(attacker) || !self.is_active(victim) { return; }
        let expires_at = now.saturating_add(COMBAT_MILLIS);
        let incoming = self.combat.get(&attacker).filter(|c| c.expires_at > now)
            .and_then(|c| c.incoming.clone());
        self.combat.insert(attacker, Combat { expires_at, incoming });
        self.combat.insert(victim, Combat { expires_at, incoming: Some(IncomingHit {
            attacker, weapon: weapon.map(str::to_owned), expires_at }) });
    }

    pub fn remaining_combat_seconds(&self, id: PlayerId, now: u64) -> u64 {
        self.combat.get(&id).map_or(0, |c| c.expires_at.saturating_sub(now).div_ceil(1000))
    }

    pub fn make_ghost(&mut self, id: PlayerId) -> bool {
        let Some(player) = self.players.get_mut(&id) else { return false; };
        if player.ghost { return false; }
        player.previous_mode = Some(player.mode);
        player.ghost = true;
        self.reveals.remove(&id);
        self.combat.remove(&id);
        true
    }

    pub fn restore(&mut self, id: PlayerId) -> Option<i32> {
        let player = self.players.get_mut(&id)?;
        if !player.ghost { return None; }
        player.ghost = false;
        player.mode = player.previous_mode.filter(|&mode| mode != 3).unwrap_or(0);
        // Retain the desired mode until the game adapter applies it. An
        // offline restoration must survive both reconnect and server restart.
        player.previous_mode = Some(player.mode);
        let mode = player.mode;
        let decision = audit::Decision { time: 0, cause: audit::Cause::Restore, victim: id,
            victim_name: player.name.clone(), attacker: None, attacker_name: None,
            weapon: None, matched: false, eliminated: false };
        player.last_decision = Some(decision.clone());
        self.pending_audit.push(decision);
        Some(mode)
    }

    pub fn death(&mut self, victim: PlayerId, attacker: Option<PlayerId>, weapon: Option<&str>) -> bool {
        self.death_at(victim, attacker, weapon, 0, audit::Cause::Death)
    }

    pub fn death_at(&mut self, victim: PlayerId, attacker: Option<PlayerId>, weapon: Option<&str>, time: u64, cause: audit::Cause) -> bool {
        self.combat.remove(&victim);
        let attacker = attacker.filter(|&id| id != victim && self.players.contains_key(&id));
        let matches = self.players.get(&victim).is_some_and(|p|
            attacker.is_some() && weapon.is_some_and(|weapon| matches_name(weapon, &p.name)));
        let eliminated = matches && self.make_ghost(victim);
        if let Some(player) = self.players.get(&victim) {
            let decision = audit::Decision { time, cause, victim, victim_name: player.name.clone(), attacker,
                attacker_name: attacker.and_then(|id| self.players.get(&id)).map(|p| p.name.clone()),
                weapon: weapon.map(|w| w.chars().take(512).collect()), matched: matches, eliminated };
            self.players.get_mut(&victim).unwrap().last_decision = Some(decision.clone());
            self.pending_audit.push(decision);
        }
        eliminated
    }

    pub fn leave(&mut self, id: PlayerId, wall_time: u64, combat_time: u64, stopping: bool) -> Logout {
        let tag = self.combat.remove(&id);
        let active = self.is_active(id);
        if let Some(player) = self.players.get_mut(&id) {
            player.online = false;
            player.last_seen = wall_time;
        }
        let mut result = Logout::Safe;
        if !stopping && active && let Some(tag) = tag.filter(|tag| tag.expires_at > combat_time) {
            result = Logout::Death;
            let hit = tag.incoming.filter(|hit| hit.expires_at > combat_time);
            if self.death_at(id, hit.as_ref().map(|h| h.attacker), hit.as_ref().and_then(|h| h.weapon.as_deref()), wall_time, audit::Cause::CombatLogout) {
                result = Logout::Eliminated;
            }
        }
        if let Some(reveal) = self.reveals.get(&id) {
            let remaining = reveal.remaining(wall_time);
            if remaining == 0 { self.reveals.remove(&id); }
            else { self.reveals.insert(id, Reveal::Paused { remaining }); }
        }
        result
    }

    pub fn participant_groups(&self, config: &Config, now: u64) -> (Vec<String>, Vec<String>) {
        let mut names: Vec<_> = if config.roster.is_empty() {
            self.players.values().map(|p| p.name.clone()).collect()
        } else { config.roster.iter().map(|n| n.trim().to_owned()).filter(|n| !n.is_empty()).collect() };
        names.sort_by_key(|name| name.to_ascii_lowercase());
        names.dedup_by(|a,b| a.eq_ignore_ascii_case(b));
        names.into_iter().partition(|name| self.players.values().any(|p|
            p.name.eq_ignore_ascii_case(name) && (p.online || p.last_seen >= now.saturating_sub(259_200_000))))
    }
}

pub fn matches_name(weapon: &str, player: &str) -> bool {
    let weapon = weapon.trim();
    let weapon = [("[", "]"), ("(", ")"), ("{", "}")].into_iter()
        .find_map(|(left,right)| weapon.strip_prefix(left).and_then(|s| s.strip_suffix(right)))
        .unwrap_or(weapon).trim();
    !weapon.is_empty() && !player.trim().is_empty() && weapon.eq_ignore_ascii_case(player.trim())
}
