//! Private, persisted scheduling. Time advances only during qualified online play.
use crate::events::{Board, Kind, Phase, Presence, Scenario};
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;
pub fn random() -> Result<u64, String> {
    let mut bytes = [0; 8];
    getrandom::fill(&mut bytes).map_err(|e| e.to_string())?;
    Ok(u64::from_le_bytes(bytes))
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct AutoConfig {
    pub enabled: bool,
    pub min_minutes: u32,
    pub max_minutes: u32,
    pub minimum_online: usize,
}
impl Default for AutoConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            min_minutes: 90,
            max_minutes: 180,
            minimum_online: 3,
        }
    }
}
impl AutoConfig {
    pub fn delay(&self, draw: u64) -> u64 {
        let lo = self.min_minutes.clamp(15, 1440);
        let hi = self.max_minutes.clamp(lo, 1440);
        u64::from(lo) * 60_000 + draw % (u64::from(hi - lo) * 60_000 + 1)
    }
}
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Schedule {
    pub remaining_ms: Option<u64>,
    pub last_variant: Option<u8>,
    pub reason: String,
    pub forced: bool,
    #[serde(skip)]
    last_tick: Option<u64>,
    #[serde(skip)]
    qualified_ms: u64,
}
impl Schedule {
    pub fn suspend(&mut self) {
        self.last_tick = None;
        self.qualified_ms = 0;
    }
}
#[derive(Debug, PartialEq)]
pub enum Action {
    None,
    Search,
    Message(String),
}
#[derive(Clone, Copy, Debug)]
pub struct Border {
    pub min_x: f64,
    pub min_z: f64,
    pub max_x: f64,
    pub max_z: f64,
}
impl Border {
    pub fn radius(&self) -> Option<f64> {
        if [self.min_x, self.min_z, self.max_x, self.max_z]
            .iter()
            .any(|n| !n.is_finite())
        {
            return None;
        }
        let width = (self.max_x - self.min_x).min(self.max_z - self.min_z);
        let radius = ((width - 80.0) / 2.0).clamp(24.0, 48.0);
        (width > 2.0 * (radius + 16.0)).then_some(radius)
    }
    pub fn contains(&self, p: [f64; 3], margin: f64) -> bool {
        p[0] - margin >= self.min_x
            && p[0] + margin < self.max_x
            && p[2] - margin >= self.min_z
            && p[2] + margin < self.max_z
    }
    pub fn candidate(&self, anchor: [f64; 3], a: u64, b: u64) -> Option<([f64; 3], f64)> {
        let radius = self.radius()?;
        let margin = radius + 16.0;
        let reach =
            ((self.max_x - self.min_x).min(self.max_z - self.min_z) / 6.0).clamp(24.0, 160.0);
        let axis = |at: f64, min: f64, max: f64, draw: u64| -> Option<f64> {
            let lo = (at - reach).max(min + margin).ceil();
            let hi = (at + reach).min(max - margin - 1.0).floor();
            if hi < lo {
                return None;
            }
            Some(lo + (draw % ((hi - lo) as u64 + 1)) as f64 + 0.5)
        };
        let p = [
            axis(anchor[0], self.min_x, self.max_x, a)?,
            anchor[1],
            axis(anchor[2], self.min_z, self.max_z, b)?,
        ];
        self.contains(p, margin).then_some((p, radius))
    }
}
impl Board {
    pub fn automatic_step(
        &mut self,
        config: &AutoConfig,
        players: &[(Presence, String)],
        now: u64,
        draw: u64,
    ) -> Action {
        let dt = now
            .saturating_sub(self.schedule.last_tick.replace(now).unwrap_or(now))
            .min(2000);
        if let Some(e) = self
            .current
            .as_mut()
            .filter(|e| e.automatic && e.phase == Phase::Gathering)
        {
            // A restart with no players cannot exhaust the invitation window.
            if players
                .iter()
                .filter(|(p, _)| p.active && p.dimension == e.dimension)
                .count()
                < 2
            {
                return Action::None;
            }
            for (p, side) in players {
                if e.members.len() < 200
                    && e.near(p, e.center, 6.0)
                    && !e.members.contains_key(&p.id)
                    && !e.opted_out.contains(&p.id)
                {
                    e.members.insert(
                        p.id,
                        crate::events::Member {
                            side: side.clone(),
                            joined: true,
                            contributed: false,
                        },
                    );
                }
            }
            e.gather_remaining_ms = e.gather_remaining_ms.saturating_sub(dt);
            e.gather_elapsed_ms += dt;
            let ids = players
                .iter()
                .filter(|(p, _)| e.eligible(p))
                .map(|(p, _)| p.id)
                .collect::<BTreeSet<_>>()
                .into_iter()
                .collect::<Vec<_>>();
            let sides = ids
                .iter()
                .map(|id| e.members[id].side.as_str())
                .collect::<BTreeSet<_>>();
            if ids.len() >= 2 && (e.kind == Kind::Cooperative || sides.len() >= 2) {
                if e.quorum_ids != ids {
                    e.quorum_ids = ids;
                    e.quorum_ms = e.quorum_target_ms;
                } else {
                    e.quorum_ms = e.quorum_ms.saturating_sub(dt);
                }
                if e.quorum_ms == 0 && e.gather_elapsed_ms >= e.gather_minimum_ms {
                    let presence = players.iter().map(|(p, _)| p.clone()).collect::<Vec<_>>();
                    return match self.start(&presence, now) {
                        Ok(message) => Action::Message(message),
                        Err(_) => Action::None,
                    };
                }
            } else {
                e.quorum_ids.clear();
                e.quorum_ms = e.quorum_target_ms;
            }
            if e.gather_remaining_ms == 0 {
                self.cancel();
                return Action::Message(
                    "Сигнал погас: недостаточно желающих для испытания. Штрафов нет.".into(),
                );
            }
            return Action::None;
        }
        if self
            .current
            .as_ref()
            .is_some_and(|e| matches!(e.phase, Phase::Prepared | Phase::Gathering | Phase::Running))
        {
            self.schedule.remaining_ms = None;
            self.schedule.qualified_ms = 0;
            return Action::None;
        }
        if !config.enabled {
            self.schedule.reason = "Автозапуск выключен".into();
            return Action::None;
        }
        let remaining = self
            .schedule
            .remaining_ms
            .get_or_insert_with(|| config.delay(draw));
        if players
            .iter()
            .filter(|(p, _)| p.active && p.dimension == "minecraft:overworld")
            .count()
            < config.minimum_online.clamp(2, 200)
        {
            self.schedule.qualified_ms = 0;
            self.schedule.reason = "Недостаточно активных игроков в обычном мире".into();
            return Action::None;
        }
        self.schedule.qualified_ms += dt;
        if !self.schedule.forced && self.schedule.qualified_ms < 120_000 {
            self.schedule.reason = "Ожидание устойчивого онлайна".into();
            return Action::None;
        }
        *remaining = remaining.saturating_sub(dt);
        if *remaining > 0 {
            self.schedule.reason = "Ожидание случайного момента".into();
            return Action::None;
        }
        self.schedule.forced = false;
        // Reserve a bounded retry before searching. A crash cannot cause a search storm.
        self.schedule.remaining_ms = Some(300_000 + draw % 300_001);
        self.schedule.reason = "Поиск безопасной площадки".into();
        Action::Search
    }
    pub fn open_automatic(
        &mut self,
        center: [f64; 3],
        points: [[f64; 3]; 4],
        radius: f64,
        draw: u64,
        battle_allowed: bool,
    ) -> Result<String, String> {
        let variant = match self.schedule.last_variant {
            Some(last) => (u64::from(last) + 1 + draw % 3) % 4,
            None => draw % 4,
        } as u8;
        let mut scenario = if variant / 2 == 0 {
            Scenario::Ruins
        } else {
            Scenario::Beacon
        };
        if !battle_allowed {
            if let Some(last) = self.schedule.last_variant {
                scenario = if last / 2 == 0 {
                    Scenario::Beacon
                } else {
                    Scenario::Ruins
                };
            }
        }
        let kind = if variant % 2 == 1 && battle_allowed {
            Kind::Battle
        } else {
            Kind::Cooperative
        };
        self.prepare(scenario, kind, center, "minecraft:overworld".into())?;
        let e = self.current.as_mut().unwrap();
        e.automatic = true;
        e.arena_radius = radius;
        e.point_positions = Some(points);
        e.pace_percent = 80 + (draw.rotate_left(13) % 41) as u32;
        e.reward_seed = Some(draw.rotate_left(29));
        e.gather_remaining_ms = 360_000 + draw.rotate_left(7) % 240_001;
        e.gather_minimum_ms = 60_000 + draw.rotate_left(17) % 60_001;
        e.quorum_target_ms = 30_000 + draw.rotate_left(37) % 30_001;
        e.quorum_ms = e.quorum_target_ms;
        self.schedule.last_variant = Some(
            (if scenario == Scenario::Ruins { 0 } else { 2 }) + u8::from(kind == Kind::Battle),
        );
        self.schedule.remaining_ms = None;
        self.schedule.reason = "Идёт сбор участников".into();
        self.open()
    }
}
