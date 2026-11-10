"use strict";
const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;
const win = window.__TAURI__.window.getCurrentWindow();

const list = document.getElementById("devices");
const empty = document.getElementById("empty");
const chip = document.getElementById("chip");
const hint = document.getElementById("hint");

let devices = [];
let paths = [];
let hintTimer = 0;

function say(text) {
  hint.textContent = text;
  clearTimeout(hintTimer);
  hintTimer = setTimeout(() => (hint.textContent = ""), 2500);
}

function hue(s) {
  let h = 0;
  for (const c of s) h = (h * 31 + c.codePointAt(0)) % 360;
  return h;
}

function render() {
  list.replaceChildren();
  for (const d of devices) {
    const li = document.createElement("li");
    const b = document.createElement("button");
    b.className = "tile" + (paths.length ? " armed" : "");
    b.dataset.fp = d.fingerprint;
    b.setAttribute("aria-label", "Envoyer à " + d.alias);
    const av = document.createElement("span");
    av.className = "avatar";
    av.style.setProperty("--h", hue(d.alias));
    av.textContent = [...d.alias][0].toUpperCase();
    const nm = document.createElement("span");
    nm.className = "name";
    nm.textContent = d.alias;
    b.append(av, nm);
    b.addEventListener("click", () => sendTo(d.fingerprint));
    li.append(b);
    list.append(li);
  }
  empty.hidden = devices.length > 0;
  chip.hidden = paths.length === 0;
  chip.textContent = paths.length === 1 ? "1 élément" : paths.length + " éléments";
}

async function sendTo(fp) {
  if (!paths.length) return say("Déposez des fichiers");
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
  // Un seul appareil : un seul geste.
  if (devices.length === 1) sendTo(devices[0].fingerprint);
}

// Glisser-deposer : sur une tuile = envoi direct, ailleurs = fichiers en attente.
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

document.addEventListener("keydown", (e) => {
  if (e.key === "Escape") win.close();
});

listen("devices", (e) => {
  devices = e.payload;
  render();
});
listen("paths", (e) => setPaths(e.payload));

(async () => {
  const opaque = !(await invoke("glass"));
  document.documentElement.classList.toggle("opaque", opaque);
  document.getElementById("me").textContent = await invoke("my_alias");
  devices = await invoke("get_devices");
  render();
  setPaths(await invoke("take_startup_paths"));
})();
