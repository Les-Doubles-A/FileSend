"use strict";
const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;
const win = window.__TAURI__.window.getCurrentWindow();

const box = document.getElementById("toast");
const title = document.getElementById("title");
const detail = document.getElementById("detail");
const bar = document.getElementById("bar");
const fill = bar.querySelector("i");
const actions = document.querySelector(".actions");

let cur = null;
let timer = 0;
let closeTimer = 0;

// Masque tout de suite, detruit la fenetre un peu plus tard (si rien de nouveau n'arrive).
function hide() {
  clearTimeout(timer);
  cur = null;
  box.hidden = true;
  win.hide();
  clearTimeout(closeTimer);
  closeTimer = setTimeout(() => win.close(), 3000);
}

function show(p) {
  clearTimeout(timer);
  clearTimeout(closeTimer);
  cur = p;
  box.hidden = false;
  title.textContent = p.title;
  detail.textContent = p.detail || "";
  const isProgress = p.kind === "progress";
  bar.hidden = !isProgress;
  bar.classList.toggle("indet", isProgress && p.percent == null);
  if (isProgress && p.percent != null) {
    fill.style.width = p.percent + "%";
    bar.setAttribute("aria-valuenow", String(p.percent));
  } else {
    bar.removeAttribute("aria-valuenow");
  }
  actions.hidden = p.kind !== "request";
  const clickable = p.kind === "done" && !!p.path;
  box.classList.toggle("clickable", clickable);
  if (clickable) {
    box.tabIndex = 0;
    box.setAttribute("role", "button");
  } else {
    box.removeAttribute("tabindex");
    box.setAttribute("role", "alert");
  }
  if (p.kind === "done" || p.kind === "fail") timer = setTimeout(hide, 4500);
}

function onToast(p) {
  if (p.kind === "clear") {
    if (cur && cur.id === p.id) hide();
    return;
  }
  show(p);
}
listen("toast", (e) => onToast(e.payload));
// Page prete : recupere la notification en attente (fenetre creee a la demande).
invoke("toast_ready").then((p) => p && onToast(p));

function reply(accept) {
  if (cur && cur.kind === "request") invoke("answer", { id: cur.id, accept });
}
document.getElementById("accept").addEventListener("click", () => reply(true));
document.getElementById("refuse").addEventListener("click", () => reply(false));

function openReceived() {
  if (cur && cur.kind === "done" && cur.path) {
    invoke("open_received", { path: cur.path }).catch(() => {});
    hide();
  }
}
box.addEventListener("click", (e) => {
  if (!e.target.closest("button")) openReceived();
});
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape") {
    if (cur && cur.kind === "request") reply(false);
    else hide();
  } else if (e.key === "Enter" && e.target === box) openReceived();
});
