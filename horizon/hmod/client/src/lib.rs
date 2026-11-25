//! UnknownMod 6.2 native client. Rules and authority remain exclusively on the server.
use mod_api::*;
use std::sync::Mutex;
pub mod masks;
pub mod menu;
pub const ANONYMOUS_RGBA: &[u8; 16384] = include!("anonymous_rgba.rs");
const ID: &str = "unknownmod_client";
#[derive(Default)]
struct View { rows: Vec<String>, scroll: usize, focus: usize, width: f32, height: f32 }
#[derive(Default)]
struct UnknownMod { view: Mutex<View> }
impl UnknownMod {
    fn activate(&self, index: usize) {
        menu::menu_click(ID,index);
        let mut rows=Vec::new();menu::menu_settings(ID,&mut rows);
        let mut view=self.view.lock().unwrap_or_else(|e|e.into_inner());view.rows=rows;
        view.focus=view.focus.min(view.rows.len().saturating_sub(1));
    }
}
#[mod_api::horizon_mod]
impl Hooks for UnknownMod {
    fn register(&self, registrar: &mut dyn Registrar, _: &RegistrationContext) {
        registrar.register_key_mapping(KeyRegistration::new("menu","key.keyboard.u").label("Меню UnknownMod").context(KeyConflictContext::InGame));
    }
    fn client_tick_pre(&self, host: &mut dyn ClientHost, _: &mut ClientTickPreEvent) {
        if host.playing() && host.consume_click(ID,"menu") {
            if host.mod_screen_open(ID) { menu::refresh(); }
        }
        let active=host.mod_screen()==Some(ID);menu::poll(active);
        if active {
            let mut rows=Vec::new();menu::menu_settings(ID,&mut rows);
            self.view.lock().unwrap_or_else(|e|e.into_inner()).rows=rows;
        }
    }
    fn render_name_tag(&self, _: &dyn ClientHost, event: &mut RenderNameTagEvent<'_>) {
        if event.kind=="minecraft:player" && masks::hidden_entity(event.entity_id) {event.visible=false;}
    }
    fn screen_render_post(&self, host: &dyn ClientHost, event: &mut ScreenRenderPostEvent<'_>) {
        if host.mod_screen()!=Some(ID){return}
        let [width,height]=event.draw.size();
        let mut view=self.view.lock().unwrap_or_else(|e|e.into_inner());view.width=width;view.height=height;
        let count=((height-64.0)/22.0).floor().max(1.0) as usize;
        view.scroll=view.scroll.min(view.rows.len().saturating_sub(count));
        event.draw.fill([0.0,0.0,width,height],0xe0101018);
        event.draw.text("UnknownMod 6.2.0",[12.0,12.0],0xffffffff,true);
        event.draw.text("Esc: закрыть · ↑↓: выбор · Enter: действие · Колесо: прокрутка",[12.0,height-18.0],0xffbbbbbb,false);
        for (index,label) in view.rows.iter().enumerate().skip(view.scroll).take(count) {
            let y=36.0+(index-view.scroll) as f32*22.0;
            event.draw.fill([10.0,y,width-20.0,20.0],if index==view.focus{0xff405070}else{0xff282832});
            event.draw.text(label,[16.0,y+6.0],0xffffffff,true);
        }
    }
    fn screen_mouse_button_pressed_pre(&self, host: &dyn ClientHost,event:&mut ScreenMouseButtonPressedPreEvent<'_>)->Flow {
        if host.mod_screen()!=Some(ID)||event.button!=0{return Flow::Continue}
        let index={let view=self.view.lock().unwrap_or_else(|e|e.into_inner());
            let [x,y]=event.screen.mouse;
            if x<10.0||x>view.width-10.0||y<36.0||y>view.height-28.0{return Flow::Continue}
            view.scroll+((y-36.0)/22.0) as usize};
        self.activate(index);Flow::Cancel
    }
    fn screen_mouse_scrolled_pre(&self,host:&dyn ClientHost,event:&mut ScreenMouseScrolledPreEvent<'_>)->Flow {
        if host.mod_screen()!=Some(ID){return Flow::Continue}
        let mut view=self.view.lock().unwrap_or_else(|e|e.into_inner());
        if event.scroll[1]>0.0 {view.scroll=view.scroll.saturating_sub(3);}else{view.scroll=view.scroll.saturating_add(3).min(view.rows.len().saturating_sub(1));}
        Flow::Cancel
    }
    fn screen_key_pressed_pre(&self,host:&dyn ClientHost,event:&mut ScreenKeyPressedPreEvent<'_>)->Flow {
        if host.mod_screen()!=Some(ID){return Flow::Continue}
        // Escape is handled by the engine for a mod screen.
        let index={let mut view=self.view.lock().unwrap_or_else(|e|e.into_inner());
            match event.key {
                "key.keyboard.down"|"key.keyboard.tab"=>view.focus=(view.focus+1).min(view.rows.len().saturating_sub(1)),
                "key.keyboard.up"=>view.focus=view.focus.saturating_sub(1),
                "key.keyboard.enter"=>{let index=view.focus;drop(view);self.activate(index);return Flow::Cancel},
                _=>return Flow::Continue,
            }
            view.focus};
        let mut view=self.view.lock().unwrap_or_else(|e|e.into_inner());
        let count=((view.height-64.0)/22.0).floor().max(1.0) as usize;
        if index<view.scroll{view.scroll=index;}else if index>=view.scroll+count{view.scroll=index+1-count;}
        Flow::Cancel
    }
}
