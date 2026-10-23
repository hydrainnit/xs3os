"""Draws the XS3OS wordmark (original geometric letterforms, PS3-logo-inspired: wide, thin stroke,
rounded corners) and the launcher banner/icon. No font files are used.
Run: python3 -I tools/brand/make_logo.py   (needs Pillow)"""
import math
from PIL import Image, ImageDraw, ImageFilter

SS = 4  # supersampling


def rounded_poly(pts, r, n=28):
    """Polyline through pts with each inner corner rounded by radius r."""
    out = [pts[0]]
    for i in range(1, len(pts) - 1):
        p0, p1, p2 = pts[i - 1], pts[i], pts[i + 1]
        v1 = (p0[0] - p1[0], p0[1] - p1[1]); v2 = (p2[0] - p1[0], p2[1] - p1[1])
        l1 = math.hypot(*v1); l2 = math.hypot(*v2)
        rr = min(r, l1 / 2, l2 / 2)
        a = (p1[0] + v1[0] / l1 * rr, p1[1] + v1[1] / l1 * rr)
        b = (p1[0] + v2[0] / l2 * rr, p1[1] + v2[1] / l2 * rr)
        for k in range(n + 1):
            t = k / n
            x = (1 - t) ** 2 * a[0] + 2 * (1 - t) * t * p1[0] + t * t * b[0]
            y = (1 - t) ** 2 * a[1] + 2 * (1 - t) * t * p1[1] + t * t * b[1]
            out.append((x, y))
    out.append(pts[-1])
    return out


def rrect(x0, y0, x1, y1, r):
    pts = [(x0 + r, y0), (x1, y0), (x1, y1), (x0, y1), (x0, y0), (x0 + r, y0)]
    # closed rounded rectangle through its corners
    c = [(x0 + (x1 - x0) / 2, y0), (x1, y0), (x1, y1), (x0, y1), (x0, y0), (x0 + (x1 - x0) / 2, y0)]
    return rounded_poly(c, r)


W = 84  # letter width


def arc(cx, cy, rx, ry, a0, a1, n=90):
    """Elliptical arc, angles in degrees (0 = right, 90 = up), screen y down."""
    return [(cx + rx * math.cos(math.radians(a0 + (a1 - a0) * i / n)),
             cy - ry * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]


GLYPHS = {
    "X": [[(6, 10), (W, 90)], [(W, 10), (6, 90)]],
    "S": [rounded_poly([(W, 10), (6, 10), (6, 50), (W, 50), (W, 90), (6, 90)], 24)],
    "3": [rounded_poly([(4, 8), (W - 4, 8), (W - 4, 92), (4, 92)], 28), [(28, 50), (W - 4, 50)]],
    "O": [[(4 + (W - 8) / 2 * (1 + math.cos(t * math.pi / 60)), 50 + 40 * math.sin(t * math.pi / 60)) for t in range(0, 121)]],
}
STROKE = 9.5


def wordmark(text="XS3OS", height=300, gap=30, color=(255, 255, 255)):
    s = height / 100.0 * SS
    total = sum(W + gap for _ in text) - gap
    pad = 12
    img = Image.new("RGBA", (int((total + pad * 2) * s), int((100 + pad * 2) * s)), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    x = pad
    for ch in text:
        for stroke in GLYPHS[ch]:
            pts = [((x + px) * s, (pad + py) * s) for px, py in stroke]
            rad = int(STROKE * s) / 2.0
            for a, b in zip(pts, pts[1:]):
                d.line([a, b], fill=color + (255,), width=int(STROKE * s))
            for p in pts:
                d.ellipse([p[0] - rad, p[1] - rad, p[0] + rad, p[1] + rad], fill=color + (255,))
        x += W + gap
    return img.resize((img.width // SS, img.height // SS), Image.LANCZOS)


def waves(size, tint=(220, 235, 255)):
    w, h = size
    img = Image.new("RGBA", size, (0, 0, 0, 0))
    for l in range(3):
        layer = Image.new("RGBA", size, (0, 0, 0, 0))
        d = ImageDraw.Draw(layer)
        top, bot = [], []
        for i in range(0, w + 8, 8):
            x = i / w
            y = h * (0.58 + (l - 1) * 0.06) + (0.5 - x) * h * 0.24 + h * 0.07 * (0.62 * math.sin(x * 5 + 1.3 + l * 2.1) + 0.38 * math.sin(x * 8.7 - 0.9 + l))
            t = h * (0.07 + 0.04 * l) * (0.3 + 0.7 * math.sin(x * 3.1 + 0.8 + l * 1.2) ** 2)
            top.append((i, y - t / 2)); bot.append((i, y + t / 2))
        d.polygon(top + bot[::-1], fill=tint + (70 - l * 12,))
        d.line(top, fill=tint + (150,), width=max(1, h // 200))
        img = Image.alpha_composite(img, layer)
    return img.filter(ImageFilter.GaussianBlur(max(1, h // 400)))


def gradient(size, top, mid, bot):
    w, h = size
    img = Image.new("RGBA", size)
    px = img.load()
    for y in range(h):
        t = y / (h - 1)
        a, b, k = (top, mid, t / 0.58) if t < 0.58 else (mid, bot, (t - 0.58) / 0.42)
        c = tuple(int(a[i] + (b[i] - a[i]) * k) for i in range(3))
        for x in range(w):
            px[x, y] = c + (255,)
    return img


def paste_center(bg, fg, cy=0.5, scale=0.7):
    f = fg.resize((int(bg.width * scale), int(fg.height * bg.width * scale / fg.width)), Image.LANCZOS)
    bg.alpha_composite(f, ((bg.width - f.width) // 2, int(bg.height * cy - f.height / 2)))


if __name__ == "__main__":
    import sys
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    logo = wordmark()
    logo.save(f"{out}/xs3os_logo.png")
    bg = gradient((640, 360), (60, 143, 174), (27, 79, 143), (6, 11, 60))
    bg.alpha_composite(waves((640, 360)))
    paste_center(bg, wordmark(height=120), cy=0.47, scale=0.62)
    bg.convert("RGB").save(f"{out}/banner.png")
    ic = gradient((512, 512), (60, 143, 174), (27, 79, 143), (6, 11, 60))
    ic.alpha_composite(waves((512, 512)))
    paste_center(ic, wordmark("XS3", height=120), cy=0.5, scale=0.78)
    ic.convert("RGB").save(f"{out}/ic_launcher.png")
