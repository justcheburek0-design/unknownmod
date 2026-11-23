use unknownmod_rules::{Config, State, ui};

#[test]
fn public_menu_does_not_depend_on_future_reveal_schedule() {
    let mut state = State::default();
    state.join(1, "Alice", 0, 1000);
    let mut config = Config::default();
    state.configure(&config, 1000, true);
    let before = serde_json::to_value(ui::snapshot(&state, &config, 1, false, 1000, 0)).unwrap();
    config.reveal_interval_hours = 1;
    state.next_reveal_at = 1001;
    let after = serde_json::to_value(ui::snapshot(&state, &config, 1, false, 1000, 0)).unwrap();
    assert_eq!(before, after);
    assert!(after.get("admin").is_none());
    assert!(after.get("next_reveal_at").is_none());
    assert!(after.get("next_reveal_seconds").is_none());
    assert!(after.get("interval").is_none());
    let admin = serde_json::to_value(ui::snapshot(&state, &config, 1, true, 1000, 0)).unwrap();
    assert_eq!(admin["admin"]["interval"], 1);
}

#[test]
fn admin_schedule_query_respects_pause_and_deadline() {
    let mut state = State::default();
    let config = Config::default();
    state.configure(&config, 1000, true);
    assert_eq!(state.next_reveal_seconds(1000), 10_800);
    assert_eq!(state.next_reveal_seconds(state.next_reveal_at - 1), 1);
    assert_eq!(state.next_reveal_seconds(state.next_reveal_at), 0);
    state.stop(2000);
    assert_eq!(state.next_reveal_seconds(900_000), 10_799);
    state.start(&config, 900_000);
    assert_eq!(state.next_reveal_seconds(900_000), 10_799);
}
