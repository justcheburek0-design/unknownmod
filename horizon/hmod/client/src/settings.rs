fn settings(game:&mut crate::game::Game<'_>,i:usize)->core::ops::ControlFlow<()> {
    if game.settings.mods.installed.get(i).is_some_and(|m|m.id=="unknownmod_client") {
        game.mod_screen_open("unknownmod_client");unknownmod_client::menu::refresh();
        return core::ops::ControlFlow::Break(());
    }
    core::ops::ControlFlow::Continue(())
}
