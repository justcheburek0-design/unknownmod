fn unknownmod_receive_packet(
    game: &mut crate::game::Game<'_>,
    packet: &horizon_protocol::packets::play::Clientbound,
) {
    crate::game::remote::unknownmod_runtime::packet(game, packet);
}
