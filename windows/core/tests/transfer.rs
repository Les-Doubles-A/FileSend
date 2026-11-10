use filesend_core::client::{send, SendError, SendOptions};
use filesend_core::events::{Event, FailReason};
use filesend_core::identity::Identity;
use filesend_core::protocol::*;
use filesend_core::server::{self, Decider, IncomingRequest, ServerConfig, ServerHandle};
use sha2::{Digest, Sha256};
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::mpsc::{unbounded_channel, UnboundedReceiver};
use tokio_util::sync::CancellationToken;

fn info(alias: &str) -> DeviceInfo {
    DeviceInfo { alias: alias.into(), fingerprint: "x".into(), device_type: "desktop".into() }
}

fn accept_all() -> Decider {
    Arc::new(|_| Box::pin(async { true }))
}

struct Rx {
    handle: ServerHandle,
    dir: tempfile::TempDir,
    events: UnboundedReceiver<Event>,
}

impl Rx {
    fn root(&self) -> &Path {
        self.dir.path()
    }
    fn addr(&self) -> SocketAddr {
        self.handle.addr
    }
}

async fn start_with(decider: Decider, tweak: impl FnOnce(&mut ServerConfig)) -> Rx {
    let dir = tempfile::tempdir().unwrap();
    let id = Identity::generate().unwrap();
    let mut cfg = ServerConfig::new(dir.path().to_path_buf(), info("recv"));
    cfg.bind = "127.0.0.1:0".parse().unwrap();
    tweak(&mut cfg);
    let (tx, events) = unbounded_channel();
    let handle = server::start(cfg, &id, decider, Some(tx)).await.unwrap();
    Rx { handle, dir, events }
}

async fn start(decider: Decider) -> Rx {
    start_with(decider, |_| {}).await
}

fn opts() -> SendOptions {
    SendOptions::new(info("sender"))
}

fn write_data(path: &Path, size: usize, seed: u8) {
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    let data: Vec<u8> = (0..size).map(|i| (i as u8).wrapping_mul(31).wrapping_add(seed)).collect();
    std::fs::write(path, data).unwrap();
}

fn sha(path: &Path) -> String {
    hex::encode(Sha256::digest(std::fs::read(path).unwrap()))
}

fn raw_client() -> reqwest::Client {
    server::install_crypto_provider();
    reqwest::Client::builder().tls_danger_accept_invalid_certs(true).build().unwrap()
}

fn url(addr: SocketAddr, route: &str) -> String {
    format!("https://{addr}{API_PREFIX}{route}")
}

async fn next_event(rx: &mut UnboundedReceiver<Event>, secs: u64, f: impl Fn(&Event) -> bool) -> Event {
    let end = Instant::now() + Duration::from_secs(secs);
    loop {
        let left = end.saturating_duration_since(Instant::now());
        match tokio::time::timeout(left, rx.recv()).await {
            Ok(Some(e)) if f(&e) => return e,
            Ok(Some(_)) => continue,
            _ => panic!("evenement attendu non recu"),
        }
    }
}

fn leftovers(root: &Path) -> Vec<PathBuf> {
    fn walk(d: &Path, out: &mut Vec<PathBuf>) {
        for e in std::fs::read_dir(d).unwrap() {
            let p = e.unwrap().path();
            if p.is_dir() {
                walk(&p, out);
            } else {
                out.push(p);
            }
        }
    }
    let mut v = Vec::new();
    walk(root, &mut v);
    v
}

// AC-F2-1, AC-F3-4 (enregistrement), AC-F4-2 (evenement Completed)
#[tokio::test]
async fn file_10mb_same_name_and_sha() {
    let mut rx = start(accept_all()).await;
    let src = tempfile::tempdir().unwrap();
    let f = src.path().join("rapport.bin");
    write_data(&f, 10 * 1024 * 1024, 7);
    send(rx.addr(), &[f.clone()], &opts(), None, None).await.unwrap();
    let got = rx.root().join("rapport.bin");
    assert_eq!(sha(&got), sha(&f));
    let ev = next_event(&mut rx.events, 5, |e| matches!(e, Event::Completed { .. })).await;
    if let Event::Completed { from, paths, .. } = ev {
        assert_eq!(from.alias, "sender");
        assert_eq!(paths, vec![rx.root().join("rapport.bin")]);
    }
    assert!(leftovers(rx.root()).iter().all(|p| p.extension().map(|e| e != "part").unwrap_or(true)));
}

// AC-F2-2
#[tokio::test]
async fn folder_tree_preserved() {
    let rx = start(accept_all()).await;
    let src = tempfile::tempdir().unwrap();
    write_data(&src.path().join("photos/a.jpg"), 5000, 1);
    write_data(&src.path().join("photos/v/b.jpg"), 70_000, 2);
    send(rx.addr(), &[src.path().join("photos")], &opts(), None, None).await.unwrap();
    for rel in ["photos/a.jpg", "photos/v/b.jpg"] {
        assert_eq!(sha(&rx.root().join(rel)), sha(&src.path().join(rel)), "{rel}");
    }
}

// AC-F2-3
#[tokio::test]
async fn collisions_never_overwrite() {
    let rx = start(accept_all()).await;
    std::fs::write(rx.root().join("a.txt"), "original").unwrap();
    std::fs::create_dir(rx.root().join("photos")).unwrap();
    std::fs::write(rx.root().join("photos/old.txt"), "old").unwrap();
    let src = tempfile::tempdir().unwrap();
    write_data(&src.path().join("a.txt"), 100, 3);
    write_data(&src.path().join("photos/a.jpg"), 100, 4);
    send(rx.addr(), &[src.path().join("a.txt"), src.path().join("photos")], &opts(), None, None)
        .await
        .unwrap();
    assert_eq!(std::fs::read_to_string(rx.root().join("a.txt")).unwrap(), "original");
    assert_eq!(sha(&rx.root().join("a (1).txt")), sha(&src.path().join("a.txt")));
    assert!(rx.root().join("photos/old.txt").exists());
    assert_eq!(sha(&rx.root().join("photos (1)/a.jpg")), sha(&src.path().join("photos/a.jpg")));
    assert!(!rx.root().join("photos/a.jpg").exists());
}

// AC-F2-4 : chemins pieges refuses avant toute question a l'utilisateur
#[tokio::test]
async fn trap_paths_rejected_without_prompt_or_write() {
    let asked = Arc::new(AtomicUsize::new(0));
    let a2 = asked.clone();
    let decider: Decider = Arc::new(move |_| {
        a2.fetch_add(1, Ordering::SeqCst);
        Box::pin(async { true })
    });
    let rx = start(decider).await;
    let http = raw_client();
    for bad in [
        "../evil.txt", "a/../../evil.txt", "/abs.txt", "C:/evil.txt", "a\\b.txt", "CON", "nul.txt",
        "ads:stream", "dir/", "x?y",
    ] {
        let body = serde_json::json!({
            "info": info("evil"),
            "files": { "1": { "id": "1", "relativePath": bad, "size": 1 } }
        });
        let r = http.post(url(rx.addr(), "/prepare-upload")).json(&body).send().await.unwrap();
        assert_eq!(r.status().as_u16(), 400, "chemin {bad:?}");
    }
    // doublon insensible a la casse
    let body = serde_json::json!({
        "info": info("evil"),
        "files": {
            "1": { "id": "1", "relativePath": "A.txt", "size": 1 },
            "2": { "id": "2", "relativePath": "a.TXT", "size": 1 } }
    });
    let r = http.post(url(rx.addr(), "/prepare-upload")).json(&body).send().await.unwrap();
    assert_eq!(r.status().as_u16(), 400);
    assert_eq!(asked.load(Ordering::SeqCst), 0);
    assert!(leftovers(rx.root()).is_empty());
    assert!(!rx.root().parent().unwrap().join("evil.txt").exists());
}

// AC-F3-1 : sans ecriture avant accord, avec expediteur, nombre et taille
#[tokio::test]
async fn request_shows_sender_count_size_and_nothing_written_before_accept() {
    let seen: Arc<Mutex<Option<(IncomingRequest, usize)>>> = Arc::new(Mutex::new(None));
    let s2 = seen.clone();
    let root: Arc<Mutex<Option<PathBuf>>> = Arc::new(Mutex::new(None));
    let r2 = root.clone();
    let decider: Decider = Arc::new(move |req| {
        let n = r2.lock().unwrap().as_ref().map(|p| leftovers(p).len()).unwrap_or(99);
        *s2.lock().unwrap() = Some((req, n));
        Box::pin(async { true })
    });
    let rx = start(decider).await;
    *root.lock().unwrap() = Some(rx.root().to_path_buf());
    let src = tempfile::tempdir().unwrap();
    write_data(&src.path().join("d/a"), 1000, 1);
    write_data(&src.path().join("d/b"), 2000, 2);
    send(rx.addr(), &[src.path().join("d")], &opts(), None, None).await.unwrap();
    let (req, files_on_disk) = seen.lock().unwrap().take().unwrap();
    assert_eq!(req.from.alias, "sender");
    assert_eq!(req.count, 2);
    assert_eq!(req.total, 3000);
    assert_eq!(files_on_disk, 0);
}

// AC-F3-2
#[tokio::test]
async fn refusal_saves_nothing_and_sender_sees_refused() {
    let rx = start(Arc::new(|_| Box::pin(async { false }))).await;
    let src = tempfile::tempdir().unwrap();
    write_data(&src.path().join("a.txt"), 100, 1);
    let r = send(rx.addr(), &[src.path().join("a.txt")], &opts(), None, None).await;
    assert_eq!(r, Err(SendError::Failed(FailReason::Refused)));
    assert_eq!(r.unwrap_err().to_string(), "Échec : Refusé");
    assert!(leftovers(rx.root()).is_empty());
}

// AC-F3-3 (delai ramene a 300 ms pour le test)
#[tokio::test]
async fn no_answer_counts_as_refusal() {
    let rx = start_with(Arc::new(|_| Box::pin(std::future::pending())), |c| {
        c.decision_timeout = Duration::from_millis(300)
    })
    .await;
    let src = tempfile::tempdir().unwrap();
    write_data(&src.path().join("a.txt"), 100, 1);
    let t = Instant::now();
    let r = send(rx.addr(), &[src.path().join("a.txt")], &opts(), None, None).await;
    assert_eq!(r, Err(SendError::Failed(FailReason::Refused)));
    assert!(t.elapsed() < Duration::from_secs(5));
    assert!(leftovers(rx.root()).is_empty());
}

// Une seule seance a la fois (409) pendant qu'une decision est en attente.
#[tokio::test]
async fn second_request_while_pending_gets_conflict() {
    let rx = start_with(Arc::new(|_| Box::pin(std::future::pending())), |c| {
        c.decision_timeout = Duration::from_secs(2)
    })
    .await;
    let http = raw_client();
    let body = serde_json::json!({
        "info": info("one"), "files": { "1": { "id": "1", "relativePath": "a.txt", "size": 1 } }
    });
    let first = {
        let http = http.clone();
        let u = url(rx.addr(), "/prepare-upload");
        let b = body.clone();
        tokio::spawn(async move { http.post(u).json(&b).send().await.unwrap().status().as_u16() })
    };
    tokio::time::sleep(Duration::from_millis(300)).await;
    let r = http.post(url(rx.addr(), "/prepare-upload")).json(&body).send().await.unwrap();
    assert_eq!(r.status().as_u16(), 409);
    assert_eq!(first.await.unwrap(), 403);
}

// AC-F3-5 : HTTP en clair sans reponse applicative
#[tokio::test]
async fn plain_http_gets_no_application_response() {
    let rx = start(accept_all()).await;
    let mut s = tokio::net::TcpStream::connect(rx.addr()).await.unwrap();
    let _ = s
        .write_all(
            b"POST /api/filesend/v1/prepare-upload HTTP/1.1\r\nHost: x\r\nContent-Length: 2\r\n\r\n{}",
        )
        .await;
    let mut buf = vec![0u8; 1024];
    let n = tokio::time::timeout(Duration::from_secs(3), s.read(&mut buf))
        .await
        .unwrap_or(Ok(0))
        .unwrap_or(0);
    assert!(!String::from_utf8_lossy(&buf[..n]).contains("HTTP/1"), "reponse HTTP en clair recue");
}

// AC-F2-5 : annulation par l'expediteur en plein transfert
#[tokio::test]
async fn sender_cancel_removes_partial_and_reports_interrupted() {
    let mut rx = start(accept_all()).await;
    let src = tempfile::tempdir().unwrap();
    let f = src.path().join("big.bin");
    write_data(&f, 200 * 1024 * 1024, 9);
    let cancel = CancellationToken::new();
    let c2 = cancel.clone();
    let progress: filesend_core::client::ProgressFn = Arc::new(move |done, _| {
        if done > 2 * 1024 * 1024 {
            c2.cancel();
        }
    });
    let mut o = opts();
    o.hash_up_to = 0;
    let r = send(rx.addr(), &[f], &o, Some(progress), Some(cancel)).await;
    assert_eq!(r, Err(SendError::Cancelled));
    let ev = next_event(&mut rx.events, 10, |e| matches!(e, Event::Failed { .. })).await;
    assert!(matches!(ev, Event::Failed { reason: FailReason::Interrupted, .. }));
    tokio::time::sleep(Duration::from_millis(300)).await;
    assert!(leftovers(rx.root()).is_empty(), "{:?}", leftovers(rx.root()));
}

// AC-F2-5 : flux qui se fige (reseau coupe) -> inactivite detectee, partiel supprime
#[tokio::test]
async fn stalled_upload_times_out_and_cleans_partial() {
    let mut rx = start_with(accept_all(), |c| c.idle_timeout = Duration::from_secs(1)).await;
    let http = raw_client();
    let body = serde_json::json!({
        "info": info("slow"), "files": { "1": { "id": "1", "relativePath": "s.bin", "size": 1000000 } }
    });
    let prep: PrepareUploadResponse = http
        .post(url(rx.addr(), "/prepare-upload"))
        .json(&body)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let token = prep.files["1"].clone();
    let stream = futures_util::stream::once(async { Ok::<_, std::io::Error>(bytes::Bytes::from(vec![1u8; 1000])) })
        .chain(futures_util::stream::pending());
    let sid = prep.session_id.clone();
    let u = url(rx.addr(), "/upload");
    let h2 = http.clone();
    let task = tokio::spawn(async move {
        h2.post(u)
            .query(&[("sessionId", sid.as_str()), ("fileId", "1"), ("token", token.as_str())])
            .body(reqwest::Body::wrap_stream(stream))
            .send()
            .await
    });
    let t = Instant::now();
    let ev = next_event(&mut rx.events, 10, |e| matches!(e, Event::Failed { .. })).await;
    assert!(t.elapsed() < Duration::from_secs(10));
    assert!(matches!(ev, Event::Failed { reason: FailReason::Interrupted, .. }));
    assert!(leftovers(rx.root()).is_empty());
    task.abort();
}

// Jeton invalide (403) et taille differente (422).
#[tokio::test]
async fn bad_token_and_size_mismatch() {
    let rx = start(accept_all()).await;
    let http = raw_client();
    let body = serde_json::json!({
        "info": info("x"), "files": { "1": { "id": "1", "relativePath": "t.bin", "size": 10 } }
    });
    let prep: PrepareUploadResponse = http
        .post(url(rx.addr(), "/prepare-upload"))
        .json(&body)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let q = |t: &str| [("sessionId", prep.session_id.clone()), ("fileId", "1".into()), ("token", t.to_string())];
    let r = http.post(url(rx.addr(), "/upload")).query(&q("mauvais")).body(vec![0u8; 10]).send().await.unwrap();
    assert_eq!(r.status().as_u16(), 403);
    let good = prep.files["1"].clone();
    let r = http.post(url(rx.addr(), "/upload")).query(&q(&good)).body(vec![0u8; 5]).send().await.unwrap();
    assert_eq!(r.status().as_u16(), 422);
    assert!(leftovers(rx.root()).is_empty());
}

// F4 : exactement quatre raisons, libelles courts
#[test]
fn fail_reason_labels() {
    let all = [FailReason::Refused, FailReason::NotFound, FailReason::Interrupted, FailReason::DiskFull];
    let labels: Vec<_> = all.iter().map(|r| r.message()).collect();
    assert_eq!(
        labels,
        ["Échec : Refusé", "Échec : Introuvable", "Échec : Interrompu", "Échec : Disque plein"]
    );
}

// Destinataire injoignable -> Introuvable
#[tokio::test]
async fn unreachable_target_is_not_found() {
    let src = tempfile::tempdir().unwrap();
    write_data(&src.path().join("a.txt"), 10, 1);
    let r = send("127.0.0.1:9".parse().unwrap(), &[src.path().join("a.txt")], &opts(), None, None).await;
    assert_eq!(r, Err(SendError::Failed(FailReason::NotFound)));
}

// AC-F2-6 (mesure manuelle de la memoire a part) : transfert d'un fichier de 2 Go.
#[tokio::test]
#[ignore = "lourd : cargo test -- --ignored big_file_2gb"]
async fn big_file_2gb() {
    let rx = start(accept_all()).await;
    let src = tempfile::tempdir().unwrap();
    let f = src.path().join("big2g.bin");
    {
        use std::io::Write;
        let mut w = std::io::BufWriter::new(std::fs::File::create(&f).unwrap());
        let chunk: Vec<u8> = (0..1_048_576u32).map(|i| (i % 251) as u8).collect();
        for _ in 0..2048 {
            w.write_all(&chunk).unwrap();
        }
    }
    let mut o = opts();
    o.hash_up_to = 0;
    send(rx.addr(), &[f.clone()], &o, None, None).await.unwrap();
    assert_eq!(
        std::fs::metadata(rx.root().join("big2g.bin")).unwrap().len(),
        2048 * 1_048_576
    );
}

use futures_util::StreamExt;
