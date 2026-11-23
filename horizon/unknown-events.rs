// Appended to unknownmod.rs. All event decisions use authenticated server positions.
use unknownmod_rules::events::{Board as EventBoard, Presence, Scenario, Kind, Phase};
use super::native_server::NativeServerPlayer;
fn event_random()->Result<u64,String>{unknownmod_rules::automatic::random()}

fn event_presence(players:&[NativeServerPlayer],state:&State,config:&Config)->Vec<Presence> {
    players.iter().map(|p|Presence{id:p.uuid,position:p.position,dimension:p.dimension.clone(),
        active:p.health>0.0 && p.game_mode==0 && state.is_event_participant(p.uuid)
            && !config.event_excluded_players.iter().any(|name|name.eq_ignore_ascii_case(&p.name))}).collect()
}
fn commit_events(rt:&mut Runtime,next:EventBoard)->Result<(),String> {
    write_json(rt.root.join("events.json"),&next)?;rt.events=next;Ok(())
}
impl Game<'_> {
    fn unknownmod_automatic_tick(&mut self,now:u64) {
        use unknownmod_rules::automatic::{Action,Border};
        let (mut board,config,presence,names,root)={
            let mut rt=lock();if rt.last_automatic_second==now/1000{return}rt.last_automatic_second=now/1000;
            if rt.state.stopped{rt.events.schedule.suspend();return}
            (rt.events.clone(),rt.config.automatic_events.clone(),rt.event_positions.clone(),rt.state.players.iter().map(|(&id,p)|(id,p.name.clone())).collect::<BTreeMap<_,_>>(),rt.root.clone())
        };
        let [min_x,min_z,max_x,max_z]=self.border.bounds();let border=Border{min_x,min_z,max_x,max_z};
        let players=presence.into_iter().filter(|p|p.dimension=="minecraft:overworld"&&border.contains(p.position,0.0)).map(|p|{
            let side=names.get(&p.id).and_then(|name|self.score.board.players_team(name)).map(|t|format!("team:{}",t.name)).unwrap_or_else(||format!("player:{:032x}",p.id));(p,side)
        }).collect::<Vec<_>>();
        let draw=match event_random(){Ok(draw)=>draw,Err(e)=>{log::error!("UnknownMod auto randomness failed: {e}");return}};
        let mut message=None;
        let unsafe_gather=board.current.as_ref().is_some_and(|e|e.automatic&&e.phase==Phase::Gathering&&!border.contains(e.center,e.arena_radius+8.0));
        let action=if unsafe_gather {board.cancel();Action::Message("Сигнал погас: граница мира приблизилась к площадке. Штрафов нет.".into())}else{board.automatic_step(&config,&players,shared::combat_time(),draw)};
        match action {
            Action::Search=>{
                let active=players.iter().filter(|(p,_)|p.active).map(|(p,_)|p).collect::<Vec<_>>();
                let battle_allowed=players.iter().filter(|(p,_)|p.active).map(|(_,side)|side).collect::<std::collections::BTreeSet<_>>().len()>=2;
                // Geometry is public during gathering. Its entropy must be
                // independent of the still-secret scenario/kind draw.
                let site=if self.dim.id()=="minecraft:overworld" {event_random().ok().and_then(|layout_draw|self.unknownmod_event_site(border,&active,layout_draw))}else{None};
                if let Some((center,points,radius))=site {
                    match board.open_automatic(center,points,radius,draw,battle_allowed) {
                        Ok(text)=>message=Some(text),Err(e)=>board.schedule.reason=e,
                    }
                }else {board.schedule.reason="Нет безопасной загруженной площадки внутри текущей границы; повтор через 5–10 минут".into();}
            },
            Action::Message(text)=>message=Some(text),Action::None=>{},
        }
        // Commit before publishing the signal or task. A restart cannot duplicate it.
        match write_json(root.join("events.json"),&board) {
            Ok(())=>{let mut rt=lock();rt.events=board;if let Some(text)=message{rt.messages.push(text);}},
            Err(e)=>log::error!("UnknownMod automatic event save failed: {e}"),
        }
    }
    fn unknownmod_event_site(&self,border:unknownmod_rules::automatic::Border,players:&[&Presence],draw:u64)->Option<([f64;3],[[f64;3];4],f64)> {
        if players.is_empty(){return None}
        let level=self.native_level("minecraft:overworld")?;
        let (bottom,height)=self.native_level_height(level)?;
        // Reads only existing loaded chunks. No forced generation, edits or teleports.
        let natural=|name:&str|matches!(name,"minecraft:grass_block"|"minecraft:dirt"|"minecraft:coarse_dirt"|"minecraft:rooted_dirt"|"minecraft:podzol"|"minecraft:mycelium"|"minecraft:stone"|"minecraft:andesite"|"minecraft:diorite"|"minecraft:granite"|"minecraft:tuff"|"minecraft:deepslate"|"minecraft:sand"|"minecraft:red_sand"|"minecraft:sandstone"|"minecraft:red_sandstone"|"minecraft:gravel"|"minecraft:clay"|"minecraft:snow_block"|"minecraft:moss_block"|"minecraft:mud");
        let air=|x:i32,y:i32,z:i32|self.native_level_block(level,[x,y,z]).is_some_and(|name|matches!(name.as_str(),"minecraft:air"|"minecraft:cave_air"|"minecraft:void_air"|"minecraft:short_grass"|"minecraft:tall_grass"|"minecraft:snow"));
        let floor=|x:i32,z:i32,lo:i32,hi:i32|->Option<i32>{
            for y in (lo..=hi).rev(){
                let name=self.native_level_block(level,[x,y,z])?;
                if matches!(name.as_str(),"minecraft:air"|"minecraft:short_grass"|"minecraft:tall_grass"|"minecraft:snow"){continue}
                return (natural(&name)&&air(x,y+1,z)&&air(x,y+2,z)).then_some(y+1)
            }None
        };
        let mut seed=draw;
        for _ in 0..8 {
            // Independent OS entropy per attempt; the initial draw also controls layout.
            let a=event_random().ok()?;let b=event_random().ok()?;seed=seed.rotate_left(9)^a;
            let anchor=players[(seed%players.len() as u64) as usize];
            let Some((mut center,radius))=border.candidate(anchor.position,a,b)else{continue};
            if players.iter().any(|p|(p.position[0]-center[0]).hypot(p.position[2]-center[2])<if radius<40.0{8.0}else{12.0}){continue}
            let x=center[0].floor() as i32;let z=center[2].floor() as i32;
            let Some(y)=floor(x,z,bottom,(bottom+height-3).min(309))else{continue};center[1]=f64::from(y);
            let spacing=6+(draw.rotate_left(23)%5) as i32;
            let rotation=(draw%4) as usize;let axes=[[-1,0],[1,0],[0,-1],[0,1]];
            let mut points=[center;4];let mut valid=true;
            for i in 0..4 {
                let [dx,dz]=axes[(i+rotation)%4];let mut previous=y;
                for step in 0..=spacing {
                    let xx=x+dx*step;let zz=z+dz*step;
                    let Some(yy)=floor(xx,zz,y-4,y+4)else{valid=false;break};
                    if (yy-previous).abs()>1 {valid=false;break}previous=yy;
                    for ox in -1..=1 {for oz in -1..=1 {
                        if !floor(xx+ox,zz+oz,yy-1,yy+1).is_some_and(|h|(h-yy).abs()<=1){valid=false;}
                    }}
                    if !valid{break}
                    if step==spacing{points[i]=[f64::from(xx)+0.5,f64::from(yy),f64::from(zz)+0.5];}
                }
                if !valid{break}
            }
            if valid&&border.contains(center,radius+16.0){return Some((center,points,radius))}
        }None
    }
    fn unknownmod_event_markers(&mut self,now:u64) {
        let event={let mut rt=lock();if rt.last_event_marker==now/1000{return}rt.last_event_marker=now/1000;rt.events.current.clone()};
        let Some(e)=event.filter(|e|matches!(e.phase,Phase::Gathering|Phase::Running))else{return};
        let dimension=match e.dimension.as_str(){"minecraft:the_nether"=>"minecraft:the_nether","minecraft:the_end"=>"minecraft:the_end",_=>"minecraft:overworld"};
        let mut points=vec![e.center];
        // Gathering looks identical for both scenarios and both hidden kinds.
        if e.phase==Phase::Gathering {points.extend((0..4).map(|i|e.point(i)));}
        else if e.kind==Kind::Cooperative {points.extend((0..e.points).map(|i|e.point(i)));}
        else if e.scenario==Scenario::Beacon {points.push(e.block);}
        for mut at in points {
            at[1]+=0.5;
            self.lan_send_particles(super::lan_particles::QueuedParticle {
                dimension,options:horizon_client::particles::ParticleOptions::simple(horizon_client::particles::ParticleType::EndRod),
                state:None,at,spread:[0.6,0.7,0.6],speed:[0.0;3],count:12,randomization:0,
            });
        }
    }
    fn unknownmod_event_command(&mut self,permission:u8,args:&[&str]) {
        let admin=args[0]=="unknown";
        let args=if admin{&args[2..]}else{&args[1..]};
        let action=args.first().copied().unwrap_or("status");
        let actor=self.native_acting_player();
        let mut players=self.native_server_players();
        // During a player's command their state may be swapped out of the hub.
        if let Some(p)=&actor {players.retain(|other|other.uuid!=p.uuid);players.push(p.clone());}
        let answer=(||->Result<String,String>{
            if admin && permission<2 {return Err("Нужны права оператора.".into())}
            let mut rt=lock();
            for (id,time) in shared::take_activity(){rt.state.record_activity(id,time);}
            let config=rt.config.clone();rt.state.update_afk(&config,shared::combat_time());
            // Guest command hooks temporarily hold the hub outside Game. Keep
            // other players from the authoritative tick snapshot, refreshing
            // the acting player's position from the current command context.
            let mut presence=rt.event_positions.clone();
            presence.retain(|p|rt.state.is_event_participant(p.id)&&rt.state.players.get(&p.id).is_some_and(|player|!config.event_excluded_players.iter().any(|name|name.eq_ignore_ascii_case(&player.name))));
            for p in event_presence(&players,&rt.state,&config){presence.retain(|other|other.id!=p.id);presence.push(p);}
            let mut next=rt.events.clone();
            if admin {
                let mut broadcast=None;
                let message=match action {
                    "auto"=>{
                        if args.len()!=2{return Err("/unknown event auto on|off|now|status".into())}
                        match args[1] {
                            "status"=>return Ok(format!("Автоивенты: {:?}\nПриватное расписание: {:?}",rt.config.automatic_events,next.schedule)),
                            "on"|"off"=>{let mut config=rt.config.clone();config.automatic_events.enabled=args[1]=="on";write_json(rt.root.join("config.json"),&config)?;rt.config=config;"Настройка автозапуска сохранена; начатое событие продолжается.".into()},
                            "now"=>{if !rt.config.automatic_events.enabled{return Err("Сначала /unknown event auto on.".into())}if next.current.as_ref().is_some_and(|e|matches!(e.phase,Phase::Prepared|Phase::Gathering|Phase::Running)){return Err("Уже есть незавершённое событие.".into())}next.schedule.remaining_ms=Some(0);next.schedule.forced=true;"Запрошена внеочередная проверка. Онлайн, границы и безопасность проверяются обычным образом.".into()},
                            _=>return Err("/unknown event auto on|off|now|status".into()),
                        }
                    },
                    "prepare"=>{
                        if rt.state.stopped{return Err("Сначала /unknown start.".into())}
                        if !(6..=7).contains(&args.len()){return Err("/unknown event prepare ruins|beacon coop|battle X Y Z [измерение]".into())}
                        let scenario=match args[1]{"ruins"=>Scenario::Ruins,"beacon"=>Scenario::Beacon,_=>return Err("Сценарий: ruins или beacon.".into())};
                        let kind=match args[2]{"coop"=>Kind::Cooperative,"battle"=>Kind::Battle,_=>return Err("Тип: coop или battle.".into())};
                        let mut center=[0.0;3];for i in 0..3 {center[i]=args[i+3].parse().map_err(|_|"Нужны абсолютные числовые координаты.")?;}
                        let dimension=args.get(6).copied().unwrap_or("minecraft:overworld").to_owned();
                        next.prepare(scenario,kind,center,dimension)?;
                        "Событие подготовлено приватно. Проверь доступность точек ±8 блоков от центра, затем /unknown event open.".into()
                    },
                    "open"=>{if args.len()!=1{return Err("/unknown event open".into())}let message=next.open()?;broadcast=Some(message.clone());message},
                    "start"=>{
                        if args.len()!=1{return Err("/unknown event start".into())}
                        if rt.state.stopped{return Err("Сначала /unknown start.".into())}
                        let message=next.start(&presence,shared::combat_time())?;broadcast=Some(message.clone());message
                    },
                    "cancel"=>{let visible=next.current.as_ref().is_some_and(|e|matches!(e.phase,Phase::Gathering|Phase::Running));next.cancel();if visible{broadcast=Some("Испытание отменено организатором без награды.".into());}"Событие отменено.".into()},
                    "exclude"=>{
                        rt.config.event_excluded_players=args.iter().skip(1).map(|n|n.to_string()).collect();
                        write_json(rt.root.join("config.json"),&rt.config)?;
                        return Ok("Исключения ивентов сохранены. Пустой список снимает исключения.".into())
                    },
                    "status"=>return Ok(format!("Запуск {}: {:?}\nНевыданные резервы: {:?}\nПодтверждённые доли: {:?}",next.serial,next.current,next.reservations,next.delivered)),
                    // Reservations are never silently replayed after a crash. An operator
                    // reconciles the inventory and explicitly closes the reservation.
                    "resolve"=>{
                        if args.len()!=2{return Err("/unknown event resolve <настоящий_ник> — закрыть проверенный резерв без повторной выдачи".into())}
                        let id=rt.target(args[1]).ok_or("Игрок не найден.")?;
                        next.reservations.remove(&id).ok_or("Нет резерва для сверки.")?;
                        "Резерв закрыт без повторной выдачи. Если выдача была прервана, недостающие предметы выдаются администратором после сверки.".into()
                    },
                    _=>return Err("/unknown event prepare|open|start|cancel|status|auto|exclude|resolve".into()),
                };
                commit_events(&mut rt,next)?;if let Some(message)=broadcast{rt.messages.push(message);}return Ok(message)
            }
            if action=="status" {
                let event=next.current.as_ref().map_or("Нет открытого события.".into(),|e|e.public_status());
                let reward=actor.as_ref().map_or(0,|p|next.rewards.get(&p.uuid).copied().unwrap_or(0));
                return Ok(format!("{event}\nТвои припасы: {reward} долей. /event join | leave | interact | drop | claim"))
            }
            let p=actor.as_ref().ok_or("Эта команда предназначена для игрока.")?;
            let own=presence.iter().find(|other|other.id==p.uuid).ok_or("Игрок не найден.")?;
            if args.len()!=1{return Err("/event join | leave | status | interact | drop | claim".into())}
            if action=="claim" {
                if !own.active{return Err("Припасы получает живой активный участник в выживании.".into())}
                if next.reservations.contains_key(&p.uuid){return Err("Предыдущая выдача требует сверки администратором; повтор заблокирован.".into())}
                let units=next.rewards.get(&p.uuid).copied().unwrap_or(0);
                if units==0{return Err("Нет заработанных припасов.".into())}
                drop(rt);
                // Reserve distinct inventory slots conservatively before any mutation.
                // Three empty slots guarantee room for all three different stacks.
                let empty=self.inventory.iter().take(36).filter(|s|s.is_none()).count();
                if empty<3{return Err("Освободи три слота основного инвентаря.".into())}
                let mut rt=lock();let mut next=rt.events.clone();
                *next.rewards.get_mut(&p.uuid).ok_or("Награда недоступна.")?-=1;
                next.reservations.insert(p.uuid,1);commit_events(&mut rt,next)?;drop(rt);
                let delivered=[("minecraft:bread",4),("minecraft:arrow",8),("minecraft:iron_ingot",2)].into_iter()
                    .all(|(item,count)|self.native_give_items(item,count)==count);
                let mut rt=lock();
                if delivered {let mut next=rt.events.clone();next.reservations.remove(&p.uuid);*next.delivered.entry(p.uuid).or_default()+=1;commit_events(&mut rt,next)?;}
                return if delivered{Ok("Получена доля: 4 хлеба, 8 стрел, 2 железных слитка. Остальные доли: /event claim.".into())}else{Err("Выдача прервана. Резерв сохранён для сверки администратором.".into())}
            }
            if rt.state.stopped{return Err("Эксперимент остановлен.".into())}
            let message=match action {
                "join"=>{
                    let side=self.score.board.players_team(&p.name).map(|t|format!("team:{}",t.name)).unwrap_or_else(||format!("player:{:032x}",p.uuid));
                    next.join(own,side)?;"Ты участвуешь. Задача и координаты: /event status. Выход: /event leave.".into()
                },
                "leave"=>{next.leave(p.uuid);"Участие прекращено без штрафа и автоматической выдачи.".into()},
                "interact"=>next.interact(own)?,
                "drop"=>{next.drop_block(own)?;"Блок оставлен на площадке.".into()},
                _=>return Err("/event join | leave | status | interact | drop | claim".into()),
            };
            commit_events(&mut rt,next)?;Ok(message)
        })();
        self.native_feedback(Text::literal(&answer.unwrap_or_else(|e|e)));
    }
}
