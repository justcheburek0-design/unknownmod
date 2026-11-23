//! Server-owned, coordinate-based encounters. No client supplied progress or rewards.
use crate::PlayerId;
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum Scenario {
    Ruins,
    Beacon,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum Kind {
    Cooperative,
    Battle,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum Phase {
    Prepared,
    Gathering,
    Running,
    Completed,
    Cancelled,
}
#[derive(Clone, Debug)]
pub struct Presence {
    pub id: PlayerId,
    pub position: [f64; 3],
    pub dimension: String,
    pub active: bool,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Member {
    pub side: String,
    pub joined: bool,
    pub contributed: bool,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Encounter {
    pub scenario: Scenario,
    pub kind: Kind,
    pub phase: Phase,
    pub center: [f64; 3],
    pub dimension: String,
    pub members: BTreeMap<PlayerId, Member>,
    pub initial_count: usize,
    pub points: usize,
    pub required_ms: u64,
    pub remaining_ms: u64,
    pub pause_ms: u64,
    pub paused: bool,
    pub progress_ms: u64,
    pub side_progress: BTreeMap<String, u64>,
    pub components: BTreeSet<usize>,
    pub carrier: Option<PlayerId>,
    pub block: [f64; 3],
    pub winner: Option<String>,
    #[serde(skip)]
    last_tick: Option<u64>,
    #[serde(default)]
    activated: bool,
    #[serde(default)]
    pub recovering: bool,
    #[serde(default)]
    pub automatic: bool,
    #[serde(default = "default_radius")]
    pub arena_radius: f64,
    #[serde(default)]
    pub point_positions: Option<[[f64; 3]; 4]>,
    #[serde(default = "default_pace")]
    pub pace_percent: u32,
    #[serde(default)]
    pub reward_seed: Option<u64>,
    #[serde(default)]
    pub gather_remaining_ms: u64,
    #[serde(default)]
    pub gather_elapsed_ms: u64,
    #[serde(default)]
    pub gather_minimum_ms: u64,
    #[serde(default)]
    pub quorum_ms: u64,
    #[serde(default)]
    pub quorum_target_ms: u64,
    #[serde(default)]
    pub quorum_ids: Vec<PlayerId>,
    #[serde(default)]
    pub opted_out: BTreeSet<PlayerId>,
}
fn default_radius() -> f64 {
    48.0
}
fn default_pace() -> u32 {
    100
}
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Board {
    pub current: Option<Encounter>,
    /// Units of four bread, eight arrows, two iron ingots. Carried between launches.
    pub rewards: BTreeMap<PlayerId, u32>,
    pub serial: u64,
    /// Durable reservations prevent duplicate claims after an unclean shutdown.
    pub reservations: BTreeMap<PlayerId, u32>,
    pub delivered: BTreeMap<PlayerId, u64>,
    pub schedule: crate::automatic::Schedule,
}
fn distance(a: [f64; 3], b: [f64; 3]) -> f64 {
    a.into_iter()
        .zip(b)
        .map(|(a, b)| (a - b).powi(2))
        .sum::<f64>()
        .sqrt()
}
impl Encounter {
    pub fn hud(&self, p: &Presence) -> String {
        if self.recovering {
            return "Ивент: ожидание участников после перезапуска; таймер сохранён".into();
        }
        if self.paused {
            return format!("Ивент: пауза, резерв {} с", self.pause_ms.div_ceil(1000));
        }
        let progress = if self.kind == Kind::Battle && self.scenario == Scenario::Ruins {
            self.members
                .get(&p.id)
                .and_then(|m| self.side_progress.get(&m.side))
                .copied()
                .unwrap_or(0)
        } else {
            self.progress_ms
        };
        let target = if self.scenario == Scenario::Beacon
            && self.kind == Kind::Battle
            && self.carrier != Some(p.id)
        {
            self.block
        } else if self.kind == Kind::Battle {
            self.center
        } else {
            (0..self.points)
                .map(|i| self.point(i))
                .min_by(|a, b| distance(p.position, *a).total_cmp(&distance(p.position, *b)))
                .unwrap_or(self.center)
        };
        format!(
            "Ивент: {}/{} с | осталось {} с | цель {:.0} {:.0} {:.0} ({:.0} м){}",
            progress / 1000,
            self.required_ms / 1000,
            self.remaining_ms.div_ceil(1000),
            target[0],
            target[1],
            target[2],
            distance(p.position, target),
            if self.carrier == Some(p.id) {
                " | блок у тебя"
            } else {
                ""
            }
        )
    }
    pub fn near(&self, p: &Presence, at: [f64; 3], radius: f64) -> bool {
        p.active && p.dimension == self.dimension && distance(p.position, at) <= radius
    }
    pub fn eligible(&self, p: &Presence) -> bool {
        self.members.get(&p.id).is_some_and(|m| m.joined)
            && self.near(p, self.center, self.arena_radius)
    }
    pub fn point(&self, index: usize) -> [f64; 3] {
        if let Some(points) = self.point_positions {
            return points[index % 4];
        }
        let offsets = [
            [-8.0, 0.0, 0.0],
            [8.0, 0.0, 0.0],
            [0.0, 0.0, -8.0],
            [0.0, 0.0, 8.0],
        ];
        std::array::from_fn(|i| self.center[i] + offsets[index % 4][i])
    }
    pub fn public_status(&self) -> String {
        if self.phase == Phase::Prepared {
            return "Нет открытого события.".into();
        }
        let location = format!(
            "{:.0} {:.0} {:.0} ({})",
            self.center[0], self.center[1], self.center[2], self.dimension
        );
        match self.phase {
            Phase::Gathering => {
                if self.automatic {
                    format!("Сигнал: {location}. Подойди к центру на 6 блоков, чтобы участвовать, или /event join. Испытание начнётся автоматически. Отказ: /event leave.")
                } else {
                    format!(
                        "Сигнал: {location}. Участие: /event join. Тип испытания пока неизвестен."
                    )
                }
            }
            Phase::Running => {
                let task = match (self.scenario, self.kind) {
                    (Scenario::Ruins, Kind::Cooperative) => "Удерживайте все точки одновременно",
                    (Scenario::Beacon, Kind::Cooperative) => {
                        "Соберите компоненты: /event interact у точек; затем удерживайте все точки"
                    }
                    (Scenario::Ruins, Kind::Battle) => "Удерживайте центр без соперников",
                    (Scenario::Beacon, Kind::Battle) => {
                        "Заберите блок: /event interact у блока; доставьте к центру и защитите"
                    }
                };
                let points = (0..self.points)
                    .map(|i| {
                        let p = self.point(i);
                        format!("{}: {:.0} {:.0} {:.0}", i + 1, p[0], p[1], p[2])
                    })
                    .collect::<Vec<_>>()
                    .join("; ");
                let detail = match (self.scenario, self.kind) {
                    (Scenario::Beacon, Kind::Battle) => format!(
                        "Блок: {:.0} {:.0} {:.0}; {}",
                        self.block[0],
                        self.block[1],
                        self.block[2],
                        if self.carrier.is_some() {
                            "несёт участник"
                        } else {
                            "на земле"
                        }
                    ),
                    (Scenario::Beacon, Kind::Cooperative) => format!(
                        "Точки: {points}. Компоненты: {}/{}",
                        self.components.len(),
                        self.points
                    ),
                    (Scenario::Ruins, Kind::Cooperative) => format!("Точки: {points}"),
                    _ => "Захват центра; при сопернике прогресс стоит".into(),
                };
                format!(
                    "{task}. Центр: {location}. {detail}. Удержание: {} с. Осталось {} с{}",
                    self.required_ms / 1000,
                    self.remaining_ms.div_ceil(1000),
                    if self.paused {
                        " (пауза: нехватка участников)"
                    } else {
                        ""
                    }
                )
            }
            Phase::Completed => "Испытание завершено. Награда: /event claim.".into(),
            _ => "Испытание отменено без награды.".into(),
        }
    }
    fn cancel(&mut self) {
        self.phase = Phase::Cancelled;
        self.carrier = None;
        self.components.clear();
        self.paused = false;
    }
}
impl Board {
    pub fn record_defense(&mut self, attacker: PlayerId, victim: PlayerId, players: &[Presence]) {
        let Some(e)=self.current.as_mut().filter(|e|e.phase==Phase::Running&&!e.paused)else{return};
        if attacker == victim {
            return;
        }
        let valid = players.iter().any(|p| p.id == attacker && e.eligible(p))
            && players
                .iter()
                .any(|p| p.id == victim && e.near(p, e.center, e.arena_radius));
        if valid {
            if let Some(m) = e.members.get_mut(&attacker) {
                m.contributed = true;
            }
        }
    }
    pub fn prepare(
        &mut self,
        scenario: Scenario,
        kind: Kind,
        center: [f64; 3],
        dimension: String,
    ) -> Result<(), String> {
        if self
            .current
            .as_ref()
            .is_some_and(|e| matches!(e.phase, Phase::Prepared | Phase::Gathering | Phase::Running))
        {
            return Err("Сначала заверши или отмени текущее событие.".into());
        }
        if center
            .iter()
            .any(|n| !n.is_finite() || n.abs() > 29_999_900.0)
            || !(-64.0..=310.0).contains(&center[1])
        {
            return Err("Недопустимые координаты площадки.".into());
        }
        if !matches!(
            dimension.as_str(),
            "minecraft:overworld" | "minecraft:the_nether" | "minecraft:the_end"
        ) {
            return Err("Неизвестное измерение.".into());
        }
        self.serial = self.serial.saturating_add(1);
        self.current = Some(Encounter {
            scenario,
            kind,
            phase: Phase::Prepared,
            center,
            dimension,
            members: BTreeMap::new(),
            initial_count: 0,
            points: 2,
            required_ms: 0,
            remaining_ms: 0,
            pause_ms: 180_000,
            paused: false,
            progress_ms: 0,
            side_progress: BTreeMap::new(),
            components: BTreeSet::new(),
            carrier: None,
            block: center,
            winner: None,
            last_tick: None,
            activated: false,
            recovering: false,
            automatic: false,
            arena_radius: 48.0,
            point_positions: None,
            pace_percent: 100,
            reward_seed: None,
            gather_remaining_ms: 0,
            gather_elapsed_ms: 0,
            gather_minimum_ms: 0,
            quorum_ms: 0,
            quorum_target_ms: 0,
            quorum_ids: Vec::new(),
            opted_out: BTreeSet::new(),
        });
        Ok(())
    }
    pub fn open(&mut self) -> Result<String, String> {
        let e = self
            .current
            .as_mut()
            .ok_or("Нет подготовленного события.")?;
        if e.phase != Phase::Prepared {
            return Err("Событие уже открыто.".into());
        }
        e.phase = Phase::Gathering;
        Ok(e.public_status())
    }
    pub fn join(&mut self, p: &Presence, side: String) -> Result<(), String> {
        let e = self
            .current
            .as_mut()
            .filter(|e| matches!(e.phase, Phase::Gathering | Phase::Running))
            .ok_or("Нет открытого события.")?;
        if !e.near(p, e.center, e.arena_radius) {
            return Err(format!(
                "Подойди к месту сигнала живым активным участником (радиус {:.0} блоков).",
                e.arena_radius
            ));
        }
        if e.members.len() >= 200 && !e.members.contains_key(&p.id) {
            return Err("Лимит участников: 200.".into());
        }
        e.opted_out.remove(&p.id);
        e.members
            .entry(p.id)
            .and_modify(|m| m.joined = true)
            .or_insert(Member {
                side,
                joined: true,
                contributed: false,
            });
        Ok(())
    }
    pub fn leave(&mut self, id: PlayerId) {
        if let Some(e) = &mut self.current {
            e.opted_out.insert(id);
            if let Some(m) = e.members.get_mut(&id) {
                m.joined = false;
            }
            if e.carrier == Some(id) {
                e.carrier = None;
            }
        }
    }
    pub fn start(&mut self, players: &[Presence], now: u64) -> Result<String, String> {
        let e = self.current.as_mut().ok_or("Нет события.")?;
        if e.phase != Phase::Gathering {
            return Err("Сначала открой сбор участников.".into());
        }
        let ids: BTreeSet<_> = players
            .iter()
            .filter(|p| e.eligible(p))
            .map(|p| p.id)
            .collect();
        if ids.len() < 2 {
            return Err("Нужно минимум два активных участника на площадке.".into());
        }
        let sides: BTreeSet<_> = ids.iter().map(|id| e.members[id].side.clone()).collect();
        if e.kind == Kind::Battle && sides.len() < 2 {
            return Err("Для боя нужны минимум два независимых претендента.".into());
        }
        e.members.retain(|id, _| ids.contains(id));
        e.initial_count = ids.len();
        let tier = if ids.len() <= 3 {
            0
        } else if ids.len() <= 6 {
            1
        } else {
            2
        };
        e.points = [2, 3, 4][tier];
        e.required_ms = if e.kind == Kind::Cooperative {
            [10_000, 15_000, 20_000][tier]
        } else {
            [60_000, 90_000, 120_000][tier]
        };
        e.remaining_ms = if e.scenario == Scenario::Ruins {
            [480_000, 720_000, 900_000][tier]
        } else {
            [600_000, 840_000, 1_080_000][tier]
        };
        e.required_ms = e.required_ms * u64::from(e.pace_percent.clamp(80, 120)) / 100;
        e.remaining_ms = e.remaining_ms * u64::from(e.pace_percent.clamp(80, 120)) / 100;
        e.block = e.point(0);
        e.phase = Phase::Running;
        e.last_tick = Some(now);
        Ok(e.public_status())
    }
    pub fn interact(&mut self, p: &Presence) -> Result<String, String> {
        let e = self
            .current
            .as_mut()
            .filter(|e| e.phase == Phase::Running)
            .ok_or("Нет начатого испытания.")?;
        if !e.eligible(p) || e.paused {
            return Err("Нужно участвовать в активном испытании без паузы.".into());
        }
        if e.scenario != Scenario::Beacon {
            return Err("В руинах занимай точки, указанные в /event status.".into());
        }
        if e.kind == Kind::Cooperative {
            let i = (0..e.points)
                .find(|&i| !e.components.contains(&i) && e.near(p, e.point(i), 2.5))
                .ok_or("Подойди к точке с ещё не найденным компонентом (2,5 блока).")?;
            e.components.insert(i);
            e.members.get_mut(&p.id).unwrap().contributed = true;
            e.activated = true;
            Ok(format!(
                "Компонент установлен: {}/{}.",
                e.components.len(),
                e.points
            ))
        } else {
            if e.carrier.is_some() || !e.near(p, e.block, 2.5) {
                return Err("Подойди к свободному блоку (2,5 блока).".into());
            }
            e.carrier = Some(p.id);
            e.members.get_mut(&p.id).unwrap().contributed = true;
            e.activated = true;
            Ok("Блок у тебя. Доставь его к центру; /event drop оставит его на месте.".into())
        }
    }
    pub fn drop_block(&mut self, p: &Presence) -> Result<(), String> {
        let e = self
            .current
            .as_mut()
            .filter(|e| e.phase == Phase::Running)
            .ok_or("Нет начатого испытания.")?;
        if e.carrier != Some(p.id) {
            return Err("У тебя нет блока.".into());
        }
        if e.near(p, e.center, e.arena_radius) {
            e.block = p.position;
        }
        e.carrier = None;
        Ok(())
    }
    pub fn cancel(&mut self) {
        if self
            .current
            .as_ref()
            .is_some_and(|e| e.phase == Phase::Prepared)
        {
            self.current = None;
            return;
        }
        if let Some(e) = &mut self.current {
            e.cancel();
        }
    }
    /// Preserve the encounter across clean or unclean shutdowns. Offline time
    /// never consumes either the objective clock or the shortage pause budget.
    pub fn recover(&mut self) {
        if let Some(e) = self.current.as_mut().filter(|e| e.phase == Phase::Running) {
            e.recovering = true;
            e.paused = true;
            e.last_tick = None;
            e.activated |=
                e.progress_ms > 0 || !e.side_progress.is_empty() || !e.components.is_empty();
        }
    }
    pub fn tick(&mut self, players: &[Presence], now: u64) -> Option<String> {
        let e = self
            .current
            .as_mut()
            .filter(|e| e.phase == Phase::Running)?;
        let dt = now
            .saturating_sub(e.last_tick.replace(now).unwrap_or(now))
            .min(2_000);
        let active: Vec<_> = players.iter().filter(|p| e.eligible(p)).collect();
        let sides: BTreeSet<_> = active
            .iter()
            .map(|p| e.members[&p.id].side.clone())
            .collect();
        let shortage = if e.kind == Kind::Cooperative {
            active.len() < e.points
        } else {
            sides.len() < 2
        };
        if e.recovering {
            if shortage {
                return None;
            }
            e.recovering = false;
            e.paused = false;
            return Some(
                "Участники вернулись. Испытание продолжается с сохранённого места.".into(),
            );
        }
        if let Some(id) = e.carrier {
            if let Some(p) = players.iter().find(|p| p.id == id && e.eligible(p)) {
                e.block = p.position;
            } else {
                e.carrier = None;
            }
        }
        let changed = e.paused != shortage;
        e.paused = shortage;
        if shortage {
            e.progress_ms = if e.kind == Kind::Cooperative {
                0
            } else {
                e.progress_ms
            };
            e.pause_ms = e.pause_ms.saturating_sub(dt);
            if e.pause_ms == 0 {
                e.cancel();
                return Some(
                    "Испытание отменено: состав не восстановился. Награда не выдаётся.".into(),
                );
            }
            return changed
                .then(|| "Испытание приостановлено: не хватает активных участников.".into());
        }
        // Time starts at the first objective interaction, not while players travel.
        let mut winners = Vec::new();
        if e.kind == Kind::Cooperative {
            let holders: Vec<_> = (0..e.points)
                .map(|i| {
                    active
                        .iter()
                        .find(|p| e.near(p, e.point(i), 2.5))
                        .map(|p| p.id)
                })
                .collect();
            if holders.iter().any(Option::is_some) {
                e.activated = true;
            }
            let ready = holders.iter().all(Option::is_some)
                && (e.scenario == Scenario::Ruins || e.components.len() == e.points);
            if ready {
                e.progress_ms = e.progress_ms.saturating_add(dt);
                for id in holders.iter().flatten() {
                    e.members.get_mut(id).unwrap().contributed = true;
                }
            } else {
                e.progress_ms = 0;
            }
            if e.progress_ms >= e.required_ms {
                winners = e
                    .members
                    .iter()
                    .filter(|(_, m)| m.joined && m.contributed)
                    .map(|(&id, _)| id)
                    .collect();
            }
        } else {
            let center: Vec<_> = active.iter().filter(|p| e.near(p, e.center, 2.5)).collect();
            let occupying: BTreeSet<_> = center
                .iter()
                .map(|p| e.members[&p.id].side.clone())
                .collect();
            if !center.is_empty() {
                e.activated = true;
            }
            if occupying.len() == 1 {
                let side = occupying.iter().next().unwrap().clone();
                let valid = e.scenario == Scenario::Ruins
                    || e.carrier
                        .is_some_and(|id| center.iter().any(|p| p.id == id));
                if valid {
                    for p in &center {
                        e.members.get_mut(&p.id).unwrap().contributed = true;
                    }
                    let progress = if e.scenario == Scenario::Ruins {
                        e.side_progress.entry(side.clone()).or_default()
                    } else {
                        &mut e.progress_ms
                    };
                    *progress = progress.saturating_add(dt);
                    if *progress >= e.required_ms {
                        e.winner = Some(side.clone());
                        winners = e
                            .members
                            .iter()
                            .filter(|(_, m)| m.side == side && m.joined && m.contributed)
                            .map(|(&id, _)| id)
                            .collect();
                    }
                }
            }
        }
        if !winners.is_empty() {
            let units = e.initial_count as u32
                + e.reward_seed
                    .map_or(0, |seed| (seed % (e.initial_count as u64 / 2 + 1)) as u32);
            for (i, id) in winners.iter().enumerate() {
                let n = units / winners.len() as u32
                    + u32::from((i as u32) < units % winners.len() as u32);
                *self.rewards.entry(*id).or_default() += n;
            }
            e.phase = Phase::Completed;
            e.carrier = None;
            e.components.clear();
            e.paused = false;
            return Some("Испытание завершено! Заработанные припасы: /event claim.".into());
        }
        if e.activated {
            e.remaining_ms = e.remaining_ms.saturating_sub(dt);
        }
        if e.remaining_ms == 0 {
            e.cancel();
            return Some("Время испытания истекло. Запуск завершён без награды.".into());
        }
        changed.then(|| "Состав восстановлен. Испытание продолжается.".into())
    }
}
