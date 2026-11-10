#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use filesend_core::client::{send, ProgressFn, SendError, SendOptions};
use filesend_core::discovery::{Device, Discovery, DiscoveryConfig};
use filesend_core::events::Event;
use filesend_core::identity::Identity;
use filesend_core::messages;
use filesend_core::protocol::{DeviceInfo, DEFAULT_PORT};
use filesend_core::server::{self, Decider, IncomingRequest, ServerConfig, ServerHandle};
use serde::Serialize;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::window::{Effect, EffectsBuilder};
use tauri::{AppHandle, Emitter, Manager, State, WebviewUrl, WebviewWindowBuilder, WindowEvent};
use tokio::sync::oneshot;

const TOAST_W: f64 = 340.0;
const TOAST_H: f64 = 112.0;

#[derive(Serialize, Clone)]
struct DeviceView {
    alias: String,
    fingerprint: String,
    kind: String,
}

/// Contenu de la fenetre de notification (E2).
#[derive(Serialize, Clone, Default)]
struct Toast {
    id: String,
    /// request | progress | done | fail | clear
    kind: String,
    title: String,
    detail: String,
    percent: Option<u8>,
    path: Option<String>,
}

struct AppState {
    info: DeviceInfo,
    dest: PathBuf,
    glass: bool,
    devices: Mutex<Vec<Device>>,
    pending: Mutex<HashMap<String, oneshot::Sender<bool>>>,
    startup_paths: Mutex<Vec<String>>,
    recv_alias: Mutex<String>,
    toast_ready: Mutex<bool>,
    toast_pending: Mutex<Option<Toast>>,
    // Maintenus en vie pendant toute l'execution.
    _discovery: Mutex<Option<Discovery>>,
    _server: Mutex<Option<ServerHandle>>,
}

static NEXT_ID: AtomicU64 = AtomicU64::new(1);

fn next_id() -> String {
    format!("r{}", NEXT_ID.fetch_add(1, Ordering::Relaxed))
}

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
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

/// Fenetre de notification creee a la demande et detruite apres usage : aucune
/// fenetre (donc aucun WebView2) n'existe au repos.
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

/// Mise a jour de progression : n'ouvre ni ne deplace la fenetre.
fn toast_progress(app: &AppHandle, t: Toast) {
    deliver(app, t, false);
}

/// Appelee par la page de notification des qu'elle est prete.
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

/// Retire la demande si la requete est abandonnee (delai ecoule, expediteur parti).
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

fn spawn_event_pump(app: AppHandle, mut rx: tokio::sync::mpsc::UnboundedReceiver<Event>) {
    tauri::async_runtime::spawn(async move {
        while let Some(ev) = rx.recv().await {
            let state = app.state::<AppState>();
            match ev {
                Event::Started { session, from, .. } => {
                    *lock(&state.recv_alias) = from.alias.clone();
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
                    toast_progress(&app, Toast {
                        id: session,
                        kind: "progress".into(),
                        title: messages::receiving(&alias),
                        percent: Some(messages::percent(done, total)),
                        ..Default::default()
                    });
                }
                Event::Completed { session, from, paths } => {
                    toast(&app, Toast {
                        id: session,
                        kind: "done".into(),
                        title: messages::received(&from.alias),
                        path: paths.first().map(|p| p.to_string_lossy().into_owned()),
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

// ---------- envoi (F2) ----------

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
    let alias = dev.info.alias.clone();
    let id = next_id();
    toast(&app, Toast {
        id: id.clone(),
        kind: "progress".into(),
        title: messages::sending(&alias),
        detail: "En attente".into(),
        percent: None,
        ..Default::default()
    });
    let opts = SendOptions::new(state.info.clone());
    let last = Arc::new(Mutex::new(Instant::now() - Duration::from_secs(1)));
    let (app2, id2, alias2) = (app.clone(), id.clone(), alias.clone());
    let progress: ProgressFn = Arc::new(move |done, total| {
        let mut l = lock(&last);
        if l.elapsed() < Duration::from_millis(150) {
            return;
        }
        *l = Instant::now();
        toast_progress(&app2, Toast {
            id: id2.clone(),
            kind: "progress".into(),
            title: messages::sending(&alias2),
            percent: Some(messages::percent(done, total)),
            ..Default::default()
        });
    });
    tauri::async_runtime::spawn(async move {
        let t = match send(dev.addr, &paths, &opts, Some(progress), None).await {
            Ok(()) => Toast {
                id,
                kind: "done".into(),
                title: messages::sent(&alias),
                ..Default::default()
            },
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

// ---------- commandes de l'interface principale (E1) ----------

#[tauri::command]
fn get_devices(state: State<'_, AppState>) -> Vec<DeviceView> {
    views(&lock(&state.devices))
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
    state.info.alias.clone()
}

/// Ouvre l'Explorateur sur un element recu (AC-F3-4). Limite au dossier de reception.
#[tauri::command]
fn open_received(state: State<'_, AppState>, path: String) -> Result<(), String> {
    let p = std::fs::canonicalize(&path).map_err(|e| e.to_string())?;
    let root = std::fs::canonicalize(&state.dest).map_err(|e| e.to_string())?;
    if !p.starts_with(&root) {
        return Err("hors du dossier de reception".into());
    }
    std::process::Command::new("explorer")
        .arg(format!("/select,{}", p.display()))
        .spawn()
        .map(|_| ())
        .map_err(|e| e.to_string())
}

fn views(devs: &[Device]) -> Vec<DeviceView> {
    devs.iter()
        .map(|d| DeviceView {
            alias: d.info.alias.clone(),
            fingerprint: d.info.fingerprint.clone(),
            kind: d.info.device_type.clone(),
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
    // "Microsoft Windows [Version 10.0.26300.1234]"
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

/// Cree l'entree "Envoyer vers > FileSend" (une seule fois, builds release).
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
        .inner_size(360.0, 480.0)
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
            answer, toast_ready, send_to, get_devices, take_startup_paths, glass, my_alias, open_received
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
            let dest = app.path().download_dir()?.join("FileSend");
            let id = Identity::load_or_create(&data.join("identity"))?;
            let alias = std::env::var("COMPUTERNAME").unwrap_or_else(|_| "PC".into());
            let info = DeviceInfo {
                alias,
                fingerprint: id.fingerprint.clone(),
                device_type: "desktop".into(),
            };
            let glass = windows_build() >= 22000;

            app.manage(AppState {
                info: info.clone(),
                dest: dest.clone(),
                glass,
                devices: Mutex::new(Vec::new()),
                pending: Mutex::new(HashMap::new()),
                startup_paths: Mutex::new(startup.clone()),
                recv_alias: Mutex::new(String::new()),
                toast_ready: Mutex::new(false),
                toast_pending: Mutex::new(None),
                _discovery: Mutex::new(None),
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
                                *lock(&st._discovery) = Some(d);
                                while sub.changed().await.is_ok() {
                                    let devs = sub.borrow_and_update().clone();
                                    let v = views(&devs);
                                    if let Some(path) = std::env::var_os("FILESEND_LOG") {
                                        use std::io::Write;
                                        if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(path) {
                                            let _ = writeln!(f, "{} appareil(s): {}", devs.len(), devs.iter().map(|d| format!("{} via {:?}", d.info.alias, d.via)).collect::<Vec<_>>().join(" ; "));
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
            let open = MenuItem::with_id(app, "open", "Ouvrir", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "Quitter", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&open, &quit])?;
            let mut tray = TrayIconBuilder::new()
                .tooltip("FileSend")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, ev| match ev.id().as_ref() {
                    "open" => show_main(app),
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
            // Sans fenetre, l'application reste dans la zone de notification.
            if let tauri::RunEvent::ExitRequested { code: None, api, .. } = ev {
                api.prevent_exit();
            }
        });
}
