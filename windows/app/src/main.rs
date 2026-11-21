#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine;
use filesend_core::bluetooth::{self, BtDevice, BtReceiverHandle};
use filesend_core::client::{send, send_text, ProgressFn, SendError, SendOptions};
use filesend_core::discovery::{Device, Discovery, DiscoveryConfig};
use filesend_core::events::Event;
use filesend_core::identity::Identity;
use filesend_core::messages;
use filesend_core::protocol::{DeviceInfo, DEFAULT_PORT};
use filesend_core::relay::{self, LocalQrServer, QrShareResult};
use filesend_core::server::{self, Decider, IncomingRequest, ServerConfig, ServerHandle};
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::window::{Effect, EffectsBuilder};
use tauri::{AppHandle, Emitter, Manager, State, WebviewUrl, WebviewWindowBuilder, WindowEvent};
use tokio::sync::oneshot;
use tokio_util::sync::CancellationToken;

const TOAST_W: f64 = 352.0;
const TOAST_H: f64 = 122.0;

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct DeviceView {
    alias: String,
    fingerprint: String,
    kind: String,
    trusted: bool,
}

#[derive(Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
struct AppSettings {
    alias: String,
    dest_dir: String,
    trusted: Vec<String>,
    theme: String,
    custom_relay: String,
}

#[derive(Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
struct HistoryEntry {
    id: String,
    /// "send" | "receive" | "text"
    direction: String,
    /// "wifi" | "qr" | "bluetooth"
    mode: String,
    peer: String,
    summary: String,
    size: String,
    path: Option<String>,
    timestamp: u64,
    ok: bool,
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct PathsSummary {
    count: usize,
    total_size: u64,
    formatted_size: String,
    names: Vec<String>,
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct QrShareView {
    uri: String,
    img_data_url: String,
    mode: String,
    name: String,
    size: String,
    expires: String,
}

/// Contenu de la fenetre de notification (E2).
#[derive(Serialize, Clone, Default)]
#[serde(rename_all = "camelCase")]
struct Toast {
    id: String,
    /// request | progress | done | fail | text | clear
    kind: String,
    title: String,
    detail: String,
    percent: Option<u8>,
    path: Option<String>,
    cancellable: bool,
    text: Option<String>,
}

struct AppState {
    data_dir: PathBuf,
    info: Mutex<DeviceInfo>,
    dest: Mutex<PathBuf>,
    settings: Mutex<AppSettings>,
    history: Mutex<Vec<HistoryEntry>>,
    glass: bool,
    devices: Mutex<Vec<Device>>,
    pending: Mutex<HashMap<String, oneshot::Sender<bool>>>,
    startup_paths: Mutex<Vec<String>>,
    recv_alias: Mutex<String>,
    recv_started: Mutex<Option<Instant>>,
    active_cancel: Mutex<Option<CancellationToken>>,
    active_bt_cancel: Mutex<Option<Arc<AtomicBool>>>,
    local_qr: Mutex<Option<LocalQrServer>>,
    bt_receiver: Mutex<Option<BtReceiverHandle>>,
    toast_ready: Mutex<bool>,
    toast_pending: Mutex<Option<Toast>>,
    discovery: Mutex<Option<Discovery>>,
    _server: Mutex<Option<ServerHandle>>,
}

static NEXT_ID: AtomicU64 = AtomicU64::new(1);

fn next_id() -> String {
    format!("r{}", NEXT_ID.fetch_add(1, Ordering::Relaxed))
}

fn now_epoch() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

fn load_settings(data_dir: &Path, default_alias: &str, default_dest: &Path) -> AppSettings {
    let path = data_dir.join("settings.json");
    if let Ok(raw) = std::fs::read_to_string(&path) {
        if let Ok(mut s) = serde_json::from_str::<AppSettings>(&raw) {
            if s.alias.trim().is_empty() {
                s.alias = default_alias.to_string();
            }
            if s.dest_dir.trim().is_empty() {
                s.dest_dir = default_dest.to_string_lossy().into_owned();
            }
            return s;
        }
    }
    AppSettings {
        alias: default_alias.to_string(),
        dest_dir: default_dest.to_string_lossy().into_owned(),
        trusted: Vec::new(),
        theme: "auto".into(),
        custom_relay: String::new(),
    }
}

fn persist_settings(data_dir: &Path, s: &AppSettings) {
    let _ = std::fs::create_dir_all(data_dir);
    if let Ok(json) = serde_json::to_string_pretty(s) {
        let _ = std::fs::write(data_dir.join("settings.json"), json);
    }
}

fn load_history(data_dir: &Path) -> Vec<HistoryEntry> {
    let path = data_dir.join("history.json");
    std::fs::read_to_string(path)
        .ok()
        .and_then(|r| serde_json::from_str(&r).ok())
        .unwrap_or_default()
}

fn push_history(app: &AppHandle, entry: HistoryEntry) {
    let st = app.state::<AppState>();
    let list = {
        let mut h = lock(&st.history);
        h.insert(0, entry);
        h.truncate(50);
        h.clone()
    };
    let _ = std::fs::create_dir_all(&st.data_dir);
    if let Ok(json) = serde_json::to_string(&list) {
        let _ = std::fs::write(st.data_dir.join("history.json"), json);
    }
    let _ = app.emit_to("main", "history", list);
}

// ---------- notifications (E2) ----------

fn place_toast(app: &AppHandle) {
    let Some(w) = app.get_webview_window("toast") else { return };
    let Ok(Some(m)) = w.primary_monitor() else { return };
    let scale = m.scale_factor();
    let area = m.work_area();
    let (w_px, h_px) = ((TOAST_W * scale) as i32, (TOAST_H * scale) as i32);
    let margin = (16.0 * scale) as i32;
    let x = area.position.x + area.size.width as i32 - w_px - margin;
    let y = area.position.y + area.size.height as i32 - h_px - margin;
    let _ = w.set_position(tauri::PhysicalPosition::new(x, y));
}

fn deliver(app: &AppHandle, t: Toast, show: bool) {
    let st = app.state::<AppState>();
    if t.kind == "clear" {
        if *lock(&st.toast_ready) {
            let _ = app.emit_to("toast", "toast", t);
        } else {
            let mut p = lock(&st.toast_pending);
            if p.as_ref().map(|x| x.id == t.id).unwrap_or(false) {
                *p = None;
            }
        }
        return;
    }
    if *lock(&st.toast_ready) && app.get_webview_window("toast").is_some() {
        let _ = app.emit_to("toast", "toast", t);
        if show {
            place_toast(app);
            if let Some(w) = app.get_webview_window("toast") {
                let _ = w.show();
            }
        }
        return;
    }
    *lock(&st.toast_pending) = Some(t);
    if app.get_webview_window("toast").is_none() {
        let _ = build_toast(app, st.glass);
    }
}

fn toast(app: &AppHandle, t: Toast) {
    deliver(app, t, true);
}

fn toast_progress(app: &AppHandle, t: Toast) {
    deliver(app, t, false);
}

#[tauri::command]
fn toast_ready(app: AppHandle, state: State<'_, AppState>) -> Option<Toast> {
    *lock(&state.toast_ready) = true;
    let t = lock(&state.toast_pending).take();
    if t.is_some() {
        place_toast(&app);
        if let Some(w) = app.get_webview_window("toast") {
            let _ = w.show();
        }
    }
    t
}

struct RequestGuard {
    app: AppHandle,
    id: String,
}

impl Drop for RequestGuard {
    fn drop(&mut self) {
        let state = self.app.state::<AppState>();
        lock(&state.pending).remove(&self.id);
        toast(&self.app, Toast { id: self.id.clone(), kind: "clear".into(), ..Default::default() });
    }
}

fn make_decider(app: AppHandle) -> Decider {
    Arc::new(move |req: IncomingRequest| {
        let app = app.clone();
        Box::pin(async move {
            // Auto-acceptation pour les appareils de confiance (Favoris).
            let trusted = {
                let st = app.state::<AppState>();
                let s = lock(&st.settings);
                s.trusted.iter().any(|fp| fp == &req.from.fingerprint)
            };
            if trusted {
                return true;
            }
            let id = next_id();
            let (tx, rx) = oneshot::channel();
            lock(&app.state::<AppState>().pending).insert(id.clone(), tx);
            let _guard = RequestGuard { app: app.clone(), id: id.clone() };
            let (title, detail) = messages::request(&req.from.alias, req.count, req.total);
            toast(&app, Toast { id, kind: "request".into(), title, detail, ..Default::default() });
            rx.await.unwrap_or(false)
        })
    })
}

#[tauri::command]
fn answer(state: State<'_, AppState>, id: String, accept: bool) {
    if let Some(tx) = lock(&state.pending).remove(&id) {
        let _ = tx.send(accept);
    }
}

#[tauri::command]
fn cancel_send(state: State<'_, AppState>) {
    if let Some(tok) = lock(&state.active_cancel).take() {
        tok.cancel();
    }
    if let Some(bt) = lock(&state.active_bt_cancel).take() {
        bt.store(true, Ordering::Relaxed);
    }
}

fn progress_detail(done: u64, total: u64, started: Instant) -> String {
    let elapsed = started.elapsed().as_secs_f64();
    if elapsed < 0.25 || done == 0 {
        return format!("{} / {}", messages::format_size(done), messages::format_size(total));
    }
    let speed = done as f64 / elapsed;
    let rem = total.saturating_sub(done) as f64 / speed.max(1.0);
    let eta = messages::format_eta(rem);
    if eta.is_empty() {
        messages::format_speed(speed)
    } else {
        format!("{} · {}", messages::format_speed(speed), eta)
    }
}

fn spawn_event_pump(app: AppHandle, mut rx: tokio::sync::mpsc::UnboundedReceiver<Event>) {
    tauri::async_runtime::spawn(async move {
        while let Some(ev) = rx.recv().await {
            let state = app.state::<AppState>();
            match ev {
                Event::Started { session, from, .. } => {
                    *lock(&state.recv_alias) = from.alias.clone();
                    *lock(&state.recv_started) = Some(Instant::now());
                    toast(&app, Toast {
                        id: session,
                        kind: "progress".into(),
                        title: messages::receiving(&from.alias),
                        percent: Some(0),
                        ..Default::default()
                    });
                }
                Event::Progress { session, done, total } => {
                    let alias = lock(&state.recv_alias).clone();
                    let started = lock(&state.recv_started).unwrap_or_else(Instant::now);
                    toast_progress(&app, Toast {
                        id: session,
                        kind: "progress".into(),
                        title: messages::receiving(&alias),
                        detail: progress_detail(done, total, started),
                        percent: Some(messages::percent(done, total)),
                        ..Default::default()
                    });
                }
                Event::Completed { session, from, paths } => {
                    let mode = if from.device_type == "bluetooth" { "bluetooth" } else { "wifi" };
                    let first_path = paths.first().map(|p| p.to_string_lossy().into_owned());
                    let summary = paths
                        .first()
                        .and_then(|p| p.file_name())
                        .map(|n| n.to_string_lossy().into_owned())
                        .unwrap_or_else(|| messages::items(paths.len()));
                    push_history(
                        &app,
                        HistoryEntry {
                            id: session.clone(),
                            direction: "receive".into(),
                            mode: mode.into(),
                            peer: from.alias.clone(),
                            summary,
                            size: messages::items(paths.len()),
                            path: first_path.clone(),
                            timestamp: now_epoch(),
                            ok: true,
                        },
                    );
                    toast(&app, Toast {
                        id: session,
                        kind: "done".into(),
                        title: messages::received(&from.alias),
                        detail: "Cliquer pour ouvrir".into(),
                        path: first_path,
                        ..Default::default()
                    });
                }
                Event::TextReceived { from, text } => {
                    let preview: String = text.chars().take(120).collect();
                    push_history(
                        &app,
                        HistoryEntry {
                            id: next_id(),
                            direction: "text".into(),
                            mode: "wifi".into(),
                            peer: from.alias.clone(),
                            summary: preview.clone(),
                            size: format!("{} car.", text.chars().count()),
                            path: None,
                            timestamp: now_epoch(),
                            ok: true,
                        },
                    );
                    let _ = app.emit_to("main", "text_received", (&from.alias, &text));
                    toast(&app, Toast {
                        id: next_id(),
                        kind: "text".into(),
                        title: format!("Texte de {}", from.alias),
                        detail: preview,
                        text: Some(text),
                        ..Default::default()
                    });
                }
                Event::Failed { session, reason } => {
                    toast(&app, Toast {
                        id: session,
                        kind: "fail".into(),
                        title: reason.message(),
                        ..Default::default()
                    });
                }
            }
        }
    });
}

// ---------- envoi Wi-Fi & Texte ----------

#[tauri::command]
async fn send_to(
    app: AppHandle,
    state: State<'_, AppState>,
    fingerprint: String,
    paths: Vec<String>,
) -> Result<(), String> {
    let dev = lock(&state.devices)
        .iter()
        .find(|d| d.info.fingerprint == fingerprint)
        .cloned()
        .ok_or("Introuvable")?;
    let paths: Vec<PathBuf> = paths.into_iter().map(PathBuf::from).filter(|p| p.exists()).collect();
    if paths.is_empty() {
        return Err("Rien à envoyer".into());
    }
    let summary_label = if paths.len() == 1 {
        paths[0].file_name().unwrap_or_default().to_string_lossy().into_owned()
    } else {
        messages::items(paths.len())
    };
    let alias = dev.info.alias.clone();
    let id = next_id();
    let cancel = CancellationToken::new();
    *lock(&state.active_cancel) = Some(cancel.clone());

    toast(&app, Toast {
        id: id.clone(),
        kind: "progress".into(),
        title: messages::sending(&alias),
        detail: "En attente d'accord…".into(),
        percent: None,
        cancellable: true,
        ..Default::default()
    });
    let info = lock(&state.info).clone();
    let opts = SendOptions::new(info);
    let started = Instant::now();
    let last = Arc::new(Mutex::new(Instant::now() - Duration::from_secs(1)));
    let (app2, id2, alias2) = (app.clone(), id.clone(), alias.clone());
    let progress: ProgressFn = Arc::new(move |done, total| {
        let mut l = lock(&last);
        if l.elapsed() < Duration::from_millis(150) && done < total {
            return;
        }
        *l = Instant::now();
        toast_progress(&app2, Toast {
            id: id2.clone(),
            kind: "progress".into(),
            title: messages::sending(&alias2),
            detail: progress_detail(done, total, started),
            percent: Some(messages::percent(done, total)),
            cancellable: true,
            ..Default::default()
        });
    });
    tauri::async_runtime::spawn(async move {
        let res = send(dev.addr, &paths, &opts, Some(progress), Some(cancel)).await;
        *lock(&app.state::<AppState>().active_cancel) = None;
        let t = match res {
            Ok(()) => {
                push_history(
                    &app,
                    HistoryEntry {
                        id: id.clone(),
                        direction: "send".into(),
                        mode: "wifi".into(),
                        peer: alias.clone(),
                        summary: summary_label,
                        size: messages::items(paths.len()),
                        path: None,
                        timestamp: now_epoch(),
                        ok: true,
                    },
                );
                Toast {
                    id,
                    kind: "done".into(),
                    title: messages::sent(&alias),
                    ..Default::default()
                }
            }
            Err(SendError::Failed(r)) => Toast {
                id,
                kind: "fail".into(),
                title: r.message(),
                ..Default::default()
            },
            Err(SendError::Cancelled) => Toast { id, kind: "clear".into(), ..Default::default() },
        };
        toast(&app, t);
    });
    Ok(())
}

#[tauri::command]
async fn send_text_to(
    app: AppHandle,
    state: State<'_, AppState>,
    fingerprint: String,
    text: String,
) -> Result<(), String> {
    let dev = lock(&state.devices)
        .iter()
        .find(|d| d.info.fingerprint == fingerprint)
        .cloned()
        .ok_or("Appareil introuvable")?;
    let info = lock(&state.info).clone();
    send_text(dev.addr, &info, &text).await.map_err(|e| e.to_string())?;
    push_history(
        &app,
        HistoryEntry {
            id: next_id(),
            direction: "send".into(),
            mode: "wifi".into(),
            peer: dev.info.alias.clone(),
            summary: text.chars().take(80).collect(),
            size: "Texte".into(),
            path: None,
            timestamp: now_epoch(),
            ok: true,
        },
    );
    Ok(())
}

// ---------- QR Code (Hors reseau E2E, Lien direct navigateur, Wi-Fi local) ----------

fn qr_to_view(r: QrShareResult) -> QrShareView {
    let b64 = B64.encode(r.svg.as_bytes());
    QrShareView {
        uri: r.uri,
        img_data_url: format!("data:image/svg+xml;base64,{b64}"),
        mode: r.mode,
        name: r.name,
        size: messages::format_size(r.size),
        expires: r.expires,
    }
}

#[tauri::command]
async fn share_qr(
    app: AppHandle,
    state: State<'_, AppState>,
    paths: Vec<String>,
    mode: String,
    expiry: String,
) -> Result<QrShareView, String> {
    let paths: Vec<PathBuf> = paths.into_iter().map(PathBuf::from).filter(|p| p.exists()).collect();
    if paths.is_empty() {
        return Err("Choisissez ou déposez des fichiers d'abord".into());
    }
    let custom_relay = lock(&state.settings).custom_relay.clone();
    let res = if mode == "lan" {
        let (r, srv) = relay::start_local_qr_share(&paths).await?;
        *lock(&state.local_qr) = Some(srv);
        r
    } else {
        let encrypted = mode != "relay-direct";
        relay::share_via_relay(&paths, encrypted, &expiry, Some(&custom_relay)).await?
    };
    push_history(
        &app,
        HistoryEntry {
            id: next_id(),
            direction: "send".into(),
            mode: "qr".into(),
            peer: if mode == "lan" { "QR Wi-Fi".into() } else { "QR Internet".into() },
            summary: res.name.clone(),
            size: messages::format_size(res.size),
            path: None,
            timestamp: now_epoch(),
            ok: true,
        },
    );
    Ok(qr_to_view(res))
}

#[tauri::command]
fn share_text_qr(text: String) -> Result<QrShareView, String> {
    let t = text.trim();
    if t.is_empty() {
        return Err("Texte vide".into());
    }
    let svg = relay::generate_qr_svg(t)?;
    let b64 = B64.encode(svg.as_bytes());
    Ok(QrShareView {
        uri: t.to_string(),
        img_data_url: format!("data:image/svg+xml;base64,{b64}"),
        mode: "text".into(),
        name: t.chars().take(48).collect(),
        size: format!("{} car.", t.chars().count()),
        expires: "Hors-ligne".into(),
    })
}

#[tauri::command]
fn stop_local_qr(state: State<'_, AppState>) {
    *lock(&state.local_qr) = None;
}

#[tauri::command]
async fn receive_from_uri(app: AppHandle, state: State<'_, AppState>, uri: String) -> Result<String, String> {
    let dest = lock(&state.dest).clone();
    let id = next_id();
    toast(&app, Toast {
        id: id.clone(),
        kind: "progress".into(),
        title: "Téléchargement QR…".into(),
        detail: "Réception & déchiffrement".into(),
        percent: None,
        ..Default::default()
    });
    match relay::download_from_uri(&uri, &dest).await {
        Ok(paths) => {
            let first = paths.first().map(|p| p.to_string_lossy().into_owned());
            let name = paths
                .first()
                .and_then(|p| p.file_name())
                .map(|n| n.to_string_lossy().into_owned())
                .unwrap_or_else(|| "Fichier reçu".into());
            push_history(
                &app,
                HistoryEntry {
                    id: id.clone(),
                    direction: "receive".into(),
                    mode: "qr".into(),
                    peer: "Lien / QR".into(),
                    summary: name.clone(),
                    size: messages::items(paths.len()),
                    path: first.clone(),
                    timestamp: now_epoch(),
                    ok: true,
                },
            );
            toast(&app, Toast {
                id,
                kind: "done".into(),
                title: format!("Reçu : {name}"),
                detail: "Cliquer pour ouvrir".into(),
                path: first,
                ..Default::default()
            });
            Ok(name)
        }
        Err(e) => {
            toast(&app, Toast {
                id,
                kind: "fail".into(),
                title: "Échec réception QR".into(),
                detail: e.clone(),
                ..Default::default()
            });
            Err(e)
        }
    }
}

// ---------- Bluetooth ----------

#[tauri::command]
async fn bt_list_devices() -> Result<Vec<BtDevice>, String> {
    tokio::task::spawn_blocking(bluetooth::list_paired_devices)
        .await
        .map_err(|e| e.to_string())?
}

#[tauri::command]
async fn bt_send(
    app: AppHandle,
    state: State<'_, AppState>,
    raw_addr: u64,
    name: String,
    paths: Vec<String>,
) -> Result<(), String> {
    let paths: Vec<PathBuf> = paths.into_iter().map(PathBuf::from).filter(|p| p.exists()).collect();
    if paths.is_empty() {
        return Err("Choisissez ou déposez des fichiers d'abord".into());
    }
    let my_alias = lock(&state.info).alias.clone();
    let id = next_id();
    let cancel = Arc::new(AtomicBool::new(false));
    *lock(&state.active_bt_cancel) = Some(cancel.clone());

    toast(&app, Toast {
        id: id.clone(),
        kind: "progress".into(),
        title: format!("Bluetooth → {name}"),
        detail: "Connexion…".into(),
        percent: None,
        cancellable: true,
        ..Default::default()
    });

    let started = Instant::now();
    let last = Arc::new(Mutex::new(Instant::now() - Duration::from_secs(1)));
    let (app2, id2, name2) = (app.clone(), id.clone(), name.clone());
    let progress: ProgressFn = Arc::new(move |done, total| {
        let mut l = lock(&last);
        if l.elapsed() < Duration::from_millis(200) && done < total {
            return;
        }
        *l = Instant::now();
        toast_progress(&app2, Toast {
            id: id2.clone(),
            kind: "progress".into(),
            title: format!("Bluetooth → {name2}"),
            detail: progress_detail(done, total, started),
            percent: Some(messages::percent(done, total)),
            cancellable: true,
            ..Default::default()
        });
    });

    let summary = if paths.len() == 1 {
        paths[0].file_name().unwrap_or_default().to_string_lossy().into_owned()
    } else {
        messages::items(paths.len())
    };
    let count = paths.len();
    tauri::async_runtime::spawn(async move {
        let res = tokio::task::spawn_blocking(move || {
            bluetooth::send_bluetooth_rfcomm(raw_addr, &paths, &my_alias, Some(progress), Some(cancel))
        })
        .await
        .unwrap_or_else(|e| Err(e.to_string()));
        *lock(&app.state::<AppState>().active_bt_cancel) = None;
        match res {
            Ok(()) => {
                push_history(
                    &app,
                    HistoryEntry {
                        id: id.clone(),
                        direction: "send".into(),
                        mode: "bluetooth".into(),
                        peer: name.clone(),
                        summary,
                        size: messages::items(count),
                        path: None,
                        timestamp: now_epoch(),
                        ok: true,
                    },
                );
                toast(&app, Toast {
                    id,
                    kind: "done".into(),
                    title: format!("Envoyé en Bluetooth à {name}"),
                    ..Default::default()
                });
            }
            Err(e) => {
                toast(&app, Toast {
                    id,
                    kind: "fail".into(),
                    title: "Bluetooth".into(),
                    detail: e,
                    ..Default::default()
                });
            }
        }
    });
    Ok(())
}

#[tauri::command]
fn bt_send_wizard(paths: Vec<String>) -> Result<(), String> {
    let paths: Vec<PathBuf> = paths.into_iter().map(PathBuf::from).filter(|p| p.exists()).collect();
    bluetooth::launch_windows_bluetooth_wizard(&paths)
}

#[tauri::command]
fn bt_receive_wizard() -> Result<(), String> {
    bluetooth::launch_windows_bluetooth_receive()
}

#[tauri::command]
fn bt_receiver_status(state: State<'_, AppState>) -> bool {
    lock(&state.bt_receiver).is_some()
}

#[tauri::command]
fn bt_toggle_receiver(app: AppHandle, state: State<'_, AppState>, enabled: bool) -> Result<bool, String> {
    let mut slot = lock(&state.bt_receiver);
    if !enabled {
        *slot = None;
        return Ok(false);
    }
    if slot.is_some() {
        return Ok(true);
    }
    let dest = lock(&state.dest).clone();
    let decider = make_decider(app.clone());
    let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
    spawn_event_pump(app, rx);
    let rt = tauri::async_runtime::handle().inner().clone();
    let handle = bluetooth::start_bluetooth_receiver(dest, decider, Some(tx), rt)?;
    *slot = Some(handle);
    Ok(true)
}

// ---------- commandes interface principale, fichiers, parametres & historique ----------

#[tauri::command]
fn get_devices(state: State<'_, AppState>) -> Vec<DeviceView> {
    let trusted: HashSet<String> = lock(&state.settings).trusted.iter().cloned().collect();
    views(&lock(&state.devices), &trusted)
}

#[tauri::command]
fn rescan_devices(state: State<'_, AppState>) {
    if let Some(d) = lock(&state.discovery).as_ref() {
        d.rescan();
    }
}

#[tauri::command]
async fn pick_files() -> Vec<String> {
    rfd::AsyncFileDialog::new()
        .set_title("Choisir des fichiers à envoyer")
        .pick_files()
        .await
        .map(|list| list.into_iter().map(|f| f.path().to_string_lossy().into_owned()).collect())
        .unwrap_or_default()
}

#[tauri::command]
async fn pick_folder() -> Vec<String> {
    rfd::AsyncFileDialog::new()
        .set_title("Choisir un dossier à envoyer")
        .pick_folder()
        .await
        .map(|f| vec![f.path().to_string_lossy().into_owned()])
        .unwrap_or_default()
}

#[tauri::command]
fn summarize_paths(paths: Vec<String>) -> PathsSummary {
    let mut total = 0u64;
    let mut names = Vec::new();
    for p in &paths {
        let pb = Path::new(p);
        if let Ok(m) = std::fs::metadata(pb) {
            if m.is_file() {
                total = total.saturating_add(m.len());
            }
            if let Some(n) = pb.file_name() {
                names.push(n.to_string_lossy().into_owned());
            }
        }
    }
    PathsSummary {
        count: paths.len(),
        total_size: total,
        formatted_size: if total > 0 { messages::format_size(total) } else { String::new() },
        names,
    }
}

#[tauri::command]
fn take_startup_paths(state: State<'_, AppState>) -> Vec<String> {
    std::mem::take(&mut *lock(&state.startup_paths))
}

#[tauri::command]
fn glass(state: State<'_, AppState>) -> bool {
    state.glass
}

#[tauri::command]
fn my_alias(state: State<'_, AppState>) -> String {
    lock(&state.info).alias.clone()
}

#[tauri::command]
fn get_settings(state: State<'_, AppState>) -> AppSettings {
    lock(&state.settings).clone()
}

#[tauri::command]
fn save_settings(app: AppHandle, state: State<'_, AppState>, mut settings: AppSettings) -> AppSettings {
    let clean_alias: String = settings
        .alias
        .chars()
        .filter(|c| !c.is_control())
        .take(32)
        .collect::<String>()
        .trim()
        .to_string();
    if !clean_alias.is_empty() {
        settings.alias = clean_alias.clone();
        lock(&state.info).alias = clean_alias;
    }
    if !settings.dest_dir.trim().is_empty() {
        let p = PathBuf::from(&settings.dest_dir);
        let _ = std::fs::create_dir_all(&p);
        *lock(&state.dest) = p;
    }
    *lock(&state.settings) = settings.clone();
    persist_settings(&state.data_dir, &settings);
    let trusted: HashSet<String> = settings.trusted.iter().cloned().collect();
    let v = views(&lock(&state.devices), &trusted);
    let _ = app.emit_to("main", "devices", v);
    settings
}

#[tauri::command]
fn toggle_trusted(app: AppHandle, state: State<'_, AppState>, fingerprint: String) -> bool {
    let now_trusted = {
        let mut s = lock(&state.settings);
        if let Some(pos) = s.trusted.iter().position(|x| x == &fingerprint) {
            s.trusted.remove(pos);
            false
        } else {
            s.trusted.push(fingerprint);
            true
        }
    };
    let s = lock(&state.settings).clone();
    persist_settings(&state.data_dir, &s);
    let trusted: HashSet<String> = s.trusted.iter().cloned().collect();
    let v = views(&lock(&state.devices), &trusted);
    let _ = app.emit_to("main", "devices", v);
    now_trusted
}

#[tauri::command]
async fn pick_dest_dir(app: AppHandle, state: State<'_, AppState>) -> Result<Option<String>, String> {
    let Some(folder) = rfd::AsyncFileDialog::new()
        .set_title("Dossier de réception FileSend")
        .pick_folder()
        .await
    else {
        return Ok(None);
    };
    let path_str = folder.path().to_string_lossy().into_owned();
    let mut s = lock(&state.settings).clone();
    s.dest_dir = path_str.clone();
    save_settings(app, state, s);
    Ok(Some(path_str))
}

#[tauri::command]
fn open_dest_dir(state: State<'_, AppState>) -> Result<(), String> {
    let root = lock(&state.dest).clone();
    let _ = std::fs::create_dir_all(&root);
    std::process::Command::new("explorer")
        .arg(root)
        .spawn()
        .map(|_| ())
        .map_err(|e| e.to_string())
}

#[tauri::command]
fn get_history(state: State<'_, AppState>) -> Vec<HistoryEntry> {
    lock(&state.history).clone()
}

#[tauri::command]
fn clear_history(state: State<'_, AppState>) {
    lock(&state.history).clear();
    let _ = std::fs::remove_file(state.data_dir.join("history.json"));
}

/// Ouvre l'Explorateur sur un element recu (AC-F3-4). Limite au dossier de reception.
#[tauri::command]
fn open_received(state: State<'_, AppState>, path: String) -> Result<(), String> {
    let p = std::fs::canonicalize(&path).map_err(|e| e.to_string())?;
    let root = std::fs::canonicalize(&*lock(&state.dest)).map_err(|e| e.to_string())?;
    if !p.starts_with(&root) {
        return Err("hors du dossier de reception".into());
    }
    std::process::Command::new("explorer")
        .arg(format!("/select,{}", p.display()))
        .spawn()
        .map(|_| ())
        .map_err(|e| e.to_string())
}

fn views(devs: &[Device], trusted: &HashSet<String>) -> Vec<DeviceView> {
    devs.iter()
        .map(|d| DeviceView {
            alias: d.info.alias.clone(),
            fingerprint: d.info.fingerprint.clone(),
            kind: d.info.device_type.clone(),
            trusted: trusted.contains(&d.info.fingerprint),
        })
        .collect()
}

// ---------- demarrage ----------

fn windows_build() -> u32 {
    use std::os::windows::process::CommandExt;
    let out = std::process::Command::new("cmd")
        .args(["/c", "ver"])
        .creation_flags(0x0800_0000)
        .output()
        .ok();
    let s = out.map(|o| String::from_utf8_lossy(&o.stdout).into_owned()).unwrap_or_default();
    s.split("10.0.")
        .nth(1)
        .and_then(|r| r.split('.').next())
        .and_then(|b| b.trim().parse().ok())
        .unwrap_or(0)
}

fn file_args(args: &[String]) -> Vec<String> {
    args.iter()
        .skip(1)
        .filter(|a| !a.starts_with("--") && Path::new(a).exists())
        .cloned()
        .collect()
}

fn show_main(app: &AppHandle) {
    match app.get_webview_window("main") {
        Some(w) => {
            let _ = w.show();
            let _ = w.unminimize();
            let _ = w.set_focus();
        }
        None => {
            let _ = build_main(app, true);
        }
    }
}

fn sendto_lnk() -> Option<PathBuf> {
    std::env::var_os("APPDATA")
        .map(|a| PathBuf::from(a).join(r"Microsoft\Windows\SendTo\FileSend.lnk"))
}

#[cfg_attr(debug_assertions, allow(dead_code))]
fn ensure_sendto() {
    use std::os::windows::process::CommandExt;
    if std::env::var_os("FILESEND_NO_SENDTO").is_some() {
        return;
    }
    let (Some(lnk), Ok(exe)) = (sendto_lnk(), std::env::current_exe()) else { return };
    if lnk.exists() {
        return;
    }
    let q = |p: &Path| p.display().to_string().replace('\'', "''");
    let script = format!(
        "$s=(New-Object -ComObject WScript.Shell).CreateShortcut('{}');$s.TargetPath='{}';$s.Save()",
        q(&lnk),
        q(&exe)
    );
    let _ = std::process::Command::new("powershell")
        .args(["-NoProfile", "-WindowStyle", "Hidden", "-Command", &script])
        .creation_flags(0x0800_0000)
        .spawn();
}

fn build_main(app: &AppHandle, visible: bool) -> tauri::Result<()> {
    let glass = app.state::<AppState>().glass;
    let mut main = WebviewWindowBuilder::new(app, "main", WebviewUrl::App("index.html".into()))
        .title("FileSend")
        .inner_size(388.0, 520.0)
        .resizable(false)
        .maximizable(false)
        .decorations(false)
        .transparent(true)
        .shadow(true)
        .center()
        .visible(visible);
    if glass {
        main = main.effects(EffectsBuilder::new().effect(Effect::Mica).build());
    }
    main.build()?;
    Ok(())
}

fn build_toast(app: &AppHandle, glass: bool) -> tauri::Result<()> {
    let mut t = WebviewWindowBuilder::new(app, "toast", WebviewUrl::App("toast.html".into()))
        .title("FileSend notification")
        .inner_size(TOAST_W, TOAST_H)
        .resizable(false)
        .maximizable(false)
        .minimizable(false)
        .decorations(false)
        .transparent(true)
        .shadow(true)
        .always_on_top(true)
        .skip_taskbar(true)
        .focused(false)
        .visible(false);
    if glass {
        t = t.effects(EffectsBuilder::new().effect(Effect::Acrylic).build());
    }
    t.build()?;
    Ok(())
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.iter().any(|a| a == "--remove-sendto") {
        if let Some(l) = sendto_lnk() {
            let _ = std::fs::remove_file(l);
        }
        return;
    }
    let hidden = args.iter().any(|a| a == "--minimized");
    let startup = file_args(&args);

    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, args, _cwd| {
            let paths = file_args(&args);
            if !paths.is_empty() {
                if app.get_webview_window("main").is_some() {
                    let _ = app.emit_to("main", "paths", paths);
                } else {
                    *lock(&app.state::<AppState>().startup_paths) = paths;
                }
            }
            show_main(app);
        }))
        .invoke_handler(tauri::generate_handler![
            answer,
            toast_ready,
            send_to,
            send_text_to,
            cancel_send,
            get_devices,
            rescan_devices,
            pick_files,
            pick_folder,
            summarize_paths,
            take_startup_paths,
            glass,
            my_alias,
            open_received,
            share_qr,
            share_text_qr,
            stop_local_qr,
            receive_from_uri,
            bt_list_devices,
            bt_send,
            bt_send_wizard,
            bt_receive_wizard,
            bt_receiver_status,
            bt_toggle_receiver,
            get_settings,
            save_settings,
            toggle_trusted,
            pick_dest_dir,
            open_dest_dir,
            get_history,
            clear_history,
        ])
        .on_window_event(|w, ev| {
            if w.label() == "toast" {
                if let WindowEvent::Destroyed = ev {
                    *lock(&w.app_handle().state::<AppState>().toast_ready) = false;
                }
            }
        })
        .setup(move |app| {
            let handle = app.handle().clone();
            let data = app.path().app_data_dir()?;
            let default_dest = app.path().download_dir()?.join("FileSend");
            let id = Identity::load_or_create(&data.join("identity"))?;
            let default_alias = std::env::var("COMPUTERNAME").unwrap_or_else(|_| "PC".into());
            let settings = load_settings(&data, &default_alias, &default_dest);
            let history = load_history(&data);
            let dest = PathBuf::from(&settings.dest_dir);
            let info = DeviceInfo {
                alias: settings.alias.clone(),
                fingerprint: id.fingerprint.clone(),
                device_type: "desktop".into(),
            };
            let glass = windows_build() >= 22000;

            app.manage(AppState {
                data_dir: data,
                info: Mutex::new(info.clone()),
                dest: Mutex::new(dest.clone()),
                settings: Mutex::new(settings),
                history: Mutex::new(history),
                glass,
                devices: Mutex::new(Vec::new()),
                pending: Mutex::new(HashMap::new()),
                startup_paths: Mutex::new(startup.clone()),
                recv_alias: Mutex::new(String::new()),
                recv_started: Mutex::new(None),
                active_cancel: Mutex::new(None),
                active_bt_cancel: Mutex::new(None),
                local_qr: Mutex::new(None),
                bt_receiver: Mutex::new(None),
                toast_ready: Mutex::new(false),
                toast_pending: Mutex::new(None),
                discovery: Mutex::new(None),
                _server: Mutex::new(None),
            });
            if !hidden || !startup.is_empty() {
                build_main(&handle, true)?;
            }

            // Serveur de reception.
            let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
            spawn_event_pump(handle.clone(), rx);
            let cfg = ServerConfig::new(dest, info.clone());
            let decider = make_decider(handle.clone());
            let h2 = handle.clone();
            let id2 = id.clone();
            let info2 = info.clone();
            tauri::async_runtime::spawn(async move {
                match server::start(cfg, &id2, decider, Some(tx)).await {
                    Ok(srv) => {
                        let st = h2.state::<AppState>();
                        *lock(&st._server) = Some(srv);
                        match Discovery::start(DiscoveryConfig::new(info2, DEFAULT_PORT)).await {
                            Ok(d) => {
                                let mut sub = d.subscribe();
                                *lock(&st.discovery) = Some(d);
                                while sub.changed().await.is_ok() {
                                    let devs = sub.borrow_and_update().clone();
                                    let trusted: HashSet<String> =
                                        lock(&st.settings).trusted.iter().cloned().collect();
                                    let v = views(&devs, &trusted);
                                    if let Some(path) = std::env::var_os("FILESEND_LOG") {
                                        use std::io::Write;
                                        if let Ok(mut f) = std::fs::OpenOptions::new()
                                            .create(true)
                                            .append(true)
                                            .open(path)
                                        {
                                            let _ = writeln!(
                                                f,
                                                "{} appareil(s): {}",
                                                devs.len(),
                                                devs.iter()
                                                    .map(|d| format!("{} via {:?}", d.info.alias, d.via))
                                                    .collect::<Vec<_>>()
                                                    .join(" ; ")
                                            );
                                        }
                                    }
                                    *lock(&st.devices) = devs;
                                    let _ = h2.emit_to("main", "devices", v);
                                }
                            }
                            Err(e) => eprintln!("decouverte: {e}"),
                        }
                    }
                    Err(e) => {
                        toast(&h2, Toast {
                            id: next_id(),
                            kind: "fail".into(),
                            title: "Port occupé".into(),
                            detail: e,
                            ..Default::default()
                        });
                    }
                }
            });

            // Zone de notification (E3).
            let open = MenuItem::with_id(app, "open", "Ouvrir FileSend", true, None::<&str>)?;
            let folder = MenuItem::with_id(app, "folder", "Dossier reçus", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "Quitter", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&open, &folder, &quit])?;
            let mut tray = TrayIconBuilder::new()
                .tooltip("FileSend")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, ev| match ev.id().as_ref() {
                    "open" => show_main(app),
                    "folder" => {
                        let st = app.state::<AppState>();
                        let root = lock(&st.dest).clone();
                        let _ = std::fs::create_dir_all(&root);
                        let _ = std::process::Command::new("explorer").arg(root).spawn();
                    }
                    "quit" => app.exit(0),
                    _ => {}
                })
                .on_tray_icon_event(|tray, ev| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = ev
                    {
                        show_main(tray.app_handle());
                    }
                });
            if let Some(icon) = app.default_window_icon() {
                tray = tray.icon(icon.clone());
            }
            tray.build(app)?;

            #[cfg(not(debug_assertions))]
            ensure_sendto();
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("erreur au lancement de FileSend")
        .run(|_app, ev| {
            if let tauri::RunEvent::ExitRequested { code: None, api, .. } = ev {
                api.prevent_exit();
            }
        });
}
