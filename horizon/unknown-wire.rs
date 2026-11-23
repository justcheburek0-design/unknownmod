//! UnknownMod profile filter, compiled in Horizon's reusable networking library.
//! Internal account names and command resolution stay original.
use horizon_protocol::packets::{play::Clientbound as C, login::Property};
use horizon_world::nbt::Tag;
use unknownmod_rules::shared;

pub const ANONYMOUS: &str = "unknownmod_anonymous";

/// Entity tracking sends pre-encoded batches instead of Guest::send. Filter
/// those too; never decode large chunk packets, and drop malformed ID packets.
pub fn encoded(body:Vec<u8>,privileged:bool,viewer:u128)->Option<Vec<u8>> {
    if !shared::enabled(){return Some(body)}
    use horizon_protocol::{buf::Reader,ids::play::clientbound as ids};
    let mut reader=Reader::new(&body);let id=reader.varint().ok()?;
    if !matches!(id,ids::ADD_ENTITY|ids::SET_ENTITY_DATA|ids::PLAYER_INFO_UPDATE|ids::PLAYER_INFO_REMOVE|ids::PLAYER_CHAT|ids::WAYPOINT|ids::SYSTEM_CHAT|ids::SET_SCORE|ids::RESET_SCORE|ids::SET_PLAYER_TEAM|ids::TAB_LIST){return Some(body)}
    let packet=C::decode(id,&body[reader.position()..]).ok()?;
    Some(masked(&packet,privileged,viewer).map_or(body,|p|p.encode()))
}

pub fn masked(packet: &C, privileged: bool, viewer: u128) -> Option<C> {
    if !shared::enabled(){return None}
    let filtered=masked_content(packet,privileged,viewer);
    let mut result=match filtered {
        Some(packet)=>packet,
        None if matches!(packet,C::PlayerInfoUpdate(_)|C::PlayerInfoRemove(_)|C::AddEntity(_)|C::SetEntityData(_)|C::TrackedWaypoint(_)|C::PlayerChat(_))=>packet.clone(),
        None=>return None,
    };
    let id=|value|shared::wire_id(value,viewer);
    match &mut result {
        C::PlayerInfoUpdate(info)=>for entry in &mut info.entries {
            if entry.uuid!=viewer {
                entry.chat_session=None;
                // Signed Mojang textures also embed the real account UUID.
                // Horizon 6.1 accepts unsigned texture URLs; keep only visual data.
                entry.properties.retain_mut(|property| {
                    if property.name == ANONYMOUS { return true; }
                    if property.name != "textures" { return false; }
                    let Some(value)=unknownmod_rules::textures::public_textures(&property.value) else { return false; };
                    property.value=value;
                    property.signature=None;
                    true
                });
            }
            entry.uuid=shared::player_wire_id(entry.uuid,viewer);
        },
        C::PlayerInfoRemove(info)=>for uuid in &mut info.uuids{*uuid=shared::player_wire_id(*uuid,viewer);},
        C::AddEntity(entity)=>entity.uuid=id(entity.uuid),
        C::SetEntityData(data)=>for (_,_,value) in &mut data.values {
            use horizon_protocol::entity_data::Value;
            match value {
                Value::OptUuid(Some(uuid))=>*uuid=id(*uuid),
                Value::Text(text)|Value::OptText(Some(text))=>{scrub_component(text);},
                _=>{}
            }
        },
        C::TrackedWaypoint(waypoint)=>if let horizon_protocol::packets::play::WaypointId::Uuid(uuid)=&mut waypoint.id{*uuid=id(*uuid);},
        C::PlayerChat(chat)=>{chat.sender=shared::player_wire_id(chat.sender,viewer);scrub_component(&mut chat.chat_type.name);if let Some(target)=&mut chat.chat_type.target{scrub_component(target);}if let Some(text)=&mut chat.unsigned_content{scrub_component(text);}},
        _=>{}
    }
    (result!=*packet).then_some(result)
}

fn masked_content(packet: &C, privileged: bool, viewer: u128) -> Option<C> {
    if !shared::enabled() { return None; }
    match packet {
        C::PlayerInfoUpdate(info) if info.actions & 1 != 0 => {
            let mut result = info.clone();
            let mut changed = false;
            for entry in &mut result.entries {
                if let Some(name) = &entry.name { shared::remember(entry.uuid, name); }
                if !shared::hidden(entry.uuid) { continue; }
                // Only this connection may receive its owner's real textures.
                // Keep the anonymous name, but omit the client-side skin mask.
                if entry.uuid == viewer {
                    entry.name = Some(shared::anonymous_name());
                    entry.properties.retain(|p| p.name != ANONYMOUS);
                    entry.display_name = Some(None);
                    changed = true;
                    continue;
                }
                let marker = entry.properties.iter().any(|p| p.name == ANONYMOUS && p.value == "1");
                if marker && entry.name.as_deref() == Some(shared::anonymous_name().as_str()) { continue; }
                entry.name = Some(shared::anonymous_name());
                entry.properties.retain(|p| p.name != "textures" && p.name != ANONYMOUS);
                entry.properties.push(Property { name: ANONYMOUS.into(), value: "1".into(), signature: None });
                entry.display_name = Some(None);
                changed = true;
            }
            changed.then_some(C::PlayerInfoUpdate(result))
        }
        C::SystemChat(message) if !privileged => {
            let mut result = message.clone();
            scrub_component(&mut result.content).then_some(C::SystemChat(result))
        }
        C::PlayerCombatKill(message) => {
            let mut result=message.clone();
            scrub_component(&mut result.message).then_some(C::PlayerCombatKill(result))
        }
        C::TabList(tab) => {
            let mut result=tab.clone();let a=scrub_component(&mut result.header);let b=scrub_component(&mut result.footer);
            (a||b).then_some(C::TabList(result))
        }
        C::SetScore(score) => {
            let mut result=score.clone();let mut changed=false;
            if let Some((id,_))=shared::snapshot().known.iter().find(|(_,name)|*name==&score.owner) {
                result.owner=format!("unknownmod_{:032x}",shared::public_id(*id));changed=true;
                if result.display.is_none(){result.display=Some(Tag::String(if shared::hidden(*id){shared::anonymous_name()}else{score.owner.clone()}.as_str().into()));}
            }
            if let Some(display)=&mut result.display{changed=scrub_component(display)||changed;}
            changed.then_some(C::SetScore(result))
        }
        C::ResetScore(score) => {
            let view=shared::snapshot();let (id,_)=view.known.iter().find(|(_,name)|*name==&score.owner)?;
            let mut result=score.clone();result.owner=format!("unknownmod_{:032x}",shared::public_id(*id));Some(C::ResetScore(result))
        }
        C::SetPlayerTeam(team) => {
            let mut result=team.clone();let view=shared::snapshot();let mut changed=false;
            for name in &mut result.players {
                if view.known.iter().any(|(id,original)|original==name && shared::hidden(*id)) {
                    *name=shared::anonymous_name();changed=true;
                }
            }
            if let Some(parameters)=&mut result.parameters {
                changed=scrub_component(&mut parameters.display_name)||changed;
                changed=scrub_component(&mut parameters.prefix)||changed;
                changed=scrub_component(&mut parameters.suffix)||changed;
            }
            changed.then_some(C::SetPlayerTeam(result))
        }
        _ => None,
    }
}

fn scrub_component(tag: &mut Tag) -> bool {
    match tag {
        Tag::Compound(values) => {
            let hidden_player = values.get(&"hover_event".into()).and_then(|hover| {
                let Tag::Compound(hover) = hover else { return None; };
                let Tag::String(kind) = hover.get(&"id".into())? else { return None; };
                if kind.to_string_lossy() != "minecraft:player" { return None; }
                let Tag::String(uuid) = hover.get(&"uuid".into())? else { return None; };
                u128::from_str_radix(&uuid.to_string_lossy().replace('-', ""), 16).ok()
            }).is_some_and(shared::hidden) || ["text","insertion"].iter().any(|key| {
                let Some(Tag::String(name))=values.get(&(*key).into())else{return false};
                hidden_name(&name.to_string_lossy())
            });
            if hidden_player {
                values.remove(&"hover_event".into());
                values.remove(&"click_event".into());
                values.remove(&"insertion".into());
                for key in ["translate","with","extra","selector","score","nbt"]{values.remove(&key.into());}
                values.insert("text".into(), Tag::String(shared::anonymous_name().as_str().into()));
            }
            values.values_mut().fold(hidden_player, |changed, value| scrub_component(value) || changed)
        }
        Tag::List(list) => list.iter_mut().fold(false, |changed, value| scrub_component(value) || changed),
        Tag::String(value) if hidden_name(&value.to_string_lossy()) => {*value=shared::anonymous_name().as_str().into();true},
        Tag::String(value) => {
            let text=value.to_string_lossy();
            let compact=text.replace('-',"");
            if matches!(text.len(),32|36)&&compact.len()==32 {
                if let Ok(real)=u128::from_str_radix(&compact,16) {
                    if shared::snapshot().known.contains_key(&real){
                        let alias=shared::public_id(real);let raw=format!("{alias:032x}");
                        let shown=if text.len()==36{format!("{}-{}-{}-{}-{}",&raw[..8],&raw[8..12],&raw[12..16],&raw[16..20],&raw[20..])}else{raw};
                        *value=shown.as_str().into();return true
                    }
                }
            }false
        },
        Tag::IntArray(values) if values.len()==4 => {
            let real=values.iter().fold(0u128,|id,n|(id<<32)|u128::from(*n as u32));
            if shared::snapshot().known.contains_key(&real){let alias=shared::public_id(real);for(i,n)in values.iter_mut().enumerate(){*n=(alias>>(96-i*32))as i32;}true}else{false}
        },
        _ => false,
    }
}

fn hidden_name(name:&str)->bool{shared::snapshot().known.iter().any(|(id,real)|real==name&&shared::hidden(*id))}
