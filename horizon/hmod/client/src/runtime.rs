use super::*;
pub fn packet(game:&mut crate::game::Game<'_>,packet:&horizon_protocol::packets::play::Clientbound){
    use horizon_protocol::packets::play::Clientbound as C;
    use unknownmod_client::masks as state;
    match packet {
        C::Login(_)=>{state::clear();unknownmod_client::menu::menu_clear();},
        C::PlayerInfoUpdate(info) if info.actions&1!=0=>{
            for entry in &info.entries {
                state::player(entry.uuid,entry.properties.iter().any(|p|p.name=="unknownmod_anonymous"&&p.value=="1"));
                if let Some(remote)=game.remote.as_mut(){
                    remote.skin_textures.remove(&entry.uuid);
                    remote.level_player_textures.clear();
                    remote.tab_players.remove(&entry.uuid);
                }
            }
        },
        C::PlayerInfoRemove(info)=>{for id in &info.uuids{state::player(*id,false);}},
        C::CustomPayload(p)=>unknownmod_client::menu::menu_message(&p.channel,&p.data),
        _=>{}
    }
}

pub fn tick(game:&mut crate::game::Game<'_>){
    for data in unknownmod_client::menu::take_requests(){
        if game.remote.is_some(){game.send(horizon_protocol::packets::play::Serverbound::CustomPayload(horizon_protocol::packets::configuration::CustomPayload{channel:"unknownmod:menu".into(),data}));}
        else{unknownmod_client::menu::disconnected();}
    }
}
