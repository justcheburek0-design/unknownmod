use unknownmod_rules::{Config,State,shared};
#[test]
fn random_aliases_are_unique_stable_and_independent_of_reveal() {
    unsafe{std::env::set_var("UNKNOWNMOD_ENABLED","1");}
    let mut state=State::default();state.join(1,"Alice",0,1000);state.join(2,"Bob",0,1000);
    shared::publish(&state,&Config::default());
    let alice=shared::public_id(1);let bob=shared::public_id(2);
    assert_ne!(alice,1);assert_ne!(bob,2);assert_ne!(alice,bob);
    assert_eq!((alice>>76)&15,4);assert_eq!((alice>>62)&3,2);
    assert_eq!(shared::real_id(alice),Some(1));assert_eq!(shared::real_id(bob),Some(2));assert_eq!(shared::real_id(1),None);
    assert_eq!(shared::wire_id(1,1),1);assert_eq!(shared::wire_id(1,2),alice);
    state.leave(1,1100,100,true);state.join(1,"Alice",0,1200);
    assert!(state.reveal(1,&Config::default(),1200));shared::publish(&state,&Config::default());
    assert_eq!(shared::public_id(1),alice);
    assert!(!shared::hidden(alice));assert_eq!(shared::player_wire_id(alice,2),alice);
    state.reveals.clear();shared::publish(&state,&Config::default());assert_eq!(shared::public_id(1),alice);
    let mut ids=std::collections::BTreeSet::new();
    for real in 1..=1000{let id=shared::public_id(real);assert!(ids.insert(id));assert_ne!(id,real);assert_eq!(shared::real_id(id),Some(real));}
}
