//! Client d'envoi (F2, cote expediteur).

use crate::events::FailReason;
use crate::protocol::*;
use crate::server::install_crypto_provider;
use futures_util::TryStreamExt;
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio_util::io::ReaderStream;
use tokio_util::sync::CancellationToken;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SendError {
    Failed(FailReason),
    Cancelled,
}

impl std::fmt::Display for SendError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SendError::Failed(r) => write!(f, "{}", r.message()),
            SendError::Cancelled => write!(f, "Annulé"),
        }
    }
}

impl std::error::Error for SendError {}

#[derive(Clone)]
pub struct SendOptions {
    pub info: DeviceInfo,
    /// Les fichiers jusqu'a cette taille ont leur sha256 envoye dans la liste.
    pub hash_up_to: u64,
    pub idle_timeout: Duration,
    pub decision_timeout: Duration,
}

impl SendOptions {
    pub fn new(info: DeviceInfo) -> Self {
        Self {
            info,
            hash_up_to: 64 * 1024 * 1024,
            idle_timeout: Duration::from_secs(10),
            decision_timeout: Duration::from_secs(60),
        }
    }
}

pub type ProgressFn = Arc<dyn Fn(u64, u64) + Send + Sync>;

struct Item {
    id: String,
    path: PathBuf,
    rel: String,
    size: u64,
}

/// Liste recursivement fichiers et dossiers (liens symboliques ignores).
fn enumerate(paths: &[PathBuf]) -> std::io::Result<Vec<Item>> {
    fn walk(dir: &Path, rel: &str, out: &mut Vec<(PathBuf, String, u64)>) -> std::io::Result<()> {
        let mut entries: Vec<_> = std::fs::read_dir(dir)?.collect::<Result<_, _>>()?;
        entries.sort_by_key(|e| e.file_name());
        for e in entries {
            let ft = e.file_type()?;
            if ft.is_symlink() {
                continue;
            }
            let name = e.file_name().to_string_lossy().into_owned();
            let r = format!("{rel}/{name}");
            if ft.is_dir() {
                walk(&e.path(), &r, out)?;
            } else if ft.is_file() {
                out.push((e.path(), r, e.metadata()?.len()));
            }
        }
        Ok(())
    }
    let mut raw = Vec::new();
    for p in paths {
        let meta = std::fs::metadata(p)?;
        let name = p
            .file_name()
            .ok_or_else(|| std::io::Error::new(std::io::ErrorKind::InvalidInput, "nom"))?
            .to_string_lossy()
            .into_owned();
        if meta.is_dir() {
            walk(p, &name, &mut raw)?;
        } else {
            raw.push((p.clone(), name, meta.len()));
        }
    }
    Ok(raw
        .into_iter()
        .enumerate()
        .map(|(i, (path, rel, size))| Item { id: format!("f{i}"), path, rel, size })
        .collect())
}

fn sha256_file(p: &Path) -> std::io::Result<String> {
    use std::io::Read;
    let mut f = std::fs::File::open(p)?;
    let mut h = Sha256::new();
    let mut buf = vec![0u8; 64 * 1024];
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        h.update(&buf[..n]);
    }
    Ok(hex::encode(h.finalize()))
}

fn net_reason(e: &reqwest::Error) -> FailReason {
    if e.is_connect() || e.is_timeout() && e.is_connect() {
        FailReason::NotFound
    } else {
        FailReason::Interrupted
    }
}

pub async fn send(
    target: SocketAddr,
    paths: &[PathBuf],
    opts: &SendOptions,
    progress: Option<ProgressFn>,
    cancel: Option<CancellationToken>,
) -> Result<(), SendError> {
    install_crypto_provider();
    let fail = |r| SendError::Failed(r);
    let paths_v = paths.to_vec();
    let hash_up_to = opts.hash_up_to;
    let (items, hashes) = tokio::task::spawn_blocking(move || {
        let items = enumerate(&paths_v)?;
        let mut hashes = HashMap::new();
        for it in &items {
            if it.size <= hash_up_to {
                hashes.insert(it.id.clone(), sha256_file(&it.path)?);
            }
        }
        Ok::<_, std::io::Error>((items, hashes))
    })
    .await
    .map_err(|_| fail(FailReason::Interrupted))?
    .map_err(|_| fail(FailReason::Interrupted))?;
    if items.is_empty() {
        return Err(fail(FailReason::Interrupted));
    }
    let total: u64 = items.iter().map(|i| i.size).sum();

    let http = reqwest::Client::builder()
        .tls_danger_accept_invalid_certs(true)
        .connect_timeout(Duration::from_secs(5))
        .build()
        .map_err(|_| fail(FailReason::Interrupted))?;
    let base = format!("https://{target}{API_PREFIX}");

    let files: HashMap<String, FileEntry> = items
        .iter()
        .map(|i| {
            (
                i.id.clone(),
                FileEntry {
                    id: i.id.clone(),
                    relative_path: i.rel.clone(),
                    size: i.size,
                    sha256: hashes.get(&i.id).cloned(),
                },
            )
        })
        .collect();
    let req = PrepareUploadRequest { info: opts.info.clone(), files };

    let prepare = http
        .post(format!("{base}/prepare-upload"))
        .timeout(opts.decision_timeout + Duration::from_secs(10))
        .json(&req)
        .send();
    let resp = tokio::select! {
        r = prepare => r.map_err(|e| fail(net_reason(&e)))?,
        _ = wait_cancel(&cancel) => return Err(SendError::Cancelled),
    };
    match resp.status().as_u16() {
        200 => {}
        403 => return Err(fail(FailReason::Refused)),
        _ => return Err(fail(FailReason::Interrupted)),
    }
    let prep: PrepareUploadResponse =
        resp.json().await.map_err(|_| fail(FailReason::Interrupted))?;
    let sid = prep.session_id.clone();

    let sent = Arc::new(AtomicU64::new(0));
    let mut base_done = 0u64;
    for it in &items {
        let token = prep.files.get(&it.id).cloned().ok_or(fail(FailReason::Interrupted))?;
        let file = match tokio::fs::File::open(&it.path).await {
            Ok(f) => f,
            Err(_) => {
                cancel_remote(&http, &base, &sid).await;
                return Err(fail(FailReason::Interrupted));
            }
        };
        sent.store(0, Ordering::SeqCst);
        let counter = sent.clone();
        let prog = progress.clone();
        let (b0, tot) = (base_done, total);
        let stream = ReaderStream::with_capacity(file, 64 * 1024).inspect_ok(move |c| {
            let n = counter.fetch_add(c.len() as u64, Ordering::SeqCst) + c.len() as u64;
            if let Some(p) = &prog {
                p(b0 + n, tot);
            }
        });
        let fut = http
            .post(format!("{base}/upload"))
            .query(&[("sessionId", sid.as_str()), ("fileId", it.id.as_str()), ("token", token.as_str())])
            .header("content-length", it.size)
            .body(reqwest::Body::wrap_stream(stream))
            .send();
        tokio::pin!(fut);
        let mut last = (0u64, Instant::now());
        let outcome: Result<reqwest::Response, SendError> = loop {
            tokio::select! {
                r = &mut fut => break r.map_err(|e| fail(net_reason(&e))),
                _ = wait_cancel(&cancel) => break Err(SendError::Cancelled),
                _ = tokio::time::sleep(Duration::from_millis(250)) => {
                    let now = sent.load(Ordering::SeqCst);
                    if now != last.0 {
                        last = (now, Instant::now());
                    } else {
                        let limit = if now < it.size { opts.idle_timeout } else { opts.idle_timeout * 3 };
                        if last.1.elapsed() >= limit {
                            break Err(fail(FailReason::Interrupted));
                        }
                    }
                }
            }
        };
        match outcome {
            Ok(r) if r.status().is_success() => {}
            Ok(r) => {
                let reason = if r.status().as_u16() == 507 {
                    FailReason::DiskFull
                } else {
                    FailReason::Interrupted
                };
                cancel_remote(&http, &base, &sid).await;
                return Err(fail(reason));
            }
            Err(e) => {
                cancel_remote(&http, &base, &sid).await;
                return Err(e);
            }
        }
        base_done += it.size;
    }
    Ok(())
}

async fn wait_cancel(c: &Option<CancellationToken>) {
    match c {
        Some(t) => t.cancelled().await,
        None => std::future::pending().await,
    }
}

async fn cancel_remote(http: &reqwest::Client, base: &str, sid: &str) {
    let _ = http
        .post(format!("{base}/cancel"))
        .query(&[("sessionId", sid)])
        .timeout(Duration::from_secs(2))
        .send()
        .await;
}
