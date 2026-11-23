use unknownmod_rules::events::*;

fn p(id: u128, position: [f64; 3]) -> Presence {
    Presence {
        id,
        position,
        dimension: "minecraft:overworld".into(),
        active: true,
    }
}
fn setup(scenario: Scenario, kind: Kind, n: usize) -> (Board, Vec<Presence>) {
    let mut board = Board::default();
    board
        .prepare(
            scenario,
            kind,
            [0.0, 70.0, 0.0],
            "minecraft:overworld".into(),
        )
        .unwrap();
    board.open().unwrap();
    let players = (1..=n)
        .map(|id| p(id as u128, [0.0, 70.0, 12.0]))
        .collect::<Vec<_>>();
    for p in &players {
        board.join(p, p.id.to_string()).unwrap();
    }
    board.start(&players, 0).unwrap();
    (board, players)
}
fn tick(board: &mut Board, players: &[Presence], seconds: u64) {
    for i in 1..=seconds {
        board.tick(players, i * 1000);
    }
}

#[test]
fn privacy_and_voluntary_actual_attendance() {
    let mut b = Board::default();
    let drop_without_event = b.drop_block(&p(1, [0.0, 70.0, 0.0])).unwrap_err();
    b.prepare(
        Scenario::Beacon,
        Kind::Battle,
        [0.0, 70.0, 0.0],
        "minecraft:overworld".into(),
    )
    .unwrap();
    assert_eq!(
        b.current.as_ref().unwrap().public_status(),
        "Нет открытого события."
    );
    assert_eq!(
        b.drop_block(&p(1, [0.0, 70.0, 0.0])).unwrap_err(),
        drop_without_event
    );
    assert!(b.join(&p(1, [0.0, 70.0, 0.0]), "a".into()).is_err());
    let message = b.open().unwrap();
    assert!(!message.contains("Battle"));
    assert!(!message.contains("Beacon"));
    let all = (1..=10)
        .map(|id| p(id, [0.0, 70.0, 0.0]))
        .collect::<Vec<_>>();
    for p in &all[..2] {
        b.join(p, p.id.to_string()).unwrap();
    }
    b.start(&all, 0).unwrap();
    assert_eq!(b.current.as_ref().unwrap().initial_count, 2);
    assert_eq!(b.current.as_ref().unwrap().required_ms, 60_000);
}

#[test]
fn tiers_and_admission() {
    for (n, points, hold) in [
        (2, 2, 10_000),
        (3, 2, 10_000),
        (4, 3, 15_000),
        (6, 3, 15_000),
        (7, 4, 20_000),
        (10, 4, 20_000),
    ] {
        let (mut b, _) = setup(Scenario::Ruins, Kind::Cooperative, n);
        assert_eq!(b.current.as_ref().unwrap().points, points);
        assert_eq!(b.current.as_ref().unwrap().required_ms, hold);
        let mut invalid = p(100, [0.0, 70.0, 0.0]);
        invalid.active = false;
        assert!(b.join(&invalid, "other".into()).is_err());
        invalid.active = true;
        invalid.dimension = "minecraft:the_nether".into();
        assert!(b.join(&invalid, "other".into()).is_err());
        assert!(b.join(&p(101, [49.0, 70.0, 0.0]), "other".into()).is_err());
        b.join(&p(102, [0.0, 70.0, 0.0]), "other".into()).unwrap();
        assert_eq!(b.current.as_ref().unwrap().initial_count, n);
    }
    let (mut b, players) = setup(Scenario::Ruins, Kind::Battle, 2);
    b.current.as_mut().unwrap().phase = Phase::Gathering;
    for m in b.current.as_mut().unwrap().members.values_mut() {
        m.side = "same".into();
    }
    assert!(b.start(&players, 0).is_err());
}

#[test]
fn cooperative_ruins_and_beacon_complete_once() {
    for scenario in [Scenario::Ruins, Scenario::Beacon] {
        let (mut b, mut players) = setup(scenario, Kind::Cooperative, 2);
        for (i, p) in players.iter_mut().enumerate() {
            p.position = b.current.as_ref().unwrap().point(i);
        }
        if scenario == Scenario::Beacon {
            for p in &players {
                b.interact(p).unwrap();
            }
        }
        tick(&mut b, &players, 10);
        assert_eq!(b.current.as_ref().unwrap().phase, Phase::Completed);
        assert_eq!(b.rewards.values().sum::<u32>(), 2);
        tick(&mut b, &players, 100);
        assert_eq!(b.rewards.values().sum::<u32>(), 2);
    }
}

#[test]
fn shortage_pauses_time_resets_hold_and_preserves_components() {
    let (mut b, mut players) = setup(Scenario::Beacon, Kind::Cooperative, 2);
    for (i, p) in players.iter_mut().enumerate() {
        p.position = b.current.as_ref().unwrap().point(i);
        b.interact(p).unwrap();
    }
    tick(&mut b, &players, 5);
    let remaining = b.current.as_ref().unwrap().remaining_ms;
    players[1].active = false;
    b.tick(&players, 6000);
    let e = b.current.as_ref().unwrap();
    assert!(e.paused);
    assert_eq!(e.progress_ms, 0);
    assert_eq!(e.components.len(), 2);
    assert_eq!(e.remaining_ms, remaining);
    players[1].active = true;
    b.tick(&players, 7000);
    assert!(!b.current.as_ref().unwrap().paused);
    assert_eq!(b.current.as_ref().unwrap().pause_ms, 179_000);
    players[1].active = false;
    for i in 8..=186 {
        b.tick(&players, i * 1000);
    }
    assert_eq!(b.current.as_ref().unwrap().phase, Phase::Cancelled);
    assert!(b.rewards.is_empty());
}

#[test]
fn battle_ruins_contested_points_and_independent_progress() {
    let (mut b, mut players) = setup(Scenario::Ruins, Kind::Battle, 2);
    players[0].position = [0.0, 70.0, 0.0];
    tick(&mut b, &players, 20);
    players[1].position = players[0].position;
    for i in 21..=30 {
        b.tick(&players, i * 1000);
    }
    assert_eq!(b.current.as_ref().unwrap().side_progress["1"], 20_000);
    players[0].position = [0.0, 70.0, 12.0];
    for i in 31..=90 {
        b.tick(&players, i * 1000);
    }
    assert_eq!(b.current.as_ref().unwrap().phase, Phase::Completed);
    assert_eq!(b.rewards.get(&2), Some(&2));
    assert!(!b.rewards.contains_key(&1));
}

#[test]
fn beacon_carrier_drop_permits_takeover_and_no_offline_win() {
    let (mut b, mut players) = setup(Scenario::Beacon, Kind::Battle, 2);
    players[0].position = b.current.as_ref().unwrap().block;
    b.interact(&players[0]).unwrap();
    players[0].position = [0.0, 70.0, 0.0];
    tick(&mut b, &players, 30);
    b.drop_block(&players[0]).unwrap();
    players[0].position = [0.0, 70.0, 12.0];
    players[1].position = [0.0, 70.0, 0.0];
    b.interact(&players[1]).unwrap();
    players[0].active = false;
    b.tick(&players, 31_000);
    assert_eq!(b.current.as_ref().unwrap().progress_ms, 30_000);
    players[0].active = true;
    for i in 32..=61 {
        b.tick(&players, i * 1000);
    }
    assert_eq!(b.rewards.get(&2), Some(&2));
    assert_eq!(b.current.as_ref().unwrap().phase, Phase::Completed);
}

#[test]
fn block_drops_on_death_exit_dimension_or_boundary_and_components_are_server_owned() {
    for reason in 0..3 {
        let (mut b, mut players) = setup(Scenario::Beacon, Kind::Battle, 2);
        players[0].position = b.current.as_ref().unwrap().block;
        b.interact(&players[0]).unwrap();
        let previous = b.current.as_ref().unwrap().block;
        match reason {
            0 => players[0].active = false,
            1 => players[0].dimension = "minecraft:the_end".into(),
            _ => players[0].position = [100.0, 70.0, 0.0],
        }
        b.tick(&players, 1000);
        assert!(b.current.as_ref().unwrap().carrier.is_none());
        assert_eq!(b.current.as_ref().unwrap().block, previous);
    }
    let (mut b, players) = setup(Scenario::Beacon, Kind::Cooperative, 2);
    assert!(b.interact(&players[0]).is_err());
}

#[test]
fn restart_preserves_rewards_and_timeout_never_mints_rewards() {
    let (mut b, mut players) = setup(Scenario::Ruins, Kind::Cooperative, 2);
    players[0].position = b.current.as_ref().unwrap().point(0);
    b.current.as_mut().unwrap().remaining_ms = 1000;
    b.tick(&players, 1000);
    assert_eq!(b.current.as_ref().unwrap().phase, Phase::Cancelled);
    assert!(b.rewards.is_empty());
    let (mut b, _) = setup(Scenario::Beacon, Kind::Battle, 2);
    b.rewards.insert(1, 3);
    b.reservations.insert(2, 1);
    let mut restored: Board = serde_json::from_str(&serde_json::to_string(&b).unwrap()).unwrap();
    restored.recover();
    assert_eq!(restored.current.as_ref().unwrap().phase, Phase::Running);
    assert!(restored.current.as_ref().unwrap().recovering);
    assert_eq!(restored.rewards.get(&1), Some(&3));
    assert_eq!(restored.reservations.get(&2), Some(&1));
}

#[test]
fn restart_preserves_exact_progress_components_carrier_and_all_clocks() {
    for scenario in [Scenario::Ruins, Scenario::Beacon] {
        for kind in [Kind::Cooperative, Kind::Battle] {
            let (mut b, mut players) = setup(scenario, kind, 2);
            if kind == Kind::Cooperative {
                for (i, p) in players.iter_mut().enumerate() {
                    p.position = b.current.as_ref().unwrap().point(i);
                    if scenario == Scenario::Beacon {
                        b.interact(p).unwrap();
                    }
                }
            } else {
                if scenario == Scenario::Beacon {
                    players[0].position = b.current.as_ref().unwrap().block;
                    b.interact(&players[0]).unwrap();
                }
                players[0].position = [0.0, 70.0, 0.0];
            }
            tick(&mut b, &players, 5);
            let saved = b.current.as_ref().unwrap().clone();
            let mut restored: Board =
                serde_json::from_str(&serde_json::to_string(&b).unwrap()).unwrap();
            restored.recover();
            for time in [1_000_000, 9_000_000, 20_000_000] {
                restored.tick(&[], time);
            }
            restored.tick(&players[..1], 30_000_000);
            let e = restored.current.as_ref().unwrap();
            assert_eq!(e.remaining_ms, saved.remaining_ms);
            assert_eq!(e.pause_ms, saved.pause_ms);
            assert_eq!(e.progress_ms, saved.progress_ms);
            assert_eq!(e.side_progress, saved.side_progress);
            assert_eq!(e.components, saved.components);
            assert_eq!(e.carrier, saved.carrier);
            assert_eq!(e.block, saved.block);
            assert!(e.recovering);
            assert!(restored.rewards.is_empty());
            restored.tick(&players, 30_001_000);
            assert!(!restored.current.as_ref().unwrap().recovering);
            assert_eq!(
                restored.current.as_ref().unwrap().remaining_ms,
                saved.remaining_ms
            );
            let remaining = (saved.required_ms - 5_000) / 1000;
            for i in 1..=remaining {
                restored.tick(&players, 30_001_000 + i * 1000);
            }
            assert_eq!(restored.current.as_ref().unwrap().phase, Phase::Completed);
            assert_eq!(restored.rewards.values().sum::<u32>(), 2);
        }
    }
}

#[test]
fn preparation_and_gathering_survive_restart_privately() {
    let mut b = Board::default();
    b.prepare(
        Scenario::Ruins,
        Kind::Battle,
        [0.0, 70.0, 0.0],
        "minecraft:overworld".into(),
    )
    .unwrap();
    b.recover();
    assert_eq!(b.current.as_ref().unwrap().phase, Phase::Prepared);
    assert_eq!(
        b.current.as_ref().unwrap().public_status(),
        "Нет открытого события."
    );
    b.cancel();
    assert!(b.current.is_none());
    b.prepare(
        Scenario::Ruins,
        Kind::Battle,
        [0.0, 70.0, 0.0],
        "minecraft:overworld".into(),
    )
    .unwrap();
    b.open().unwrap();
    b.recover();
    assert_eq!(b.current.as_ref().unwrap().phase, Phase::Gathering);
}

#[test]
fn leave_and_rejoin_cannot_change_side_or_reward_budget() {
    let (mut b, players) = setup(Scenario::Ruins, Kind::Battle, 2);
    b.leave(1);
    b.join(&players[0], "2".into()).unwrap();
    assert_eq!(b.current.as_ref().unwrap().members[&1].side, "1");
    assert_eq!(b.current.as_ref().unwrap().initial_count, 2);
    assert!(b
        .prepare(
            Scenario::Beacon,
            Kind::Cooperative,
            [0.0, 70.0, 0.0],
            "minecraft:overworld".into()
        )
        .is_err());
}

#[test]
fn every_tier_can_complete_all_four_variants() {
    for n in [2, 3, 4, 6, 7, 10] {
        for scenario in [Scenario::Ruins, Scenario::Beacon] {
            for kind in [Kind::Cooperative, Kind::Battle] {
                let (mut b, mut players) = setup(scenario, kind, n);
                if kind == Kind::Cooperative {
                    for i in 0..b.current.as_ref().unwrap().points {
                        players[i].position = b.current.as_ref().unwrap().point(i);
                        if scenario == Scenario::Beacon {
                            b.interact(&players[i]).unwrap();
                        }
                    }
                } else {
                    if scenario == Scenario::Beacon {
                        players[0].position = b.current.as_ref().unwrap().block;
                        b.interact(&players[0]).unwrap();
                    }
                    players[0].position = [0.0, 70.0, 0.0];
                }
                let seconds = b.current.as_ref().unwrap().required_ms / 1000;
                tick(&mut b, &players, seconds);
                assert_eq!(
                    b.current.as_ref().unwrap().phase,
                    Phase::Completed,
                    "{scenario:?} {kind:?} {n}"
                );
                assert_eq!(b.rewards.values().sum::<u32>(), n as u32);
            }
        }
    }
}

#[test]
fn team_capture_divides_one_fixed_pot_between_contributors() {
    let (mut b, mut players) = setup(Scenario::Ruins, Kind::Battle, 3);
    b.current
        .as_mut()
        .unwrap()
        .members
        .get_mut(&2)
        .unwrap()
        .side = "1".into();
    for p in &mut players[..2] {
        p.position = [0.0, 70.0, 0.0];
    }
    tick(&mut b, &players, 60);
    assert_eq!(b.rewards.get(&1), Some(&2));
    assert_eq!(b.rewards.get(&2), Some(&1));
    assert!(!b.rewards.contains_key(&3));
}

#[test]
fn stationary_spectator_cannot_defend_and_nonparticipant_cannot_touch_block() {
    let (mut b, mut players) = setup(Scenario::Beacon, Kind::Cooperative, 3);
    players[0].position = [0.0, 70.0, 0.0];
    players[1].position = players[0].position;
    players[0].active = false;
    b.record_defense(1, 2, &players);
    assert!(!b.current.as_ref().unwrap().members[&1].contributed);
    players[0].active = true;
    b.record_defense(1, 2, &players);
    assert!(b.current.as_ref().unwrap().members[&1].contributed);
    let (mut b, _) = setup(Scenario::Beacon, Kind::Battle, 2);
    assert!(b
        .interact(&p(50, b.current.as_ref().unwrap().block))
        .is_err());
}
