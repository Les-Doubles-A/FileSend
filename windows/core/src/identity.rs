//! Identite de l'appareil : certificat TLS auto-signe et empreinte.

use sha2::{Digest, Sha256};
use std::path::Path;

#[derive(Clone)]
pub struct Identity {
    pub cert_pem: String,
    pub key_pem: String,
    pub fingerprint: String,
}

impl Identity {
    pub fn generate() -> Result<Self, String> {
        let key = rcgen::KeyPair::generate().map_err(|e| e.to_string())?;
        let params = rcgen::CertificateParams::new(vec!["filesend.local".to_string()])
            .map_err(|e| e.to_string())?;
        let cert = params.self_signed(&key).map_err(|e| e.to_string())?;
        let fingerprint = hex::encode(Sha256::digest(cert.der().as_ref()));
        Ok(Self {
            cert_pem: cert.pem(),
            key_pem: key.serialize_pem(),
            fingerprint,
        })
    }

    /// Charge l'identite stockee dans `dir`, ou la cree au premier lancement.
    pub fn load_or_create(dir: &Path) -> Result<Self, String> {
        let cert_path = dir.join("cert.pem");
        let key_path = dir.join("key.pem");
        if let (Ok(c), Ok(k)) = (
            std::fs::read_to_string(&cert_path),
            std::fs::read_to_string(&key_path),
        ) {
            if let Some(fp) = fingerprint_of_pem(&c) {
                return Ok(Self { cert_pem: c, key_pem: k, fingerprint: fp });
            }
        }
        let id = Self::generate()?;
        std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
        std::fs::write(&cert_path, &id.cert_pem).map_err(|e| e.to_string())?;
        std::fs::write(&key_path, &id.key_pem).map_err(|e| e.to_string())?;
        Ok(id)
    }
}

fn fingerprint_of_pem(pem: &str) -> Option<String> {
    let b64: String = pem.lines().filter(|l| !l.starts_with("-----")).collect();
    let der = base64_decode(&b64)?;
    Some(hex::encode(Sha256::digest(der)))
}

fn base64_decode(s: &str) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(s.len() * 3 / 4);
    let (mut acc, mut bits) = (0u32, 0u32);
    for ch in s.bytes() {
        let v = match ch {
            b'A'..=b'Z' => ch - b'A',
            b'a'..=b'z' => ch - b'a' + 26,
            b'0'..=b'9' => ch - b'0' + 52,
            b'+' => 62,
            b'/' => 63,
            b'=' => break,
            _ => return None,
        } as u32;
        acc = (acc << 6) | v;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
            acc &= (1 << bits) - 1;
        }
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn persists_and_reloads_same_fingerprint() {
        let d = tempfile::tempdir().unwrap();
        let a = Identity::load_or_create(d.path()).unwrap();
        let b = Identity::load_or_create(d.path()).unwrap();
        assert_eq!(a.fingerprint, b.fingerprint);
        assert_eq!(a.fingerprint.len(), 64);
    }
}
