fn unknownmod_skins(game:&mut crate::game::Game<'_>)->core::ops::ControlFlow<()> {
let run=|game:&mut crate::game::Game<'_>| {
        let local = game.remote.as_ref().map(|r| r.profile);
        game.poll_own_profile();
        let Some(r) = game.remote.as_mut() else {
            return;
        };
        let local = local.unwrap_or(r.profile);
        let mut entries = skin_entries(
            &r.level_players,
            &r.skin_textures,
            &mut r.level_player_textures,
        );
        let own = match &r.own_profile {
            OwnProfile::Done(Some(t)) => Some(t.clone()),
            _ => None,
        };
        // Minecraft.profileFuture: the signed-in account's active skin and cape
        // from `/minecraft/profile` when the server sent none (single player) or
        // only a skin without the equipped cape.
        if let Some(own) = own {
            if let Some((_, t)) = entries.iter_mut().find(|(u, _)| *u == local) {
                if t.skin.is_none() {
                    t.skin = own.skin.clone();
                    if own.skin.is_some() {
                        t.slim = own.slim;
                    }
                }
                if t.cape.is_none() {
                    t.cape = own.cape.clone();
                }
            } else if own.skin.is_some() || own.cape.is_some() {
                entries.retain(|(u, _)| *u != local);
                entries.push((local, own));
            }
        }
        let mut resolved: Vec<(u128, String)> = Vec::new();
        // PlayerSkin.model of each resolved skin: the textures' `slim`.
        let mut slim = std::collections::HashSet::new();
        // PlayerSkin.cape of each resolved skin.
        let mut capes: HashMap<u128, String> = HashMap::new();
        for (uuid, textures) in entries {
            // PlayerInfo.createSkinLookup: only the local player's own skin
            // may be unsigned.
            let skin = {
                let mut downloads = game.skin_downloads.borrow_mut();
                horizon_client::skins::lookup(&textures, uuid != local, |kind, url| {
                    downloads.state(kind, url)
                })
            };
            if let Some(skin) = skin {
                if textures.slim {
                    slim.insert(uuid);
                }
                if let Some(cape) = horizon_client::skins::cape(&textures) {
                    capes.insert(uuid, cape);
                }
                resolved.push((uuid, skin));
            }
        }
        // An offline name's skin from the launcher, where neither the server
        // nor an account gives the local player one.
        if !resolved.iter().any(|(u, _)| *u == local)
            && let Some((rgba, is_slim)) = horizon_client::skins::offline_skin()
        {
            const OFFLINE: &str = "horizon/offline_skin";
            if !game.skin_slots.contains(OFFLINE) || !game.gui.atlas.sprites.contains_key(OFFLINE) {
                game.store_skin(OFFLINE.into(), rgba.clone());
            }
            if *is_slim {
                slim.insert(local);
            }
            resolved.push((local, OFFLINE.into()));
        }
        // An offline name's cape from the launcher, where the profile has none.
        if !capes.contains_key(&local)
            && let Some(rgba) = horizon_client::skins::offline_cape()
        {
            const OFFLINE: &str = "capes/offline";
            if !game.skin_slots.contains(OFFLINE) || !game.gui.atlas.sprites.contains_key(OFFLINE) {
                game.store_skin(OFFLINE.into(), rgba.to_vec());
            }
            capes.insert(local, OFFLINE.into());
        }
        // PlayerInfo.getSkin: the downloaded skin and its model, else the
        // profile's default skin (DefaultPlayerSkin.get), slim or wide.
        let masks=unknownmod_client::masks::players();
        if !masks.is_empty() {
            const ANON:&str="skins/unknownmod_anonymous";
            if !game.skin_slots.contains(ANON)||!game.gui.atlas.sprites.contains_key(ANON){
                game.store_skin(ANON.into(),unknownmod_client::ANONYMOUS_RGBA.to_vec());
            }
            for uuid in &masks {resolved.retain(|(id,_)|id!=uuid);resolved.push((*uuid,ANON.into()));slim.remove(uuid);capes.remove(uuid);}
        }
        let skin_of = |skins: &HashMap<u128, String>, uuid: u128| match skins.get(&uuid) {
            Some(skin) => (skin.clone(), slim.contains(&uuid)),
            None => {
                let skin = horizon_client::tab_list::default_skin(uuid);
                (skin.to_owned(), skin.contains("/slim/"))
            }
        };
        let skins: HashMap<_, _> = resolved.into_iter().collect();
        let Some((level, own)) = game.remote.as_ref().map(|r| {
            (
                r.level_players
                    .iter()
                    .map(|(id, uuid)| (*id as u32, *uuid))
                    .collect::<Vec<_>>(),
                r.profile,
            )
        }) else {
            return;
        };
        // Only the skins drawn hold atlas slots: the level's players, not
        // every entry of a busy server's player list.
        // Build once: resolving each player must not scan every level entity.
        let players: HashMap<_, _> = game
            .mobs
            .iter()
            .enumerate()
            .filter(|(_, m)| &*m.kind.id == "minecraft:player")
            .map(|(i, m)| (m.id, i))
            .collect();
        for (id, uuid) in level {
            unknownmod_client::masks::entity(id,masks.contains(&uuid));
            let Some(&i) = players.get(&id) else {
                continue;
            };
            let (skin, is_slim) = game.drawn_skin(uuid, skin_of(&skins, uuid));
            let kind = game.mobs[i].kind.clone();
            let skin_3d = game.skin_3d_model(&kind, &skin, is_slim);
            let cape = capes.get(&uuid).filter(|c| game.again_skin(c)).cloned();
            let mob = &mut game.mobs[i];
            mob.skin = Some(skin);
            mob.slim = is_slim;
            mob.skin_3d = skin_3d;
            mob.cape = cape;
        }
        if game.player_model.is_some() {
            let (skin, is_slim) = game.drawn_skin(own, skin_of(&skins, own));
            // Avatar.isModelPartShown(CAPE): the Skin Customization option.
            let cape = capes
                .get(&own)
                .filter(|_| game.settings.file.bool("modelPart_cape"))
                .filter(|c| game.again_skin(c))
                .cloned();
            let kind = game.player_model.as_ref().map(|m| m.kind.clone());
            let skin_3d = kind
                .as_ref()
                .and_then(|kind| game.skin_3d_model(kind, &skin, is_slim));
            if let Some(mob) = game.player_model.as_mut() {
                mob.skin = Some(skin);
                mob.slim = is_slim;
                mob.skin_3d = skin_3d;
                mob.cape = cape;
            }
        }
        if let Some(r) = game.remote.as_mut()
            && r.skins != skins
        {
            r.skins_revision = r.skins_revision.wrapping_add(1);
            r.skins = skins;
        }
        // ClientMannequin.updateSkin (PlayerSkinRenderCache.lookup): a
        // mannequin's DATA_PROFILE `textures` property resolves like a
        // player's, never required to be signed.
        let mannequins: Vec<(usize, horizon_client::skins::Textures)> = game
            .mobs
            .iter()
            .enumerate()
            .filter(|(_, m)| &*m.kind.id == "minecraft:mannequin" && m.profile.is_some())
            .map(|(i, m)| {
                let profile = m.profile.as_ref();
                let t = profile.and_then(|p| p.properties.iter().find(|p| p.name == "textures"));
                let textures = if let Some(t) = t {
                    let signature = if t.signature.is_some() {
                        horizon_client::skins::Signature::Signed
                    } else {
                        horizon_client::skins::Signature::Unsigned
                    };
                    horizon_client::skins::unpack_known(&t.value, signature)
                } else {
                    // `ResolvableProfile.resolveProfile`: no `textures` and
                    // only a name or an id resolves through the services.
                    let key = profile.and_then(|p| match (&p.name, p.uuid) {
                        (Some(name), None) => {
                            Some(horizon_client::skins::NameOrId::Name(name.clone()))
                        }
                        (None, Some(id)) => Some(horizon_client::skins::NameOrId::Id(id)),
                        _ => None,
                    });
                    key.and_then(
                        |key| match game.skin_profiles.borrow_mut().state_for_game(&key) {
                            horizon_client::skins::Resolve::Done(Some(profile)) => {
                                profile.textures().map(|(value, signature)| {
                                    horizon_client::skins::unpack_known(&value, signature)
                                })
                            }
                            _ => None,
                        },
                    )
                    .unwrap_or_default()
                };
                (i, textures)
            })
            .collect();
        let mut resolved: Vec<(usize, Option<String>, bool)> = Vec::new();
        for (id, textures) in mannequins {
            let skin = {
                let mut downloads = game.skin_downloads.borrow_mut();
                horizon_client::skins::lookup(&textures, false, |kind, url| {
                    downloads.state(kind, url)
                })
            };
            let slim = skin.is_some() && textures.slim;
            resolved.push((id, skin, slim));
        }
        for (i, skin, slim) in resolved {
            let skin = skin.filter(|s| !s.starts_with("skins/") || game.again_skin(s));
            // Resolution and atlas uploads do not insert or remove mobs.
            let mob = &mut game.mobs[i];
            mob.slim = slim && skin.is_some();
            mob.skin = skin;
        }
    
};run(game);core::ops::ControlFlow::Break(())
}
