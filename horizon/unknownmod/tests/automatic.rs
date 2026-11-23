use unknownmod_rules::{
    automatic::{Action, AutoConfig, Border},
    events::{Board, Kind, Phase, Presence, Scenario},
};
fn players() -> Vec<(Presence, String)> {
    (1..=3)
        .map(|id| {
            (
                Presence {
                    id,
                    position: [0.0, 70.0, 0.0],
                    dimension: "minecraft:overworld".into(),
                    active: true,
                },
                id.to_string(),
            )
        })
        .collect()
}
fn open(draw: u64) -> Board {
    let mut b = Board::default();
    b.open_automatic(
        [0.0, 70.0, 0.0],
        [
            [-8.0, 70.0, 0.0],
            [8.0, 70.0, 0.0],
            [0.0, 70.0, -8.0],
            [0.0, 70.0, 8.0],
        ],
        48.0,
        draw,
        true,
    )
    .unwrap();
    b
}

#[test]
fn default_enabled_delay_and_variation_are_bounded() {
    let c = AutoConfig::default();
    assert!(c.enabled);
    assert_eq!(c.minimum_online, 3);
    let mut variants = std::collections::BTreeSet::new();
    for draw in 0..500u64 {
        let delay = c.delay(draw * 1_000_003);
        assert!((90 * 60_000..=180 * 60_000).contains(&delay));
        let b = open(draw);
        let e = b.current.unwrap();
        variants.insert(format!("{:?}{:?}", e.scenario, e.kind));
        assert!((80..=120).contains(&e.pace_percent));
        assert!((360_000..=600_000).contains(&e.gather_remaining_ms));
        assert!((60_000..=120_000).contains(&e.gather_minimum_ms));
        assert!((30_000..=60_000).contains(&e.quorum_target_ms));
    }
    assert_eq!(variants.len(), 4);
}

#[test]
fn clock_freezes_for_small_online_and_never_catches_up_offline() {
    let c = AutoConfig::default();
    let mut b = Board::default();
    let mut p = players();
    b.automatic_step(&c, &p, 0, 7);
    let saved = b.schedule.remaining_ms;
    p[2].0.active = false;
    for i in 1..=20 {
        b.automatic_step(&c, &p, i * 1000, 7);
    }
    assert_eq!(b.schedule.remaining_ms, saved);
    let mut loaded: Board = serde_json::from_str(&serde_json::to_string(&b).unwrap()).unwrap();
    p[2].0.active = true;
    loaded.automatic_step(&c, &p, 90_000_000, 7);
    assert_eq!(loaded.schedule.remaining_ms, saved);
    for i in 1..120 {
        assert_eq!(
            loaded.automatic_step(&c, &p, 90_000_000 + i * 1000, 7),
            Action::None
        );
    }
    assert_eq!(loaded.schedule.remaining_ms, saved);
    loaded.automatic_step(&c, &p, 90_120_000, 7);
    assert_eq!(loaded.schedule.remaining_ms, saved.map(|n| n - 1000));
}

#[test]
fn forced_search_still_requires_online_and_reserves_a_retry() {
    let c = AutoConfig::default();
    let mut b = Board::default();
    b.schedule.remaining_ms = Some(0);
    b.schedule.forced = true;
    assert_eq!(b.automatic_step(&c, &players()[..2], 0, 123), Action::None);
    assert_eq!(b.automatic_step(&c, &players(), 1000, 123), Action::Search);
    assert!((300_000..=600_000).contains(&b.schedule.remaining_ms.unwrap()));
    assert!(!b.schedule.forced);
    assert_eq!(b.automatic_step(&c, &players(), 2000, 123), Action::None);
}

#[test]
fn ordinary_random_deadline_triggers_without_any_force_command() {
    let mut b = Board::default();
    let c = AutoConfig {
        min_minutes: 15,
        max_minutes: 15,
        ..Default::default()
    };
    let mut triggered = None;
    for second in 0..=1100 {
        if b.automatic_step(&c, &players(), second * 1000, 123) == Action::Search {
            triggered = Some(second);
            break;
        }
    }
    assert!(triggered.is_some_and(|s| (1018..=1021).contains(&s)));
    assert!(!b.schedule.forced);
    assert!(b.schedule.remaining_ms.unwrap() >= 300_000);
}

#[test]
fn auto_enrollment_stable_quorum_and_opt_out() {
    let mut b = open(0);
    let p = players();
    let c = AutoConfig::default();
    b.automatic_step(&c, &p, 0, 0);
    assert_eq!(b.current.as_ref().unwrap().members.len(), 3);
    b.leave(1);
    for i in 1..=60 {
        b.automatic_step(&c, &p, i * 1000, 0);
    }
    let e = b.current.as_ref().unwrap();
    assert_eq!(e.phase, Phase::Running);
    assert_eq!(e.initial_count, 2);
    assert!(!e.members.contains_key(&1));
    assert_eq!(e.required_ms, 8000);
    assert!(e.automatic);
}

#[test]
fn no_arrivals_and_one_battle_side_cancel_without_rewards() {
    for battle in [false, true] {
        let mut b = open(u64::from(battle));
        let mut p = players();
        for (player, side) in &mut p {
            if battle {
                *side = "team:one".into();
            } else {
                player.position[0] = 30.0;
            }
        }
        for i in 0..=601 {
            b.automatic_step(&AutoConfig::default(), &p, i * 1000, 0);
        }
        assert_eq!(b.current.unwrap().phase, Phase::Cancelled);
        assert!(b.rewards.is_empty());
    }
}

#[test]
fn restart_preserves_gathering_and_cooldown() {
    let mut b = open(0);
    let p = players();
    for i in 0..=20 {
        b.automatic_step(&AutoConfig::default(), &p, i * 1000, 0);
    }
    let remaining = b.current.as_ref().unwrap().gather_remaining_ms;
    let quorum = b.current.as_ref().unwrap().quorum_ms;
    let mut restored: Board = serde_json::from_str(&serde_json::to_string(&b).unwrap()).unwrap();
    restored.recover();
    for i in 0..=300 {
        restored.automatic_step(&AutoConfig::default(), &[], 9_000_000 + i * 1000, 0);
    }
    assert_eq!(
        restored.current.as_ref().unwrap().gather_remaining_ms,
        remaining
    );
    assert_eq!(restored.current.as_ref().unwrap().quorum_ms, quorum);
    restored.automatic_step(&AutoConfig::default(), &p, 9_301_000, 0);
    assert!(restored.current.as_ref().unwrap().gather_remaining_ms < remaining);
}

#[test]
fn manual_event_is_never_replaced_and_off_does_not_cancel_announced_event() {
    let mut b = Board::default();
    b.prepare(
        Scenario::Ruins,
        Kind::Cooperative,
        [0.0, 70.0, 0.0],
        "minecraft:overworld".into(),
    )
    .unwrap();
    b.schedule.remaining_ms = Some(0);
    b.schedule.forced = true;
    assert_eq!(
        b.automatic_step(&AutoConfig::default(), &players(), 0, 0),
        Action::None
    );
    assert_eq!(b.current.as_ref().unwrap().phase, Phase::Prepared);
    let mut b = open(0);
    let c = AutoConfig {
        enabled: false,
        ..Default::default()
    };
    for i in 0..=60 {
        b.automatic_step(&c, &players(), i * 1000, 0);
    }
    assert_eq!(b.current.as_ref().unwrap().phase, Phase::Running);
    b.cancel();
    assert_eq!(b.automatic_step(&c, &players(), 61_000, 0), Action::None);
}

#[test]
fn border_is_dynamic_offset_and_whole_arena_stays_inside() {
    let early = Border {
        min_x: -50.0,
        max_x: 50.0,
        min_z: -50.0,
        max_z: 50.0,
    };
    assert!(early.candidate([0.0, 70.0, 0.0], 12, 13).is_some());
    for width in [80.0, 100.0, 128.0, 256.0, 2000.0, 60_000_000.0] {
        let border = Border {
            min_x: 1000.0 - width / 2.0,
            max_x: 1000.0 + width / 2.0,
            min_z: -500.0 - width / 2.0,
            max_z: -500.0 + width / 2.0,
        };
        for seed in 0..1000 {
            if let Some((center, radius)) =
                border.candidate([1000.0, 70.0, -500.0], seed * 79, seed * 103)
            {
                assert!(border.contains(center, radius + 16.0));
                assert!((24.0..=48.0).contains(&radius));
                assert!((center[0] - 1000.0).abs() <= 161.0);
                assert!((center[2] + 500.0).abs() <= 161.0);
            }
        }
    }
    let small = Border {
        min_x: -30.0,
        max_x: 30.0,
        min_z: -30.0,
        max_z: 30.0,
    };
    assert!(small.radius().is_none());
    let larger = Border {
        min_x: -128.0,
        max_x: 128.0,
        min_z: -128.0,
        max_z: 128.0,
    };
    assert!(larger.candidate([0.0, 70.0, 0.0], 1, 2).is_some());
    assert!(!small.contains([0.0, 70.0, 0.0], 48.0));
}

#[test]
fn opt_out_before_arriving_is_respected_until_explicit_join() {
    let mut b = open(0);
    let p = players();
    b.leave(1);
    b.automatic_step(&AutoConfig::default(), &p, 0, 0);
    assert!(!b.current.as_ref().unwrap().members.contains_key(&1));
    b.join(&p[0].0, "1".into()).unwrap();
    assert!(b.current.as_ref().unwrap().members[&1].joined);
}

#[test]
fn public_gathering_hides_variant_pace_reward_and_schedule() {
    let a = open(0);
    let b = open(1);
    assert_eq!(
        a.current.unwrap().public_status(),
        b.current.unwrap().public_status()
    );
}

#[test]
fn variants_do_not_repeat_and_single_group_only_gets_cooperative() {
    let mut b = Board::default();
    for seed in 0..100 {
        let previous = b.schedule.last_variant;
        b.open_automatic([0.0, 70.0, 0.0], [[0.0, 70.0, 0.0]; 4], 48.0, seed, true)
            .unwrap();
        assert_ne!(b.schedule.last_variant, previous);
        b.cancel();
    }
    for seed in 0..100 {
        b.open_automatic([0.0, 70.0, 0.0], [[0.0, 70.0, 0.0]; 4], 48.0, seed, false)
            .unwrap();
        assert_eq!(b.current.as_ref().unwrap().kind, Kind::Cooperative);
        b.cancel();
    }
}
