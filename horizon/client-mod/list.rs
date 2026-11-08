fn unknownmod_list(mods:&mut Vec<crate::game::native_hooks::NativeMod>){
    mods.push(crate::game::native_hooks::NativeMod{
        id:"unknownmod_client".into(),title:"UnknownMod 6.1.0".into(),
        description:"Анонимность, статус, участники и меню оператора. Открыть: U.".into(),
        keys:vec![["menu".into(),"Меню UnknownMod".into(),"key.keyboard.u".into()]],
    });
}
