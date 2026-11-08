//! UnknownMod's authoritative Horizon adapter. No client packet can select a victim.
use super::{Game, lan::LanHost, native_server::NativeCommand};
use horizon_client::{text::Text, lan::data::component_nbt};
use horizon_protocol::packets::play::{self, Clientbound as C};
use unknownmod_rules::{Config, State, Logout, Reveal, shared};
use std::{collections::BTreeMap, path::PathBuf, sync::{Mutex, OnceLock}};

#[derive(Default)]
struct Runtime {
    root: PathBuf, config: Config, state: State, stopping: bool,
    entities: BTreeMap<u32,u128>, modes: Vec<(u128,i32)>, refresh: bool,
    messages: Vec<String>, last_second: u64, winner: Option<String>,
    profiles: BTreeMap<u128,(TextureProperty,u64)>,
}
fn runtime() -> &'static Mutex<Runtime> {
    static RUNTIME: OnceLock<Mutex<Runtime>> = OnceLock::new();
    RUNTIME.get_or_init(Default::default)
}
fn lock() -> std::sync::MutexGuard<'static, Runtime> { runtime().lock().unwrap_or_else(|e| e.into_inner()) }
fn write_json(path: PathBuf, value: &impl serde::Serialize) -> Result<(),String> {
    let temporary = path.with_extension("json.tmp");
    std::fs::write(&temporary, serde_json::to_vec_pretty(value).map_err(|e|e.to_string())?).map_err(|e|e.to_string())?;
    // Retain a last-known-good backup before atomically replacing the data file.
    if path.exists() { std::fs::copy(&path, path.with_extension("json.bak")).map_err(|e|e.to_string())?; }
    std::fs::rename(&temporary, &path).map_err(|e|e.to_string())
}
impl Runtime {
    fn save(&self) {
        if self.root.as_os_str().is_empty() { return; }
        let now=shared::wall_time();let mut durable=self.state.clone();
        for player in durable.players.values_mut(){if player.online{player.last_seen=now;}}
        // Persist remaining online time, so an unclean restart also pauses hunts.
        for reveal in durable.reveals.values_mut(){*reveal=Reveal::Paused{remaining:reveal.remaining(now)};}
        let result = write_json(self.root.join("state.json"), &durable)
            .and_then(|_| write_json(self.root.join("winner.json"), &self.winner));
        if let Err(e) = result { log::error!("UnknownMod: save failed: {e}"); }
    }
    fn publish(&self) { shared::publish(&self.state,&self.config); }
    fn changed(&mut self) { self.refresh=true; self.publish(); self.save(); }
    fn target(&self, name: &str) -> Option<u128> {
        let mut matches = self.state.players.iter().filter(|(_,p)|p.name.eq_ignore_ascii_case(name));
        let id=*matches.next()?.0;
        matches.next().is_none().then_some(id)
    }
}
impl Game<'_> {
    fn unknownmod_init(&self) {
        if !shared::enabled() { return; }
        let Some(world)=self.native_world_dir() else { return; };
        let root=world.join("unknownmod");
        let mut rt=lock();
        if rt.root==root { return; }
        std::fs::create_dir_all(&root).expect("UnknownMod data directory");
        fn read<T: serde::de::DeserializeOwned+Default>(path: PathBuf)->T {
            if !path.exists() { return T::default(); }
            serde_json::from_slice(&std::fs::read(&path).expect("UnknownMod read data"))
                .unwrap_or_else(|e|panic!("UnknownMod refuses to overwrite invalid {}: {e}",path.display()))
        }
        *rt=Runtime { config: read(root.join("config.json")), state: read(root.join("state.json")),
            winner: read(root.join("winner.json")), root, ..Default::default() };
        if rt.config.roster.is_empty() {
            if let Ok(text)=std::fs::read(Self::game_dir().join("whitelist.json")) {
                if let Ok(list)=serde_json::from_slice::<Vec<serde_json::Value>>(&text) {
                    rt.config.roster=list.iter().filter_map(|v|v["name"].as_str().map(str::to_owned)).collect();
                }
            }
        }
        // A crashed server's running hunts resume from their saved remaining time.
        let now=shared::wall_time();
        for reveal in rt.state.reveals.values_mut() {
            if let Reveal::Running{ends_at}=reveal { *reveal=Reveal::Paused{remaining:ends_at.saturating_sub(now)}; }
        }
        let config=rt.config.clone(); rt.state.configure(&config,now,false);
        write_json(rt.root.join("config.json"),&config).expect("UnknownMod config write");
        rt.publish();
        log::info!("UnknownMod 6.0 enabled: reveal {} min / {} h / minimum {}",config.reveal_duration_minutes,config.reveal_interval_hours,config.minimum_players);
    }
    pub(super) fn unknownmod_join(&mut self, id:u128, name:&str) {
        if !shared::enabled(){return} self.unknownmod_init();
        let mode=self.native_server_players().iter().find(|p|p.uuid==id).map_or(0,|p|p.game_mode);
        let original=self.lan.as_ref().and_then(|h|h.hub.guests.iter().find(|g|g.uuid==id))
            .and_then(|g|g.properties.iter().find(|p|p.name=="textures")).cloned();
        let mut rt=lock(); rt.state.join(id,name,mode,shared::wall_time());
        if rt.state.players.get(&id).is_some_and(|p|p.ghost){rt.modes.push((id,3));}
        rt.changed();
        let now=shared::wall_time();
        let fetch=original.is_none() && !rt.profiles.get(&id).is_some_and(|(_,expires)|*expires>now);
        if let Some(original)=original {rt.profiles.insert(id,(original,now+300000));}
        else if let Some((property,expires))=rt.profiles.get(&id).cloned() {if expires>now {profile_results().lock().unwrap_or_else(|e|e.into_inner()).push((id,property));}}
        drop(rt);
        if fetch { fetch_profile(id,name.to_owned()); }
    }
    pub(super) fn unknownmod_respawn(&mut self,id:u128) {
        if shared::enabled() && lock().state.players.get(&id).is_some_and(|p|p.ghost) { lock().modes.push((id,3)); }
    }
    pub(super) fn unknownmod_tick(&mut self) {
        if !shared::enabled(){return} self.unknownmod_init();
        let now=shared::wall_time();
        let profiles=std::mem::take(&mut *profile_results().lock().unwrap_or_else(|e|e.into_inner()));
        if let Some(host)=self.lan.as_mut(){
            for (id,property) in profiles {
                lock().profiles.insert(id,(property.clone(),now+300000));
                if let Some(g)=host.hub.guests.iter_mut().find(|g|g.uuid==id) {
                    g.properties.retain(|p|p.name!="textures");g.properties.push(property);lock().refresh=true;
                }
            }
        }
        let players=self.native_server_players();
        let (modes,refresh,messages,combat)={
            let mut rt=lock();
            rt.entities=players.iter().map(|p|(p.entity_id as u32,p.uuid)).collect();
            for p in &players { if let Some(record)=rt.state.players.get_mut(&p.uuid){record.mode=p.game_mode;} }
            if now/1000!=rt.last_second {
                rt.last_second=now/1000;
                let expired=rt.state.expire_reveals(now);
                for id in expired {let name=rt.state.players[&id].name.clone();rt.messages.push(format!("Охота на {name} закончилась. Анонимность восстановлена."));rt.refresh=true;}
                let config=rt.config.clone();
                if let Some(id)=rt.state.automatic_reveal(&config,now,now as usize){
                    let name=rt.state.players[&id].name.clone();
                    rt.messages.push(format!("Раскрыт {name}. Для выбывания всё ещё нужно оружие с его ником.")); rt.refresh=true;
                }
                for p in &players { if rt.state.players.get(&p.uuid).is_some_and(|p|p.ghost) && p.game_mode!=3 && p.health>0.0 {rt.modes.push((p.uuid,3));} }
                if rt.refresh || now/1000%30==0 {rt.publish();rt.save();}
            }
            let combat=players.iter().filter_map(|p|{
                let seconds=rt.state.remaining_combat_seconds(p.uuid,shared::combat_time());
                if seconds>0 {Some((p.uuid,format!("Бой: {seconds} с. Выход засчитает смерть")))}
                else {rt.state.reveals.get(&p.uuid).map(|r|(p.uuid,format!("Ты раскрыт. Осталось: {} мин {} с",r.remaining(now)/60000,r.remaining(now)/1000%60)))}
            }).collect::<Vec<_>>();
            (std::mem::take(&mut rt.modes),std::mem::take(&mut rt.refresh),std::mem::take(&mut rt.messages),combat)
        };
        if let Some(host)=self.lan.as_mut(){
            for (id,mode) in modes { if let Some(conn)=host.hub.guests.iter().find(|g|g.uuid==id).map(|g|g.conn){host.hub.set_game_mode(conn,mode,&self.world,None);} }
            if refresh {
                let infos=host.hub.guests.iter().filter(|g|g.placed).map(|g|host.hub.player_info(g.uuid,&g.name,g.game_mode,0)).collect::<Vec<_>>();
                // ADD updates the profile in our client, avoiding removal of tracked entities.
                host.hub.broadcast(&C::PlayerInfoUpdate(play::PlayerInfoUpdate{actions:0xFF,entries:infos}));
            }
            for (id,message) in combat {
                if let Some(g)=host.hub.guests.iter().find(|g|g.uuid==id){g.send(&C::SetActionBarText(play::Text{text:component_nbt(&Text::literal(&message).component())}));}
            }
        }
        for message in messages {self.native_broadcast(Text::literal(&message));}
    }
    pub(super) fn unknownmod_hit(&mut self, attacker:u32, weapon:Option<&Text>){
        if !shared::enabled(){return} let Some(victim)=self.lan_acting_uuid else{return};
        let mut rt=lock();let Some(attacker)=rt.entities.get(&attacker).copied() else{return};
        let weapon=weapon.map(Text::plain);rt.state.hit(attacker,victim,weapon.as_deref(),shared::combat_time());
    }
    pub(super) fn unknownmod_death(&mut self,host:&LanHost,gi:usize){
        if !shared::enabled(){return} let g=&host.hub.guests[gi]; if g.health>0.0 || g.dead{return}
        if host.play.get(&g.conn).is_some_and(|p|p.death_cause.is_some()){return}
        let blow=self.guest_combat.get(&g.conn).and_then(|c|c.unknownmod_blow());
        let mut rt=lock();let attacker=blow.as_ref().and_then(|(id,_)|rt.entities.get(id).copied());
        let weapon=blow.as_ref().and_then(|(_,name)|name.as_deref());
        if rt.state.death(g.uuid,attacker,weapon){rt.messages.push("Игрок выбыл из эксперимента.".into());rt.changed();}
    }
    pub(super) fn unknownmod_leave(&mut self,host:&mut LanHost,gi:usize){
        if !shared::enabled(){return}
        let id=host.hub.guests[gi].uuid;let conn=host.hub.guests[gi].conn;
        let result={let mut rt=lock();let stopping=rt.stopping;
            let result=rt.state.leave(id,shared::wall_time(),shared::combat_time(),stopping);rt.changed();result};
        if result!=Logout::Safe && !host.hub.guests[gi].dead {
            host.hub.guests[gi].health=0.0;
            host.play.entry(conn).or_default().death_cause=Some("death.attack.genericKill".into());
            self.guest_die(host,gi,conn);
        }
        if result==Logout::Eliminated {host.hub.set_game_mode(conn,3,&self.world,None);}
    }
    pub(super) fn unknownmod_stopping(&mut self){
        if !shared::enabled(){return} let mut rt=lock();rt.stopping=true;
        let ids=rt.state.players.iter().filter_map(|(&id,p)|p.online.then_some(id)).collect::<Vec<_>>();
        for id in ids {rt.state.leave(id,shared::wall_time(),shared::combat_time(),true);}
        rt.publish();rt.save();
    }
    pub(super) fn unknownmod_commands(&mut self,commands:&mut Vec<NativeCommand>){
        if shared::enabled(){commands.push(NativeCommand::op("unknown [args:text]",2));commands.push(NativeCommand::new("players"));}
    }
    pub(super) fn unknownmod_command(&mut self,permission:u8,command:&str,ran:&mut bool){
        if !shared::enabled(){return} let mut args=command.split_whitespace().collect::<Vec<_>>();
        if !matches!(args.first(),Some(&"unknown")|Some(&"players")){return} *ran=true; self.unknownmod_init();
        // Keep the existing Fabric command spellings as aliases.
        if args.get(1)==Some(&"reveal") {
            match args.get(2).copied(){
                Some("player")=>{args.remove(2);},
                Some("cancel")=>{args.remove(2);args[1]="hide";},
                Some("status"|"duration"|"interval"|"minplayers")=>{args.remove(1);},
                _=>{}
            }
        }
        let answer=(||->Result<String,String>{
            let mut rt=lock();let now=shared::wall_time();
            if args[0]=="players" {let (recent,old)=rt.state.participant_groups(&rt.config,now);return Ok(format!("За последние 3 дня заходили: {}\nДавно не заходили: {}",recent.join(", "),old.join(", ")));}
            if permission<2{return Err("Нужны права оператора".into())}
            match args.get(1).copied().unwrap_or("status"){
                "status"=>Ok(format!("UnknownMod 6.0 | раскрытие {} мин, каждые {} ч, минимум {} | раскрыто {} | победитель: {}",rt.config.reveal_duration_minutes,rt.config.reveal_interval_hours,rt.config.minimum_players,rt.state.reveals.len(),rt.winner.as_deref().unwrap_or("ещё нет"))),
                "reveal"|"hide"|"ghost"|"restore"=>{
                    if args.len()==2 {
                        if args[1]=="hide" {rt.state.reveals.clear();rt.changed();return Ok("Все раскрытия завершены.".into())}
                        if args[1]=="reveal" {
                            let ids=rt.state.players.keys().copied().filter(|id|rt.state.is_active(*id)&&!rt.state.reveals.contains_key(id)).collect::<Vec<_>>();
                            if ids.is_empty(){return Err("Нет доступных игроков".into())}
                            let id=ids[now as usize%ids.len()];let config=rt.config.clone();rt.state.reveal(id,&config,now);rt.changed();return Ok(format!("Раскрыт {}",rt.state.players[&id].name));
                        }
                    }
                    let name=args.get(2).ok_or("Укажи настоящий ник игрока")?;let id=rt.target(name).ok_or("Игрок не найден или имя неоднозначно")?;
                    match args[1]{
                        "reveal"=>{let mut config=rt.config.clone();if let Some(minutes)=args.get(3){config.reveal_duration_minutes=minutes.parse::<u32>().map_err(|_|"Нужно число минут")?.clamp(1,10080);}
                            if !rt.state.reveal(id,&config,now){return Err("Игрок недоступен или уже раскрыт".into())}
                            rt.messages.push(format!("Раскрыт {name} на {} мин. Для выбывания нужно оружие с его ником.",config.reveal_duration_minutes));},
                        "hide"=>{rt.state.reveals.remove(&id);},
                        "ghost"=>{if let Some(mode)=rt.state.restore(id){rt.modes.push((id,mode));}else{rt.state.make_ghost(id);rt.modes.push((id,3));}},
                        "restore"=>{let mode=rt.state.restore(id).ok_or("Игрок не выбывал")?;rt.modes.push((id,mode));},_=>{}
                    } rt.changed();Ok(format!("{}: {}",args[1],name))
                },
                "duration"|"interval"|"minplayers"=>{
                    let value=args.get(2).ok_or("Укажи значение")?.parse::<u32>().map_err(|_|"Нужно целое число")?;
                    if value==0 || value>10080{return Err("Значение должно быть от 1 до 10080".into())}
                    match args[1]{"duration"=>rt.config.reveal_duration_minutes=value,"interval"=>rt.config.reveal_interval_hours=value,_=>rt.config.minimum_players=value as usize};
                    let config=rt.config.clone();rt.state.configure(&config,now,args[1]=="interval");write_json(rt.root.join("config.json"),&config)?;rt.changed();Ok("Настройка сохранена; текущие раскрытия продолжаются.".into())
                },
                "roster"=>{rt.config.roster=args.iter().skip(2).map(|n|n.to_string()).collect();write_json(rt.root.join("config.json"),&rt.config)?;Ok("Список участников сохранён.".into())},
                "nickname"=>{let name=*args.get(2).ok_or("Укажи анонимный ник")?;if name.len()>16||name.is_empty()||!name.bytes().all(|b|b.is_ascii_alphanumeric()||b==b'_'){return Err("Ник: 1–16 латинских букв, цифр или подчёркиваний".into())}rt.config.anonymous_name=name.into();write_json(rt.root.join("config.json"),&rt.config)?;rt.changed();Ok("Анонимный ник обновлён.".into())},
                "reload"=>{let config:Config=serde_json::from_slice(&std::fs::read(rt.root.join("config.json")).map_err(|e|e.to_string())?).map_err(|e|e.to_string())?;rt.config=config.clone();rt.state.configure(&config,now,false);rt.changed();Ok("Конфигурация перечитана.".into())},
                _=>Err("/unknown status | reveal <ник> [мин] | hide/ghost/restore <ник> | duration/interval/minplayers <число> | roster <ники> | reload".into())
            }
        })();
        self.native_feedback(Text::literal(&answer.unwrap_or_else(|e|e)));
    }
    pub(super) fn unknownmod_dragon_win(&mut self){
        if !shared::enabled(){return}let Some(id)=self.lan_acting_uuid else{return};let mut rt=lock();
        if rt.winner.is_some() || !rt.state.is_active(id){return}
        let name=rt.state.players[&id].name.clone();
        let winner=self.score.board.players_team(&name).map(|t|t.name.clone()).unwrap_or(name);
        rt.winner=Some(winner.clone());rt.messages.push(format!("Дракон повержен! Победитель эксперимента: {winner}"));rt.save();
    }
}

type TextureProperty=horizon_protocol::packets::login::Property;
fn profile_results()->&'static Mutex<Vec<(u128,TextureProperty)>>{
    static RESULTS:OnceLock<Mutex<Vec<(u128,TextureProperty)>>>=OnceLock::new();RESULTS.get_or_init(Default::default)
}
fn fetch_profile(uuid:u128,name:String){
    // Offline test clients have no signed login properties. Resolve originals
    // away from the server thread; other players' anonymous packets omit it.
    std::thread::spawn(move||{
        let result=(||->Result<TextureProperty,String>{
            let agent=ureq::AgentBuilder::new().timeout(std::time::Duration::from_secs(8)).build();
            fn json(agent:&ureq::Agent,url:&str)->Result<serde_json::Value,String>{
                let text=agent.get(url).call().map_err(|e|e.to_string())?.into_string().map_err(|e|e.to_string())?;
                serde_json::from_str(&text).map_err(|e|e.to_string())
            }
            let account=json(&agent,&format!("https://api.mojang.com/users/profiles/minecraft/{name}"))?;
            let id=account["id"].as_str().ok_or("No official account")?;
            let profile=json(&agent,&format!("https://sessionserver.mojang.com/session/minecraft/profile/{id}?unsigned=false"))?;
            let prop=profile["properties"].as_array().and_then(|p|p.iter().find(|p|p["name"]=="textures")).ok_or("No skin property")?;
            Ok(TextureProperty{name:"textures".into(),value:prop["value"].as_str().ok_or("No skin data")?.into(),signature:prop["signature"].as_str().map(str::to_owned)})
        })();
        match result{Ok(p)=>profile_results().lock().unwrap_or_else(|e|e.into_inner()).push((uuid,p)),Err(e)=>log::warn!("UnknownMod: original profile {name} unavailable: {e}")}
    });
}
