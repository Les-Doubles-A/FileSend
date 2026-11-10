use filesend_core::discovery::*;
use filesend_core::identity::Identity;
use filesend_core::protocol::DeviceInfo;
use filesend_core::server::{self, ServerConfig, ServerHandle};
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::time::{Duration, Instant};

struct Node {
    info: DeviceInfo,
    server: ServerHandle,
    _dir: tempfile::TempDir,
}

impl Node {
    async fn new(alias: &str) -> Node {
        let id = Identity::generate().unwrap();
        let info = DeviceInfo {
            alias: alias.into(),
            fingerprint: id.fingerprint.clone(),
            device_type: "desktop".into(),
        };
        let dir = tempfile::tempdir().unwrap();
        let mut cfg = ServerConfig::new(dir.path().to_path_buf(), info.clone());
        cfg.bind = "0.0.0.0:0".parse().unwrap();
        let server = server::start(cfg, &id, Arc::new(|_| Box::pin(async { true })), None)
            .await
            .unwrap();
        Node { info, server, _dir: dir }
    }
    fn port(&self) -> u16 {
        self.server.addr.port()
    }
    fn local(&self) -> SocketAddr {
        SocketAddr::from((Ipv4Addr::LOCALHOST, self.port()))
    }
    fn cfg(&self, udp_port: u16) -> DiscoveryConfig {
        let mut c = DiscoveryConfig::new(self.info.clone(), self.port());
        c.udp_port = udp_port;
        c.scan = ScanTargets::Off;
        c
    }
}

async fn wait_until(d: &Discovery, secs: u64, f: impl Fn(&[Device]) -> bool) -> bool {
    let mut rx = d.subscribe();
    let end = Instant::now() + Duration::from_secs(secs);
    loop {
        if f(&rx.borrow_and_update()) {
            return true;
        }
        let left = end.saturating_duration_since(Instant::now());
        if left.is_zero() || tokio::time::timeout(left, rx.changed()).await.is_err() {
            return f(&rx.borrow());
        }
    }
}

// AC-F1-1 : visibles l'un pour l'autre en moins de 5 s ; jamais soi-meme.
#[tokio::test]
async fn two_nodes_see_each_other_via_multicast_but_not_themselves() {
    let (a, b) = (Node::new("PC").await, Node::new("Tel").await);
    let t = Instant::now();
    let da = Discovery::start(a.cfg(53411)).await.unwrap();
    let db = Discovery::start(b.cfg(53411)).await.unwrap();
    assert!(wait_until(&da, 5, |d| d.len() == 1).await, "A ne voit pas B");
    assert!(wait_until(&db, 5, |d| d.len() == 1).await, "B ne voit pas A");
    assert!(t.elapsed() < Duration::from_secs(5));
    let seen_by_a = da.devices();
    assert_eq!(seen_by_a[0].info.alias, "Tel");
    assert_eq!(seen_by_a[0].info.fingerprint, b.info.fingerprint);
    assert_eq!(seen_by_a[0].addr.port(), b.port());
    assert_eq!(seen_by_a[0].via, Via::Multicast);
    assert_eq!(db.devices()[0].info.alias, "PC");
}

// AC-F1-2 : un appareil ferme disparait. Le delai par defaut est borne a moins de 15 s.
#[tokio::test]
async fn closed_device_disappears_within_ttl() {
    assert!(DEFAULT_TTL + Duration::from_secs(1) < Duration::from_secs(15));
    let (a, b) = (Node::new("PC").await, Node::new("Tel").await);
    let mut ca = a.cfg(53412);
    ca.ttl = Duration::from_secs(2);
    ca.announce_interval = Duration::from_millis(500);
    let mut cb = b.cfg(53412);
    cb.announce_interval = Duration::from_millis(500);
    let da = Discovery::start(ca).await.unwrap();
    let db = Discovery::start(cb).await.unwrap();
    assert!(wait_until(&da, 5, |d| d.len() == 1).await);
    drop(db);
    assert!(wait_until(&da, 6, |d| d.is_empty()).await, "B devrait avoir disparu");
}

// AC-F1-3 : multicast coupe, repli par sondage. Soi-meme filtre, appareils gardes
// vivants par la reverification, retires quand ils s'eteignent.
#[tokio::test]
async fn scan_fallback_finds_device_when_multicast_is_off() {
    let (a, b) = (Node::new("PC").await, Node::new("Tel").await);
    let mk = |me: &Node, other: &Node| {
        let mut c = DiscoveryConfig::new(me.info.clone(), me.port());
        c.multicast = false;
        c.scan = ScanTargets::Explicit(vec![me.local(), other.local()]);
        c.scan_initial_delay = Duration::from_millis(100);
        c.keepalive_interval = Duration::from_millis(500);
        c.ttl = Duration::from_secs(2);
        c
    };
    let t = Instant::now();
    let da = Discovery::start(mk(&a, &b)).await.unwrap();
    let db = Discovery::start(mk(&b, &a)).await.unwrap();
    assert!(wait_until(&da, 20, |d| d.len() == 1).await, "repli : A ne voit pas B");
    assert!(wait_until(&db, 20, |d| d.len() == 1).await, "repli : B ne voit pas A");
    assert!(t.elapsed() < Duration::from_secs(20));
    assert_eq!(da.devices()[0].info.alias, "Tel");
    assert_eq!(da.devices()[0].via, Via::Scan);
    // Plus long que le ttl : la reverification doit le maintenir.
    tokio::time::sleep(Duration::from_secs(4)).await;
    assert_eq!(da.devices().len(), 1, "appareil perdu malgre la reverification");
    b.server.shutdown();
    assert!(wait_until(&da, 8, |d| d.is_empty()).await, "B eteint devrait disparaitre");
    drop(db);
}

// Paquets mal formes, trop gros ou hors protocole : ignores.
#[tokio::test]
async fn junk_packets_are_ignored() {
    let a = Node::new("PC").await;
    let da = Discovery::start(a.cfg(53413)).await.unwrap();
    let s = tokio::net::UdpSocket::bind("0.0.0.0:0").await.unwrap();
    let dest = SocketAddr::from((MULTICAST_GROUP, 53413));
    let valid = |alias: &str, port: u16, announce: bool, fp: &str| {
        serde_json::json!({"info":{"alias":alias,"fingerprint":fp,"deviceType":"mobile"},"port":port,"announce":announce})
            .to_string()
            .into_bytes()
    };
    s.send_to(b"pas du json", dest).await.unwrap();
    s.send_to(&vec![b'x'; 5000], dest).await.unwrap();
    s.send_to(&valid("Zero", 0, true, "f1"), dest).await.unwrap();
    s.send_to(&valid("Depart", 5000, false, "f2"), dest).await.unwrap();
    s.send_to(&valid("   ", 5000, true, "f3"), dest).await.unwrap();
    tokio::time::sleep(Duration::from_millis(1500)).await;
    assert!(da.devices().is_empty(), "{:?}", da.devices());
}

// Alias assaini : controles retires, 32 caracteres au plus.
#[tokio::test]
async fn alias_is_sanitized() {
    let a = Node::new("PC").await;
    let da = Discovery::start(a.cfg(53414)).await.unwrap();
    let s = tokio::net::UdpSocket::bind("0.0.0.0:0").await.unwrap();
    let alias = format!("\u{7}\u{0}{}\n", "x".repeat(100));
    let pkt = serde_json::json!({"info":{"alias":alias,"fingerprint":"zz","deviceType":"mobile"},"port":6000,"announce":true});
    s.send_to(pkt.to_string().as_bytes(), SocketAddr::from((MULTICAST_GROUP, 53414))).await.unwrap();
    assert!(wait_until(&da, 5, |d| d.len() == 1).await);
    let got = &da.devices()[0].info.alias;
    assert_eq!(got.chars().count(), 32);
    assert!(got.chars().all(|c| c == 'x'));
}

// Aucun appareil : liste vide (base de AC-F1-4, cote interface).
#[tokio::test]
async fn alone_means_empty_list() {
    let a = Node::new("PC").await;
    let da = Discovery::start(a.cfg(53415)).await.unwrap();
    tokio::time::sleep(Duration::from_secs(1)).await;
    assert!(da.devices().is_empty());
}
