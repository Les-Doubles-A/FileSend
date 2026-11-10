//! Assainissement des chemins recus et gestion des collisions (AC-F2-3, AC-F2-4).

use std::path::{Path, PathBuf};

const MAX_COMPONENT: usize = 255;
const MAX_TOTAL: usize = 1024;
const RESERVED: [&str; 22] = [
    "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8",
    "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
];

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
#[error("chemin refuse: {0}")]
pub struct PathRefused(pub &'static str);

/// Valide un chemin relatif recu et le decoupe en composants surs.
/// Refuse tout ce qui pourrait sortir du dossier de reception.
pub fn sanitize_relative_path(raw: &str) -> Result<Vec<String>, PathRefused> {
    if raw.is_empty() || raw.len() > MAX_TOTAL {
        return Err(PathRefused("longueur"));
    }
    if raw.contains('\\') {
        return Err(PathRefused("separateur"));
    }
    if raw.starts_with('/') {
        return Err(PathRefused("absolu"));
    }
    let mut out = Vec::new();
    for comp in raw.split('/') {
        check_component(comp)?;
        out.push(comp.to_string());
    }
    Ok(out)
}

fn check_component(c: &str) -> Result<(), PathRefused> {
    if c.is_empty() {
        return Err(PathRefused("composant vide"));
    }
    if c == "." || c == ".." {
        return Err(PathRefused("point"));
    }
    if c.chars().count() > MAX_COMPONENT {
        return Err(PathRefused("composant trop long"));
    }
    if c.chars().any(|ch| ch.is_control() || "<>:\"|?*\\/".contains(ch)) {
        return Err(PathRefused("caractere interdit"));
    }
    if c.ends_with('.') || c.ends_with(' ') {
        return Err(PathRefused("fin interdite"));
    }
    let stem = c.split('.').next().unwrap_or("").to_ascii_uppercase();
    if RESERVED.contains(&stem.as_str()) {
        return Err(PathRefused("nom reserve"));
    }
    Ok(())
}

/// Premier composant disponible dans `dest` : `a.txt` -> `a (1).txt`, `photos` -> `photos (1)`.
pub fn unique_top_name(dest: &Path, name: &str) -> String {
    if !dest.join(name).exists() {
        return name.to_string();
    }
    let (stem, ext) = match name.rfind('.') {
        Some(i) if i > 0 => (&name[..i], &name[i..]),
        _ => (name, ""),
    };
    for n in 1u32.. {
        let candidate = format!("{stem} ({n}){ext}");
        if !dest.join(&candidate).exists() {
            return candidate;
        }
    }
    unreachable!()
}

/// Verifie que `p` reste sous `root` (les deux doivent exister ou avoir un parent existant).
pub fn is_under(root: &Path, p: &Path) -> bool {
    p.starts_with(root)
        && p.components()
            .all(|c| !matches!(c, std::path::Component::ParentDir))
}

pub fn join_components(root: &Path, comps: &[String]) -> PathBuf {
    let mut p = root.to_path_buf();
    for c in comps {
        p.push(c);
    }
    p
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_nested() {
        assert_eq!(
            sanitize_relative_path("photos/v/b.jpg").unwrap(),
            vec!["photos", "v", "b.jpg"]
        );
    }

    #[test]
    fn refuses_traps() {
        for bad in [
            "../x", "a/../../x", "/etc/passwd", "C:/x", "C:x", "a\\b", "CON", "con.txt",
            "a/NUL.log", "x:stream", "a/", "a//b", ".", "a/./b", "bad?.txt", "end.", "end ",
            "", "a\0b",
        ] {
            assert!(sanitize_relative_path(bad).is_err(), "devrait refuser {bad:?}");
        }
        assert!(sanitize_relative_path(&"a".repeat(256)).is_err());
        assert!(sanitize_relative_path(&vec!["ab"; 600].join("/")).is_err());
    }

    #[test]
    fn collision_suffix() {
        let d = tempfile::tempdir().unwrap();
        assert_eq!(unique_top_name(d.path(), "a.txt"), "a.txt");
        std::fs::write(d.path().join("a.txt"), "x").unwrap();
        assert_eq!(unique_top_name(d.path(), "a.txt"), "a (1).txt");
        std::fs::write(d.path().join("a (1).txt"), "x").unwrap();
        assert_eq!(unique_top_name(d.path(), "a.txt"), "a (2).txt");
        std::fs::create_dir(d.path().join("photos")).unwrap();
        assert_eq!(unique_top_name(d.path(), "photos"), "photos (1)");
    }

    #[test]
    fn under_root() {
        let r = Path::new("/r");
        assert!(is_under(r, Path::new("/r/a/b")));
        assert!(!is_under(r, Path::new("/x/a")));
    }
}
