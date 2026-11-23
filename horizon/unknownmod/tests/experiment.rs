use unknownmod_rules::{Config, Logout, Reveal, State, ui};

fn season() -> State {
    let mut s=State::default();
    s.join(1,"Alice",0,1000);s.join(2,"Bob",0,1000);s.join(3,"Carol",0,1000);
    s.configure(&Config::default(),1000,false);
    s
}

#[test]
fn stop_freezes_hunts_and_schedule_and_start_resumes_online_only() {
    let config=Config::default();let mut s=season();
    assert!(s.reveal(1,&config,1000));assert!(s.reveal(2,&config,1000));
    let scheduled=s.next_reveal_at;
    assert!(s.stop(2000));assert!(!s.stop(3000));
    let remaining=s.reveals[&1].remaining(2000);
    s.leave(2,5000,5000,false);
    assert!(s.expire_reveals(u64::MAX).is_empty());
    assert_eq!(s.automatic_reveal(&config,u64::MAX,0),None);
    assert!(!s.reveal(3,&config,5000));
    assert!(s.start(&config,100_000));assert!(!s.start(&config,200_000));
    assert_eq!(s.next_reveal_at,100_000+scheduled-2000);
    assert_eq!(s.reveals[&1].remaining(100_000),remaining);
    assert!(matches!(s.reveals[&2],Reveal::Paused{..}));
    s.join(2,"Bob",0,200_000);
    assert_eq!(s.reveals[&2].remaining(200_000),remaining);
}

#[test]
fn paused_season_survives_restart_and_reconnect_without_advancing_timers() {
    let config=Config::default();let mut s=season();s.reveal(1,&config,1000);s.stop(2000);
    let data=serde_json::to_vec(&s).unwrap();let mut restored:State=serde_json::from_slice(&data).unwrap();
    restored.join(1,"Alice",0,999_000);
    assert!(restored.stopped);assert!(matches!(restored.reveals[&1],Reveal::Paused{..}));
    assert_eq!(restored.reveals[&1].remaining(999_000),s.reveals[&1].remaining(2000));
    assert!(ui::snapshot(&restored,&config,1,false,999_000,0).status.contains("остановлен"));
    restored.start(&config,999_000);
    assert_eq!(restored.reveals[&1].remaining(999_000),s.reveals[&1].remaining(2000));
}

#[test]
fn stop_clears_combat_and_disables_elimination_until_start() {
    let mut s=season();s.hit(1,2,Some("Bob"),1000);s.stop(2000);
    assert_eq!(s.remaining_combat_seconds(2,2000),0);
    s.hit(1,2,Some("Bob"),3000);
    assert!(!s.death(2,Some(1),Some("Bob")));
    assert_eq!(s.leave(2,3000,3000,false),Logout::Safe);
    assert!(s.pending_audit.is_empty());assert!(!s.players[&2].ghost);
    s.start(&Config::default(),4000);s.join(2,"Bob",0,4000);
    assert!(s.death(2,Some(1),Some("Bob")));
}

#[test]
fn legacy_state_stays_running_and_new_interval_changes_work_while_paused() {
    let mut legacy:State=serde_json::from_str("{}").unwrap();assert!(!legacy.stopped);
    legacy.stop(1000);let config=Config{reveal_interval_hours:2,..Config::default()};
    legacy.configure(&config,2000,true);legacy.start(&config,100_000);
    assert_eq!(legacy.next_reveal_at,100_000+7_200_000);
}
