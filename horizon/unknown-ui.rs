// Appended to the server adapter; only authenticated connection IDs reach it.
impl Game<'_> {
    pub(super) fn unknownmod_ui(&mut self, viewer:u128, channel:&str, data:&[u8]) {
        if !shared::enabled() || channel!="unknownmod:menu" || data.len()>2048 {return}
        self.unknownmod_init();
        let Some(permission)=self.lan.as_ref().and_then(|h|h.hub.guests.iter().find(|g|g.uuid==viewer&&g.placed)).map(|g|g.permission) else{return};
        let now=shared::wall_time();let monotonic=shared::combat_time();
        let Ok(request)=serde_json::from_slice::<unknownmod_rules::ui::Request>(data) else{return};
        let request_key=(viewer,matches!(request,unknownmod_rules::ui::Request::Status{..}));
        let mut rt=lock();
        if rt.ui_requests.get(&request_key).is_some_and(|last|monotonic.saturating_sub(*last)<250){return}
        rt.ui_requests.insert(request_key,monotonic);
        let result=(||->Result<String,String>{
            use unknownmod_rules::ui::{Request,Setting};
            if matches!(request,Request::Status{..}){return Ok(String::new())}
            if permission<2{return Err("Это действие доступно только оператору.".into())}
            match request {
                Request::Setting{field,value}=>{
                    let valid=match field {Setting::Duration=>1..=10080,Setting::Interval=>1..=168,Setting::Minimum=>1..=200};
                    if !valid.contains(&value){return Err("Настройка вне допустимого диапазона.".into())}
                    let interval=matches!(field,Setting::Interval);
                    match field {Setting::Duration=>rt.config.reveal_duration_minutes=value,Setting::Interval=>rt.config.reveal_interval_hours=value,Setting::Minimum=>rt.config.minimum_players=value as usize}
                    let config=rt.config.clone();rt.state.configure(&config,now,interval);
                    write_json(rt.root.join("config.json"),&config)?;rt.changed();
                    Ok("Настройка сохранена. Текущие раскрытия продолжаются.".into())
                },
                other=>{
                    let target=match &other {Request::Reveal{target}|Request::Hide{target}|Request::Restore{target}=>target,_=>unreachable!()};
                    let id=u128::from_str_radix(target,16).map_err(|_|"Неверный адресат")?;
                    if !rt.state.players.contains_key(&id){return Err("Игрок не найден.".into())}
                    let message=match other {
                        Request::Reveal{..}=>{let config=rt.config.clone();if !rt.state.reveal(id,&config,now){return Err("Игрок недоступен или уже раскрыт.".into())}let name=rt.state.players[&id].name.clone();rt.messages.push(format!("Раскрыт {name}. Для выбывания нужно оружие с его ником."));"Игрок раскрыт."},
                        Request::Hide{..}=>{rt.state.reveals.remove(&id);"Анонимность восстановлена."},
                        Request::Restore{..}=>{let mode=rt.state.restore(id).ok_or("Игрок не выбывал.")?;rt.modes.push((id,mode));"Игрок возвращён в игру. Восстановление записано в журнал."},
                        _=>unreachable!()
                    };rt.changed();Ok(message.into())
                }
            }
        })();
        let mut snapshot=serde_json::to_value(unknownmod_rules::ui::snapshot(&rt.state,&rt.config,viewer,permission>=2,now,monotonic)).unwrap();
        snapshot["message"]=serde_json::Value::String(result.unwrap_or_else(|e|e));
        if permission>=2 {
            use std::io::{Read,Seek,SeekFrom};
            let mut text=String::new();
            if let Ok(mut file)=std::fs::File::open(rt.root.join("audit.jsonl")) {
                let size=file.metadata().map_or(0,|m|m.len());let _=file.seek(SeekFrom::Start(size.saturating_sub(65536)));
                let mut bytes=Vec::new();let _=file.read_to_end(&mut bytes);text=String::from_utf8_lossy(&bytes).into_owned();
            }
            let records=text.lines().rev().filter_map(|line|serde_json::from_str::<unknownmod_rules::audit::Decision>(line).ok()).take(12).collect::<Vec<_>>();
            // serde_json::Value cannot hold arbitrary u128 numbers. UUIDs are
            // strings on the wire; never panic while an operator opens a log.
            snapshot["audit"]=serde_json::Value::Array(records.iter().map(|e|serde_json::json!({
                "time":e.time,"cause":e.cause,"victim":format!("{:032x}",e.victim),
                "victim_name":e.victim_name,"attacker":e.attacker.map(|id|format!("{id:032x}")),
                "attacker_name":e.attacker_name,"weapon":e.weapon,"matched":e.matched,"eliminated":e.eliminated
            })).collect());
        }
        drop(rt);
        loop {
            let Ok(bytes)=serde_json::to_vec(&snapshot)else{return};
            if bytes.len()<=29_000 {let _=self.native_net_send_to_player(viewer,"unknownmod:menu",&bytes);break;}
            let mut shortened=false;
            for path in ["/audit","/inactive","/recent","/admin/players"] {
                if let Some(items)=snapshot.pointer_mut(path).and_then(serde_json::Value::as_array_mut) {
                    if items.pop().is_some(){shortened=true;break;}
                }
            }
            if !shortened {return}
            snapshot["message"]=serde_json::Value::String("Показана часть списка. Полный журнал сохранён на сервере.".into());
        }
    }
}
