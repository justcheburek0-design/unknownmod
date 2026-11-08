fn unknownmod_list(mods:&mut Vec<crate::game::native_hooks::NativeMod>){
    mods.push(crate::game::native_hooks::NativeMod{
        id:"unknownmod_client".into(),title:"UnknownMod 6.0.0".into(),
        description:"Anonymous skins, hidden capes and temporary identity reveals. Requires an UnknownMod server.".into(),keys:vec![],
    });
}
