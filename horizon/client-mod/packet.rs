fn unknownmod_packet(game:&mut crate::game::Game<'_>,packet:&horizon_protocol::packets::play::Clientbound){
    use horizon_protocol::packets::play::Clientbound as C;
    use crate::game::unknownmod_client as state;
    match packet {
        C::Login(_)=>state::clear(),
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
        _=>{}
    }
}
