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
    ui_requests: BTreeMap<(u128,bool),u64>,
    events: unknownmod_rules::events::Board,
    event_positions: Vec<unknownmod_rules::events::Presence>,
    last_event_marker: u64,
    last_automatic_second: u64,
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
    fn flush_audit(&mut self) {
        use std::io::Write;
        if self.state.pending_audit.is_empty() || self.root.as_os_str().is_empty() { return; }
        let result=(||->Result<(),String>{
            let mut lines=Vec::new();
            for entry in &self.state.pending_audit {
                let mut entry=entry.clone();if entry.time==0 {entry.time=shared::wall_time();}
                serde_json::to_writer(&mut lines,&entry).map_err(|e|e.to_string())?;lines.push(b'\n');
            }
            let mut file=std::fs::OpenOptions::new().create(true).append(true).open(self.root.join("audit.jsonl")).map_err(|e|e.to_string())?;
            file.write_all(&lines).and_then(|_|file.sync_data()).map_err(|e|e.to_string())
        })();
        match result {Ok(())=>self.state.pending_audit.clear(),Err(e)=>log::error!("UnknownMod audit write failed: {e}")}
    }
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
    fn changed(&mut self) { self.flush_audit(); self.refresh=true; self.publish(); self.save(); }
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
            winner: read(root.join("winner.json")), events: read(root.join("events.json")), root, ..Default::default() };
        rt.events.recover();
        write_json(rt.root.join("events.json"),&rt.events).expect("UnknownMod event recovery write");
        // New worlds open with ordinary skins; existing seasons keep their state.
        if !rt.root.join("state.json").exists() { rt.state.stop(shared::wall_time()); }
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
        rt.save();
        log::info!("UnknownMod 6.1.1 enabled: reveal {} min / {} h / minimum {}",config.reveal_duration_minutes,config.reveal_interval_hours,config.minimum_players);
    }
    pub(super) fn unknownmod_join(&mut self, id:u128, name:&str) {
        if !shared::enabled(){return} self.unknownmod_init();
        let mode=self.native_server_players().iter().find(|p|p.uuid==id).map_or(0,|p|p.game_mode);
        let original=self.lan.as_ref().and_then(|h|h.hub.guests.iter().find(|g|g.uuid==id))
            .and_then(|g|g.properties.iter().find(|p|p.name=="textures")).cloned();
        let mut rt=lock(); rt.state.join(id,name,mode,shared::wall_time());
        rt.state.record_activity(id,shared::combat_time());
        if let Some(reason)=rt.state.players.get(&id).and_then(|p|p.last_decision.as_ref()).filter(|d|d.eliminated||d.cause==unknownmod_rules::audit::Cause::CombatLogout) {
            if let Some(g)=self.lan.as_ref().and_then(|h|h.hub.guests.iter().find(|g|g.uuid==id)) {
                g.send(&C::SystemChat(play::SystemChat{content:component_nbt(&Text::literal(&reason.explanation()).component()),overlay:false}));
            }
        }
        if rt.state.players.get(&id).is_some_and(|p|p.ghost){rt.modes.push((id,3));}
        else if let Some(mode)=rt.state.players.get(&id).and_then(|p|p.previous_mode){rt.modes.push((id,mode));}
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
        let [min_x,min_z,max_x,max_z]=self.border.bounds();
        let event_border=unknownmod_rules::automatic::Border{min_x,min_z,max_x,max_z};
        let (modes,refresh,messages,combat)={
            let mut rt=lock();
            rt.entities=players.iter().map(|p|(p.entity_id as u32,p.uuid)).collect();
            for p in &players { if let Some(record)=rt.state.players.get_mut(&p.uuid){record.mode=p.game_mode;} }
            for (id,time) in shared::take_activity() { rt.state.record_activity(id,time); }
            let config=rt.config.clone();
            rt.state.update_afk(&config,shared::combat_time());
            rt.event_positions=event_presence(&players,&rt.state,&config);
            if now/1000!=rt.last_second {
                rt.last_second=now/1000;
                if rt.events.current.as_ref().is_some_and(|e|e.phase==unknownmod_rules::events::Phase::Running) {
                    let presence=rt.event_positions.clone();
                    let mut next=rt.events.clone();
                    if rt.state.stopped {next.cancel();}
                    let outside=next.current.as_ref().is_some_and(|e|e.automatic&&!event_border.contains(e.center,e.arena_radius+8.0));
                    let message=if outside {next.cancel();Some("Сигнал погас: граница мира приблизилась к площадке. Штрафов нет.".into())}else{next.tick(&presence,shared::combat_time())};
                    match write_json(rt.root.join("events.json"),&next) {
                        Ok(())=>{rt.events=next;if let Some(message)=message{rt.messages.push(message);}},
                        Err(e)=>log::error!("UnknownMod event save failed: {e}"),
                    }
                }
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
            let mut combat=players.iter().filter_map(|p|{
                let seconds=rt.state.remaining_combat_seconds(p.uuid,shared::combat_time());
                if seconds>0 {Some((p.uuid,format!("Бой: {seconds} с. Выход засчитает смерть")))}
                else if rt.state.stopped {None}
                else {rt.state.reveals.get(&p.uuid).map(|r|(p.uuid,format!("Ты раскрыт. Осталось: {} мин {} с",r.remaining(now)/60000,r.remaining(now)/1000%60)))}
            }).collect::<Vec<_>>();
            if let Some(e)=rt.events.current.as_ref().filter(|e|e.phase==unknownmod_rules::events::Phase::Running) {
                for p in &rt.event_positions {if e.eligible(p)&&!combat.iter().any(|(id,_)|*id==p.id){combat.push((p.id,e.hud(p)));}}
            }
            (std::mem::take(&mut rt.modes),std::mem::take(&mut rt.refresh),std::mem::take(&mut rt.messages),combat)
        };
        if let Some(host)=self.lan.as_mut(){
            for (id,mode) in modes { if let Some(conn)=host.hub.guests.iter().find(|g|g.uuid==id).map(|g|g.conn){
                host.hub.set_game_mode(conn,mode,&self.world,None);
                if let Some(player)=lock().state.players.get_mut(&id){if !player.ghost{player.previous_mode=None;}}
            } }
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
        self.unknownmod_automatic_tick(now);
        self.unknownmod_event_markers(now);
    }
    pub(super) fn unknownmod_hit(&mut self, attacker:u32, weapon:Option<&Text>){
        if !shared::enabled(){return} let Some(victim)=self.lan_acting_uuid else{return};
        let mut rt=lock();let Some(attacker)=rt.entities.get(&attacker).copied() else{return};
        let weapon=weapon.map(Text::plain);rt.state.hit(attacker,victim,weapon.as_deref(),shared::combat_time());
        let positions=rt.event_positions.clone();rt.events.record_defense(attacker,victim,&positions);
    }
    pub(super) fn unknownmod_death(&mut self,host:&LanHost,gi:usize){
        if !shared::running(){return} let g=&host.hub.guests[gi]; if g.health>0.0 || g.dead{return}
        if !lock().state.players.get(&g.uuid).is_some_and(|p|p.online) {return}
        let environmental=host.play.get(&g.conn).is_some_and(|p|p.death_cause.is_some());
        let blow=self.guest_combat.get(&g.conn).and_then(|c|c.unknownmod_blow());
        let mut rt=lock();let attacker=if environmental {None}else{blow.as_ref().and_then(|(id,_)|rt.entities.get(id).copied())};
        let weapon=if environmental {None}else{blow.as_ref().and_then(|(_,name)|name.as_deref())};
        if rt.state.death_at(g.uuid,attacker,weapon,shared::wall_time(),unknownmod_rules::audit::Cause::Death){rt.messages.push("Игрок выбыл из эксперимента.".into());}
        if let Some(reason)=rt.state.players.get(&g.uuid).and_then(|p|p.last_decision.as_ref()) {
            g.send(&C::SystemChat(play::SystemChat{content:component_nbt(&Text::literal(&reason.explanation()).component()),overlay:false}));
        }
        rt.changed();
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
        rt.events.recover();
        if let Err(e)=write_json(rt.root.join("events.json"),&rt.events){log::error!("UnknownMod event shutdown save failed: {e}");}
        rt.publish();rt.save();
    }
    pub(super) fn unknownmod_commands(&mut self,commands:&mut Vec<NativeCommand>){
        if shared::enabled(){commands.push(NativeCommand::op("unknown [args:text]",2));commands.push(NativeCommand::new("players"));commands.push(NativeCommand::new("event [args:text]"));}
    }
    pub(super) fn unknownmod_command(&mut self,permission:u8,command:&str,ran:&mut bool){
        if !shared::enabled(){return} let mut args=command.split_whitespace().collect::<Vec<_>>();
        if args.first()==Some(&"event") || (args.first()==Some(&"unknown")&&args.get(1)==Some(&"event")) {
            *ran=true;self.unknownmod_init();self.unknownmod_event_command(permission,&args);return;
        }
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
            for (id,time) in shared::take_activity() { rt.state.record_activity(id,time); }
            let config=rt.config.clone();rt.state.update_afk(&config,shared::combat_time());
            match args.get(1).copied().unwrap_or("status"){
                "online"|"afk"=>{
                    if args.len()!=2 {return Err("Используй /unknown online или /unknown afk".into())}
                    Ok(rt.state.online_report())
                },
                "afktimeout"=>{
                    if args.len()!=3 {return Err("Используй /unknown afktimeout <минуты>".into())}
                    let minutes=args[2].parse::<u32>().map_err(|_|"Нужно целое число минут")?;
                    if !(1..=10080).contains(&minutes) {return Err("Значение должно быть от 1 до 10080".into())}
                    rt.config.afk_timeout_minutes=minutes;
                    write_json(rt.root.join("config.json"),&rt.config)?;
                    let config=rt.config.clone();rt.state.update_afk(&config,shared::combat_time());
                    Ok(format!("AFK после {minutes} мин без действий. Статус виден только операторам."))
                },
                "start"|"stop"=>{
                    if args.len()!=2 {return Err("Используй /unknown start или /unknown stop".into())}
                    let config=rt.config.clone();
                    if args[1]=="stop" {let mut events=rt.events.clone();events.cancel();commit_events(&mut rt,events)?;}
                    let changed=if args[1]=="start" {rt.state.start(&config,now)} else {rt.state.stop(now)};
                    if !changed {return Ok(if rt.state.stopped {"Эксперимент уже остановлен."}else{"Эксперимент уже запущен."}.into())}
                    rt.messages.clear();
                    let message=if rt.state.stopped {"Эксперимент остановлен. Обычные скины включены; таймеры на паузе."}else{"Эксперимент запущен. Анонимность включена; таймеры продолжаются."};
                    rt.messages.push(message.into());rt.changed();Ok(message.into())
                },
                "status"=>Ok(format!("UnknownMod 6.1.1 | {} | раскрытие {} мин, каждые {} ч, минимум {} | до следующей проверки раскрытия: {} с{} | раскрыто {} | победитель: {}",if rt.state.stopped {"остановлен"}else{"запущен"},rt.config.reveal_duration_minutes,rt.config.reveal_interval_hours,rt.config.minimum_players,rt.state.next_reveal_seconds(now),if rt.state.stopped {" (пауза)"}else{""},rt.state.reveals.len(),rt.winner.as_deref().unwrap_or("ещё нет"))),
                "reveal"|"hide"|"ghost"|"restore"=>{
                    if args[1]=="reveal" && rt.state.stopped {return Err("Сначала запусти эксперимент: /unknown start".into())}
                    if args.len()==2 {
                        if args[1]=="hide" {rt.state.reveals.clear();rt.changed();return Ok("Все раскрытия завершены.".into())}
                        if args[1]=="reveal" {
                            let ids=rt.state.players.keys().copied().filter(|id|rt.state.is_event_participant(*id)&&!rt.state.reveals.contains_key(id)).collect::<Vec<_>>();
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
                _=>Err("/unknown start | stop | status | online | afk | afktimeout <минуты> | reveal <ник> [мин] | hide/ghost/restore <ник> | duration/interval/minplayers <число> | roster <ники> | reload".into())
            }
        })();
        self.native_feedback(Text::literal(&answer.unwrap_or_else(|e|e)));
    }
    pub(super) fn unknownmod_dragon_win(&mut self){
        if !shared::running(){return}let Some(id)=self.lan_acting_uuid else{return};let mut rt=lock();
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
