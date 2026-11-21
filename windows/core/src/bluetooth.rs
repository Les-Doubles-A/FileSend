//! Transfert Bluetooth RFCOMM natif Windows (protocole FSBT v1 partage avec Android)
//! + decouverte des appareils appaires + repli assistant Bluetooth Windows (fsquirt.exe).

use crate::client::ProgressFn;
use crate::events::{emit, Event, EventSink, FailReason};
use crate::paths::{is_under, join_components, sanitize_relative_path, unique_top_name};
use crate::protocol::DeviceInfo;
use crate::server::{Decider, IncomingRequest};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

/// UUID du service Bluetooth RFCOMM FileSend (partage avec Android).
/// 9f4b2c10-7e3a-4d1b-8c5f-2a6e9d0b1f34
pub const FILESEND_BT_UUID_STR: &str = "9f4b2c10-7e3a-4d1b-8c5f-2a6e9d0b1f34";
const FSBT_MAGIC: &[u8; 5] = b"FSBT\x01";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BtDevice {
    pub name: String,
    /// Adresse Bluetooth formatee `AA:BB:CC:DD:EE:FF`.
    pub address: String,
    /// Adresse brute 48 bits (u64) pour la connexion RFCOMM.
    pub raw_addr: u64,
    pub connected: bool,
    pub authenticated: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BtHeader {
    pub alias: String,
    pub files: Vec<BtFileMeta>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BtFileMeta {
    pub relative_path: String,
    pub size: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sha256: Option<String>,
}

pub fn format_bt_addr(addr: u64) -> String {
    format!(
        "{:02X}:{:02X}:{:02X}:{:02X}:{:02X}:{:02X}",
        (addr >> 40) & 0xFF,
        (addr >> 32) & 0xFF,
        (addr >> 24) & 0xFF,
        (addr >> 16) & 0xFF,
        (addr >> 8) & 0xFF,
        addr & 0xFF
    )
}

pub fn parse_bt_addr(s: &str) -> Result<u64, String> {
    let parts: Vec<&str> = s.trim().split(':').collect();
    if parts.len() != 6 {
        return Err("Adresse Bluetooth invalide".into());
    }
    let mut val = 0u64;
    for p in parts {
        let b = u8::from_str_radix(p, 16).map_err(|_| "Adresse Bluetooth invalide")?;
        val = (val << 8) | (b as u64);
    }
    Ok(val)
}

/// Lance l'assistant natif Windows de transfert Bluetooth (OBEX) en repli pour les
/// appareils qui n'ont pas FileSend installe.
pub fn launch_windows_bluetooth_wizard(paths: &[PathBuf]) -> Result<(), String> {
    #[cfg(windows)]
    {
        let mut cmd = std::process::Command::new("fsquirt.exe");
        cmd.arg("-send");
        for p in paths {
            if p.is_file() {
                cmd.arg(p);
            }
        }
        cmd.spawn().map_err(|e| format!("Assistant Bluetooth indisponible : {e}"))?;
        Ok(())
    }
    #[cfg(not(windows))]
    {
        let _ = paths;
        Err("Non disponible hors Windows".into())
    }
}

/// Lance l'assistant natif Windows en mode reception Bluetooth (OBEX).
pub fn launch_windows_bluetooth_receive() -> Result<(), String> {
    #[cfg(windows)]
    {
        std::process::Command::new("fsquirt.exe")
            .arg("-receive")
            .spawn()
            .map_err(|e| format!("Assistant Bluetooth indisponible : {e}"))?;
        Ok(())
    }
    #[cfg(not(windows))]
    {
        Err("Non disponible hors Windows".into())
    }
}

// ---------- FFI Win32 Bluetooth (BluetoothAPIs.dll + ws2_32.dll) ----------

#[cfg(windows)]
mod win_bt {
    use super::*;
    use std::ffi::c_void;

    const AF_BTH: i32 = 32;
    const SOCK_STREAM: i32 = 1;
    const BTHPROTO_RFCOMM: i32 = 3;
    const INVALID_SOCKET: usize = !0usize;

    #[repr(C)]
    #[derive(Clone, Copy)]
    struct SystemTime {
        year: u16,
        month: u16,
        day_of_week: u16,
        day: u16,
        hour: u16,
        minute: u16,
        second: u16,
        milliseconds: u16,
    }

    #[repr(C)]
    struct BluetoothDeviceSearchParams {
        dw_size: u32,
        f_return_authenticated: i32,
        f_return_remembered: i32,
        f_return_unknown: i32,
        f_return_connected: i32,
        f_issue_inquiry: i32,
        c_timeout_multiplier: u8,
        h_radio: *mut c_void,
    }

    #[repr(C)]
    struct BluetoothDeviceInfo {
        dw_size: u32,
        address: u64,
        ul_class_of_device: u32,
        f_connected: i32,
        f_remembered: i32,
        f_authenticated: i32,
        st_last_seen: SystemTime,
        st_last_used: SystemTime,
        sz_name: [u16; 248],
    }

    #[repr(C)]
    #[derive(Clone, Copy)]
    struct Guid {
        data1: u32,
        data2: u16,
        data3: u16,
        data4: [u8; 8],
    }

    /// 9f4b2c10-7e3a-4d1b-8c5f-2a6e9d0b1f34
    const FILESEND_GUID: Guid = Guid {
        data1: 0x9f4b2c10,
        data2: 0x7e3a,
        data3: 0x4d1b,
        data4: [0x8c, 0x5f, 0x2a, 0x6e, 0x9d, 0x0b, 0x1f, 0x34],
    };

    #[repr(C, packed)]
    struct SockAddrBth {
        address_family: u16,
        bt_addr: u64,
        service_class_id: Guid,
        port: u32,
    }

    #[repr(C)]
    struct WsaData {
        w_version: u16,
        w_high_version: u16,
        i_max_sockets: u16,
        i_max_udp_dg: u16,
        lp_vendor_info: *mut u8,
        sz_description: [u8; 257],
        sz_system_status: [u8; 129],
    }

    #[link(name = "BluetoothApis")]
    extern "system" {
        fn BluetoothFindFirstDevice(
            pbtsp: *const BluetoothDeviceSearchParams,
            pbtdi: *mut BluetoothDeviceInfo,
        ) -> *mut c_void;
        fn BluetoothFindNextDevice(
            hfind: *mut c_void,
            pbtdi: *mut BluetoothDeviceInfo,
        ) -> i32;
        fn BluetoothFindDeviceClose(hfind: *mut c_void) -> i32;
    }

    #[link(name = "ws2_32")]
    extern "system" {
        fn WSAStartup(w_version_requested: u16, lp_wsa_data: *mut WsaData) -> i32;
        fn socket(af: i32, sock_type: i32, protocol: i32) -> usize;
        fn connect(s: usize, name: *const SockAddrBth, namelen: i32) -> i32;
        fn bind(s: usize, name: *const SockAddrBth, namelen: i32) -> i32;
        fn listen(s: usize, backlog: i32) -> i32;
        fn accept(s: usize, addr: *mut SockAddrBth, addrlen: *mut i32) -> usize;
        fn send(s: usize, buf: *const u8, len: i32, flags: i32) -> i32;
        fn recv(s: usize, buf: *mut u8, len: i32, flags: i32) -> i32;
        fn closesocket(s: usize) -> i32;
        fn WSAGetLastError() -> i32;
    }

    fn init_wsa() {
        static ONCE: std::sync::Once = std::sync::Once::new();
        ONCE.call_once(|| unsafe {
            let mut data: WsaData = std::mem::zeroed();
            let _ = WSAStartup(0x0202, &mut data);
        });
    }

    pub struct BtSocket(usize);

    impl Drop for BtSocket {
        fn drop(&mut self) {
            if self.0 != INVALID_SOCKET {
                unsafe {
                    closesocket(self.0);
                }
            }
        }
    }

    impl Read for BtSocket {
        fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
            let max_len = buf.len().min(i32::MAX as usize) as i32;
            let n = unsafe { recv(self.0, buf.as_mut_ptr(), max_len, 0) };
            if n < 0 {
                let err = unsafe { WSAGetLastError() };
                Err(std::io::Error::from_raw_os_error(err))
            } else {
                Ok(n as usize)
            }
        }
    }

    impl Write for BtSocket {
        fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
            let max_len = buf.len().min(i32::MAX as usize) as i32;
            let n = unsafe { send(self.0, buf.as_ptr(), max_len, 0) };
            if n < 0 {
                let err = unsafe { WSAGetLastError() };
                Err(std::io::Error::from_raw_os_error(err))
            } else {
                Ok(n as usize)
            }
        }

        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    pub fn list_paired() -> Result<Vec<BtDevice>, String> {
        let mut devices = Vec::new();
        unsafe {
            let params = BluetoothDeviceSearchParams {
                dw_size: std::mem::size_of::<BluetoothDeviceSearchParams>() as u32,
                f_return_authenticated: 1,
                f_return_remembered: 1,
                f_return_unknown: 0,
                f_return_connected: 1,
                f_issue_inquiry: 0,
                c_timeout_multiplier: 0,
                h_radio: std::ptr::null_mut(),
            };
            let mut info: BluetoothDeviceInfo = std::mem::zeroed();
            info.dw_size = std::mem::size_of::<BluetoothDeviceInfo>() as u32;
            let h = BluetoothFindFirstDevice(&params, &mut info);
            if h.is_null() {
                return Ok(devices);
            }
            loop {
                let len = info.sz_name.iter().position(|&c| c == 0).unwrap_or(info.sz_name.len());
                let name = String::from_utf16_lossy(&info.sz_name[..len]).trim().to_string();
                let addr = info.address;
                if addr != 0 {
                    devices.push(BtDevice {
                        name: if name.is_empty() { format_bt_addr(addr) } else { name },
                        address: format_bt_addr(addr),
                        raw_addr: addr,
                        connected: info.f_connected != 0,
                        authenticated: info.f_authenticated != 0,
                    });
                }
                info = std::mem::zeroed();
                info.dw_size = std::mem::size_of::<BluetoothDeviceInfo>() as u32;
                if BluetoothFindNextDevice(h, &mut info) == 0 {
                    break;
                }
            }
            BluetoothFindDeviceClose(h);
        }
        devices.sort_by(|a, b| b.connected.cmp(&a.connected).then(a.name.cmp(&b.name)));
        Ok(devices)
    }

    pub fn connect_rfcomm(bt_addr: u64) -> Result<BtSocket, String> {
        init_wsa();
        let s = unsafe { socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM) };
        if s == INVALID_SOCKET {
            return Err("Bluetooth indisponible sur ce PC".into());
        }
        let sock = BtSocket(s);
        let sa = SockAddrBth {
            address_family: AF_BTH as u16,
            bt_addr,
            service_class_id: FILESEND_GUID,
            port: 0, // Resolution SDP par UUID
        };
        let r = unsafe { connect(sock.0, &sa, std::mem::size_of::<SockAddrBth>() as i32) };
        if r != 0 {
            let err = unsafe { WSAGetLastError() };
            return Err(format!(
                "Connexion Bluetooth impossible (code {err}). Ouvrez FileSend (Bluetooth) sur l'autre appareil ou utilisez l'envoi Bluetooth Windows."
            ));
        }
        Ok(sock)
    }

    pub fn bind_rfcomm_listener() -> Result<BtSocket, String> {
        init_wsa();
        let s = unsafe { socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM) };
        if s == INVALID_SOCKET {
            return Err("Adaptateur Bluetooth non détecté".into());
        }
        let sock = BtSocket(s);
        let sa = SockAddrBth {
            address_family: AF_BTH as u16,
            bt_addr: 0,
            service_class_id: FILESEND_GUID,
            port: 0xFFFF_FFFF, // BT_PORT_ANY
        };
        let r = unsafe { bind(sock.0, &sa, std::mem::size_of::<SockAddrBth>() as i32) };
        if r != 0 {
            return Err("Impossible d'écouter en Bluetooth RFCOMM".into());
        }
        let lr = unsafe { listen(sock.0, 4) };
        if lr != 0 {
            return Err("Impossible de démarrer l'écoute Bluetooth".into());
        }
        Ok(sock)
    }

    pub fn accept_rfcomm(listener: &BtSocket) -> Result<BtSocket, String> {
        let mut sa: SockAddrBth = unsafe { std::mem::zeroed() };
        let mut len = std::mem::size_of::<SockAddrBth>() as i32;
        let client = unsafe { accept(listener.0, &mut sa, &mut len) };
        if client == INVALID_SOCKET {
            return Err("Accept Bluetooth fermé".into());
        }
        Ok(BtSocket(client))
    }
}

/// Liste les appareils Bluetooth appaires sur Windows.
pub fn list_paired_devices() -> Result<Vec<BtDevice>, String> {
    #[cfg(windows)]
    {
        win_bt::list_paired()
    }
    #[cfg(not(windows))]
    {
        Ok(Vec::new())
    }
}

fn enumerate_bt_files(paths: &[PathBuf]) -> Result<Vec<(PathBuf, BtFileMeta)>, String> {
    fn walk(dir: &Path, rel: &str, out: &mut Vec<(PathBuf, String, u64)>) -> Result<(), String> {
        for e in std::fs::read_dir(dir).map_err(|e| e.to_string())? {
            let e = e.map_err(|e| e.to_string())?;
            let ft = e.file_type().map_err(|e| e.to_string())?;
            if ft.is_symlink() {
                continue;
            }
            let name = e.file_name().to_string_lossy().into_owned();
            let r = format!("{rel}/{name}");
            if ft.is_dir() {
                walk(&e.path(), &r, out)?;
            } else if ft.is_file() {
                out.push((e.path(), r, e.metadata().map_err(|e| e.to_string())?.len()));
            }
        }
        Ok(())
    }
    let mut raw = Vec::new();
    for p in paths {
        let meta = std::fs::metadata(p).map_err(|e| e.to_string())?;
        let name = p
            .file_name()
            .ok_or("Nom invalide")?
            .to_string_lossy()
            .into_owned();
        if meta.is_dir() {
            walk(p, &name, &mut raw)?;
        } else if meta.is_file() {
            raw.push((p.clone(), name, meta.len()));
        }
    }
    let mut out = Vec::with_capacity(raw.len());
    for (path, rel, size) in raw {
        let sha256 = if size <= 32 * 1024 * 1024 {
            let mut f = std::fs::File::open(&path).map_err(|e| e.to_string())?;
            let mut h = Sha256::new();
            let mut buf = [0u8; 32 * 1024];
            loop {
                let n = f.read(&mut buf).map_err(|e| e.to_string())?;
                if n == 0 {
                    break;
                }
                h.update(&buf[..n]);
            }
            Some(hex::encode(h.finalize()))
        } else {
            None
        };
        out.push((path, BtFileMeta { relative_path: rel, size, sha256 }));
    }
    Ok(out)
}

/// Envoie des fichiers en Bluetooth RFCOMM (protocole FSBT v1) vers un appareil FileSend.
pub fn send_bluetooth_rfcomm(
    bt_addr: u64,
    paths: &[PathBuf],
    my_alias: &str,
    progress: Option<ProgressFn>,
    cancel: Option<Arc<AtomicBool>>,
) -> Result<(), String> {
    #[cfg(windows)]
    {
        let items = enumerate_bt_files(paths)?;
        if items.is_empty() {
            return Err("Rien à envoyer".into());
        }
        let total: u64 = items.iter().map(|(_, m)| m.size).sum();
        let mut sock = win_bt::connect_rfcomm(bt_addr)?;

        let hdr = BtHeader {
            alias: my_alias.to_string(),
            files: items.iter().map(|(_, m)| m.clone()).collect(),
        };
        let hdr_bytes = serde_json::to_vec(&hdr).map_err(|e| e.to_string())?;
        sock.write_all(FSBT_MAGIC).map_err(|e| e.to_string())?;
        sock.write_all(&(hdr_bytes.len() as u32).to_be_bytes())
            .map_err(|e| e.to_string())?;
        sock.write_all(&hdr_bytes).map_err(|e| e.to_string())?;

        // Attendre accord du receveur (0x01 = accepte, 0x00 = refuse)
        let mut decision = [0u8; 1];
        sock.read_exact(&mut decision).map_err(|_| "Connexion Bluetooth interrompue")?;
        if decision[0] != 0x01 {
            return Err("Refusé par le destinataire".into());
        }

        let mut done = 0u64;
        let mut buf = vec![0u8; 16 * 1024];
        for (path, meta) in &items {
            let mut f = std::fs::File::open(path).map_err(|e| e.to_string())?;
            let mut rem = meta.size;
            while rem > 0 {
                if cancel.as_ref().map(|c| c.load(Ordering::Relaxed)).unwrap_or(false) {
                    return Err("Annulé".into());
                }
                let to_read = (rem as usize).min(buf.len());
                let n = f.read(&mut buf[..to_read]).map_err(|e| e.to_string())?;
                if n == 0 {
                    return Err("Lecture fichier interrompue".into());
                }
                sock.write_all(&buf[..n]).map_err(|_| "Envoi Bluetooth interrompu")?;
                rem -= n as u64;
                done += n as u64;
                if let Some(p) = &progress {
                    p(done, total);
                }
            }
            let mut ack = [0u8; 1];
            sock.read_exact(&mut ack).map_err(|_| "Confirmation Bluetooth perdue")?;
            if ack[0] != 0x06 {
                return Err("Erreur d'intégrité sur le destinataire".into());
            }
        }
        Ok(())
    }
    #[cfg(not(windows))]
    {
        let _ = (bt_addr, paths, my_alias, progress, cancel);
        Err("Non disponible hors Windows".into())
    }
}

/// Handle d'arret du serveur de reception Bluetooth.
pub struct BtReceiverHandle {
    stop: Arc<AtomicBool>,
}

impl Drop for BtReceiverHandle {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
    }
}

/// Demarre un recepteur Bluetooth RFCOMM (FSBT v1) en arriere-plan.
pub fn start_bluetooth_receiver(
    dest_dir: PathBuf,
    decider: Decider,
    events: Option<EventSink>,
    rt: tokio::runtime::Handle,
) -> Result<BtReceiverHandle, String> {
    #[cfg(windows)]
    {
        let listener = win_bt::bind_rfcomm_listener()?;
        let stop = Arc::new(AtomicBool::new(false));
        let stop2 = stop.clone();
        std::thread::Builder::new()
            .name("filesend-bt-rx".into())
            .spawn(move || {
                while !stop2.load(Ordering::Relaxed) {
                    let Ok(mut sock) = win_bt::accept_rfcomm(&listener) else {
                        break;
                    };
                    let _ = handle_bt_client(&mut sock, &dest_dir, &decider, &events, &rt);
                }
            })
            .map_err(|e| e.to_string())?;
        Ok(BtReceiverHandle { stop })
    }
    #[cfg(not(windows))]
    {
        let _ = (dest_dir, decider, events, rt);
        Err("Non disponible hors Windows".into())
    }
}

#[cfg(windows)]
fn handle_bt_client<S: Read + Write>(
    sock: &mut S,
    dest_dir: &Path,
    decider: &Decider,
    events: &Option<EventSink>,
    rt: &tokio::runtime::Handle,
) -> Result<(), String> {
    let mut magic = [0u8; 5];
    sock.read_exact(&mut magic).map_err(|e| e.to_string())?;
    if &magic != FSBT_MAGIC {
        return Err("Protocole BT inconnu".into());
    }
    let mut len_buf = [0u8; 4];
    sock.read_exact(&mut len_buf).map_err(|e| e.to_string())?;
    let hlen = u32::from_be_bytes(len_buf) as usize;
    if hlen == 0 || hlen > 2 * 1024 * 1024 {
        return Err("En-tête BT invalide".into());
    }
    let mut hbuf = vec![0u8; hlen];
    sock.read_exact(&mut hbuf).map_err(|e| e.to_string())?;
    let hdr: BtHeader = serde_json::from_slice(&hbuf).map_err(|e| e.to_string())?;
    if hdr.files.is_empty() {
        let _ = sock.write_all(&[0x00]);
        return Err("Aucun fichier".into());
    }
    let total: u64 = hdr.files.iter().map(|f| f.size).sum();
    let from = DeviceInfo {
        alias: hdr.alias.clone(),
        fingerprint: "bluetooth".into(),
        device_type: "bluetooth".into(),
    };
    let req = IncomingRequest { from: from.clone(), count: hdr.files.len(), total };
    let accepted = rt.block_on(decider(req));
    if !accepted {
        let _ = sock.write_all(&[0x00]);
        return Ok(());
    }
    sock.write_all(&[0x01]).map_err(|e| e.to_string())?;

    let sid = format!("bt-{}", uuid::Uuid::new_v4());
    emit(events, Event::Started { session: sid.clone(), from: from.clone(), count: hdr.files.len(), total });
    std::fs::create_dir_all(dest_dir).map_err(|e| e.to_string())?;

    let mut top_map = HashMap::<String, String>::new();
    let mut tops = Vec::new();
    let mut done = 0u64;
    let mut buf = vec![0u8; 32 * 1024];

    for f in &hdr.files {
        let Ok(comps) = sanitize_relative_path(&f.relative_path) else {
            let _ = sock.write_all(&[0x15]);
            emit(events, Event::Failed { session: sid, reason: FailReason::Interrupted });
            return Err("Chemin interdit".into());
        };
        let top = top_map
            .entry(comps[0].clone())
            .or_insert_with(|| {
                let n = unique_top_name(dest_dir, &comps[0]);
                tops.push(dest_dir.join(&n));
                n
            })
            .clone();
        let mut mapped = comps;
        mapped[0] = top;
        let dest = join_components(dest_dir, &mapped);
        if !is_under(dest_dir, &dest) {
            let _ = sock.write_all(&[0x15]);
            emit(events, Event::Failed { session: sid, reason: FailReason::Interrupted });
            return Err("Chemin hors dossier".into());
        }
        if let Some(p) = dest.parent() {
            let _ = std::fs::create_dir_all(p);
        }
        let part = dest.with_extension("part");
        let mut fout = std::io::BufWriter::with_capacity(
            128 * 1024,
            std::fs::File::create(&part).map_err(|e| e.to_string())?,
        );
        let mut hasher = Sha256::new();
        let mut rem = f.size;
        while rem > 0 {
            let to_read = (rem as usize).min(buf.len());
            if sock.read_exact(&mut buf[..to_read]).is_err() {
                drop(fout);
                let _ = std::fs::remove_file(&part);
                emit(events, Event::Failed { session: sid, reason: FailReason::Interrupted });
                return Err("Réception Bluetooth interrompue".into());
            }
            hasher.update(&buf[..to_read]);
            if fout.write_all(&buf[..to_read]).is_err() {
                drop(fout);
                let _ = std::fs::remove_file(&part);
                let _ = sock.write_all(&[0x15]);
                emit(events, Event::Failed { session: sid, reason: FailReason::DiskFull });
                return Err("Disque plein".into());
            }
            rem -= to_read as u64;
            done += to_read as u64;
            emit(events, Event::Progress { session: sid.clone(), done, total });
        }
        let _ = fout.flush();
        drop(fout);
        if let Some(exp) = &f.sha256 {
            if !exp.eq_ignore_ascii_case(&hex::encode(hasher.finalize())) {
                let _ = std::fs::remove_file(&part);
                let _ = sock.write_all(&[0x15]);
                emit(events, Event::Failed { session: sid, reason: FailReason::Interrupted });
                return Err("SHA-256 invalide".into());
            }
        }
        let _ = std::fs::rename(&part, &dest);
        let _ = sock.write_all(&[0x06]);
    }
    emit(events, Event::Completed { session: sid, from, paths: tops });
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bt_addr_roundtrip() {
        let a = 0xAA_BB_CC_DD_EE_FFu64;
        let s = format_bt_addr(a);
        assert_eq!(s, "AA:BB:CC:DD:EE:FF");
        assert_eq!(parse_bt_addr(&s).unwrap(), a);
        assert!(parse_bt_addr("invalid").is_err());
    }

    #[test]
    fn bt_header_serialization() {
        let h = BtHeader {
            alias: "Mon PC".into(),
            files: vec![BtFileMeta {
                relative_path: "doc.pdf".into(),
                size: 1024,
                sha256: Some("abc".into()),
            }],
        };
        let j = serde_json::to_string(&h).unwrap();
        let h2: BtHeader = serde_json::from_str(&j).unwrap();
        assert_eq!(h2.alias, "Mon PC");
        assert_eq!(h2.files.len(), 1);
    }
}
