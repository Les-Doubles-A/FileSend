// Verifie le contraste (WCAG, 4,5:1) des couleurs de windows/app/ui/style.css.
// Usage : node tools/check-contrast.mjs
import { readFileSync } from "node:fs";

const css = readFileSync(new URL("../windows/app/ui/style.css", import.meta.url), "utf8");

function block(re) {
  const m = css.match(re);
  if (!m) throw new Error("bloc introuvable : " + re);
  return m[1];
}
function vars(text) {
  const o = {};
  for (const m of text.matchAll(/--([\w-]+):\s*([^;]+);/g)) o[m[1]] = m[2].trim();
  return o;
}
const light = vars(block(/:root\s*\{([^}]*)\}/));
const dark = { ...light, ...vars(block(/prefers-color-scheme:\s*dark\)\s*\{\s*:root\s*\{([^}]*)\}/)) };

function parse(c) {
  let m = c.match(/^#([0-9a-f]{6})$/i);
  if (m) return [0, 2, 4].map((i) => parseInt(m[1].slice(i, i + 2), 16)).concat(1);
  m = c.match(/^rgba?\(([^)]+)\)$/);
  if (m) {
    const p = m[1].split(",").map((s) => parseFloat(s));
    return [p[0], p[1], p[2], p[3] ?? 1];
  }
  throw new Error("couleur non geree : " + c);
}
const over = (fg, bg) => [0, 1, 2].map((i) => fg[i] * fg[3] + bg[i] * (1 - fg[3])).concat(1);
const lum = (c) => {
  const f = c.slice(0, 3).map((v) => {
    v /= 255;
    return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * f[0] + 0.7152 * f[1] + 0.0722 * f[2];
};
const ratio = (a, b) => {
  const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
};

let failed = 0;
function check(label, fg, bg) {
  const r = ratio(fg, bg);
  const ok = r >= 4.5;
  if (!ok) failed++;
  console.log(`${ok ? "ok  " : "FAIL"} ${r.toFixed(2).padStart(5)}  ${label}`);
}

// Fonds possibles derriere la surface translucide (cas extremes realistes pour Mica/acrylique).
const backdrops = {
  light: { clair: [255, 255, 255, 1], moyen: [176, 176, 176, 1] },
  dark: { sombre: [0, 0, 0, 1], moyen: [74, 74, 74, 1] },
};

for (const [theme, v] of [["clair", light], ["sombre", dark]]) {
  const t = theme === "clair" ? backdrops.light : backdrops.dark;
  const text = parse(v.text), muted = parse(v.muted), solid = parse(v.solid);
  check(`${theme} : texte / fond opaque (repli)`, text, solid);
  check(`${theme} : texte attenue / fond opaque (repli)`, muted, solid);
  for (const [name, bd] of Object.entries(t)) {
    const surf = over(parse(v.surface), bd);
    check(`${theme} : texte / surface sur fond ${name}`, text, surf);
    check(`${theme} : texte attenue / surface sur fond ${name}`, muted, surf);
    // Texte attenue directement sur le fond (en-tete, message vide) : fond Mica proche de bd.
    check(`${theme} : texte attenue / fond ${name}`, muted, bd);
  }
  check(`${theme} : bouton principal (texte / accent)`, parse(v["on-accent"]), parse(v.accent));
}
// ---- Android : memes jetons que le CSS (parite) et memes seuils ----
const kt = readFileSync(new URL("../android/app/src/main/kotlin/app/filesend/android/Theme.kt", import.meta.url), "utf8");
const K = {};
for (const m of kt.matchAll(/const val ([A-Z_]+) = 0x([0-9A-Fa-f]{8})L/g)) {
  const n = parseInt(m[2], 16);
  K[m[1]] = [(n >> 16) & 255, (n >> 8) & 255, n & 255, ((n >>> 24) & 255) / 255];
}
const names = { TEXT: "text", MUTED: "muted", SOLID: "solid", SURFACE: "surface", STROKE: "stroke", ACCENT: "accent", ON_ACCENT: "on-accent" };
let parity = 0;
for (const [theme, v, prefix] of [["clair", light, "LIGHT_"], ["sombre", dark, "DARK_"]]) {
  for (const [k, css] of Object.entries(names)) {
    const a = K[prefix + k];
    if (!a) { console.log(`FAIL jeton Android absent : ${prefix}${k}`); parity++; continue; }
    const b = parse(v[css]);
    const same = [0, 1, 2].every((i) => a[i] === b[i]) && Math.abs(a[3] - b[3]) < 0.01;
    if (!same) { console.log(`FAIL parite ${theme} ${css} : Android ${a} / CSS ${b}`); parity++; }
  }
}
console.log(parity ? "" : "ok   parite des palettes Android et Windows");
failed += parity;
for (const [theme, prefix, t] of [["clair", "LIGHT_", backdrops.light], ["sombre", "DARK_", backdrops.dark]]) {
  const text = K[prefix + "TEXT"], muted = K[prefix + "MUTED"], solid = K[prefix + "SOLID"];
  check(`Android ${theme} : texte / fond opaque (repli)`, text, solid);
  check(`Android ${theme} : texte attenue / fond opaque (repli)`, muted, solid);
  for (const [name, bd] of Object.entries(t)) {
    const surf = over(K[prefix + "SURFACE"], bd);
    check(`Android ${theme} : texte / surface sur fond ${name}`, text, surf);
    check(`Android ${theme} : texte attenue / surface sur fond ${name}`, muted, surf);
    check(`Android ${theme} : texte attenue / fond ${name}`, muted, bd);
  }
  check(`Android ${theme} : bouton principal (texte / accent)`, K[prefix + "ON_ACCENT"], K[prefix + "ACCENT"]);
  // Initiale blanche sur l'avatar le plus clair possible (toutes les teintes, L = 30 %).
  const hsl = (h) => { const s = 0.6, l = 0.30, c = (1 - Math.abs(2 * l - 1)) * s, x = c * (1 - Math.abs(((h / 60) % 2) - 1)), m = l - c / 2;
    const [r, g, b] = h < 60 ? [c, x, 0] : h < 120 ? [x, c, 0] : h < 180 ? [0, c, x] : h < 240 ? [0, x, c] : h < 300 ? [x, 0, c] : [c, 0, x];
    return [(r + m) * 255, (g + m) * 255, (b + m) * 255, 1]; };
  for (let h = 0; h < 360; h += 5) check(`Android avatar teinte ${h} : initiale blanche`, [255, 255, 255, 1], hsl(h));
}

if (failed) {
  console.log(`\n${failed} combinaison(s) sous 4,5:1`);
  process.exit(1);
}
console.log("\nContraste conforme (>= 4,5:1)");
