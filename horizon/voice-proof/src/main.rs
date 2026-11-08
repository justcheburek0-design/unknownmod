//! Receives session secrets over stdin, never logs them. Checks the actual voice
//! client's UDP/encryption/Opus path through a running Horizon voice server.
use horizon_voice_engine::{Version, FRAME_SIZE, SAMPLE_RATE};
use horizon_voice_engine::{client::VoiceClient, codec::{Encoder, Decoder}, payload::{Codec, SecretInfo}, session::Event};
use std::{io::{self, BufRead}, thread, time::{Duration, Instant}};

fn read_secret(line: String) -> SecretInfo {
    let bytes: Vec<u8> = line.trim().as_bytes().chunks_exact(2)
        .map(|c| u8::from_str_radix(std::str::from_utf8(c).unwrap(), 16).unwrap()).collect();
    SecretInfo::decode(&bytes, Version::V20).expect("valid game-delivered voice handshake")
}

fn collect(client: &VoiceClient, decoder: &mut Decoder, expected_sender: u128, pcm: &mut Vec<f32>) {
    for event in client.poll() {
        match event {
            Event::Sound { sound, .. } => {
                assert_eq!(sound.sender, expected_sender, "voice must belong to the other game session");
                pcm.extend_from_slice(decoder.decode(&sound.data).expect("valid Opus frame"));
            }
            Event::TimedOut => panic!("voice session timed out"),
            _ => {}
        }
    }
}

fn power_at(samples: &[f32], frequency: f64) -> f64 {
    let (mut real, mut imag) = (0.0, 0.0);
    for (i, &sample) in samples.iter().enumerate() {
        let angle = std::f64::consts::TAU * frequency * i as f64 / SAMPLE_RATE as f64;
        real += sample as f64 * angle.cos();
        imag += sample as f64 * angle.sin();
    }
    real * real + imag * imag
}

fn one_client(info: SecretInfo, send_tone: bool) {
    let client = VoiceClient::connect(&info, "127.0.0.1").unwrap();
    let deadline = Instant::now() + Duration::from_secs(8);
    loop {
        if client.poll().iter().any(|event| *event == Event::Connected) { break; }
        assert!(Instant::now() < deadline, "voice connection must authenticate");
        thread::sleep(Duration::from_millis(10));
    }
    println!("{{\"voice_connected\":true,\"mode\":\"{}\"}}", if send_tone { "tone" } else { "listen" });
    let mut encoder = Encoder::new(Codec::Voip).unwrap();
    let mut decoder = Decoder::new().unwrap();
    let mut samples = 0usize;
    let mut peak_rms = 0.0f64;
    for frame in 0..3000 {
        if send_tone {
            let pcm: Vec<i16> = (0..FRAME_SIZE).map(|n| {
                let phase = (frame * FRAME_SIZE + n) as f64 / SAMPLE_RATE as f64;
                (0.10 * 32767.0 * (std::f64::consts::TAU * 440.0 * phase).sin()) as i16
            }).collect();
            client.send_mic(encoder.encode(&pcm).unwrap(), false);
        }
        for event in client.poll() {
            if event == Event::TimedOut { panic!("voice session timed out"); }
            if let Event::Sound { sound, .. } = event {
                assert_ne!(sound.sender, info.player, "microphone proof must come from another player");
                let pcm = decoder.decode(&sound.data).unwrap();
                samples += pcm.len();
                let rms = (pcm.iter().map(|&v| (v as f64).powi(2)).sum::<f64>() / pcm.len() as f64).sqrt();
                peak_rms = peak_rms.max(rms);
            }
        }
        if !send_tone && samples > SAMPLE_RATE as usize && peak_rms > 0.003 { break; }
        thread::sleep(Duration::from_millis(20));
    }
    if !send_tone { assert!(samples > SAMPLE_RATE as usize && peak_rms > 0.003, "no audible microphone stream received"); }
    if send_tone { println!("{{\"tone_stream\":\"finished\"}}"); }
    else { println!("{{\"microphone_input_proof\":\"passed\",\"received_samples\":{samples},\"peak_rms\":{peak_rms}}}"); }
}

fn main() {
    if std::env::args().any(|arg| arg == "--devices") {
        println!("input_devices={:?}", horizon_voice_engine::audio::input_device_names());
        println!("output_devices={:?}", horizon_voice_engine::audio::output_device_names());
        return;
    }
    let relay = std::env::args().any(|arg| arg == "--standalone").then(|| {
        let server = voicechat_server_relay::Server::start(voicechat_server_relay::Config {
            bind: "127.0.0.1".into(), port: 0, ..Default::default()
        }).unwrap();
        server.tick([1, 2].map(|uuid| voicechat_server_relay::Player {
            uuid, name: "JustPlayer".into(), dimension: "minecraft:overworld".into(), position: [uuid as f64, 64.0, 0.0]
        }).to_vec());
        server
    });
    let mut input = io::stdin().lock().lines();
    let mut info = |who| {
        if let Some(server) = &relay {
            server.message(who, voicechat_server_relay::proto::REQUEST_SECRET, &20i32.to_be_bytes());
            let (_, _, bytes) = server.outgoing(24).into_iter()
                .find(|(uuid, channel, _)| *uuid == who && channel == voicechat_server_relay::proto::SECRET).unwrap();
            SecretInfo::decode(&bytes, Version::V20).unwrap()
        } else { read_secret(input.next().expect("game handshake").unwrap()) }
    };
    let a = info(1);
    if std::env::args().any(|arg| arg == "--tone" || arg == "--listen") {
        one_client(a, std::env::args().any(|arg| arg == "--tone"));
        return;
    }
    let b = info(2);
    let clients = [VoiceClient::connect(&a, "127.0.0.1").unwrap(), VoiceClient::connect(&b, "127.0.0.1").unwrap()];
    let deadline = Instant::now() + Duration::from_secs(8);
    let mut connected = [false; 2];
    while !connected.iter().all(|&c| c) && Instant::now() < deadline {
        for (i, client) in clients.iter().enumerate() {
            for event in client.poll() { if event == Event::Connected { connected[i] = true; } }
        }
        thread::sleep(Duration::from_millis(10));
    }
    assert_eq!(connected, [true, true], "both voice handshakes must complete");
    let mut encoders = [Encoder::new(Codec::Voip).unwrap(), Encoder::new(Codec::Voip).unwrap()];
    let mut decoders = [Decoder::new().unwrap(), Decoder::new().unwrap()];
    let mut received = [Vec::new(), Vec::new()];
    for frame in 0..80 {
        for i in 0..2 {
            let frequency = [440.0, 660.0][i];
            let pcm: Vec<i16> = (0..FRAME_SIZE).map(|n| {
                let phase = (frame * FRAME_SIZE + n) as f64 / SAMPLE_RATE as f64;
                (0.35 * 32767.0 * (std::f64::consts::TAU * frequency * phase).sin()) as i16
            }).collect();
            clients[i].send_mic(encoders[i].encode(&pcm).unwrap(), false);
        }
        thread::sleep(Duration::from_millis(20));
        collect(&clients[0], &mut decoders[0], b.player, &mut received[0]);
        collect(&clients[1], &mut decoders[1], a.player, &mut received[1]);
    }
    thread::sleep(Duration::from_millis(200));
    collect(&clients[0], &mut decoders[0], b.player, &mut received[0]);
    collect(&clients[1], &mut decoders[1], a.player, &mut received[1]);
    for i in 0..2 {
        assert!(received[i].len() > 30 * FRAME_SIZE, "enough audio must arrive in both directions");
        let expected = [660.0, 440.0][i];
        let wrong = [440.0, 660.0][i];
        assert!(power_at(&received[i], expected) > 10.0 * power_at(&received[i], wrong),
                "decoded audio must contain the other client's tone, not local loopback");
    }
    println!("{{\"voice_proof\":\"passed\",\"samples_at_a\":{},\"samples_at_b\":{},\"transport\":\"AES-GCM UDP\",\"codec\":\"Opus\"}}", received[0].len(), received[1].len());
}
