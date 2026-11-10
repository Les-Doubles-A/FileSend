//! Serveur de reception HTTPS (F3, cote receveur de F2).

use crate::events::{emit, Event, EventSink, FailReason};
use crate::identity::Identity;
use crate::paths::{is_under, join_components, sanitize_relative_path, unique_top_name};
use crate::protocol::*;
use axum::body::Body;
use axum::extract::{DefaultBodyLimit, Query, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::routing::post;
use axum::{Json, Router};
use futures_util::StreamExt;
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
use std::future::Future;
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::pin::Pin;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::io::AsyncWriteExt;

/// Demande entrante presentee a l'utilisateur (AC-F3-1).
#[derive(Debug, Clone)]
pub struct IncomingRequest {
    pub from: DeviceInfo,
    pub count: usize,
    pub total: u64,
}

/// Retourne `true` pour accepter. Appele sans aucune ecriture sur disque.
pub type Decider =
    Arc<dyn Fn(IncomingRequest) -> Pin<Box<dyn Future<Output = bool> + Send>> + Send + Sync>;

#[derive(Clone)]
pub struct ServerConfig {
    pub bind: SocketAddr,
    pub dest_dir: PathBuf,
    pub info: DeviceInfo,
    pub decision_timeout: Duration,
    pub idle_timeout: Duration,
    pub session_expiry: Duration,
    pub max_files: usize,
    pub max_total_bytes: u64,
}

impl ServerConfig {
    pub fn new(dest_dir: PathBuf, info: DeviceInfo) -> Self {
        Self {
            bind: SocketAddr::from(([0, 0, 0, 0], DEFAULT_PORT)),
            dest_dir,
            info,
            decision_timeout: Duration::from_secs(60),
            idle_timeout: Duration::from_secs(10),
            session_expiry: Duration::from_secs(120),
            max_files: 10_000,
            max_total_bytes: 1 << 42,
        }
    }
}

#[derive(PartialEq, Eq, Clone, Copy)]
enum FileState {
    Pending,
    Receiving,
    Done,
}

struct SessionFile {
    entry: FileEntry,
    token: String,
    dest: PathBuf,
    state: FileState,
}

struct Session {
    id: String,
    from: DeviceInfo,
    accepted: bool,
    files: HashMap<String, SessionFile>,
    tops: Vec<PathBuf>,
    total: u64,
    done_bytes: u64,
    done_files: usize,
    last_activity: Instant,
    cancelled: Arc<AtomicBool>,
}

struct Shared {
    cfg: ServerConfig,
    decider: Decider,
    events: Option<EventSink>,
    state: Mutex<Option<Session>>,
}

pub struct ServerHandle {
    pub addr: SocketAddr,
    handle: axum_server::Handle<SocketAddr>,
}

impl ServerHandle {
    pub fn shutdown(&self) {
        self.handle.shutdown();
    }
}

pub fn install_crypto_provider() {
    let _ = rustls::crypto::ring::default_provider().install_default();
}

pub async fn start(
    cfg: ServerConfig,
    identity: &Identity,
    decider: Decider,
    events: Option<EventSink>,
) -> Result<ServerHandle, String> {
    install_crypto_provider();
    std::fs::create_dir_all(&cfg.dest_dir).map_err(|e| e.to_string())?;
    let tls = axum_server::tls_rustls::RustlsConfig::from_pem(
        identity.cert_pem.clone().into_bytes(),
        identity.key_pem.clone().into_bytes(),
    )
    .await
    .map_err(|e| e.to_string())?;
    let bind = cfg.bind;
    let shared = Arc::new(Shared { cfg, decider, events, state: Mutex::new(None) });
    let p = |s: &str| format!("{API_PREFIX}{s}");
    let app = Router::new()
        .route(&p("/prepare-upload"), post(prepare_upload))
        .route(&p("/upload"), post(upload))
        .route(&p("/cancel"), post(cancel))
        .route(&p("/info"), axum::routing::get(info))
        .layer(DefaultBodyLimit::max(16 * 1024 * 1024))
        .with_state(shared);
    let handle = axum_server::Handle::new();
    let h2 = handle.clone();
    tokio::spawn(async move {
        let _ = axum_server::bind_rustls(bind, tls)
            .handle(h2)
            .serve(app.into_make_service())
            .await;
    });
    let addr = handle.listening().await.ok_or("ecoute impossible")?;
    Ok(ServerHandle { addr, handle })
}

fn code(c: StatusCode) -> Response {
    c.into_response()
}

fn lock(s: &Shared) -> std::sync::MutexGuard<'_, Option<Session>> {
    s.state.lock().unwrap_or_else(|e| e.into_inner())
}

/// Identite publique de l'appareil (sondage de decouverte).
async fn info(State(sh): State<Arc<Shared>>) -> Response {
    Json(sh.cfg.info.clone()).into_response()
}

struct Reservation {
    sh: Arc<Shared>,
    id: String,
    armed: bool,
}

impl Drop for Reservation {
    fn drop(&mut self) {
        if self.armed {
            let mut st = lock(&self.sh);
            if st.as_ref().map(|s| s.id == self.id).unwrap_or(false) {
                *st = None;
            }
        }
    }
}

async fn prepare_upload(State(sh): State<Arc<Shared>>, body: bytes::Bytes) -> Response {
    let req: PrepareUploadRequest = match serde_json::from_slice(&body) {
        Ok(r) => r,
        Err(_) => return code(StatusCode::BAD_REQUEST),
    };
    if req.files.is_empty() || req.files.len() > sh.cfg.max_files {
        return code(StatusCode::BAD_REQUEST);
    }
    let mut total = 0u64;
    let mut parsed: Vec<(&FileEntry, Vec<String>)> = Vec::new();
    let mut seen = HashSet::new();
    for (key, f) in &req.files {
        if key != &f.id || f.id.is_empty() {
            return code(StatusCode::BAD_REQUEST);
        }
        let comps = match sanitize_relative_path(&f.relative_path) {
            Ok(c) => c,
            Err(_) => return code(StatusCode::BAD_REQUEST),
        };
        if !seen.insert(comps.join("/").to_lowercase()) {
            return code(StatusCode::BAD_REQUEST);
        }
        total = match total.checked_add(f.size) {
            Some(t) => t,
            None => return code(StatusCode::BAD_REQUEST),
        };
        parsed.push((f, comps));
    }
    if total > sh.cfg.max_total_bytes {
        return code(StatusCode::BAD_REQUEST);
    }

    let session_id = uuid::Uuid::new_v4().to_string();
    // Reserve la place (409 pour les autres) pendant que l'utilisateur decide.
    {
        let mut st = lock(&sh);
        if let Some(cur) = st.as_ref() {
            if cur.last_activity.elapsed() < sh.cfg.session_expiry {
                return code(StatusCode::CONFLICT);
            }
            cur.cancelled.store(true, Ordering::SeqCst);
        }
        *st = Some(Session {
            id: session_id.clone(),
            from: req.info.clone(),
            accepted: false,
            files: HashMap::new(),
            tops: Vec::new(),
            total,
            done_bytes: 0,
            done_files: 0,
            last_activity: Instant::now(),
            cancelled: Arc::new(AtomicBool::new(false)),
        });
    }

    // Libere la reservation si la requete est abandonnee ou refusee.
    let mut guard = Reservation { sh: sh.clone(), id: session_id.clone(), armed: true };
    let incoming = IncomingRequest { from: req.info.clone(), count: req.files.len(), total };
    let accepted =
        tokio::time::timeout(sh.cfg.decision_timeout, (sh.decider)(incoming)).await.unwrap_or(false);
    if !accepted {
        return code(StatusCode::FORBIDDEN);
    }

    // Noms de premier niveau uniques (AC-F2-3), calcules apres l'accord.
    let root = &sh.cfg.dest_dir;
    let mut top_map: HashMap<String, String> = HashMap::new();
    let mut tops = Vec::new();
    let mut files = HashMap::new();
    let mut tokens = HashMap::new();
    for (f, comps) in parsed {
        let top = top_map
            .entry(comps[0].clone())
            .or_insert_with(|| {
                let n = unique_top_name(root, &comps[0]);
                tops.push(root.join(&n));
                n
            })
            .clone();
        let mut mapped = comps.clone();
        mapped[0] = top;
        let dest = join_components(root, &mapped);
        if !is_under(root, &dest) {
            return code(StatusCode::BAD_REQUEST);
        }
        let token = uuid::Uuid::new_v4().to_string();
        tokens.insert(f.id.clone(), token.clone());
        files.insert(
            f.id.clone(),
            SessionFile { entry: f.clone(), token, dest, state: FileState::Pending },
        );
    }
    {
        let mut st = lock(&sh);
        match st.as_mut() {
            Some(s) if s.id == session_id && !s.cancelled.load(Ordering::SeqCst) => {
                s.accepted = true;
                s.files = files;
                s.tops = tops;
                s.last_activity = Instant::now();
            }
            _ => {
                guard.armed = false;
                return code(StatusCode::CONFLICT);
            }
        }
    }
    guard.armed = false;
    emit(
        &sh.events,
        Event::Started { session: session_id.clone(), from: req.info, count: req.files.len(), total },
    );
    Json(PrepareUploadResponse { session_id, files: tokens }).into_response()
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct UploadQuery {
    session_id: String,
    file_id: String,
    token: String,
}

fn part_path(dest: &Path) -> PathBuf {
    let mut name = dest.file_name().unwrap_or_default().to_os_string();
    name.push(".part");
    dest.with_file_name(name)
}

fn reason_for_io(e: &std::io::Error) -> (StatusCode, FailReason) {
    if e.kind() == std::io::ErrorKind::StorageFull {
        (StatusCode::INSUFFICIENT_STORAGE, FailReason::DiskFull)
    } else {
        (StatusCode::INTERNAL_SERVER_ERROR, FailReason::Interrupted)
    }
}

/// Ferme la seance en echec (idempotent) et previent l'interface.
fn fail_session(sh: &Shared, id: &str, reason: FailReason) {
    let mut st = lock(sh);
    if st.as_ref().map(|s| s.id == id).unwrap_or(false) {
        if let Some(s) = st.take() {
            s.cancelled.store(true, Ordering::SeqCst);
            drop(st);
            emit(&sh.events, Event::Failed { session: id.to_string(), reason });
        }
    }
}

/// Marque un fichier recu. Retourne (seance vivante, seance terminee si c'etait le dernier).
fn commit_file(sh: &Shared, sid: &str, fid: &str, size: u64) -> (bool, Option<Session>) {
    let mut st = lock(sh);
    let finished = match st.as_mut() {
        Some(s) if s.id == sid && !s.cancelled.load(Ordering::SeqCst) => {
            if let Some(f) = s.files.get_mut(fid) {
                f.state = FileState::Done;
            }
            s.done_files += 1;
            s.done_bytes += size;
            s.last_activity = Instant::now();
            s.done_files == s.files.len()
        }
        _ => return (false, None),
    };
    (true, if finished { st.take() } else { None })
}

async fn upload(
    State(sh): State<Arc<Shared>>,
    Query(q): Query<UploadQuery>,
    body: Body,
) -> Response {
    // Validation de la seance, du fichier et du jeton.
    let (entry, dest, cancelled) = {
        let mut st = lock(&sh);
        let s = match st.as_mut() {
            Some(s) if s.id == q.session_id && s.accepted => s,
            _ => return code(StatusCode::FORBIDDEN),
        };
        let cancelled = s.cancelled.clone();
        let f = match s.files.get_mut(&q.file_id) {
            Some(f) if f.token == q.token && f.state == FileState::Pending => f,
            _ => return code(StatusCode::FORBIDDEN),
        };
        f.state = FileState::Receiving;
        s.last_activity = Instant::now();
        (f.entry.clone(), f.dest.clone(), cancelled)
    };

    let part = part_path(&dest);
    let result = receive_file(&sh, &q.session_id, &entry, &dest, &part, body, &cancelled).await;
    match result {
        Ok(()) => {
            // La seance a-t-elle ete annulee entre-temps ?
            let (alive, finished) = commit_file(&sh, &q.session_id, &q.file_id, entry.size);
            if !alive {
                let _ = tokio::fs::remove_file(&dest).await;
                return code(StatusCode::FORBIDDEN);
            }
            if let Some(s) = finished {
                emit(
                    &sh.events,
                    Event::Completed { session: s.id, from: s.from, paths: s.tops },
                );
            }
            code(StatusCode::OK)
        }
        Err((status, reason)) => {
            let _ = tokio::fs::remove_file(&part).await;
            fail_session(&sh, &q.session_id, reason);
            code(status)
        }
    }
}

async fn receive_file(
    sh: &Shared,
    session_id: &str,
    entry: &FileEntry,
    dest: &Path,
    part: &Path,
    body: Body,
    cancelled: &AtomicBool,
) -> Result<(), (StatusCode, FailReason)> {
    let io = |e: std::io::Error| reason_for_io(&e);
    if let Some(parent) = dest.parent() {
        tokio::fs::create_dir_all(parent).await.map_err(io)?;
    }
    let mut file = tokio::fs::File::create(part).await.map_err(io)?;
    let mut stream = body.into_data_stream();
    let mut hasher = Sha256::new();
    let mut received = 0u64;
    let mut last_emit = 0u64;
    let step = (entry.size / 100).max(256 * 1024);
    let interrupted = (StatusCode::BAD_REQUEST, FailReason::Interrupted);
    loop {
        if cancelled.load(Ordering::SeqCst) {
            return Err((StatusCode::FORBIDDEN, FailReason::Interrupted));
        }
        let next = tokio::time::timeout(sh.cfg.idle_timeout, stream.next())
            .await
            .map_err(|_| interrupted)?;
        let chunk = match next {
            None => break,
            Some(Ok(c)) => c,
            Some(Err(_)) => return Err(interrupted),
        };
        received += chunk.len() as u64;
        if received > entry.size {
            return Err((StatusCode::UNPROCESSABLE_ENTITY, FailReason::Interrupted));
        }
        hasher.update(&chunk);
        file.write_all(&chunk).await.map_err(io)?;
        if received - last_emit >= step {
            last_emit = received;
            let done = {
                let mut st = lock(sh);
                match st.as_mut() {
                    Some(s) if s.id == session_id => {
                        s.last_activity = Instant::now();
                        s.done_bytes + received
                    }
                    _ => return Err((StatusCode::FORBIDDEN, FailReason::Interrupted)),
                }
            };
            let total = lock(sh).as_ref().map(|s| s.total).unwrap_or(entry.size);
            emit(&sh.events, Event::Progress { session: session_id.to_string(), done, total });
        }
    }
    if received != entry.size {
        return Err((StatusCode::UNPROCESSABLE_ENTITY, FailReason::Interrupted));
    }
    if let Some(expected) = &entry.sha256 {
        if !expected.eq_ignore_ascii_case(&hex::encode(hasher.finalize())) {
            return Err((StatusCode::UNPROCESSABLE_ENTITY, FailReason::Interrupted));
        }
    }
    file.flush().await.map_err(io)?;
    drop(file);
    if dest.exists() {
        return Err((StatusCode::INTERNAL_SERVER_ERROR, FailReason::Interrupted));
    }
    tokio::fs::rename(part, dest).await.map_err(io)?;
    Ok(())
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CancelQuery {
    session_id: String,
}

async fn cancel(State(sh): State<Arc<Shared>>, Query(q): Query<CancelQuery>) -> Response {
    fail_session(&sh, &q.session_id, FailReason::Interrupted);
    code(StatusCode::OK)
}
