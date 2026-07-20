#!/usr/bin/env python3
"""Generate JKPHermex adaptive launcher icons — a golden jester crown.

Design notes (addresses the vision audit):
  - TOP-LIT vertical gradient gold (light at top -> deep gold at bottom).
    This is symmetric about the vertical axis, so left/right mirror exactly,
    while still reading as a 3D faceted metal crown. No left/right directional
    shading (that broke symmetry in v1).
  - The crown's bounding box is optically centered (nudged down a touch since
    the visual mass sits in the base band).
  - Adaptive foreground is transparent + sized for the 66/108 safe zone, so the
    system mask never clips it. Background is a dark vertical gradient.
  - Rendered at high supersample then LANCZOS-downsampled for clean AA edges.

Emits:
  - mipmap-*/ic_launcher.png + ic_launcher_round.png   (legacy, crown on dark)
  - mipmap-*/ic_launcher_foreground.png                 (adaptive fg, transparent)
  - drawable/ic_launcher_background.xml                 (adaptive bg gradient)
  - mipmap-anydpi-v26/ic_launcher.xml + ic_launcher_round.xml
"""
import math
import os
import sys
from PIL import Image, ImageDraw

if len(sys.argv) > 1:
    REPO = os.path.abspath(sys.argv[1])
else:
    REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(REPO, "android", "app", "src", "main", "res")

# --- palette -------------------------------------------------------------
GOLD_HI = (255, 232, 158)     # top highlight (top-lit)
GOLD = (226, 176, 64)         # mid gold
GOLD_LO = (150, 104, 30)      # bottom shadow
GOLD_EDGE = (96, 62, 16)      # outline
GOLD_RIDGE = (255, 244, 200)  # center ridge sparkle
GEM = (158, 30, 52)           # ruby
GEM_HI = (240, 110, 130)      # ruby highlight
GEM_EDGE = (90, 14, 28)
BG_TOP = (26, 20, 42)         # deep purple-black
BG_BOT = (8, 6, 14)


def radial_bg(size: int) -> Image.Image:
    img = Image.new("RGB", (size, size), BG_BOT)
    px = img.load()
    cx = cy = size / 2
    maxd = math.hypot(cx, cy)
    for y in range(size):
        for x in range(size):
            d = math.hypot(x - cx, y - cy) / maxd
            t = max(0.0, 1.0 - d * 1.15)
            px[x, y] = (
                int(BG_BOT[0] + (BG_TOP[0] - BG_BOT[0]) * t),
                int(BG_BOT[1] + (BG_TOP[1] - BG_BOT[1]) * t),
                int(BG_BOT[2] + (BG_TOP[2] - BG_BOT[2]) * t),
            )
    return img


def gradient_fill(canvas: Image.Image, points, top_rgb, mid_rgb, bot_rgb):
    """Fill `points` polygon on RGBA `canvas` with a vertical 3-stop gradient.

    Three stops (top -> mid -> bottom, with mid at 55% height) give a more
    polished, metallic ramp than a flat two-stop fill. Symmetric about the
    vertical axis, so left/right mirror exactly while still reading as 3D.
    """
    w, h = canvas.size
    ys = [p[1] for p in points]
    y0 = int(round(min(ys)))
    y1 = int(round(max(ys)))
    bh = max(1, y1 - y0)
    col = Image.new("RGB", (1, bh))
    pc = col.load()
    for yy in range(bh):
        t = yy / (bh - 1) if bh > 1 else 0.0
        if t < 0.55:
            u = t / 0.55
            a, b = top_rgb, mid_rgb
        else:
            u = (t - 0.55) / 0.45
            a, b = mid_rgb, bot_rgb
        pc[0, yy] = (
            int(a[0] + (b[0] - a[0]) * u),
            int(a[1] + (b[1] - a[1]) * u),
            int(a[2] + (b[2] - a[2]) * u),
        )
    grad = col.resize((w, bh), Image.NEAREST).convert("RGBA")
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).polygon(points, fill=255)
    full = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    full.paste(grad, (0, y0))
    full.putalpha(mask)
    canvas.alpha_composite(full)


def bell(draw, bx, by, br):
    draw.ellipse([bx - br, by - br, bx + br, by + br], fill=GEM,
                 outline=GEM_EDGE, width=max(2, int(br * 0.20)))
    draw.ellipse([bx - br * 0.45, by - br * 0.5, bx + br * 0.15, by + br * 0.1],
                 fill=GEM_HI)


def gem(draw, gx, gy, gr):
    draw.ellipse([gx - gr, gy - gr, gx + gr, gy + gr], fill=GEM,
                 outline=GEM_EDGE, width=max(2, int(gr * 0.28)))
    draw.ellipse([gx - gr * 0.42, gy - gr * 0.46, gx + gr * 0.14, gy + gr * 0.1],
                 fill=GEM_HI)


def draw_crown(canvas: Image.Image, cx: float, cy: float, w: float):
    """A symmetric 3-point jester crown, top-lit, centered at (cx, cy)."""
    d = ImageDraw.Draw(canvas)
    h = w * 0.78
    half = w * 0.50
    band_h = h * 0.20
    base = cy + h * 0.42
    band_top = base - band_h
    mid_top = cy - h * 0.58
    side_tip_y = mid_top + h * 0.22
    side_tip_x = half * 1.02
    inner_x = half * 0.40
    valley_y = band_top - h * 0.10
    edge_w = max(3, int(w * 0.016))

    # --- spiky body (one symmetric "M" polygon), top-lit gradient --------
    body = [
        (cx - half, band_top),
        (cx - side_tip_x, side_tip_y),
        (cx - inner_x, valley_y),
        (cx, mid_top),
        (cx + inner_x, valley_y),
        (cx + side_tip_x, side_tip_y),
        (cx + half, band_top),
    ]
    gradient_fill(canvas, body, GOLD_HI, GOLD, GOLD_LO)
    d.line([(cx, mid_top), (cx, band_top)], fill=GOLD_RIDGE, width=max(2, int(w * 0.010)))
    d.polygon(body, outline=GOLD_EDGE, width=edge_w)

    # --- base band (darker gradient, the crown's foot) -------------------
    band = [
        (cx - half, band_top), (cx + half, band_top),
        (cx + half, base), (cx - half, base),
    ]
    gradient_fill(canvas, band, GOLD, GOLD_LO, GOLD_EDGE)
    d.polygon(band, outline=GOLD_EDGE, width=edge_w)
    # bright rim along the top of the band
    d.line([(cx - half, band_top + edge_w * 0.5), (cx + half, band_top + edge_w * 0.5)],
           fill=GOLD_HI, width=max(2, int(h * 0.018)))

    # --- bells at each tip (symmetric) -----------------------------------
    br = w * 0.058
    bell(d, cx, mid_top - br * 0.25, br)
    bell(d, cx - side_tip_x, side_tip_y - br * 0.25, br * 0.82)
    bell(d, cx + side_tip_x, side_tip_y - br * 0.25, br * 0.82)

    # --- band gems at -0.5 / 0 / +0.5 (symmetric) ------------------------
    gr = band_h * 0.26
    gy = band_top + band_h * 0.55
    for gx in (cx - half * 0.5, cx, cx + half * 0.5):
        gem(d, gx, gy, gr)


def master(draw_size: int) -> Image.Image:
    """Legacy square: crown on the dark radial bg, optically centered."""
    img = radial_bg(draw_size).convert("RGBA")
    w = draw_size * 0.60
    h = w * 0.78
    cx = draw_size * 0.5
    cy = draw_size * 0.5 + h * 0.08   # nudge down so bbox center sits at canvas center
    draw_crown(img, cx, cy, w)
    return img


def master_adaptive(draw_size: int) -> Image.Image:
    """Adaptive foreground: crown on transparent, sized for the 66/108 safe zone."""
    img = Image.new("RGBA", (draw_size, draw_size), (0, 0, 0, 0))
    safe = draw_size * (66 / 108)
    w = safe * 0.88
    h = w * 0.78
    cx = draw_size * 0.5
    cy = draw_size * 0.5 + h * 0.08
    draw_crown(img, cx, cy, w)
    return img


def ensure(p):
    os.makedirs(p, exist_ok=True)


def main():
    densities = {
        "mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192,
    }
    for name, px in densities.items():
        ensure(os.path.join(OUT, name))
        sq = master(px * 4).resize((px, px), Image.LANCZOS)
        sq.convert("RGB").save(os.path.join(OUT, name, "ic_launcher.png"))
        rnd = sq.convert("RGBA")
        mask = Image.new("L", (px, px), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, px, px], fill=255)
        rnd.putalpha(mask)
        rnd.save(os.path.join(OUT, name, "ic_launcher_round.png"))
        print(f"  {name}: ic_launcher.png + ic_launcher_round.png ({px}px)")

    adp_px = {
        "mipmap-mdpi": 108, "mipmap-hdpi": 162, "mipmap-xhdpi": 216,
        "mipmap-xxhdpi": 324, "mipmap-xxxhdpi": 432,
    }
    fg_master = master_adaptive(432 * 4)  # 4x supersample for crisp AA edges
    for name, px in adp_px.items():
        fg_master.resize((px, px), Image.LANCZOS).save(
            os.path.join(OUT, name, "ic_launcher_foreground.png"))
        print(f"  {name}: ic_launcher_foreground.png ({px}px)")

    draw_dir = os.path.join(OUT, "drawable")
    ensure(draw_dir)
    with open(os.path.join(draw_dir, "ic_launcher_background.xml"), "w") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n')
        f.write('<shape xmlns:android="http://schemas.android.com/apk/res/android" '
                'android:shape="rectangle">\n')
        f.write('    <gradient android:angle="270" android:startColor="#1A142A" '
                'android:endColor="#08060E" android:type="linear" />\n')
        f.write('</shape>\n')
    print("  drawable/ic_launcher_background.xml")

    anydpi = os.path.join(OUT, "mipmap-anydpi-v26")
    ensure(anydpi)
    adaptive_xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@drawable/ic_launcher_background" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        '    <monochrome android:drawable="@mipmap/ic_launcher_foreground" />\n'
        '</adaptive-icon>\n'
    )
    for fname in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(anydpi, fname), "w") as f:
            f.write(adaptive_xml)
    print("  mipmap-anydpi-v26/ic_launcher.xml + ic_launcher_round.xml")
    print("\nIcon generation complete.")


if __name__ == "__main__":
    main()
