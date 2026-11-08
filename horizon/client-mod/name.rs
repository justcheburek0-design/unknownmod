fn unknownmod_name(entity_id:u32,kind:&str,visible:&mut bool){
    if kind=="minecraft:player"&&crate::game::unknownmod_client::hidden_entity(entity_id){*visible=false;}
}
