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
                name: "textures".into(), value: "eyJwcm9maWxlSWQiOiJyZWFsLXV1aWQiLCJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHBzOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2FiYyJ9fX0=".into(), signature: Some("signed".into()),
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
            let other = entry(&filtered, shared::public_id(3 - owner));
            assert!(!has(other, "textures") && has(other, wire::ANONYMOUS));
        }
    }
    assert!(state.reveal(2, &cfg, 1000));
    shared::publish(&state, &cfg);
    let revealed = wire::masked(&packet, false, 1).unwrap();
    let mut expected=entry(&packet,2).clone();expected.uuid=shared::public_id(2);
    expected.properties[0].value=unknownmod_rules::textures::public_textures(&expected.properties[0].value).unwrap();
    expected.properties[0].signature=None;
    assert_eq!(entry(&revealed, expected.uuid), &expected);
    state.reveals.clear();
    shared::publish(&state, &cfg);
    let hidden = wire::masked(&packet, false, 1).unwrap();
    assert!(!has(entry(&hidden, shared::public_id(2)), "textures"));
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
    let alias=shared::public_id(1);assert_ne!(alias,1);assert_eq!(entry(&public,alias).uuid,alias);
    let remove=C::PlayerInfoRemove(horizon_protocol::packets::play::PlayerInfoRemove{uuids:vec![1,2]});
    let C::PlayerInfoRemove(remove)=wire::masked(&remove,false,2).unwrap()else{unreachable!()};assert_eq!(remove.uuids,vec![alias,2]);
    let add=C::AddEntity(horizon_protocol::packets::play::AddEntity{id:10,uuid:1,kind:0,position:[0.;3],velocity:[0.;3],pitch:0,yaw:0,head_yaw:0,data:0});
    let C::AddEntity(add)=wire::masked(&add,false,2).unwrap()else{unreachable!()};assert_eq!(add.uuid,alias);
    let raw=C::AddEntity(horizon_protocol::packets::play::AddEntity{id:10,uuid:1,kind:0,position:[0.;3],velocity:[0.;3],pitch:0,yaw:0,head_yaw:0,data:0});
    let bytes=wire::encoded(raw.encode(),false,2).unwrap();let mut reader=horizon_protocol::buf::Reader::new(&bytes);let packet_id=reader.varint().unwrap();
    let C::AddEntity(batched)=C::decode(packet_id,&bytes[reader.position()..]).unwrap()else{unreachable!()};assert_eq!(batched.uuid,alias);
    assert!(wire::encoded(vec![packet_id as u8],false,2).is_none());
    let update=C::PlayerInfoUpdate(PlayerInfoUpdate{actions:4,entries:vec![PlayerInfo{uuid:987654321,game_mode:Some(0),..Default::default()}]});
    let update=wire::masked(&update,false,2).unwrap();assert_eq!(entry(&update,shared::public_id(987654321)).uuid,shared::public_id(987654321));
    let metadata=C::SetEntityData(horizon_protocol::packets::play::SetEntityData{id:10,values:vec![(0,0,horizon_protocol::entity_data::Value::OptUuid(Some(1)))]});
    let C::SetEntityData(metadata)=wire::masked(&metadata,false,2).unwrap()else{unreachable!()};assert_eq!(metadata.values[0].2,horizon_protocol::entity_data::Value::OptUuid(Some(alias)));
    state.stop(2000);shared::publish(&state,&cfg);
    let normal=wire::masked(&packet,false,2).unwrap();
    assert_eq!(entry(&normal,alias).name.as_deref(),Some("Alice"));
    assert!(has(entry(&normal,alias),"textures")&&!has(entry(&normal,alias),wire::ANONYMOUS));
    assert_eq!(entry(&normal,2),entry(&packet,2));
    state.start(&cfg,3000);shared::publish(&state,&cfg);
    let resumed=wire::masked(&packet,false,2).unwrap();
    assert_eq!(entry(&resumed,alias).name.as_deref(),Some("JustPlayer"));
    assert!(has(entry(&resumed,alias),wire::ANONYMOUS));
    assert!(!has(entry(&resumed,alias),"textures"));
    println!("PASS: stop restores original skin URLs; owner signed skin preserved; start remasks with stable UUID");
    println!("PASS: own signed textures preserved; others masked; operator isolation; reveal/unreveal; shared packet unchanged");
    println!("PASS: literal chat, TAB header/footer, scoreboard owner/display and reset consistency");
    println!("PASS: profile, entity, removal and metadata UUIDs agree; own UUID stays local");
}
