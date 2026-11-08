use unknownmod_rules::{Config, Logout, Reveal, State, matches_name};

fn players() -> State {
    let mut state = State::default();
    state.join(1, "Alice", 0, 1_000_000_000);
    state.join(2, "Bob", 0, 1_000_000_000);
    state.join(3, "Carol", 0, 1_000_000_000);
    state
}

#[test]
fn reveal_does_not_change_the_named_weapon_rule() {
    for revealed in [false, true] {
        let mut state = players();
        if revealed { assert!(state.reveal(2, &Config::default(), 10)); }
        assert!(!state.death(2, Some(1), None));
        assert!(!state.death(2, Some(1), Some("JustPlayer")));
        assert!(state.death(2, Some(1), Some("Bob")));
        assert!(state.players[&2].ghost);
        assert!(!state.reveals.contains_key(&2));
    }
}

#[test]
fn environmental_death_clears_combat_without_elimination() {
    let mut state = players();
    state.hit(1, 2, Some("Bob"), 100);
    assert!(!state.death(2, None, Some("Bob")));
    assert_eq!(state.leave(2, 1000, 200, false), Logout::Safe);
    assert!(!state.players[&2].ghost);
}

#[test]
fn logout_uses_the_weapon_snapshot_and_last_attacker() {
    let mut state = players();
    let mut weapon = String::from("Bob");
    state.hit(1, 2, Some(&weapon), 100);
    weapon.clear();
    assert_eq!(state.leave(2, 1000, 200, false), Logout::Eliminated);
    let mut state = players();
    state.hit(1, 2, Some("Bob"), 100);
    state.hit(3, 2, Some("Wrong"), 200);
    assert_eq!(state.leave(2, 1000, 300, false), Logout::Death);
    assert!(!state.players[&2].ghost);
}

#[test]
fn outgoing_hit_extends_combat_but_not_the_incoming_weapon() {
    let mut state = players();
    state.hit(1, 2, Some("Bob"), 0);
    state.hit(2, 3, Some("Carol"), 25_000);
    assert_eq!(state.remaining_combat_seconds(2, 35_000), 20);
    assert_eq!(state.leave(2, 1000, 35_000, false), Logout::Death);
}

#[test]
fn combat_deadline_and_shutdown_are_safe() {
    let mut state = players();
    state.hit(1, 2, Some("Bob"), 0);
    assert_eq!(state.leave(2, 1000, 30_000, false), Logout::Safe);
    let mut state = players();
    state.hit(1, 2, Some("Bob"), 0);
    assert_eq!(state.leave(2, 1000, 100, true), Logout::Safe);
    let mut state = players();
    state.hit(1, 2, None, 0);
    assert_eq!(state.leave(1, 1000, 100, false), Logout::Death);
}

#[test]
fn spectators_and_self_hits_do_not_start_combat() {
    let mut state = players();
    state.hit(1, 1, Some("Alice"), 0);
    state.players.get_mut(&2).unwrap().mode = 3;
    state.hit(1, 2, Some("Bob"), 0);
    assert_eq!(state.remaining_combat_seconds(1, 100), 0);
}

#[test]
fn offline_reveal_keeps_exact_remaining_time_through_restart() {
    let mut state = players();
    let config = Config { reveal_duration_minutes: 1, ..Default::default() };
    assert!(state.reveal(2, &config, 100_000));
    assert_eq!(state.leave(2, 120_000, 0, false), Logout::Safe);
    let encoded = serde_json::to_string(&state).unwrap();
    let mut state: State = serde_json::from_str(&encoded).unwrap();
    assert_eq!(state.reveals[&2].remaining(900_000), 40_000);
    state.join(2, "Bob", 0, 900_000);
    assert!(matches!(state.reveals[&2], Reveal::Running { ends_at: 940_000 }));
    state.join(2, "Bob", 0, 910_000);
    assert_eq!(state.reveals[&2].remaining(910_000), 30_000);
    assert!(state.expire_reveals(939_999).is_empty());
    assert_eq!(state.expire_reveals(940_000), vec![2]);
}

#[test]
fn reload_and_interval_changes_do_not_extend_running_reveals() {
    let mut state = players();
    let mut config = Config { reveal_duration_minutes: 1, ..Default::default() };
    state.configure(&config, 10, false);
    let scheduled = state.next_reveal_at;
    assert!(state.reveal(2, &config, 10));
    config.reveal_duration_minutes = 90;
    state.configure(&config, 20_000, false);
    assert_eq!(state.next_reveal_at, scheduled);
    config.reveal_interval_hours = 4;
    state.configure(&config, 30_000, true);
    assert_eq!(state.next_reveal_at, 14_430_000);
    assert_eq!(state.reveals[&2].remaining(30_000), 30_010);
}

#[test]
fn automatic_selection_counts_living_revealed_players_but_skips_spectators() {
    let mut state = players();
    let config = Config::default();
    state.reveal(1, &config, 0);
    state.players.get_mut(&3).unwrap().mode = 3;
    state.next_reveal_at = 1;
    assert_eq!(state.automatic_reveal(&config, 2, 0), None);
    state.players.get_mut(&3).unwrap().mode = 0;
    state.next_reveal_at = 1;
    assert_eq!(state.automatic_reveal(&config, 2, 0), Some(2));
}

#[test]
fn ghost_survives_restart_rename_and_rejoin() {
    let mut state = players();
    state.players.get_mut(&2).unwrap().mode = 1;
    state.make_ghost(2);
    let mut state: State = serde_json::from_str(&serde_json::to_string(&state).unwrap()).unwrap();
    state.join(2, "NewBob", 0, 1000);
    assert!(state.players[&2].ghost);
    assert_eq!(state.players[&2].name, "NewBob");
    assert!(!state.is_active(2));
    assert_eq!(state.restore(2), Some(1));
}

#[test]
fn fixed_roster_has_recent_inactive_and_never_joined_groups() {
    let mut state = players();
    state.players.get_mut(&1).unwrap().online = false;
    state.players.get_mut(&1).unwrap().last_seen = 1;
    let config = Config { roster: vec!["alice".into(), "Bob".into(), "BOB".into(), "NeverJoined".into()], ..Default::default() };
    let (recent, inactive) = state.participant_groups(&config, 1_500_000_000);
    assert_eq!(recent, vec!["Bob"]);
    assert_eq!(inactive, vec!["alice", "NeverJoined"]);
}

#[test]
fn nickname_matching_preserves_java_rules() {
    for weapon in ["Bob", "bob", " Bob ", "[ Bob ]", "(Bob)", "{Bob}"] {
        assert!(matches_name(weapon, "Bob"));
    }
    for weapon in ["", "[]", "JustPlayer", "Bob1", "[Bob)", "[[Bob]]"] {
        assert!(!matches_name(weapon, "Bob"));
    }
}
