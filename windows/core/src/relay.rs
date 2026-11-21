//! Partage par QR code (hors reseau local via relais chiffre E2E AES-256-GCM,
//! lien direct navigateur, ou lien Wi-Fi local ephemere).

use crate::paths::{is_under, join_components, sanitize_relative_path, unique_top_name};
use crate::server::install_crypto_provider;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use qrcode::render::svg;
use qrcode::QrCode;
use ring::aead::{Aad, LessSafeKey, Nonce, UnboundKey, AES_256_GCM};
use ring::rand::{SecureRandom, SystemRandom};
use serde::{Deserialize, Serialize};
use std::io::{Read, Write};
use std::net::{IpAddr, SocketAddr};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;
use tokio::io::AsyncWriteExt;

const MAGIC: &[u8; 4] = b"FSE1";
const CHUNK_PLAIN: usize = 1024 * 1024; // 1 Mio par bloc chiffre
const TAG_LEN: usize = 16;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct QrShareResult {
    /// Lien encode dans le QR code (`filesend://r?...` ou `https://...` ou `http://...`).
    pub uri: String,
    /// Image SVG prete a afficher (hors-ligne).
    pub svg: String,
    /// Mode utilise : "relay-e2e" | "relay-direct" | "lan"
    pub mode: String,
    /// Nom du fichier ou archive partagee.
    pub name: String,
    /// Taille totale en octets.
    pub size: u64,
    /// Duree de validite affichee (ex: "1 h", "24 h", "Local").
    pub expires: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParsedRelayUri {
    pub url: String,
    pub key: Option<[u8; 32]>,
    pub name: String,
    pub size: u64,
    pub is_zip: bool,
}

/// Genere un QR code SVG compact et net, 100% hors-ligne.
pub fn generate_qr_svg(data: &str) -> Result<String, String> {
    let code = QrCode::new(data.as_bytes()).map_err(|e| format!("QR impossible : {e}"))?;
    Ok(code
        .render::<svg::Color>()
        .min_dimensions(220, 220)
        .quiet_zone(true)
        .dark_color(svg::Color("#111116"))
        .light_color(svg::Color("#ffffff"))
        .build())
}

// ---------- Chiffrement E2E AES-256-GCM en blocs (FSE1) ----------

fn chunk_nonce(base: &[u8; 12], index: u64) -> [u8; 12] {
    let mut n = *base;
    let idx = index.to_be_bytes();
    for i in 0..8 {
        n[4 + i] ^= idx[i];
    }
    n
}

pub fn encrypt_file_e2e(src: &Path, dst: &Path) -> Result<[u8; 32], String> {
    let rng = SystemRandom::new();
    let mut key_bytes = [0u8; 32];
    let mut base_nonce = [0u8; 12];
    rng.fill(&mut key_bytes).map_err(|_| "RNG echoue")?;
    rng.fill(&mut base_nonce).map_err(|_| "RNG echoue")?;

    let unbound = UnboundKey::new(&AES_256_GCM, &key_bytes).map_err(|_| "Cle AES invalide")?;
    let key = LessSafeKey::new(unbound);

    let mut fin = std::fs::File::open(src).map_err(|e| e.to_string())?;
    let mut fout = std::io::BufWriter::with_capacity(256 * 1024, std::fs::File::create(dst).map_err(|e| e.to_string())?);
    fout.write_all(MAGIC).map_err(|e| e.to_string())?;
    fout.write_all(&base_nonce).map_err(|e| e.to_string())?;

    let mut buf = vec![0u8; CHUNK_PLAIN + TAG_LEN];
    let mut idx = 0u64;
    loop {
        let mut read_total = 0;
        while read_total < CHUNK_PLAIN {
            let n = fin.read(&mut buf[read_total..CHUNK_PLAIN]).map_err(|e| e.to_string())?;
            if n == 0 {
                break;
            }
            read_total += n;
        }
        if read_total == 0 && idx > 0 {
            break;
        }
        let mut chunk = buf[..read_total].to_vec();
        let nonce = Nonce::assume_unique_for_key(chunk_nonce(&base_nonce, idx));
        key.seal_in_place_append_tag(nonce, Aad::empty(), &mut chunk)
            .map_err(|_| "Chiffrement echoue")?;
        let len = chunk.len() as u32;
        fout.write_all(&len.to_be_bytes()).map_err(|e| e.to_string())?;
        fout.write_all(&chunk).map_err(|e| e.to_string())?;
        idx += 1;
        if read_total < CHUNK_PLAIN {
            break;
        }
    }
    fout.flush().map_err(|e| e.to_string())?;
    Ok(key_bytes)
}

pub fn decrypt_file_e2e(src: &Path, dst: &Path, key_bytes: &[u8; 32]) -> Result<(), String> {
    let unbound = UnboundKey::new(&AES_256_GCM, key_bytes).map_err(|_| "Cle AES invalide")?;
    let key = LessSafeKey::new(unbound);

    let mut fin = std::io::BufReader::with_capacity(256 * 1024, std::fs::File::open(src).map_err(|e| e.to_string())?);
    let mut fout = std::io::BufWriter::with_capacity(256 * 1024, std::fs::File::create(dst).map_err(|e| e.to_string())?);

    let mut magic = [0u8; 4];
    fin.read_exact(&mut magic).map_err(|_| "Fichier chiffre invalide")?;
    if &magic != MAGIC {
        return Err("Format chiffre non reconnu".into());
    }
    let mut base_nonce = [0u8; 12];
    fin.read_exact(&mut base_nonce).map_err(|_| "En-tete incomplet")?;

    let mut idx = 0u64;
    let mut len_buf = [0u8; 4];
    loop {
        match fin.read_exact(&mut len_buf) {
            Ok(()) => {}
            Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => break,
            Err(e) => return Err(e.to_string()),
        }
        let clen = u32::from_be_bytes(len_buf) as usize;
        if clen < TAG_LEN || clen > CHUNK_PLAIN + TAG_LEN + 64 {
            return Err("Bloc chiffre corrompu".into());
        }
        let mut chunk = vec![0u8; clen];
        fin.read_exact(&mut chunk).map_err(|_| "Flux chiffre tronque")?;
        let nonce = Nonce::assume_unique_for_key(chunk_nonce(&base_nonce, idx));
        let plain = key
            .open_in_place(nonce, Aad::empty(), &mut chunk)
            .map_err(|_| "Cle invalide ou fichier altere")?;
        fout.write_all(plain).map_err(|e| e.to_string())?;
        idx += 1;
    }
    fout.flush().map_err(|e| e.to_string())?;
    Ok(())
}

// ---------- Archive ZIP sans dependance (mode Stored, compatible Windows & Android) ----------

#[cfg(test)]
fn crc32(data: &[u8]) -> u32 {
    let mut crc = 0xFFFF_FFFFu32;
    for &b in data {
        crc ^= b as u32;
        for _ in 0..8 {
            let mask = (crc & 1).wrapping_neg();
            crc = (crc >> 1) ^ (0xEDB8_8320 & mask);
        }
    }
    !crc
}

fn crc32_update(mut crc: u32, data: &[u8]) -> u32 {
    crc = !crc;
    for &b in data {
        crc ^= b as u32;
        for _ in 0..8 {
            let mask = (crc & 1).wrapping_neg();
            crc = (crc >> 1) ^ (0xEDB8_8320 & mask);
        }
    }
    !crc
}

fn collect_entries(paths: &[PathBuf]) -> Result<Vec<(PathBuf, String)>, String> {
    fn walk(dir: &Path, rel: &str, out: &mut Vec<(PathBuf, String)>) -> Result<(), String> {
        let mut entries: Vec<_> = std::fs::read_dir(dir)
            .map_err(|e| e.to_string())?
            .collect::<Result<_, _>>()
            .map_err(|e| e.to_string())?;
        entries.sort_by_key(|e| e.file_name());
        for e in entries {
            let ft = e.file_type().map_err(|e| e.to_string())?;
            if ft.is_symlink() {
                continue;
            }
            let name = e.file_name().to_string_lossy().into_owned();
            let r = format!("{rel}/{name}");
            if ft.is_dir() {
                walk(&e.path(), &r, out)?;
            } else if ft.is_file() {
                out.push((e.path(), r));
            }
        }
        Ok(())
    }
    let mut out = Vec::new();
    for p in paths {
        let meta = std::fs::metadata(p).map_err(|e| e.to_string())?;
        let name = p
            .file_name()
            .ok_or("Nom de fichier invalide")?
            .to_string_lossy()
            .into_owned();
        if meta.is_dir() {
            walk(p, &name, &mut out)?;
        } else if meta.is_file() {
            out.push((p.clone(), name));
        }
    }
    Ok(out)
}

/// Empaquete plusieurs fichiers/dossiers dans une archive ZIP standard (Stored).
pub fn pack_zip(paths: &[PathBuf], dst: &Path) -> Result<u64, String> {
    let entries = collect_entries(paths)?;
    if entries.is_empty() {
        return Err("Aucun fichier à empaqueter".into());
    }
    let mut fout = std::io::BufWriter::with_capacity(
        256 * 1024,
        std::fs::File::create(dst).map_err(|e| e.to_string())?,
    );
    let mut central: Vec<(String, u32, u32, u32)> = Vec::new();
    let mut offset = 0u32;
    let mut buf = vec![0u8; 128 * 1024];

    for (path, rel) in entries {
        let meta = std::fs::metadata(&path).map_err(|e| e.to_string())?;
        if meta.len() > u32::MAX as u64 {
            return Err("Fichier trop volumineux pour l'archive ZIP (> 4 Go)".into());
        }
        let size = meta.len() as u32;
        // Calcul du CRC32 en flux
        let mut fin = std::fs::File::open(&path).map_err(|e| e.to_string())?;
        let mut crc = 0u32;
        loop {
            let n = fin.read(&mut buf).map_err(|e| e.to_string())?;
            if n == 0 {
                break;
            }
            crc = crc32_update(crc, &buf[..n]);
        }
        let name_bytes = rel.as_bytes();
        let local_offset = offset;

        // Local file header (30 bytes + name)
        let mut lh = Vec::with_capacity(30 + name_bytes.len());
        lh.extend_from_slice(&0x0403_4b50u32.to_le_bytes());
        lh.extend_from_slice(&20u16.to_le_bytes()); // version needed
        lh.extend_from_slice(&0x0800u16.to_le_bytes()); // UTF-8 flag
        lh.extend_from_slice(&0u16.to_le_bytes()); // method: stored
        lh.extend_from_slice(&0u16.to_le_bytes()); // time
        lh.extend_from_slice(&0u16.to_le_bytes()); // date
        lh.extend_from_slice(&crc.to_le_bytes());
        lh.extend_from_slice(&size.to_le_bytes());
        lh.extend_from_slice(&size.to_le_bytes());
        lh.extend_from_slice(&(name_bytes.len() as u16).to_le_bytes());
        lh.extend_from_slice(&0u16.to_le_bytes()); // extra len
        lh.extend_from_slice(name_bytes);
        fout.write_all(&lh).map_err(|e| e.to_string())?;
        offset = offset.checked_add(lh.len() as u32).ok_or("Archive trop grande")?;

        // File data
        let mut fin2 = std::fs::File::open(&path).map_err(|e| e.to_string())?;
        loop {
            let n = fin2.read(&mut buf).map_err(|e| e.to_string())?;
            if n == 0 {
                break;
            }
            fout.write_all(&buf[..n]).map_err(|e| e.to_string())?;
        }
        offset = offset.checked_add(size).ok_or("Archive trop grande")?;
        central.push((rel, crc, size, local_offset));
    }

    let cd_start = offset;
    let mut cd_size = 0u32;
    for (rel, crc, size, local_offset) in &central {
        let name_bytes = rel.as_bytes();
        let mut ch = Vec::with_capacity(46 + name_bytes.len());
        ch.extend_from_slice(&0x0201_4b50u32.to_le_bytes());
        ch.extend_from_slice(&20u16.to_le_bytes());
        ch.extend_from_slice(&20u16.to_le_bytes());
        ch.extend_from_slice(&0x0800u16.to_le_bytes()); // UTF-8
        ch.extend_from_slice(&0u16.to_le_bytes()); // stored
        ch.extend_from_slice(&0u16.to_le_bytes());
        ch.extend_from_slice(&0u16.to_le_bytes());
        ch.extend_from_slice(&crc.to_le_bytes());
        ch.extend_from_slice(&size.to_le_bytes());
        ch.extend_from_slice(&size.to_le_bytes());
        ch.extend_from_slice(&(name_bytes.len() as u16).to_le_bytes());
        ch.extend_from_slice(&0u16.to_le_bytes()); // extra
        ch.extend_from_slice(&0u16.to_le_bytes()); // comment
        ch.extend_from_slice(&0u16.to_le_bytes()); // disk
        ch.extend_from_slice(&0u16.to_le_bytes()); // internal
        ch.extend_from_slice(&0u32.to_le_bytes()); // external
        ch.extend_from_slice(&local_offset.to_le_bytes());
        ch.extend_from_slice(name_bytes);
        fout.write_all(&ch).map_err(|e| e.to_string())?;
        cd_size += ch.len() as u32;
    }

    // End of central directory
    let count = central.len() as u16;
    let mut eocd = Vec::with_capacity(22);
    eocd.extend_from_slice(&0x0605_4b50u32.to_le_bytes());
    eocd.extend_from_slice(&0u16.to_le_bytes());
    eocd.extend_from_slice(&0u16.to_le_bytes());
    eocd.extend_from_slice(&count.to_le_bytes());
    eocd.extend_from_slice(&count.to_le_bytes());
    eocd.extend_from_slice(&cd_size.to_le_bytes());
    eocd.extend_from_slice(&cd_start.to_le_bytes());
    eocd.extend_from_slice(&0u16.to_le_bytes());
    fout.write_all(&eocd).map_err(|e| e.to_string())?;
    fout.flush().map_err(|e| e.to_string())?;

    Ok(std::fs::metadata(dst).map_err(|e| e.to_string())?.len())
}

/// Extrait une archive ZIP (Stored) dans `dest_dir` en appliquant la validation de chemins anti-ZipSlip.
pub fn unpack_zip(src: &Path, dest_dir: &Path) -> Result<Vec<PathBuf>, String> {
    let mut fin = std::io::BufReader::with_capacity(
        256 * 1024,
        std::fs::File::open(src).map_err(|e| e.to_string())?,
    );
    std::fs::create_dir_all(dest_dir).map_err(|e| e.to_string())?;
    let mut top_map = std::collections::HashMap::<String, String>::new();
    let mut tops = Vec::new();
    let mut sig = [0u8; 4];

    loop {
        match fin.read_exact(&mut sig) {
            Ok(()) => {}
            Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => break,
            Err(e) => return Err(e.to_string()),
        }
        let s = u32::from_le_bytes(sig);
        if s != 0x0403_4b50 {
            break; // Debut du Central Directory
        }
        let mut hdr = [0u8; 26];
        fin.read_exact(&mut hdr).map_err(|e| e.to_string())?;
        let method = u16::from_le_bytes([hdr[4], hdr[5]]);
        let expected_crc = u32::from_le_bytes([hdr[10], hdr[11], hdr[12], hdr[13]]);
        let comp_size = u32::from_le_bytes([hdr[14], hdr[15], hdr[16], hdr[17]]) as u64;
        let name_len = u16::from_le_bytes([hdr[22], hdr[23]]) as usize;
        let extra_len = u16::from_le_bytes([hdr[24], hdr[25]]) as usize;

        let mut name_buf = vec![0u8; name_len];
        fin.read_exact(&mut name_buf).map_err(|e| e.to_string())?;
        if extra_len > 0 {
            let mut skip = vec![0u8; extra_len];
            fin.read_exact(&mut skip).map_err(|e| e.to_string())?;
        }
        if method != 0 {
            return Err("Archive ZIP compressee : enregistrer le fichier .zip directement".into());
        }
        let rel = String::from_utf8(name_buf).map_err(|_| "Nom UTF-8 invalide dans ZIP")?;
        if rel.ends_with('/') {
            continue;
        }
        let comps = sanitize_relative_path(&rel).map_err(|_| "Chemin interdit dans l'archive")?;
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
            return Err("Chemin hors du dossier de reception".into());
        }
        if let Some(parent) = dest.parent() {
            std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        let mut fout = std::io::BufWriter::with_capacity(
            128 * 1024,
            std::fs::File::create(&dest).map_err(|e| e.to_string())?,
        );
        let mut rem = comp_size;
        let mut buf = vec![0u8; 64 * 1024];
        let mut actual_crc = 0u32;
        while rem > 0 {
            let to_read = (rem as usize).min(buf.len());
            fin.read_exact(&mut buf[..to_read]).map_err(|e| e.to_string())?;
            actual_crc = crc32_update(actual_crc, &buf[..to_read]);
            fout.write_all(&buf[..to_read]).map_err(|e| e.to_string())?;
            rem -= to_read as u64;
        }
        fout.flush().map_err(|e| e.to_string())?;
        if expected_crc != 0 && actual_crc != expected_crc {
            return Err("Archive ZIP corrompue (CRC)".into());
        }
    }
    if tops.is_empty() {
        return Err("Archive ZIP vide".into());
    }
    Ok(tops)
}

// ---------- Encodage / decodage URI filesend://r?... ----------

pub fn build_relay_uri(url: &str, key: Option<&[u8; 32]>, name: &str, size: u64, is_zip: bool) -> String {
    let mut u = url::Url::parse("filesend://r").unwrap();
    {
        let mut q = u.query_pairs_mut();
        q.append_pair("u", url);
        if let Some(k) = key {
            q.append_pair("k", &URL_SAFE_NO_PAD.encode(k));
        }
        q.append_pair("n", name);
        q.append_pair("s", &size.to_string());
        if is_zip {
            q.append_pair("z", "1");
        }
    }
    u.to_string()
}

pub fn parse_relay_uri(raw: &str) -> Result<ParsedRelayUri, String> {
    let trimmed = raw.trim();
    if trimmed.starts_with("http://") || trimmed.starts_with("https://") {
        let u = url::Url::parse(trimmed).map_err(|_| "Lien invalide")?;
        let name = u
            .path_segments()
            .and_then(|s| s.last())
            .filter(|s| !s.is_empty())
            .unwrap_or("fichier_recu")
            .to_string();
        return Ok(ParsedRelayUri {
            url: trimmed.to_string(),
            key: None,
            name,
            size: 0,
            is_zip: false,
        });
    }
    let u = url::Url::parse(trimmed).map_err(|_| "Lien FileSend invalide")?;
    if u.scheme() != "filesend" {
        return Err("Protocole non pris en charge".into());
    }
    let mut dl_url = None;
    let mut key = None;
    let mut name = String::from("fichier_recu");
    let mut size = 0u64;
    let mut is_zip = false;
    for (k, v) in u.query_pairs() {
        match k.as_ref() {
            "u" => dl_url = Some(v.into_owned()),
            "k" => {
                let bytes = URL_SAFE_NO_PAD
                    .decode(v.as_bytes())
                    .map_err(|_| "Clé de déchiffrement invalide")?;
                if bytes.len() != 32 {
                    return Err("Taille de clé invalide".into());
                }
                let mut arr = [0u8; 32];
                arr.copy_from_slice(&bytes);
                key = Some(arr);
            }
            "n" => name = v.into_owned(),
            "s" => size = v.parse().unwrap_or(0),
            "z" => is_zip = v == "1" || v == "true",
            _ => {}
        }
    }
    let url = dl_url.ok_or("URL manquante dans le lien")?;
    if !url.starts_with("https://") && !url.starts_with("http://") {
        return Err("URL de relais invalide".into());
    }
    Ok(ParsedRelayUri { url, key, name, size, is_zip })
}

// ---------- Upload & Download relais Internet ----------

async fn upload_litterbox(http: &reqwest::Client, file_path: &Path, filename: &str, expiry: &str) -> Result<String, String> {
    let data = tokio::fs::read(file_path).await.map_err(|e| e.to_string())?;
    let part = reqwest::multipart::Part::bytes(data)
        .file_name(filename.to_string())
        .mime_str("application/octet-stream")
        .map_err(|e| e.to_string())?;
    let form = reqwest::multipart::Form::new()
        .text("reqtype", "fileupload")
        .text("time", expiry.to_string())
        .part("fileToUpload", part);
    let resp = http
        .post("https://litterbox.catbox.moe/resources/internals/api.php")
        .multipart(form)
        .send()
        .await
        .map_err(|e| format!("Relais Litterbox inaccessible : {e}"))?;
    if !resp.status().is_success() {
        return Err(format!("Relais Litterbox erreur HTTP {}", resp.status()));
    }
    let text = resp.text().await.map_err(|e| e.to_string())?.trim().to_string();
    if !text.starts_with("https://") {
        return Err(format!("Réponse relais inattendue : {text}"));
    }
    Ok(text)
}

async fn upload_tmpfiles(http: &reqwest::Client, file_path: &Path, filename: &str) -> Result<String, String> {
    let data = tokio::fs::read(file_path).await.map_err(|e| e.to_string())?;
    let part = reqwest::multipart::Part::bytes(data)
        .file_name(filename.to_string())
        .mime_str("application/octet-stream")
        .map_err(|e| e.to_string())?;
    let form = reqwest::multipart::Form::new().part("file", part);
    let resp = http
        .post("https://tmpfiles.org/api/v1/upload")
        .multipart(form)
        .send()
        .await
        .map_err(|e| format!("Relais tmpfiles inaccessible : {e}"))?;
    if !resp.status().is_success() {
        return Err(format!("Relais tmpfiles erreur HTTP {}", resp.status()));
    }
    let v: serde_json::Value = resp.json().await.map_err(|e| e.to_string())?;
    let raw = v["data"]["url"]
        .as_str()
        .ok_or("URL absente dans réponse tmpfiles")?;
    // Convertir https://tmpfiles.org/12345/foo -> https://tmpfiles.org/dl/12345/foo
    Ok(raw.replace("http://", "https://").replacen("tmpfiles.org/", "tmpfiles.org/dl/", 1))
}

/// Partage un ou plusieurs fichiers/dossiers via QR code utilisable hors reseau local.
/// `encrypted = true` : chiffre localement en AES-256-GCM (lien `filesend://r?...`).
/// `encrypted = false` : lien direct HTTPS ouvrable dans n'importe quel navigateur sans app.
pub async fn share_via_relay(
    paths: &[PathBuf],
    encrypted: bool,
    expiry: &str,
    custom_relay: Option<&str>,
) -> Result<QrShareResult, String> {
    install_crypto_provider();
    if paths.is_empty() {
        return Err("Aucun fichier sélectionné".into());
    }
    let tmp_dir = std::env::temp_dir().join(format!("filesend-qr-{}", uuid::Uuid::new_v4()));
    tokio::fs::create_dir_all(&tmp_dir).await.map_err(|e| e.to_string())?;

    let (payload_path, orig_name, orig_size, is_zip) = if paths.len() == 1 && paths[0].is_file() {
        let p = &paths[0];
        let name = p
            .file_name()
            .ok_or("Nom invalide")?
            .to_string_lossy()
            .into_owned();
        let size = tokio::fs::metadata(p).await.map_err(|e| e.to_string())?.len();
        (p.clone(), name, size, false)
    } else {
        let zip_name = if paths.len() == 1 {
            format!(
                "{}.zip",
                paths[0]
                    .file_name()
                    .unwrap_or_default()
                    .to_string_lossy()
            )
        } else {
            format!("FileSend_{}_elements.zip", paths.len())
        };
        let zip_path = tmp_dir.join(&zip_name);
        let paths_c = paths.to_vec();
        let zp = zip_path.clone();
        let size = tokio::task::spawn_blocking(move || pack_zip(&paths_c, &zp))
            .await
            .map_err(|e| e.to_string())??;
        (zip_path, zip_name, size, true)
    };

    let (upload_path, upload_name, key) = if encrypted {
        let enc_path = tmp_dir.join("payload.fse");
        let pp = payload_path.clone();
        let ep = enc_path.clone();
        let k = tokio::task::spawn_blocking(move || encrypt_file_e2e(&pp, &ep))
            .await
            .map_err(|e| e.to_string())??;
        (enc_path, "filesend.fse".to_string(), Some(k))
    } else {
        (payload_path.clone(), orig_name.clone(), None)
    };

    let http = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(15))
        .timeout(Duration::from_secs(300))
        .build()
        .map_err(|e| e.to_string())?;

    let exp = match expiry {
        "24h" | "72h" => expiry,
        _ => "1h",
    };

    let remote_url = if let Some(custom) = custom_relay.filter(|s| !s.trim().is_empty()) {
        let data = tokio::fs::read(&upload_path).await.map_err(|e| e.to_string())?;
        let part = reqwest::multipart::Part::bytes(data)
            .file_name(upload_name.clone())
            .mime_str("application/octet-stream")
            .map_err(|e| e.to_string())?;
        let form = reqwest::multipart::Form::new().part("file", part);
        let resp = http.post(custom.trim()).multipart(form).send().await.map_err(|e| e.to_string())?;
        if !resp.status().is_success() {
            return Err(format!("Relais personnalisé erreur HTTP {}", resp.status()));
        }
        resp.text().await.map_err(|e| e.to_string())?.trim().to_string()
    } else {
        match upload_litterbox(&http, &upload_path, &upload_name, exp).await {
            Ok(u) => u,
            Err(_) => upload_tmpfiles(&http, &upload_path, &upload_name).await?,
        }
    };

    let _ = tokio::fs::remove_dir_all(&tmp_dir).await;

    let (uri, mode) = if let Some(k) = key.as_ref() {
        (build_relay_uri(&remote_url, Some(k), &orig_name, orig_size, is_zip), "relay-e2e".into())
    } else {
        (remote_url, "relay-direct".into())
    };
    let svg = generate_qr_svg(&uri)?;
    let expires_label = match exp {
        "24h" => "24 h",
        "72h" => "72 h",
        _ => "1 h",
    };
    Ok(QrShareResult {
        uri,
        svg,
        mode,
        name: orig_name,
        size: orig_size,
        expires: expires_label.into(),
    })
}

/// Telecharge, dechiffre (si E2E) et enregistre un lien `filesend://r?...` ou `https://...`.
pub async fn download_from_uri(raw_uri: &str, dest_dir: &Path) -> Result<Vec<PathBuf>, String> {
    install_crypto_provider();
    let parsed = parse_relay_uri(raw_uri)?;
    tokio::fs::create_dir_all(dest_dir).await.map_err(|e| e.to_string())?;

    let tmp_dir = std::env::temp_dir().join(format!("filesend-dl-{}", uuid::Uuid::new_v4()));
    tokio::fs::create_dir_all(&tmp_dir).await.map_err(|e| e.to_string())?;
    let raw_dl = tmp_dir.join("raw.bin");

    let http = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(15))
        .timeout(Duration::from_secs(300))
        .build()
        .map_err(|e| e.to_string())?;

    let resp = http
        .get(&parsed.url)
        .send()
        .await
        .map_err(|e| format!("Téléchargement impossible : {e}"))?;
    if !resp.status().is_success() {
        let _ = tokio::fs::remove_dir_all(&tmp_dir).await;
        return Err(format!("Lien expiré ou indisponible (HTTP {})", resp.status()));
    }

    {
        use futures_util::StreamExt;
        let mut f = tokio::io::BufWriter::with_capacity(
            256 * 1024,
            tokio::fs::File::create(&raw_dl).await.map_err(|e| e.to_string())?,
        );
        let mut stream = resp.bytes_stream();
        while let Some(chunk) = stream.next().await {
            let bytes = chunk.map_err(|e| e.to_string())?;
            f.write_all(&bytes).await.map_err(|e| e.to_string())?;
        }
        f.flush().await.map_err(|e| e.to_string())?;
    }

    let plain_path = if let Some(key) = parsed.key {
        let dec = tmp_dir.join("plain.bin");
        let r = raw_dl.clone();
        let d = dec.clone();
        tokio::task::spawn_blocking(move || decrypt_file_e2e(&r, &d, &key))
            .await
            .map_err(|e| e.to_string())??;
        dec
    } else {
        raw_dl
    };

    let result = if parsed.is_zip {
        let p = plain_path.clone();
        let dd = dest_dir.to_path_buf();
        tokio::task::spawn_blocking(move || unpack_zip(&p, &dd))
            .await
            .map_err(|e| e.to_string())?
    } else {
        let safe_name = sanitize_relative_path(&parsed.name)
            .ok()
            .and_then(|c| c.into_iter().last())
            .unwrap_or_else(|| "fichier_recu".into());
        let unique = unique_top_name(dest_dir, &safe_name);
        let final_path = dest_dir.join(&unique);
        if tokio::fs::rename(&plain_path, &final_path).await.is_err() {
            tokio::fs::copy(&plain_path, &final_path).await.map_err(|e| e.to_string())?;
        }
        Ok(vec![final_path])
    };

    let _ = tokio::fs::remove_dir_all(&tmp_dir).await;
    result
}

// ---------- Partage QR Local Wi-Fi (serveur HTTP ephemere, ouvrable par n'importe quel navigateur) ----------

pub struct LocalQrServer {
    handle: tokio::task::JoinHandle<()>,
    tmp_dir: Option<PathBuf>,
}

impl Drop for LocalQrServer {
    fn drop(&mut self) {
        self.handle.abort();
        if let Some(d) = &self.tmp_dir {
            let _ = std::fs::remove_dir_all(d);
        }
    }
}

/// Demarre un serveur HTTP local ephemere sur le reseau Wi-Fi pour telecharger par QR code
/// depuis n'importe quel telephone (meme sans FileSend installe).
pub async fn start_local_qr_share(paths: &[PathBuf]) -> Result<(QrShareResult, LocalQrServer), String> {
    if paths.is_empty() {
        return Err("Aucun fichier sélectionné".into());
    }
    let ip = crate::discovery::local_ip().ok_or("Aucune adresse IP locale trouvée")?;
    let mut tmp_dir = None;
    let (file_path, name, size) = if paths.len() == 1 && paths[0].is_file() {
        let p = &paths[0];
        let n = p.file_name().ok_or("Nom invalide")?.to_string_lossy().into_owned();
        let s = tokio::fs::metadata(p).await.map_err(|e| e.to_string())?.len();
        (p.clone(), n, s)
    } else {
        let td = std::env::temp_dir().join(format!("filesend-lanqr-{}", uuid::Uuid::new_v4()));
        tokio::fs::create_dir_all(&td).await.map_err(|e| e.to_string())?;
        let n = if paths.len() == 1 {
            format!("{}.zip", paths[0].file_name().unwrap_or_default().to_string_lossy())
        } else {
            format!("FileSend_{}_elements.zip", paths.len())
        };
        let zp = td.join(&n);
        let paths_c = paths.to_vec();
        let zp2 = zp.clone();
        let s = tokio::task::spawn_blocking(move || pack_zip(&paths_c, &zp2))
            .await
            .map_err(|e| e.to_string())??;
        tmp_dir = Some(td);
        (zp, n, s)
    };

    let token = uuid::Uuid::new_v4().simple().to_string();
    let listener = tokio::net::TcpListener::bind(SocketAddr::from(([0, 0, 0, 0], 0)))
        .await
        .map_err(|e| e.to_string())?;
    let port = listener.local_addr().map_err(|e| e.to_string())?.port();

    let state = Arc::new((token.clone(), file_path, name.clone(), size));
    let app = axum::Router::new().route(
        "/dl/{tok}",
        axum::routing::get({
            let st = state.clone();
            move |axum::extract::Path(tok): axum::extract::Path<String>| {
                let st = st.clone();
                async move {
                    use axum::response::IntoResponse;
                    if tok != st.0 {
                        return axum::http::StatusCode::NOT_FOUND.into_response();
                    }
                    let Ok(f) = tokio::fs::File::open(&st.1).await else {
                        return axum::http::StatusCode::NOT_FOUND.into_response();
                    };
                    let stream = tokio_util::io::ReaderStream::with_capacity(f, 256 * 1024);
                    let body = axum::body::Body::from_stream(stream);
                    let disp = format!("attachment; filename=\"{}\"", st.2.replace('"', "_"));
                    axum::http::Response::builder()
                        .header("content-type", "application/octet-stream")
                        .header("content-length", st.3)
                        .header("content-disposition", disp)
                        .body(body)
                        .unwrap()
                        .into_response()
                }
            }
        }),
    );

    let handle = tokio::spawn(async move {
        let _ = axum::serve(listener, app).await;
    });

    let host = match ip {
        IpAddr::V4(v4) => v4.to_string(),
        IpAddr::V6(v6) => format!("[{v6}]"),
    };
    let uri = format!("http://{host}:{port}/dl/{token}");
    let svg = generate_qr_svg(&uri)?;
    Ok((
        QrShareResult {
            uri,
            svg,
            mode: "lan".into(),
            name,
            size,
            expires: "Wi-Fi local".into(),
        },
        LocalQrServer { handle, tmp_dir },
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn e2e_encrypt_decrypt_roundtrip_and_tamper() {
        let dir = tempfile::tempdir().unwrap();
        let src = dir.path().join("hello.bin");
        let enc = dir.path().join("hello.fse");
        let dec = dir.path().join("hello.out");
        let data: Vec<u8> = (0..(CHUNK_PLAIN + 12345)).map(|i| (i % 251) as u8).collect();
        std::fs::write(&src, &data).unwrap();

        let key = encrypt_file_e2e(&src, &enc).unwrap();
        decrypt_file_e2e(&enc, &dec, &key).unwrap();
        assert_eq!(std::fs::read(&dec).unwrap(), data);

        // Alterer 1 octet doit echouer
        let mut bad = std::fs::read(&enc).unwrap();
        let last = bad.len() - 1;
        bad[last] ^= 0xFF;
        std::fs::write(&enc, &bad).unwrap();
        assert!(decrypt_file_e2e(&enc, &dec, &key).is_err());
    }

    #[test]
    fn zip_pack_unpack_roundtrip() {
        let dir = tempfile::tempdir().unwrap();
        let folder = dir.path().join("vacances");
        std::fs::create_dir_all(folder.join("sub")).unwrap();
        std::fs::write(folder.join("a.txt"), b"bonjour").unwrap();
        std::fs::write(folder.join("sub").join("b.txt"), b"monde").unwrap();

        let zip_path = dir.path().join("out.zip");
        pack_zip(&[folder], &zip_path).unwrap();

        let dest = dir.path().join("dest");
        let tops = unpack_zip(&zip_path, &dest).unwrap();
        assert_eq!(tops.len(), 1);
        assert_eq!(std::fs::read(dest.join("vacances/a.txt")).unwrap(), b"bonjour");
        assert_eq!(std::fs::read(dest.join("vacances/sub/b.txt")).unwrap(), b"monde");
    }

    #[test]
    fn relay_uri_roundtrip_and_qr() {
        let key = [42u8; 32];
        let uri = build_relay_uri("https://litter.catbox.moe/abc123.fse", Some(&key), "rapport final.pdf", 4096, false);
        let parsed = parse_relay_uri(&uri).unwrap();
        assert_eq!(parsed.url, "https://litter.catbox.moe/abc123.fse");
        assert_eq!(parsed.key, Some(key));
        assert_eq!(parsed.name, "rapport final.pdf");
        assert_eq!(parsed.size, 4096);
        assert!(!parsed.is_zip);

        let svg = generate_qr_svg(&uri).unwrap();
        assert!(svg.contains("<svg"));
    }

    #[test]
    fn crc32_standard() {
        assert_eq!(crc32(b"123456789"), 0xCBF4_3926);
    }
}
