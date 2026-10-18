# AI Keyboard: логотип «жёлтая звёздочка» (две четырёхконечные искры). Запуск: python3 make_logo.py
# SVG и файлы Android создаются без библиотек; PNG только если установлена Pillow (pip install --user pillow).
import os
GOLD_HEX, BG_HEX = "#F6C945", "#121318"
GOLD, BG = (246, 201, 69), (18, 19, 24)
K = 0.17                       # «пухлость» звезды
BIG = (50.0, 58.0, 26.0)       # cx, cy, r в квадрате 108x108 (адаптивная иконка; безопасная зона: круг радиуса 33 от 54,54)
SMALL = (72.0, 36.0, 9.0)

def tips_ctrl(cx, cy, r, k=K):
    t = [(cx, cy - r), (cx + r, cy), (cx, cy + r), (cx - r, cy)]
    c = [(cx + k * r, cy - k * r), (cx + k * r, cy + k * r), (cx - k * r, cy + k * r), (cx - k * r, cy - k * r)]
    return t, c

def path_d(cx, cy, r):
    t, c = tips_ctrl(cx, cy, r)
    s = "M%.2f,%.2f " % t[0]
    for i in range(4):
        s += "Q%.2f,%.2f %.2f,%.2f " % (c[i][0], c[i][1], t[(i + 1) % 4][0], t[(i + 1) % 4][1])
    return s + "Z"

def write(p, s):
    os.makedirs(os.path.dirname(p) or ".", exist_ok=True)
    open(p, "w", encoding="utf-8").write(s)

def main():
    here = os.path.dirname(os.path.abspath(__file__))
    b, s = path_d(*BIG), path_d(*SMALL)
    write(f"{here}/logo_star.svg", f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="1024" height="1024"><path fill="{GOLD_HEX}" d="{b}"/><path fill="{GOLD_HEX}" d="{s}"/></svg>\n')
    write(f"{here}/app_icon.svg", f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="1024" height="1024"><rect width="108" height="108" fill="{BG_HEX}"/><path fill="{GOLD_HEX}" d="{b}"/><path fill="{GOLD_HEX}" d="{s}"/></svg>\n')
    vec = ('<?xml version="1.0" encoding="utf-8"?>\n<!-- %s -->\n<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n'
           '    <path android:fillColor="%s" android:pathData="%s"/>\n    <path android:fillColor="%s" android:pathData="%s"/>\n</vector>\n')
    write(f"{here}/android/ic_launcher_foreground.xml", vec % ("AI Keyboard: передний план адаптивной иконки (жёлтая звёздочка)", GOLD_HEX, b, GOLD_HEX, s))
    write(f"{here}/android/ic_launcher_monochrome.xml", vec % ("AI Keyboard: одноцветный значок для тематических иконок Android 13+", "#FFFFFFFF", b, "#FFFFFFFF", s))
    write(f"{here}/android/ic_launcher.xml", '<?xml version="1.0" encoding="utf-8"?>\n<!-- res/mipmap-anydpi-v26/ic_launcher.xml и ic_launcher_round.xml -->\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n    <background android:drawable="@color/ic_launcher_background"/>\n    <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>\n</adaptive-icon>\n')
    write(f"{here}/android/ic_launcher_background_color.xml", f'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">{BG_HEX}</color>\n</resources>\n')
    print("SVG и файлы Android созданы")
    try:
        from PIL import Image, ImageDraw
    except Exception:
        print("Pillow не установлена: PNG пропущены (pip install --user pillow)"); return
    def star_pts(cx, cy, r, f, n=48):
        t, c = tips_ctrl(cx, cy, r); pts = []
        for i in range(4):
            p0, cc, p1 = t[i], c[i], t[(i + 1) % 4]
            for j in range(n):
                u = j / n
                pts.append((((1 - u) ** 2 * p0[0] + 2 * (1 - u) * u * cc[0] + u * u * p1[0]) * f, ((1 - u) ** 2 * p0[1] + 2 * (1 - u) * u * cc[1] + u * u * p1[1]) * f))
        return pts
    def logo(size, bg=None, scale=1.0, color=GOLD, rounded=0.0, ss=4):
        S = size * ss; img = Image.new("RGBA", (S, S), (0, 0, 0, 0)); d = ImageDraw.Draw(img)
        if bg is not None:
            (d.rounded_rectangle((0, 0, S - 1, S - 1), radius=int(S * rounded), fill=bg + (255,)) if rounded > 0 else d.rectangle((0, 0, S, S), fill=bg + (255,)))
        f = S / 108.0
        for (cx, cy, r) in (BIG, SMALL):
            c = (54 + (cx - 54) * scale, 54 + (cy - 54) * scale)
            d.polygon(star_pts(c[0], c[1], r * scale, f), fill=color + (255,))
        return img.resize((size, size), Image.LANCZOS)
    out = f"{here}/png"; os.makedirs(out, exist_ok=True)
    logo(1024, None, 1.55).save(f"{out}/logo_star_transparent_1024.png")
    logo(512, BG, 1.25).save(f"{out}/store_icon_512.png")
    logo(1024, BG, 1.25, rounded=0.22).save(f"{out}/app_icon_rounded_1024.png")
    logo(432, None, 1.0).save(f"{out}/android_adaptive_foreground_432.png")
    logo(432, None, 1.0, color=(255, 255, 255)).save(f"{out}/android_monochrome_432.png")
    print("PNG созданы в", out)

if __name__ == "__main__":
    main()
