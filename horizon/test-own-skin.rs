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
    println!("PASS: own signed textures preserved; others masked; operator isolation; reveal/unreveal; shared packet unchanged");
}
