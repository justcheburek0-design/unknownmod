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
        C::TabList(tab) => {
            let mut result=tab.clone();let a=scrub_component(&mut result.header);let b=scrub_component(&mut result.footer);
            (a||b).then_some(C::TabList(result))
        }
        C::SetScore(score) => {
            let mut result=score.clone();let mut changed=false;
            if let Some((id,_))=shared::snapshot().known.iter().find(|(_,name)|*name==&score.owner) {
                result.owner=format!("unknownmod_{id:032x}");changed=true;
                if result.display.is_none(){result.display=Some(Tag::String(if shared::hidden(*id){shared::anonymous_name()}else{score.owner.clone()}.as_str().into()));}
            }
            if let Some(display)=&mut result.display{changed=scrub_component(display)||changed;}
            changed.then_some(C::SetScore(result))
        }
        C::ResetScore(score) => {
            let view=shared::snapshot();let (id,_)=view.known.iter().find(|(_,name)|*name==&score.owner)?;
            let mut result=score.clone();result.owner=format!("unknownmod_{id:032x}");Some(C::ResetScore(result))
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
        _ => false,
    }
}

fn hidden_name(name:&str)->bool{shared::snapshot().known.iter().any(|(id,real)|real==name&&shared::hidden(*id))}
