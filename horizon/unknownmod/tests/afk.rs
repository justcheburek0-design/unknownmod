use unknownmod_rules::{Config, Logout, State, ui};

fn players() -> State {
    let mut state = State::default();
    for (id, name) in [(1, "Alice"), (2, "Bob"), (3, "Carol")] {
        state.join(id, name, 0, 0);
        state.record_activity(id, 1000);
    }
    state
}

#[test]
fn idle_threshold_activity_and_reconnect() {
    let mut state = players();
    let config = Config::default();
    state.update_afk(&config, 300_999);
    assert!(!state.players[&1].afk);
    state.update_afk(&config, 301_000);
    assert!(state.players[&1].afk);
    state.record_activity(1, 301_001);
    assert!(!state.players[&1].afk);
    state.update_afk(&config, 601_001);
    assert!(state.players[&1].afk);
    state.leave(1, 0, 0, false);
    state.record_activity(1, 700_000);
    assert!(!state.players[&1].afk);
    state.join(1, "Alice", 0, 0);
    state.update_afk(&config, 800_000);
    assert!(!state.players[&1].afk);
    assert!(state.is_event_participant(1));
}

#[test]
fn afk_is_excluded_from_minimum_and_random_selection() {
    let mut state = players();
    let mut config = Config::default();
    state.update_afk(&config, 301_000);
    for id in [2, 3] { state.record_activity(id, 301_000); }
    state.next_reveal_at = 1;
    assert_eq!(state.automatic_reveal(&config, 301_000, 0), None);
    config.minimum_players = 2;
    state.next_reveal_at = 1;
    assert_eq!(state.automatic_reveal(&config, 301_000, 0), Some(2));
    assert!(state.is_active(1));
    assert!(!state.is_event_participant(1));
}

#[test]
fn afk_does_not_pause_hunts_or_remove_combat_penalty() {
    let mut state = players();
    let config = Config::default();
    assert!(state.reveal(1, &config, 0));
    state.update_afk(&config, 301_000);
    assert_eq!(state.reveals[&1].remaining(301_000), 5_099_000);
    state.hit(2, 1, Some("Alice"), 301_000);
    assert_eq!(state.leave(1, 301_001, 301_001, false), Logout::Eliminated);
}

#[test]
fn report_separates_connected_players_from_event_participants() {
    let mut state = players();
    state.update_afk(&Config::default(), 301_000);
    state.record_activity(2, 301_000);
    state.record_activity(3, 301_000);
    state.players.get_mut(&3).unwrap().mode = 3;
    state.join(4, "Offline", 0, 0);
    state.leave(4, 0, 0, false);
    let report = state.online_report();
    assert!(report.contains("На сервере: 3"));
    assert!(report.contains("Онлайн без AFK (2): Bob, Carol"));
    assert!(report.contains("AFK (1): Alice"));
    assert!(report.contains("Доступны для ивента (1): Bob"));
    assert!(!report.contains("Offline"));
    state.make_ghost(2);
    assert!(state.online_report().contains("Доступны для ивента (0): —"));
}

#[test]
fn afk_does_not_leak_into_public_ui_or_persistence() {
    let mut state = players();
    let config = Config::default();
    let before = serde_json::to_value(ui::snapshot(&state, &config, 1, false, 0, 0)).unwrap();
    state.update_afk(&config, 301_000);
    let after = serde_json::to_value(ui::snapshot(&state, &config, 1, false, 0, 0)).unwrap();
    assert_eq!(before, after);
    let json = serde_json::to_string(&state).unwrap();
    assert!(!json.contains("afk"));
    assert!(!json.contains("last_activity"));
    let restored: State = serde_json::from_str(&json).unwrap();
    assert!(restored.players.values().all(|player| !player.online && !player.afk));
}

#[test]
fn timeout_changes_apply_without_resetting_activity() {
    let mut state = players();
    let mut config = Config { afk_timeout_minutes: 1, ..Default::default() };
    state.update_afk(&config, 61_000);
    assert!(state.players[&1].afk);
    config.afk_timeout_minutes = 5;
    state.update_afk(&config, 61_000);
    assert!(!state.players[&1].afk);
    let legacy: Config = serde_json::from_str("{}").unwrap();
    assert_eq!(legacy.afk_timeout_minutes, 5);
}
