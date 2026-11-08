// Standalone regression probe; compiled against the server's existing rlibs.
#[path = "unknown-wire.rs"]
mod wire;
use horizon_protocol::packets::{login::Property, play::{Clientbound as C, PlayerInfo, PlayerInfoUpdate}};
use unknownmod_rules::{Config, State, shared};

fn entry(packet: &C, id: u128) -> &PlayerInfo {
    let C::PlayerInfoUpdate(info) = packet else { panic!("not player info") };
    info.entries.iter().find(|e| e.uuid == id).unwrap()
}
fn has(entry: &PlayerInfo, key: &str) -> bool { entry.properties.iter().any(|p| p.name == key) }
fn main() {
    unsafe { std::env::set_var("UNKNOWNMOD_ENABLED", "1"); }
    let cfg = Config::default();
    let mut state = State::default();
    state.join(1, "Alice", 0, 1000);
    state.join(2, "Bob", 0, 1000);
    shared::publish(&state, &cfg);
    let packet = C::PlayerInfoUpdate(PlayerInfoUpdate {
        actions: 1,
        entries: [(1, "Alice"), (2, "Bob")].into_iter().map(|(uuid, name)| PlayerInfo {
            uuid, name: Some(name.into()), properties: vec![Property {
                name: "textures".into(), value: format!("original-{name}"), signature: Some("signed".into()),
            }], ..Default::default()
        }).collect(),
    });
    for privileged in [false, true] {
        for owner in [1, 2] {
            let filtered = wire::masked(&packet, privileged, owner).unwrap();
            let own = entry(&filtered, owner);
            assert!(has(own, "textures") && !has(own, wire::ANONYMOUS));
            assert_eq!(own.properties, entry(&packet, owner).properties);
            assert_eq!(own.name.as_deref(), Some("JustPlayer"));
            let other = entry(&filtered, 3 - owner);
            assert!(!has(other, "textures") && has(other, wire::ANONYMOUS));
        }
    }
    assert!(state.reveal(2, &cfg, 1000));
    shared::publish(&state, &cfg);
    let revealed = wire::masked(&packet, false, 1).unwrap();
    assert_eq!(entry(&revealed, 2), entry(&packet, 2));
    state.reveals.clear();
    shared::publish(&state, &cfg);
    let hidden = wire::masked(&packet, false, 1).unwrap();
    assert!(!has(entry(&hidden, 2), "textures"));
    let own = wire::masked(&packet, false, 2).unwrap();
    assert!(has(entry(&own, 2), "textures") && !has(entry(&own, 2), wire::ANONYMOUS));
    assert!(has(entry(&packet, 1), "textures") && has(entry(&packet, 2), "textures"));
    use horizon_world::nbt::Tag;
    let literal=||Tag::String("Alice".into());
    let chat=C::SystemChat(horizon_protocol::packets::play::SystemChat{content:literal(),overlay:false});
    let filtered=wire::masked(&chat,false,2).unwrap();
    let C::SystemChat(chat)=filtered else {unreachable!()};assert_ne!(chat.content,literal());
    assert!(wire::masked(&C::SystemChat(chat.clone()),false,2).is_none());
    let tab=C::TabList(horizon_protocol::packets::play::TabList{header:literal(),footer:literal()});
    let C::TabList(tab)=wire::masked(&tab,false,2).unwrap()else{unreachable!()};assert_ne!(tab.header,literal());assert_ne!(tab.footer,literal());
    let score=C::SetScore(horizon_protocol::packets::play::SetScore{owner:"Alice".into(),objective:"points".into(),score:5,display:None,number_format:None});
    let C::SetScore(score)=wire::masked(&score,false,2).unwrap()else{unreachable!()};assert!(!score.owner.contains("Alice"));assert_ne!(score.display,Some(literal()));
    let reset=C::ResetScore(horizon_protocol::packets::play::ResetScore{owner:"Alice".into(),objective:None});
    let C::ResetScore(reset)=wire::masked(&reset,false,2).unwrap()else{unreachable!()};assert_eq!(score.owner,reset.owner);
    let hidden_uuid=C::PlayerInfoUpdate(PlayerInfoUpdate{actions:1,entries:vec![entry(&packet,1).clone()]});
    let public=wire::masked(&hidden_uuid,false,2).unwrap();
    assert_eq!(entry(&public,1).uuid,1); // Known limit: UUID is still the vanilla account/offline UUID.
    println!("PASS: own signed textures preserved; others masked; operator isolation; reveal/unreveal; shared packet unchanged");
    println!("PASS: literal chat, TAB header/footer, scoreboard owner/display and reset consistency");
    println!("KNOWN LIMIT: public profile UUIDs are not pseudonymized yet");
}
