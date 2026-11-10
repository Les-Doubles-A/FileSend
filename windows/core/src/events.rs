//! Evenements emis par le coeur vers l'interface (notifications, F4).

use crate::protocol::DeviceInfo;
use std::path::PathBuf;
use tokio::sync::mpsc::UnboundedSender;

/// Raisons d'echec affichables (AC-F4-3). Exactement quatre.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FailReason {
    Refused,
    NotFound,
    Interrupted,
    DiskFull,
}

impl FailReason {
    pub fn label(self) -> &'static str {
        match self {
            FailReason::Refused => "Refusé",
            FailReason::NotFound => "Introuvable",
            FailReason::Interrupted => "Interrompu",
            FailReason::DiskFull => "Disque plein",
        }
    }

    pub fn message(self) -> String {
        format!("Échec : {}", self.label())
    }
}

#[derive(Debug, Clone)]
pub enum Event {
    /// Cote receveur : un transfert accepte demarre.
    Started { session: String, from: DeviceInfo, count: usize, total: u64 },
    Progress { session: String, done: u64, total: u64 },
    /// Cote receveur : tout est recu. `paths` = elements de premier niveau enregistres.
    Completed { session: String, from: DeviceInfo, paths: Vec<PathBuf> },
    Failed { session: String, reason: FailReason },
}

pub type EventSink = UnboundedSender<Event>;

pub(crate) fn emit(sink: &Option<EventSink>, ev: Event) {
    if let Some(s) = sink {
        let _ = s.send(ev);
    }
}
