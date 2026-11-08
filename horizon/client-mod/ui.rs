// Menu state stays separate from profile masks; no credentials or real-name map.
#[derive(Default)]
struct MenuState {
    snapshot: serde_json::Value,
    actions: Vec<serde_json::Value>,
    selected: usize,
    show_roster: bool,
    show_audit: bool,
    confirm: Option<String>,
    active: bool,
    last_poll: Option<std::time::Instant>,
}
fn menu()->std::sync::MutexGuard<'static,MenuState>{
    static MENU:std::sync::OnceLock<std::sync::Mutex<MenuState>>=std::sync::OnceLock::new();
    MENU.get_or_init(Default::default).lock().unwrap_or_else(|e|e.into_inner())
}
fn menu_timer(seconds:u64)->String {
    if seconds>=3600{format!("{} ч {} мин",seconds/3600,seconds/60%60)}
    else if seconds>=60{format!("{} мин {} с",seconds/60,seconds%60)}else{format!("{seconds} с")}
}
fn menu_lines(text:&str)->Vec<String>{
    let mut lines=Vec::new();let mut line=String::new();
    for word in text.split_whitespace(){
        if !line.is_empty()&&line.chars().count()+word.chars().count()+1>46{lines.push(std::mem::take(&mut line));}
        if !line.is_empty(){line.push(' ');}line.push_str(word);
    }
    if !line.is_empty(){lines.push(line);}lines
}
pub(super) fn menu_clear(){*menu()=MenuState::default();}
fn request(game:&mut super::Game<'_>,value:serde_json::Value){
    if let Ok(bytes)=serde_json::to_vec(&value) {
        if game.native_net_send_to_server("unknownmod:menu",&bytes).is_err(){menu().snapshot=serde_json::json!({"message":"Подключись к серверу UnknownMod 6.1, чтобы открыть меню."});}
    }
}
pub(super) fn menu_key(game:&mut super::Game<'_>,key:&str,pressed:bool){
    let binding=game.native_mod_key("unknownmod_client","menu","key.keyboard.u");
    if !pressed||key!=binding||!game.native_playing(){return}
    game.capture(false);game.open_options(false);
    game.ui.options.show(horizon_client::options_ui::Page::Mods);game.list_native_mods();
    if let Some(i)=game.settings.mods.installed.iter().position(|m|m.id=="unknownmod_client"){
        game.ui.options.show(horizon_client::options_ui::Page::NativeMod(i));game.native_settings_refresh(i);
    }
    request(game,serde_json::json!({"action":"status"}));
}
pub(super) fn menu_frame(game:&mut super::Game<'_>){
    let poll={let mut state=menu();let ready=state.active&&game.screen==super::Screen::Options&&state.last_poll.is_none_or(|t|t.elapsed().as_secs()>=1);
        if ready {state.last_poll=Some(std::time::Instant::now());}ready};
    if poll {request(game,serde_json::json!({"action":"status"}));}
}
pub(super) fn menu_message(game:&mut super::Game<'_>,channel:&str,data:&[u8]){
    if channel!="unknownmod:menu"||data.len()>131072{return}
    let Ok(mut snapshot)=serde_json::from_slice::<serde_json::Value>(data)else{return};
    {let mut state=menu();
        if snapshot["message"].as_str()==Some("")&&state.snapshot.get("status").is_some(){snapshot["message"]=state.snapshot["message"].clone();}
        let selected=state.snapshot["admin"]["players"].as_array().and_then(|players|players.get(state.selected)).and_then(|p|p["id"].as_str()).map(str::to_owned);
        state.snapshot=snapshot;
        if let Some(id)=selected {state.selected=state.snapshot["admin"]["players"].as_array().and_then(|players|players.iter().position(|p|p["id"].as_str()==Some(id.as_str()))).unwrap_or(0);}
        if let Some(id)=state.confirm.as_deref(){
            let still_ghost=state.snapshot["admin"]["players"].as_array().is_some_and(|players|players.iter().any(|p|p["id"].as_str()==Some(id)&&p["ghost"].as_bool()==Some(true)));
            if !still_ghost{state.confirm=None;}
        }
    }
    if let Some(i)=game.settings.mods.installed.iter().position(|m|m.id=="unknownmod_client"){game.native_settings_refresh(i);}
}
pub(super) fn menu_settings(mod_id:&str,buttons:&mut Vec<String>){
    if mod_id!="unknownmod_client"{return}
    use serde_json::json;
    let mut state=menu();state.active=true;
    let data=state.snapshot.clone();let mut actions=Vec::new();
    let mut row=|label:String,action:serde_json::Value|{buttons.push(label);actions.push(action);};
    row(data["status"].as_str().unwrap_or("Ожидание сервера…").into(),json!({"local":"refresh"}));
    row(format!("Бой: {} · Раскрытие: {}",menu_timer(data["combat_seconds"].as_u64().unwrap_or(0)),menu_timer(data["reveal_seconds"].as_u64().unwrap_or(0))),json!({"local":"refresh"}));
    if let Some(message)=data["message"].as_str().filter(|s|!s.is_empty()){for line in menu_lines(message){row(line,json!({"local":"refresh"}));}}
    if let Some(reason)=data["last_decision"].as_str(){
        row("Последний исход:".into(),json!({"local":"refresh"}));
        for line in menu_lines(reason){row(line,json!({"local":"refresh"}));}
    }
    row("Участники: показать / скрыть".into(),json!({"local":"roster"}));
    if state.show_roster {
        for (key,title) in [("recent","За последние 3 дня заходили"),("inactive","Давно не заходили")] {
            row(title.into(),json!({"local":"refresh"}));
            let names=data[key].as_array().cloned().unwrap_or_default();
            if names.is_empty(){row("—".into(),json!({"local":"refresh"}));}
            for chunk in names.chunks(2){row(chunk.iter().filter_map(|n|n.as_str()).collect::<Vec<_>>().join(", "),json!({"local":"refresh"}));}
        }
    }
    if let Some(admin)=data.get("admin") {
        let players=admin["players"].as_array().cloned().unwrap_or_default();
        if !players.is_empty(){
            state.selected%=players.len();let target=&players[state.selected];let id=target["id"].as_str().unwrap_or("");let name=target["name"].as_str().unwrap_or("?");
            row(format!("Игрок: {name} · следующий →"),json!({"local":"next"}));
            row("Раскрыть выбранного игрока".into(),json!({"action":"reveal","target":id}));
            row("Вернуть выбранному анонимность".into(),json!({"action":"hide","target":id}));
            if target["ghost"].as_bool()==Some(true){
                if state.confirm.as_deref()==Some(id){row(format!("Подтвердить возврат {name} в игру"),json!({"action":"restore","target":id}));}
                else{row("Вернуть выбывшего в игру…".into(),json!({"local":"confirm","target":id}));}
            }
        }
        for (field,key,title,choices) in [
            ("duration","duration","Раскрытие, мин",vec![30,60,90,120]),
            ("interval","interval","Интервал, ч",vec![1,2,3,4,6]),
            ("minimum","minimum","Минимум игроков",vec![2,3,4,5,6])]{
            let current=admin[key].as_u64().unwrap_or(0);let next=choices.iter().copied().find(|n|*n>current).unwrap_or(choices[0]);
            row(format!("{title}: {current} → {next}"),json!({"action":"setting","field":field,"value":next}));
        }
        row("Журнал решений: показать / скрыть".into(),json!({"local":"audit"}));
        if state.show_audit {for record in data["audit"].as_array().into_iter().flatten(){
            let victim=record["victim_name"].as_str().unwrap_or("?");let attacker=record["attacker_name"].as_str().unwrap_or("окружение");
            let weapon=record["weapon"].as_str().unwrap_or("без имени");let result=if record["eliminated"].as_bool()==Some(true){"выбыл"}else{"остаётся"};
            if record["cause"].as_str()==Some("restore"){
                row(format!("{victim}: возвращён оператором"),json!({"local":"refresh"}));
            }else{
                row(format!("{victim} ← {attacker} · {result}"),json!({"local":"refresh"}));
                let cause=if record["cause"].as_str()==Some("combat_logout"){"Выход из боя"}else{"Смерть"};
                row(format!("{cause} · Оружие: {}",weapon.chars().take(28).collect::<String>()),json!({"local":"refresh"}));
            }
        }}
    }
    row("Обновить".into(),json!({"local":"refresh"}));
    state.actions=actions;
}
pub(super) fn menu_click(game:&mut super::Game<'_>,mod_id:&str,button:usize){
    if mod_id!="unknownmod_client"{return}
    let action={let mut state=menu();let Some(action)=state.actions.get(button).cloned()else{return};
        state.snapshot["message"]=serde_json::Value::String(String::new());
        match action["local"].as_str(){
            Some("roster")=>state.show_roster=!state.show_roster,
            Some("audit")=>state.show_audit=!state.show_audit,
            Some("next")=>{state.selected+=1;state.confirm=None;},
            Some("confirm")=>state.confirm=action["target"].as_str().map(str::to_owned),
            _=>{}
        }
        if action.get("action").is_some(){state.confirm=None;}action};
    if action.get("action").is_some(){request(game,action)}else if action["local"].as_str()==Some("refresh"){request(game,serde_json::json!({"action":"status"}));}
}
