use unknownmod_rules::{audit::Cause, ui, Config, State};

fn state() -> State {let mut s=State::default();s.join(1,"Alice",0,1000);s.join(2,"Bob",0,1000);s}

#[test]
fn death_reason_and_private_journal_agree() {
    let mut s=state();
    assert!(!s.death_at(2,Some(1),Some("wrong"),2000,Cause::Death));
    let last=s.pending_audit.last().unwrap();assert!(!last.matched&&!last.eliminated);assert_eq!(last.attacker_name.as_deref(),Some("Alice"));
    assert!(!last.explanation().contains("Alice"));
    assert!(s.death_at(2,Some(1),Some("Bob"),3000,Cause::Death));
    let last=s.pending_audit.last().unwrap();assert!(last.matched&&last.eliminated);assert_eq!(last.time,3000);
    assert!(last.explanation().contains("наблюдатель"));
    let restored=s.restore(2);assert_eq!(restored,Some(0));assert_eq!(s.pending_audit.last().unwrap().cause,Cause::Restore);
}

#[test]
fn logout_has_one_reason_and_stale_hits_do_not_eliminate() {
    let mut s=state();s.hit(1,2,Some("Bob"),100);
    s.leave(2,2000,200,false);
    assert_eq!(s.pending_audit.len(),1);let last=s.pending_audit.last().unwrap();
    assert_eq!(last.cause,Cause::CombatLogout);assert!(last.eliminated);
    let mut s=state();s.hit(1,2,Some("Bob"),100);s.hit(2,1,None,30101);
    s.leave(2,4000,30200,false);
    assert!(!s.players[&2].ghost);
}

#[test]
fn shutdown_never_writes_a_combat_penalty() {
    let mut s=state();s.hit(1,2,Some("Bob"),100);s.leave(2,2000,200,true);
    assert!(s.pending_audit.is_empty());assert!(!s.players[&2].ghost);
}

#[test]
fn ordinary_ui_never_sends_admin_identity_links_or_audit() {
    let mut s=state();s.death_at(2,Some(1),Some("Bob"),2000,Cause::Death);
    let public=serde_json::to_value(ui::snapshot(&s,&Config::default(),2,false,2100,200)).unwrap();
    assert!(public.get("admin").is_none());assert!(public.get("audit").is_none());
    assert!(!public["last_decision"].as_str().unwrap().contains("Alice"));
    let admin=serde_json::to_value(ui::snapshot(&s,&Config::default(),1,true,2100,200)).unwrap();
    assert_eq!(admin["admin"]["players"][1]["id"],format!("{:032x}",2));
    let persisted=serde_json::to_vec(&s).unwrap();let restored:State=serde_json::from_slice(&persisted).unwrap();
    assert!(restored.players[&2].last_decision.as_ref().unwrap().eliminated);
    assert!(restored.pending_audit.is_empty());
}

#[test]
fn menu_rejects_arbitrary_commands_and_unsupported_fields() {
    for invalid in [r#"{"action":"command","text":"op Alice"}"#,r#"{"action":"status","admin":true}"#,r#"{"action":"setting","field":"roster","value":3}"#] {
        assert!(serde_json::from_str::<ui::Request>(invalid).is_err());
    }
}

#[test]
fn offline_restore_persists_the_mode_until_the_game_can_apply_it() {
    let mut s=State::default();let id=u128::MAX-10;
    s.join(id,"CreativePlayer",1,1000);s.make_ghost(id);s.leave(id,2000,0,true);
    assert_eq!(s.restore(id),Some(1));
    let durable=serde_json::to_vec(&s).unwrap();let mut restored:State=serde_json::from_slice(&durable).unwrap();
    assert!(!restored.players[&id].ghost);assert_eq!(restored.players[&id].previous_mode,Some(1));
    restored.join(id,"CreativePlayer",3,3000);
    assert_eq!(restored.players[&id].previous_mode,Some(1));
    assert!(restored.players[&id].last_decision.as_ref().unwrap().explanation().contains("вернул"));
}
