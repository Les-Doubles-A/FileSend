"use strict";
const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;
const win = window.__TAURI__.window.getCurrentWindow();

const list = document.getElementById("devices");
const empty = document.getElementById("empty");
const chip = document.getElementById("chip");
const chipText = document.getElementById("chip-text");
const hint = document.getElementById("hint");
const plusMenu = document.getElementById("plus-menu");

const sheetQr = document.getElementById("sheet-qr");
const sheetBt = document.getElementById("sheet-bt");
const sheetMore = document.getElementById("sheet-more");

let devices = [];
let paths = [];
let settings = null;
let hintTimer = 0;
let qrMode = "relay-e2e";
let qrExpiry = "1h";
let lastQrUri = "";

function say(text) {
  hint.textContent = text;
  clearTimeout(hintTimer);
  hintTimer = setTimeout(() => (hint.textContent = ""), 3000);
}

function hue(s) {
  let h = 0;
  for (const c of s) h = (h * 31 + c.codePointAt(0)) % 360;
  return h;
}

function applyTheme(theme) {
  if (!theme || theme === "auto") document.documentElement.removeAttribute("data-theme");
  else document.documentElement.setAttribute("data-theme", theme);
}

function closeSheets() {
  if (!sheetQr.hidden) invoke("stop_local_qr").catch(() => {});
  sheetQr.hidden = true;
  sheetBt.hidden = true;
  sheetMore.hidden = true;
  plusMenu.hidden = true;
}

function openSheet(el) {
  closeSheets();
  el.hidden = false;
}

async function updateChip() {
  if (!paths.length) {
    chip.hidden = true;
    return;
  }
  chip.hidden = false;
  try {
    const s = await invoke("summarize_paths", { paths });
    const base = s.count === 1 ? (s.names[0] || "1 élément") : `${s.count} éléments`;
    chipText.textContent = s.formattedSize ? `${base} · ${s.formattedSize}` : base;
  } catch {
    chipText.textContent = paths.length === 1 ? "1 élément" : `${paths.length} éléments`;
  }
}

function render() {
  list.replaceChildren();
  for (const d of devices) {
    const li = document.createElement("li");
    const b = document.createElement("button");
    b.className = "tile" + (paths.length ? " armed" : "");
    b.dataset.fp = d.fingerprint;
    b.setAttribute("aria-label", "Envoyer à " + d.alias);
    b.title = d.trusted ? `${d.alias} (auto-accepté — clic droit pour retirer)` : `${d.alias} (clic droit pour auto-accepter)`;

    const av = document.createElement("span");
    av.className = "avatar";
    av.style.setProperty("--h", hue(d.alias));
    av.textContent = [...d.alias][0].toUpperCase();

    const badge = document.createElement("span");
    badge.className = "badge";
    badge.textContent = d.kind === "mobile" ? "📱" : "💻";
    av.append(badge);

    const nm = document.createElement("span");
    nm.className = "name";
    nm.textContent = d.alias;

    b.append(av, nm);
    if (d.trusted) {
      const star = document.createElement("span");
      star.className = "trust-star";
      star.textContent = "★";
      b.append(star);
    }
    b.addEventListener("click", () => sendTo(d.fingerprint));
    b.addEventListener("contextmenu", async (e) => {
      e.preventDefault();
      const trusted = await invoke("toggle_trusted", { fingerprint: d.fingerprint });
      say(trusted ? `${d.alias} : auto-accepté ★` : `${d.alias} : confirmation requise`);
    });
    li.append(b);
    list.append(li);
  }
  empty.hidden = devices.length > 0;
  updateChip();
  renderTextTargets();
}

async function sendTo(fp) {
  if (!paths.length) {
    const picked = await invoke("pick_files");
    if (!picked || !picked.length) return say("Déposez ou choisissez des fichiers");
    paths = picked;
  }
  const p = paths;
  paths = [];
  render();
  try {
    await invoke("send_to", { fingerprint: fp, paths: p });
  } catch (e) {
    say(String(e));
  }
}

function setPaths(p) {
  if (!p || !p.length) return;
  paths = p;
  render();
  if (devices.length === 1 && sheetQr.hidden && sheetBt.hidden && sheetMore.hidden) {
    sendTo(devices[0].fingerprint);
  }
}

// ---------- Header & Dock ----------

document.getElementById("btn-close").addEventListener("click", () => win.close());
document.getElementById("btn-rescan").addEventListener("click", () => {
  invoke("rescan_devices").catch(() => {});
  say("Recherche sur le réseau…");
});
document.getElementById("btn-empty-qr").addEventListener("click", () => openSheet(sheetQr));
document.getElementById("btn-qr").addEventListener("click", () => openSheet(sheetQr));
document.getElementById("btn-bt").addEventListener("click", () => {
  openSheet(sheetBt);
  loadBluetooth();
});
document.getElementById("btn-more").addEventListener("click", () => {
  openSheet(sheetMore);
  loadHistory();
  loadSettings();
});
document.querySelectorAll(".sheet-close").forEach((b) => b.addEventListener("click", closeSheets));

document.getElementById("chip-clear").addEventListener("click", () => {
  paths = [];
  render();
});

document.getElementById("btn-plus").addEventListener("click", (e) => {
  e.stopPropagation();
  plusMenu.hidden = !plusMenu.hidden;
});
document.addEventListener("click", (e) => {
  if (!plusMenu.hidden && !e.target.closest(".plus-wrap")) plusMenu.hidden = true;
});

document.getElementById("pick-files").addEventListener("click", async () => {
  plusMenu.hidden = true;
  const p = await invoke("pick_files");
  if (p && p.length) setPaths(p);
});
document.getElementById("pick-folder").addEventListener("click", async () => {
  plusMenu.hidden = true;
  const p = await invoke("pick_folder");
  if (p && p.length) setPaths(p);
});
document.getElementById("open-text").addEventListener("click", () => {
  plusMenu.hidden = true;
  openSheet(sheetMore);
  selectMoreTab("text");
});

// ---------- QR Code Sheet ----------

document.querySelectorAll("[data-qr-tab]").forEach((btn) => {
  btn.addEventListener("click", () => {
    const tab = btn.dataset.qrTab;
    document.querySelectorAll("[data-qr-tab]").forEach((b) => b.classList.toggle("active", b === btn));
    document.getElementById("qr-send-pane").hidden = tab !== "send";
    document.getElementById("qr-recv-pane").hidden = tab !== "recv";
  });
});

document.querySelectorAll("[data-qr-mode]").forEach((btn) => {
  btn.addEventListener("click", () => {
    qrMode = btn.dataset.qrMode;
    document.querySelectorAll("[data-qr-mode]").forEach((b) => b.classList.toggle("active", b === btn));
    document.getElementById("qr-expiry-row").hidden = qrMode === "lan";
  });
});

document.querySelectorAll("[data-qr-exp]").forEach((btn) => {
  btn.addEventListener("click", () => {
    qrExpiry = btn.dataset.qrExp;
    document.querySelectorAll("[data-qr-exp]").forEach((b) => b.classList.toggle("active", b === btn));
  });
});

function showQrView(view) {
  lastQrUri = view.uri;
  document.getElementById("qr-img").src = view.imgDataUrl;
  document.getElementById("qr-name").textContent = view.name;
  document.getElementById("qr-info").textContent = `${view.size} · ${view.expires}`;
  document.getElementById("qr-result").hidden = false;
}

document.getElementById("qr-generate").addEventListener("click", async () => {
  const btn = document.getElementById("qr-generate");
  const status = document.getElementById("qr-status");
  let targetPaths = paths;
  if (!targetPaths.length) {
    const picked = await invoke("pick_files");
    if (!picked || !picked.length) return;
    paths = picked;
    targetPaths = picked;
    updateChip();
  }
  btn.disabled = true;
  status.textContent = qrMode === "lan" ? "Préparation du serveur Wi-Fi…" : "Chiffrement & envoi vers le relais…";
  document.getElementById("qr-result").hidden = true;
  try {
    const view = await invoke("share_qr", { paths: targetPaths, mode: qrMode, expiry: qrExpiry });
    showQrView(view);
    status.textContent = qrMode === "relay-e2e"
      ? "Chiffré de bout en bout — scannez avec FileSend (n'importe quel réseau)"
      : qrMode === "relay-direct"
        ? "Scannez avec l'appareil photo de n'importe quel téléphone"
        : "Scannez sur le même Wi-Fi (téléchargement direct)";
  } catch (e) {
    status.textContent = String(e);
  } finally {
    btn.disabled = false;
  }
});

document.getElementById("qr-copy").addEventListener("click", async () => {
  if (!lastQrUri) return;
  try {
    await navigator.clipboard.writeText(lastQrUri);
    document.getElementById("qr-status").textContent = "Lien copié dans le presse-papiers";
  } catch {
    document.getElementById("qr-status").textContent = "Copie impossible";
  }
});

document.getElementById("qr-paste").addEventListener("click", async () => {
  try {
    document.getElementById("qr-uri-input").value = await navigator.clipboard.readText();
  } catch {}
});

document.getElementById("qr-download").addEventListener("click", async () => {
  const uri = document.getElementById("qr-uri-input").value.trim();
  const st = document.getElementById("qr-recv-status");
  if (!uri) return (st.textContent = "Collez un lien d'abord");
  st.textContent = "Téléchargement & déchiffrement…";
  try {
    const name = await invoke("receive_from_uri", { uri });
    st.textContent = `Reçu : ${name}`;
    document.getElementById("qr-uri-input").value = "";
  } catch (e) {
    st.textContent = String(e);
  }
});

// ---------- Bluetooth Sheet ----------

async function loadBluetooth() {
  const st = document.getElementById("bt-status");
  const ul = document.getElementById("bt-list");
  st.textContent = "Recherche des appareils appairés…";
  ul.replaceChildren();
  try {
    document.getElementById("bt-rx-toggle").checked = await invoke("bt_receiver_status");
    const devs = await invoke("bt_list_devices");
    if (!devs.length) {
      st.textContent = "Aucun appareil Bluetooth appairé. Associez votre téléphone dans Windows > Bluetooth.";
      return;
    }
    st.textContent = "Cliquez un appareil pour envoyer via FileSend Bluetooth :";
    for (const d of devs) {
      const li = document.createElement("li");
      li.className = "flat-item";
      const main = document.createElement("div");
      main.className = "flat-item-main";
      const t = document.createElement("div");
      t.className = "flat-item-title";
      t.textContent = d.name;
      const s = document.createElement("div");
      s.className = "flat-item-sub";
      s.textContent = d.address;
      main.append(t, s);
      const tag = document.createElement("span");
      tag.className = "tag";
      tag.textContent = d.connected ? "Connecté" : "Appairé";
      li.append(main, tag);
      li.addEventListener("click", async () => {
        let p = paths;
        if (!p.length) {
          const picked = await invoke("pick_files");
          if (!picked || !picked.length) return;
          p = picked;
        }
        paths = [];
        render();
        closeSheets();
        try {
          await invoke("bt_send", { rawAddr: d.rawAddr, name: d.name, paths: p });
        } catch (e) {
          say(String(e));
        }
      });
      ul.append(li);
    }
  } catch (e) {
    st.textContent = String(e);
  }
}

document.getElementById("bt-refresh").addEventListener("click", loadBluetooth);
document.getElementById("bt-rx-toggle").addEventListener("change", async (e) => {
  try {
    const on = await invoke("bt_toggle_receiver", { enabled: e.target.checked });
    document.getElementById("bt-status").textContent = on
      ? "Écoute Bluetooth active — prêt à recevoir depuis Android FileSend"
      : "Réception Bluetooth désactivée";
  } catch (err) {
    e.target.checked = false;
    document.getElementById("bt-status").textContent = String(err);
  }
});
document.getElementById("bt-win-send").addEventListener("click", async () => {
  let p = paths;
  if (!p.length) {
    const picked = await invoke("pick_files");
    if (!picked || !picked.length) return;
    p = picked;
  }
  invoke("bt_send_wizard", { paths: p }).catch((e) => say(String(e)));
});
document.getElementById("bt-win-recv").addEventListener("click", () => {
  invoke("bt_receive_wizard").catch((e) => say(String(e)));
});

// ---------- More Sheet (Texte, Historique, Reglages) ----------

function selectMoreTab(tab) {
  document.querySelectorAll("[data-more-tab]").forEach((b) => b.classList.toggle("active", b.dataset.moreTab === tab));
  document.getElementById("more-text-pane").hidden = tab !== "text";
  document.getElementById("more-history-pane").hidden = tab !== "history";
  document.getElementById("more-settings-pane").hidden = tab !== "settings";
}
document.querySelectorAll("[data-more-tab]").forEach((btn) => {
  btn.addEventListener("click", () => {
    selectMoreTab(btn.dataset.moreTab);
    if (btn.dataset.moreTab === "history") loadHistory();
    if (btn.dataset.moreTab === "settings") loadSettings();
  });
});

function renderTextTargets() {
  const wrap = document.getElementById("text-targets");
  wrap.replaceChildren();
  for (const d of devices) {
    const b = document.createElement("button");
    b.className = "pill-btn";
    b.type = "button";
    b.textContent = `→ ${d.alias}`;
    b.addEventListener("click", async () => {
      const txt = document.getElementById("text-input").value.trim();
      if (!txt) return say("Saisissez un texte d'abord");
      try {
        await invoke("send_text_to", { fingerprint: d.fingerprint, text: txt });
        document.getElementById("text-input").value = "";
        closeSheets();
        say(`Texte envoyé à ${d.alias}`);
      } catch (e) {
        say(String(e));
      }
    });
    wrap.append(b);
  }
}

document.getElementById("text-paste").addEventListener("click", async () => {
  try {
    document.getElementById("text-input").value = await navigator.clipboard.readText();
  } catch {}
});
document.getElementById("text-as-qr").addEventListener("click", async () => {
  const txt = document.getElementById("text-input").value.trim();
  if (!txt) return say("Saisissez un texte d'abord");
  try {
    const view = await invoke("share_text_qr", { text: txt });
    openSheet(sheetQr);
    showQrView(view);
    document.getElementById("qr-status").textContent = "QR code texte hors-ligne";
  } catch (e) {
    say(String(e));
  }
});

async function loadHistory() {
  const ul = document.getElementById("history-list");
  ul.replaceChildren();
  const items = await invoke("get_history");
  if (!items.length) {
    const p = document.createElement("p");
    p.className = "muted center";
    p.textContent = "Aucun transfert récent";
    ul.append(p);
    return;
  }
  for (const it of items) {
    const li = document.createElement("li");
    li.className = "flat-item";
    const main = document.createElement("div");
    main.className = "flat-item-main";
    const arrow = it.direction === "receive" ? "↓" : it.direction === "text" ? "💬" : "↑";
    const t = document.createElement("div");
    t.className = "flat-item-title";
    t.textContent = `${arrow} ${it.summary}`;
    const s = document.createElement("div");
    s.className = "flat-item-sub";
    s.textContent = `${it.peer} · ${it.size}`;
    main.append(t, s);
    const tag = document.createElement("span");
    tag.className = "tag";
    tag.textContent = it.mode === "qr" ? "QR" : it.mode === "bluetooth" ? "BT" : "Wi-Fi";
    li.append(main, tag);
    if (it.path) {
      li.addEventListener("click", () => invoke("open_received", { path: it.path }).catch(() => {}));
    }
    ul.append(li);
  }
}

document.getElementById("history-clear").addEventListener("click", async () => {
  await invoke("clear_history");
  loadHistory();
});

async function loadSettings() {
  settings = await invoke("get_settings");
  document.getElementById("set-alias").value = settings.alias;
  document.getElementById("set-dest").value = settings.destDir;
  document.getElementById("set-relay").value = settings.customRelay || "";
  document.querySelectorAll("[data-theme]").forEach((b) =>
    b.classList.toggle("active", b.dataset.theme === (settings.theme || "auto"))
  );
}

document.querySelectorAll("[data-theme]").forEach((b) => {
  b.addEventListener("click", () => {
    if (!settings) return;
    settings.theme = b.dataset.theme;
    applyTheme(settings.theme);
    document.querySelectorAll("[data-theme]").forEach((x) => x.classList.toggle("active", x === b));
  });
});

document.getElementById("set-dest-pick").addEventListener("click", async () => {
  const dir = await invoke("pick_dest_dir");
  if (dir) {
    document.getElementById("set-dest").value = dir;
    if (settings) settings.destDir = dir;
  }
});
document.getElementById("set-dest-open").addEventListener("click", () => {
  invoke("open_dest_dir").catch(() => {});
});
document.getElementById("set-save").addEventListener("click", async () => {
  if (!settings) return;
  settings.alias = document.getElementById("set-alias").value.trim() || settings.alias;
  settings.customRelay = document.getElementById("set-relay").value.trim();
  settings = await invoke("save_settings", { settings });
  document.getElementById("me").textContent = settings.alias;
  applyTheme(settings.theme);
  closeSheets();
  say("Réglages enregistrés");
});

// ---------- Drag & Drop + Raccourcis clavier ----------

window.__TAURI__.webview.getCurrentWebview().onDragDropEvent((e) => {
  const t = e.payload.type;
  if (t === "enter" || t === "over") document.body.classList.add("dragging");
  else document.body.classList.remove("dragging");
  if (t !== "drop") return;
  const dpr = window.devicePixelRatio || 1;
  const el = document.elementFromPoint(e.payload.position.x / dpr, e.payload.position.y / dpr);
  const tile = el && el.closest("[data-fp]");
  if (tile) {
    paths = e.payload.paths;
    sendTo(tile.dataset.fp);
  } else {
    setPaths(e.payload.paths);
  }
});

document.addEventListener("keydown", async (e) => {
  if (e.key === "Escape") {
    if (!sheetQr.hidden || !sheetBt.hidden || !sheetMore.hidden || !plusMenu.hidden) closeSheets();
    else win.close();
  } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "v" && !e.target.closest("input, textarea")) {
    try {
      const txt = (await navigator.clipboard.readText()).trim();
      if (txt.startsWith("filesend://")) {
        openSheet(sheetQr);
        document.querySelector('[data-qr-tab="recv"]').click();
        document.getElementById("qr-uri-input").value = txt;
      } else if (txt) {
        openSheet(sheetMore);
        selectMoreTab("text");
        document.getElementById("text-input").value = txt;
      }
    } catch {}
  }
});

listen("devices", (e) => {
  devices = e.payload;
  render();
});
listen("paths", (e) => setPaths(e.payload));
listen("history", () => {
  if (!sheetMore.hidden && !document.getElementById("more-history-pane").hidden) loadHistory();
});

(async () => {
  const opaque = !(await invoke("glass"));
  document.documentElement.classList.toggle("opaque", opaque);
  settings = await invoke("get_settings");
  applyTheme(settings.theme);
  document.getElementById("me").textContent = settings.alias;
  devices = await invoke("get_devices");
  render();
  setPaths(await invoke("take_startup_paths"));
})();
