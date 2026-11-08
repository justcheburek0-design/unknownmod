//! UnknownMod profile filter, compiled in Horizon's reusable networking library.
//! Internal account names and command resolution stay original.
use horizon_protocol::packets::{play::Clientbound as C, login::Property};
use horizon_world::nbt::Tag;
use unknownmod_rules::shared;

pub const ANONYMOUS: &str = "unknownmod_anonymous";

pub fn masked(packet: &C, privileged: bool, viewer: u128) -> Option<C> {
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
        C::SetPlayerTeam(team) => {
            let mut result=team.clone();let view=shared::snapshot();let mut changed=false;
            for name in &mut result.players {
                if view.known.iter().any(|(id,original)|original==name && shared::hidden(*id)) {
                    *name=shared::anonymous_name();changed=true;
                }
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
            }).is_some_and(shared::hidden);
            if hidden_player {
                values.remove(&"hover_event".into());
                values.remove(&"click_event".into());
                values.remove(&"insertion".into());
                values.insert("text".into(), Tag::String(shared::anonymous_name().as_str().into()));
            }
            values.values_mut().fold(hidden_player, |changed, value| scrub_component(value) || changed)
        }
        Tag::List(list) => list.iter_mut().fold(false, |changed, value| scrub_component(value) || changed),
        _ => false,
    }
}
