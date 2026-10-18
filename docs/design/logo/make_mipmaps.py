# Запуск: PYTHONPATH=<папка с Pillow> python3 make_mipmaps.py make_logo.py ../../../app/src/main/res
# legacy launcher PNGs (Android 5-7) from the logo sources in docs/design/logo/make_logo.py
import sys, importlib.util
from PIL import Image, ImageDraw
spec = importlib.util.spec_from_file_location("make_logo", sys.argv[1]); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
res = sys.argv[2]
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
def star_pts(cx, cy, r, f, n=64):
    t, c = m.tips_ctrl(cx, cy, r); pts = []
    for i in range(4):
        p0, cc, p1 = t[i], c[i], t[(i + 1) % 4]
        for j in range(n):
            u = j / n
            pts.append((((1-u)**2*p0[0] + 2*(1-u)*u*cc[0] + u*u*p1[0]) * f, ((1-u)**2*p0[1] + 2*(1-u)*u*cc[1] + u*u*p1[1]) * f))
    return pts
def icon(size, round_, scale=1.3, ss=8):
    S = size * ss; img = Image.new("RGBA", (S, S), (0, 0, 0, 0)); d = ImageDraw.Draw(img)
    # legacy icons keep a small transparent margin like the platform templates (2/48)
    pad = S * 2 / 48
    box = (pad, pad, S - pad, S - pad)
    if round_: d.ellipse(box, fill=m.BG + (255,))
    else: d.rounded_rectangle(box, radius=int((S - 2 * pad) * 0.2), fill=m.BG + (255,))
    f = S / 108.0
    for (cx, cy, r) in (m.BIG, m.SMALL):
        c = (54 + (cx - 54) * scale, 54 + (cy - 54) * scale)
        d.polygon(star_pts(c[0], c[1], r * scale, f), fill=m.GOLD + (255,))
    return img.resize((size, size), Image.LANCZOS)
for dpi, px in SIZES.items():
    icon(px, False).save(f"{res}/mipmap-{dpi}/ic_launcher.png", optimize=True)
    icon(px, True).save(f"{res}/mipmap-{dpi}/ic_launcher_round.png", optimize=True)
print("ok")
