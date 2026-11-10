//! Decouverte des appareils proches (F1) : annonces multicast UDP, repli par
//! sondage du sous-reseau, expiration des appareils absents.

use crate::protocol::{DeviceInfo, API_PREFIX, DEFAULT_PORT};
use crate::server::install_crypto_provider;
use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::net::UdpSocket;
use tokio::sync::watch;
use tokio::task::JoinHandle;

pub const MULTICAST_GROUP: Ipv4Addr = Ipv4Addr::new(224, 0, 0, 167);
/// Un appareil muet depuis plus longtemps que cela disparait (AC-F1-2 : moins de 15 s).
pub const DEFAULT_TTL: Duration = Duration::from_secs(10);
const MAX_DEVICES: usize = 256;
const MAX_PACKET: usize = 2048;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Via {
    Multicast,
    Scan,
}

#[derive(Debug, Clone)]
pub struct Device {
    pub info: DeviceInfo,
    pub addr: SocketAddr,
    pub via: Via,
    pub last_seen: Instant,
}

#[derive(Debug, Clone)]
pub enum ScanTargets {
    Off,
    /// Les 254 adresses du /24 de l'interface principale.
    Subnet,
    /// Adresses precises (tests).
    Explicit(Vec<SocketAddr>),
}

#[derive(Clone)]
pub struct DiscoveryConfig {
    pub info: DeviceInfo,
    /// Port HTTPS de ce serveur, annonce aux autres.
    pub https_port: u16,
    pub multicast: bool,
    pub group: Ipv4Addr,
    pub udp_port: u16,
    pub interface: Ipv4Addr,
    pub announce_interval: Duration,
    pub ttl: Duration,
    pub scan: ScanTargets,
    pub scan_initial_delay: Duration,
    pub scan_interval: Duration,
    pub keepalive_interval: Duration,
}

impl DiscoveryConfig {
    pub fn new(info: DeviceInfo, https_port: u16) -> Self {
        Self {
            info,
            https_port,
            multicast: true,
            group: MULTICAST_GROUP,
            udp_port: DEFAULT_PORT,
            interface: Ipv4Addr::UNSPECIFIED,
            announce_interval: Duration::from_secs(3),
            ttl: DEFAULT_TTL,
            scan: ScanTargets::Subnet,
            scan_initial_delay: Duration::from_millis(1500),
            scan_interval: Duration::from_secs(10),
            keepalive_interval: Duration::from_secs(4),
        }
    }
}

#[derive(Serialize, Deserialize)]
struct Announce {
    info: DeviceInfo,
    port: u16,
    announce: bool,
}

type Map = Arc<Mutex<HashMap<String, Device>>>;

struct Core {
    own_fingerprint: String,
    ttl: Duration,
    map: Map,
    tx: watch::Sender<Vec<Device>>,
}

impl Core {
    fn publish(&self, map: &HashMap<String, Device>) {
        let mut v: Vec<Device> = map.values().cloned().collect();
        v.sort_by(|a, b| a.info.alias.cmp(&b.info.alias).then(a.addr.cmp(&b.addr)));
        // Ne notifie que si la liste visible change (pas pour un simple rafraichissement).
        self.tx.send_if_modified(|cur| {
            let same = cur.len() == v.len()
                && cur.iter().zip(&v).all(|(a, b)| {
                    a.info == b.info && a.addr == b.addr
                });
            if same {
                for (c, n) in cur.iter_mut().zip(&v) {
                    c.last_seen = n.last_seen;
                }
                false
            } else {
                *cur = v;
                true
            }
        });
    }

    fn upsert(&self, mut info: DeviceInfo, addr: SocketAddr, via: Via) {
        if info.fingerprint == self.own_fingerprint {
            return; // soi-meme
        }
        info.alias = clean_alias(&info.alias);
        if info.alias.is_empty() {
            return;
        }
        let mut map = self.map.lock().unwrap_or_else(|e| e.into_inner());
        if !map.contains_key(&info.fingerprint) && map.len() >= MAX_DEVICES {
            return;
        }
        let via = match map.get(&info.fingerprint) {
            Some(d) if d.via == Via::Multicast => Via::Multicast,
            _ => via,
        };
        map.insert(
            info.fingerprint.clone(),
            Device { info, addr, via, last_seen: Instant::now() },
        );
        self.publish(&map);
    }

    fn touch(&self, fingerprint: &str) {
        let mut map = self.map.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(d) = map.get_mut(fingerprint) {
            d.last_seen = Instant::now();
        }
    }

    fn reap(&self) {
        let mut map = self.map.lock().unwrap_or_else(|e| e.into_inner());
        let ttl = self.ttl;
        let before = map.len();
        map.retain(|_, d| d.last_seen.elapsed() <= ttl);
        if map.len() != before {
            self.publish(&map);
        }
    }

    fn scan_devices(&self) -> Vec<(String, SocketAddr)> {
        let map = self.map.lock().unwrap_or_else(|e| e.into_inner());
        map.values()
            .filter(|d| d.via == Via::Scan)
            .map(|d| (d.info.fingerprint.clone(), d.addr))
            .collect()
    }

    fn is_empty(&self) -> bool {
        self.map.lock().unwrap_or_else(|e| e.into_inner()).is_empty()
    }
}

fn clean_alias(s: &str) -> String {
    s.chars().filter(|c| !c.is_control()).take(32).collect::<String>().trim().to_string()
}

pub struct Discovery {
    rx: watch::Receiver<Vec<Device>>,
    tasks: Vec<JoinHandle<()>>,
}

impl Discovery {
    pub async fn start(cfg: DiscoveryConfig) -> Result<Self, String> {
        install_crypto_provider();
        let (tx, rx) = watch::channel(Vec::new());
        let core = Arc::new(Core {
            own_fingerprint: cfg.info.fingerprint.clone(),
            ttl: cfg.ttl,
            map: Arc::new(Mutex::new(HashMap::new())),
            tx,
        });
        let mut tasks = Vec::new();

        if cfg.multicast {
            let sock = Arc::new(bind_multicast(&cfg)?);
            let dest = SocketAddr::new(IpAddr::V4(cfg.group), cfg.udp_port);
            let packet = serde_json::to_vec(&Announce {
                info: cfg.info.clone(),
                port: cfg.https_port,
                announce: true,
            })
            .map_err(|e| e.to_string())?;
            let (s1, interval) = (sock.clone(), cfg.announce_interval);
            tasks.push(tokio::spawn(async move {
                loop {
                    let _ = s1.send_to(&packet, dest).await;
                    tokio::time::sleep(interval).await;
                }
            }));
            let c = core.clone();
            tasks.push(tokio::spawn(async move {
                let mut buf = vec![0u8; MAX_PACKET + 1];
                loop {
                    let Ok((n, from)) = sock.recv_from(&mut buf).await else {
                        tokio::time::sleep(Duration::from_millis(100)).await;
                        continue;
                    };
                    if n > MAX_PACKET {
                        continue;
                    }
                    let Ok(a) = serde_json::from_slice::<Announce>(&buf[..n]) else { continue };
                    if !a.announce || a.port == 0 {
                        continue;
                    }
                    // L'adresse vient du paquet recu, jamais du contenu annonce.
                    c.upsert(a.info, SocketAddr::new(from.ip(), a.port), Via::Multicast);
                }
            }));
        }

        let c = core.clone();
        tasks.push(tokio::spawn(async move {
            loop {
                tokio::time::sleep(Duration::from_millis(500)).await;
                c.reap();
            }
        }));

        if !matches!(cfg.scan, ScanTargets::Off) {
            let http = probe_client()?;
            let (c, h, scan) = (core.clone(), http.clone(), cfg.scan.clone());
            let (delay, every, port) = (cfg.scan_initial_delay, cfg.scan_interval, cfg.https_port);
            tasks.push(tokio::spawn(async move {
                tokio::time::sleep(delay).await;
                loop {
                    // Repli : on ne sonde que si aucun appareil n'est connu.
                    if c.is_empty() {
                        let targets = match &scan {
                            ScanTargets::Explicit(v) => v.clone(),
                            ScanTargets::Subnet => subnet_candidates(port).await,
                            ScanTargets::Off => Vec::new(),
                        };
                        let found: Vec<_> = futures_util::stream::iter(targets)
                            .map(|addr| {
                                let h = h.clone();
                                async move { probe(&h, addr).await.map(|i| (i, addr)) }
                            })
                            .buffer_unordered(64)
                            .filter_map(|x| async move { x })
                            .collect()
                            .await;
                        for (info, addr) in found {
                            c.upsert(info, addr, Via::Scan);
                        }
                    }
                    tokio::time::sleep(every).await;
                }
            }));
            // Les appareils trouves par sondage n'annoncent rien : on les reverifie.
            let (c, h, every) = (core.clone(), http, cfg.keepalive_interval);
            tasks.push(tokio::spawn(async move {
                loop {
                    tokio::time::sleep(every).await;
                    for (fp, addr) in c.scan_devices() {
                        if let Some(i) = probe(&h, addr).await {
                            if i.fingerprint == fp {
                                c.touch(&fp);
                            }
                        }
                    }
                }
            }));
        }
        Ok(Self { rx, tasks })
    }

    pub fn devices(&self) -> Vec<Device> {
        self.rx.borrow().clone()
    }

    /// Notifie a chaque changement de la liste visible.
    pub fn subscribe(&self) -> watch::Receiver<Vec<Device>> {
        self.rx.clone()
    }

    pub fn stop(self) {}
}

impl Drop for Discovery {
    fn drop(&mut self) {
        for t in &self.tasks {
            t.abort();
        }
    }
}

fn bind_multicast(cfg: &DiscoveryConfig) -> Result<UdpSocket, String> {
    use socket2::{Domain, Protocol, Socket, Type};
    let s = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP)).map_err(|e| e.to_string())?;
    s.set_reuse_address(true).map_err(|e| e.to_string())?;
    s.bind(&SocketAddr::from((Ipv4Addr::UNSPECIFIED, cfg.udp_port)).into())
        .map_err(|e| format!("port UDP {} : {e}", cfg.udp_port))?;
    // Interface multicast : celle de la route par defaut (le Wi-Fi), pas celle que Windows choisirait
    // par la plus petite metrique (carte virtuelle, VPN) : sinon les annonces du reseau n'arrivent pas.
    // Reglage de diagnostic : FILESEND_MCAST_IF=default (laisse le choix a Windows) ou une adresse IPv4.
    let forced = std::env::var("FILESEND_MCAST_IF").ok();
    let iface = if let Some(v) = forced.as_deref() {
        if v == "default" { Ipv4Addr::UNSPECIFIED } else { v.parse().unwrap_or(Ipv4Addr::UNSPECIFIED) }
    } else if cfg.interface.is_unspecified() {
        match primary_ip() {
            Some(IpAddr::V4(ip)) => ip,
            _ => Ipv4Addr::UNSPECIFIED,
        }
    } else {
        cfg.interface
    };
    s.join_multicast_v4(&cfg.group, &iface).map_err(|e| e.to_string())?;
    if !iface.is_unspecified() {
        s.set_multicast_if_v4(&iface).map_err(|e| e.to_string())?;
    }
    s.set_multicast_loop_v4(true).map_err(|e| e.to_string())?;
    s.set_multicast_ttl_v4(1).map_err(|e| e.to_string())?;
    s.set_nonblocking(true).map_err(|e| e.to_string())?;
    UdpSocket::from_std(s.into()).map_err(|e| e.to_string())
}

fn probe_client() -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .tls_danger_accept_invalid_certs(true)
        .connect_timeout(Duration::from_millis(800))
        .timeout(Duration::from_secs(2))
        .build()
        .map_err(|e| e.to_string())
}

async fn probe(http: &reqwest::Client, addr: SocketAddr) -> Option<DeviceInfo> {
    let r = http.get(format!("https://{addr}{API_PREFIX}/info")).send().await.ok()?;
    if !r.status().is_success() {
        return None;
    }
    r.json::<DeviceInfo>().await.ok()
}

/// Adresses du /24 de l'interface principale ayant le port ouvert.
async fn subnet_candidates(port: u16) -> Vec<SocketAddr> {
    let Some(IpAddr::V4(me)) = primary_ip() else { return Vec::new() };
    let o = me.octets();
    let hosts: Vec<SocketAddr> = (1..=254u8)
        .filter(|h| *h != o[3])
        .map(|h| SocketAddr::from((Ipv4Addr::new(o[0], o[1], o[2], h), port)))
        .collect();
    futures_util::stream::iter(hosts)
        .map(|a| async move {
            let ok = tokio::time::timeout(Duration::from_millis(300), tokio::net::TcpStream::connect(a))
                .await
                .map(|r| r.is_ok())
                .unwrap_or(false);
            ok.then_some(a)
        })
        .buffer_unordered(64)
        .filter_map(|x| async move { x })
        .collect()
        .await
}

/// Adresse locale utilisee pour sortir du reseau (aucun paquet n'est envoye).
fn primary_ip() -> Option<IpAddr> {
    let s = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    s.connect("192.0.2.1:9").ok()?;
    Some(s.local_addr().ok()?.ip())
}
