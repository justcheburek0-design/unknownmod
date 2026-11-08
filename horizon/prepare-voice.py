"""Stage the pinned MIT voice bundles and adapt the client to Horizon's mod network.

Run after fetching the sources recorded in sources.json. No user installation is modified.
The existing raw Simple Voice Chat transport remains available for Java servers.
"""
from pathlib import Path
import json
import shutil

PROJECT = Path(__file__).resolve().parent.parent
BUILD = PROJECT / "build" / "horizon"
GAME = "66f3981f522b24c250b21523a70080965b940424"
MODS = "d2de4c18de0cfc4c1e4f193f2ec09b47adb0d236"
CLIENT = "3dd997f70d4d3ec547c69bde9416a503783d6357"


def main():
    destination = BUILD / "voice-bundles"
    client = BUILD / "voice-client-source" / f"horizon-voice-{CLIENT}"
    server = BUILD / "mods-source" / f"horizon-mods-{MODS}" / "native" / "voicechat-server"
    for name, source in (("horizon_voice", client / "mod"),
                         ("voicechat_server", server / "mixins" / "voicechat_server")):
        target = destination / name
        shutil.copytree(source, target, dirs_exist_ok=True)
        manifest = target / "horizon-mixin.toml"
        import re
        text = re.sub(r'horizon_revision = "[0-9a-f]{40}"', f'horizon_revision = "{GAME}"',
                      manifest.read_text(encoding="utf-8"))
        manifest.write_text(text, encoding="utf-8")
    target = destination / "horizon_voice"
    shutil.copytree(client / "engine", target / "engine", dirs_exist_ok=True,
                    ignore=shutil.ignore_patterns("target", "Cargo.lock"))
    shutil.copyfile(client / "LICENSE", target / "LICENSE")
    shutil.copyfile(server / "LICENSE", destination / "voicechat_server" / "LICENSE")
    glue = destination / "voicechat_server" / "glue.rs"
    server_text = glue.read_text(encoding="utf-8")
    server_text = server_text.replace(".into_iter()\n        .map(|p| Player {",
                                      ".into_iter()\n        .filter(|p| p.game_mode != 3)\n        .map(|p| Player {")
    server_text = server_text.replace("name: p.name,", 'name: "JustPlayer".to_owned(),')
    glue.write_text(server_text, encoding="utf-8")
    runtime = target / "runtime.rs"
    text = runtime.read_text(encoding="utf-8")
    original = '''fn flush(game: &Game<'_>, st: &mut State) {
    for (channel, data) in st.voice.take_outgoing() {
        game.send(S::CustomPayload(CustomPayload { channel, data }));
    }
}'''
    replacement = '''// UnknownMod adapter: the native relay receives authenticated Horizon mod messages.
pub(super) fn on_horizon_payload(channel: &str, data: &[u8]) {
    if channel.starts_with("voicechat:") {
        state().voice.payload(channel, data);
    }
}

fn flush(game: &mut Game<'_>, st: &mut State) {
    for (channel, data) in st.voice.take_outgoing() {
        if channel.starts_with("voicechat:") && game.remote.as_ref().is_some_and(|remote| remote.mod_net) {
            if let Err(error) = game.native_net_send_to_server(&channel, &data) {
                log::debug!("Voice: native message not sent: {error}");
            }
        } else {
            game.send(S::CustomPayload(CustomPayload { channel, data }));
        }
    }
}'''
    if original not in text:
        raise RuntimeError("Pinned voice client changed: flush adapter does not match")
    runtime.write_text(text.replace(original, replacement), encoding="utf-8")
    (target / "horizon_net.rs").write_text('''fn unknown_voice_net(channel: &str, data: &[u8]) {
    crate::game::horizon_voice::on_horizon_payload(channel, data);
}
''', encoding="utf-8")
    manifest = target / "horizon-mixin.toml"
    with manifest.open("a", encoding="utf-8") as out:
        out.write('''
# UnknownMod adapter: responses from the native Horizon voice relay.
[[injection]]
id = "horizon_net"
kind = "HEAD"
target = "horizon_client::game::native_hooks::Game<'_>::native_net_from_server"
helper = "horizon_net.rs"
bindings = [
  { name = "channel", ty = "&str", value = "channel" },
  { name = "data", ty = "&[u8]", value = "data" },
]
''')
    print(json.dumps({"bundles": str(destination), "horizon": GAME}))


if __name__ == "__main__":
    main()
