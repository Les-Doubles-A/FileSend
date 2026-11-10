//! Outil de developpement : `receive <dossier> [port]` (accepte tout) et
//! `send <hote:port> <chemin>...`. Remplace l'interface pendant la tranche 1.

use filesend_core::client::{send, SendOptions};
use filesend_core::discovery::{Discovery, DiscoveryConfig};
use filesend_core::events::Event;
use filesend_core::identity::Identity;
use filesend_core::protocol::{DeviceInfo, DEFAULT_PORT};
use filesend_core::server::{self, ServerConfig};
use std::path::PathBuf;
use std::sync::Arc;

fn usage() -> ! {
    eprintln!("usage:\n  filesend-dev receive <dossier> [port]\n  filesend-dev send <hote:port> <chemin>...");
    std::process::exit(2);
}

#[tokio::main]
async fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.first().map(String::as_str) {
        Some("receive") if args.len() >= 2 => {
            let dir = PathBuf::from(&args[1]);
            let port: u16 = args.get(2).and_then(|p| p.parse().ok()).unwrap_or(DEFAULT_PORT);
            let id = Identity::load_or_create(&dir.join(".identity")).unwrap_or_else(|e| {
                eprintln!("identite: {e}");
                std::process::exit(1)
            });
            let info = DeviceInfo {
                alias: "dev-receiver".into(),
                fingerprint: id.fingerprint.clone(),
                device_type: "desktop".into(),
            };
            let mut cfg = ServerConfig::new(dir, info);
            cfg.bind = ([0, 0, 0, 0], port).into();
            let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel();
            let h = server::start(cfg, &id, Arc::new(|_| Box::pin(async { true })), Some(tx))
                .await
                .unwrap_or_else(|e| {
                    eprintln!("demarrage: {e}");
                    std::process::exit(1)
                });
            println!("ecoute sur {}", h.addr);
            while let Some(ev) = rx.recv().await {
                match ev {
                    Event::Completed { paths, .. } => println!("recu: {paths:?}"),
                    Event::Failed { reason, .. } => println!("{}", reason.message()),
                    _ => {}
                }
            }
        }
        Some("send") if args.len() >= 3 => {
            let target = args[1].parse().unwrap_or_else(|_| usage());
            let paths: Vec<PathBuf> = args[2..].iter().map(PathBuf::from).collect();
            let info = DeviceInfo {
                alias: "dev-sender".into(),
                fingerprint: String::new(),
                device_type: "desktop".into(),
            };
            let mut o = SendOptions::new(info);
            o.hash_up_to = 0;
            match send(target, &paths, &o, None, None).await {
                Ok(()) => println!("envoye"),
                Err(e) => {
                    eprintln!("{e}");
                    std::process::exit(1);
                }
            }
        }
        Some("discover") => {
            let secs: u64 = args.get(1).and_then(|s| s.parse().ok()).unwrap_or(30);
            let info = DeviceInfo {
                alias: "dev-discover".into(),
                fingerprint: "dev-discover".into(),
                device_type: "desktop".into(),
            };
            let d = Discovery::start(DiscoveryConfig::new(info, DEFAULT_PORT)).await.unwrap_or_else(|e| {
                eprintln!("decouverte: {e}");
                std::process::exit(1)
            });
            let mut rx = d.subscribe();
            let t0 = std::time::Instant::now();
            println!("decouverte demarree (multicast + sondage), {secs} s");
            while t0.elapsed().as_secs() < secs {
                if tokio::time::timeout(std::time::Duration::from_millis(500), rx.changed()).await.is_ok() {
                    let list = rx.borrow_and_update().clone();
                    println!(
                        "[{:>4.1} s] {} appareil(s): {}",
                        t0.elapsed().as_secs_f32(),
                        list.len(),
                        list.iter().map(|x| format!("{} {} via {:?}", x.info.alias, x.addr, x.via)).collect::<Vec<_>>().join(" ; ")
                    );
                }
            }
        }
        _ => usage(),
    }
}
