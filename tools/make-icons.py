"""Genere les icones FileSend (Windows .ico/.png, Android mipmaps). Usage : python tools/make-icons.py (Pillow requis)."""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
S = 1024
C1, C2 = (37, 99, 235), (14, 165, 233)  # degrade bleu -> cyan

PLANE = {  # coordonnees normalisees (boite 0..1)
    "upper": [(0.10, 0.46), (0.92, 0.10), (0.46, 0.60)],
    "lower": [(0.46, 0.60), (0.92, 0.10), (0.58, 0.92)],
}

def gradient(size):
    g = Image.new("RGB", (size, size))
    px = g.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * size - 2)
            px[x, y] = tuple(round(C1[i] + (C2[i] - C1[i]) * t) for i in range(3))
    return g

def plane_layer(size, scale, mono=False):
    """Avion en papier centre, occupant `scale` de la taille."""
    L = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(L)
    off = (1 - scale) / 2
    pt = lambda p: (size * (off + p[0] * scale), size * (off + p[1] * scale))
    d.polygon([pt(p) for p in PLANE["upper"]], fill=(255, 255, 255, 255))
    d.polygon([pt(p) for p in PLANE["lower"]], fill=(255, 255, 255, 255) if mono else (219, 234, 254, 255))
    return L

def full_icon():
    bg = gradient(S).convert("RGBA")
    bg.alpha_composite(plane_layer(S, 0.58))
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.22), fill=255)
    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    out.paste(bg, mask=mask)
    return out

def save(img, path, size):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(path)

icon = full_icon()
win = os.path.join(ROOT, "windows", "app", "icons")
save(icon, os.path.join(win, "icon.png"), 256)
icon.save(os.path.join(win, "icon.ico"), sizes=[(s, s) for s in (16, 24, 32, 48, 64, 128, 256)])

res = os.path.join(ROOT, "android", "app", "src", "main", "res")
for dens, px in {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}.items():
    d = os.path.join(res, "mipmap-" + dens)
    save(plane_layer(S, 0.56), os.path.join(d, "ic_launcher_foreground.png"), px)
    save(plane_layer(S, 0.56, mono=True), os.path.join(d, "ic_launcher_monochrome.png"), px)
    # icone ronde/heritage pour les lanceurs sans icone adaptative
    save(icon, os.path.join(d, "ic_launcher.png"), round(px * 48 / 108))

os.makedirs(os.path.join(res, "mipmap-anydpi"), exist_ok=True)
with open(os.path.join(res, "mipmap-anydpi", "ic_launcher.xml"), "w", encoding="utf-8") as f:
    f.write('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
</adaptive-icon>
''')
with open(os.path.join(res, "values", "ic_launcher_background.xml"), "w", encoding="utf-8") as f:
    f.write('''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#1D7FEA</color>
</resources>
''')
print("ok")
