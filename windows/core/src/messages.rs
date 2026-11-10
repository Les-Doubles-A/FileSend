//! Textes affiches dans les notifications (F4). Courts, en francais, testables.

/// Taille lisible, unites en octets (o, Ko, Mo, Go), base 1024.
pub fn format_size(bytes: u64) -> String {
    const UNITS: [&str; 4] = ["Ko", "Mo", "Go", "To"];
    if bytes < 1024 {
        return format!("{bytes} o");
    }
    let mut v = bytes as f64 / 1024.0;
    let mut u = 0;
    while v >= 1024.0 && u < UNITS.len() - 1 {
        v /= 1024.0;
        u += 1;
    }
    if v < 10.0 {
        format!("{:.1} {}", v, UNITS[u]).replace('.', ",")
    } else {
        format!("{:.0} {}", v, UNITS[u])
    }
}

pub fn items(n: usize) -> String {
    if n <= 1 {
        format!("{n} élément")
    } else {
        format!("{n} éléments")
    }
}

/// (titre, detail) de la demande entrante (AC-F3-1).
pub fn request(alias: &str, count: usize, total: u64) -> (String, String) {
    (format!("{alias} veut envoyer {}", items(count)), format_size(total))
}

pub fn sending(alias: &str) -> String {
    format!("Envoi à {alias}")
}

pub fn sent(alias: &str) -> String {
    format!("Envoyé à {alias}")
}

pub fn receiving(alias: &str) -> String {
    format!("Réception de {alias}")
}

pub fn received(alias: &str) -> String {
    format!("Reçu de {alias}")
}

pub fn percent(done: u64, total: u64) -> u8 {
    if total == 0 {
        return 100;
    }
    ((done as u128 * 100) / total as u128).min(100) as u8
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::events::FailReason;

    #[test]
    fn sizes() {
        assert_eq!(format_size(0), "0 o");
        assert_eq!(format_size(1023), "1023 o");
        assert_eq!(format_size(1024), "1,0 Ko");
        assert_eq!(format_size(1536), "1,5 Ko");
        assert_eq!(format_size(12 * 1024 * 1024), "12 Mo");
        assert_eq!(format_size(2 * 1024 * 1024 * 1024), "2,0 Go");
    }

    #[test]
    fn request_text_has_sender_count_and_size() {
        let (t, d) = request("Léa", 3, 12 * 1024 * 1024);
        assert_eq!(t, "Léa veut envoyer 3 éléments");
        assert_eq!(d, "12 Mo");
        assert_eq!(request("Léa", 1, 10).0, "Léa veut envoyer 1 élément");
    }

    #[test]
    fn status_texts() {
        assert_eq!(sending("Tel"), "Envoi à Tel");
        assert_eq!(sent("Tel"), "Envoyé à Tel");
        assert_eq!(receiving("PC"), "Réception de PC");
        assert_eq!(received("PC"), "Reçu de PC");
        assert_eq!(FailReason::Refused.message(), "Échec : Refusé");
    }

    #[test]
    fn short_texts_stay_short() {
        // Pas verbeux : chaque libelle fixe tient en moins de 6 mots.
        for s in [sending("A"), sent("A"), receiving("A"), received("A")] {
            assert!(s.split_whitespace().count() < 6, "{s}");
        }
    }

    #[test]
    fn percent_is_clamped() {
        assert_eq!(percent(0, 0), 100);
        assert_eq!(percent(50, 200), 25);
        assert_eq!(percent(300, 200), 100);
    }
}
