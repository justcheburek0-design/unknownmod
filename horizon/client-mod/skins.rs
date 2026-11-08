fn unknownmod_skins(game:&mut crate::game::Game<'_>,_value:()){
    use crate::game::unknownmod_client as state;
    const ANON:&str="skins/unknownmod_anonymous";
    let masked=state::players();
    let Some((level,own))=game.remote.as_ref().map(|r|(r.level_players.iter().map(|(id,uuid)|(*id as u32,*uuid)).collect::<Vec<_>>(),r.profile)) else{return};
    if !masked.is_empty() && (!game.skin_slots.contains(ANON)||!game.gui.atlas.sprites.contains_key(ANON)){
        game.store_skin(ANON.into(),state::ANONYMOUS_RGBA.to_vec());
    }
    if let Some(remote)=game.remote.as_mut(){for id in &masked{remote.skins.insert(*id,ANON.into());}}
    for (id,uuid) in level {
        let hidden=masked.contains(&uuid);state::entity(id,hidden);if !hidden{continue}
        let Some(index)=game.mobs.iter().position(|m|m.id==id&&&*m.kind.id=="minecraft:player") else{continue};
        let kind=game.mobs[index].kind.clone();
        let layers=game.skin_3d_model(&kind,ANON,false);
        let mob=&mut game.mobs[index];mob.skin=Some(ANON.into());mob.slim=false;mob.cape=None;mob.skin_3d=layers;
    }
    if masked.contains(&own){
        let kind=game.player_model.as_ref().map(|m|m.kind.clone());
        let layers=kind.as_ref().and_then(|kind|game.skin_3d_model(kind,ANON,false));
        if let Some(mob)=game.player_model.as_mut(){mob.skin=Some(ANON.into());mob.slim=false;mob.cape=None;mob.skin_3d=layers;}
    }
}
