#!/usr/bin/env python3
"""Renders the README art into assets/: the wordmark tube, the grumbling tube, the speed signs, the icon.

Everything is drawn from the app's own parts: the 5x7 matrix font (MatrixDisplay.kt), the palette
(Palette.kt), Archivo (res/font) and the launcher icon. Text is outlined, so the SVGs need no font.

    python3 tools/readme/make_art.py        # needs fontTools
"""
import os
import re
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "assets")
FONT = os.path.join(ROOT, "app/src/main/res/font/archivo_variable.ttf")
MATRIX = os.path.join(ROOT, "app/src/main/java/dev/steven/s3control/ui/components/MatrixDisplay.kt")
ICON = os.path.join(ROOT, "app/src/main/res/drawable/ic_launcher_foreground.xml")

# Palette.kt, NightRide
ASPHALT, FRAME, RIB = "#191C21", "#252A31", "#353B45"
LED, LED_OFF, GLOW = "#D6ECFF", "#2E343D", "#A9D4FF"
SIGNAL, PAINT, FOG = "#FF5A61", "#F1F2F4", "#8E96A1"

COLS, ROWS = 36, 7
LIT_R, GHOST_R = 0.41, 0.265  # in p, the dot pitch

WORDMARK = [
    "##...##..#####..######...#####.",
    "##...##.##...##.##...##.##...##",
    ".##.##..##...##.##...##.##...##",
    "..###...##...##.######..##...##",
    "..###...#######.##...##.#######",
    "..###...##...##.##...##.##...##",
    "..###...##...##.######..##...##",
]


# --- text as outlines ----------------------------------------------------------------------------

_fonts = {}


def _font(wght, wdth):
    key = (wght, wdth)
    if key not in _fonts:
        _fonts[key] = instantiateVariableFont(TTFont(FONT), {"wght": wght, "wdth": wdth})
    return _fonts[key]


def text_path(text, x, y, size, wght=400, wdth=100, tracking=0.0, anchor="start"):
    """One SVG path for [text] with its baseline at (x, y). Returns (d, width)."""
    font = _font(wght, wdth)
    upm = font["head"].unitsPerEm
    cmap, glyphs, hmtx = font.getBestCmap(), font.getGlyphSet(), font["hmtx"]
    scale = size / upm
    names = [cmap.get(ord(c), ".notdef") for c in text]
    width = sum(hmtx[n][0] for n in names) * scale + tracking * size * max(len(names) - 1, 0)
    cx = x - (width / 2 if anchor == "middle" else width if anchor == "end" else 0)
    pen = SVGPathPen(glyphs, ntos=lambda v: ("%.2f" % v).rstrip("0").rstrip("."))
    for n in names:
        glyphs[n].draw(TransformPen(pen, (scale, 0, 0, -scale, cx, y)))
        cx += hmtx[n][0] * scale + tracking * size
    return pen.getCommands(), width


def text(text_, x, y, size, fill, **kw):
    d, _ = text_path(text_, x, y, size, **kw)
    return f'<path fill="{fill}" d="{d}"/>'


# --- the matrix ----------------------------------------------------------------------------------

def matrix_font():
    src = open(MATRIX, encoding="utf-8").read()
    return {m[1]: re.findall(r'"([#.]+)"', m[2]) for m in re.finditer(r"'(.)' to arrayOf\(([^)]*)\)", src)}


def render(word, font):
    """Rows of '#'/'.' for [word] in the app's 5x7 font, one empty column between glyphs."""
    rows = [""] * ROWS
    for i, ch in enumerate(word):
        glyph = font.get(ch) or ["..."] * ROWS
        for r in range(ROWS):
            rows[r] += ("." if i else "") + glyph[r]
    return rows


def centred(rows):
    start = (COLS - len(rows[0])) // 2
    return [(start + c, r) for r, row in enumerate(rows) for c, ch in enumerate(row) if ch == "#"]


def dots(cells, p, ox, oy, radius, fill):
    d = "".join(
        f"M{ox + (c + .5) * p - radius * p:.1f},{oy + (r + .5) * p:.1f}a{radius * p:.2f},{radius * p:.2f} 0 1,0 {2 * radius * p:.2f},0a{radius * p:.2f},{radius * p:.2f} 0 1,0 {-2 * radius * p:.2f},0"
        for c, r in cells
    )
    return f'<path fill="{fill}" d="{d}"/>'


def tube(p, x, y):
    """The top tube: a capsule in frame with a rib outline, the two end marks, every ghost dot. Returns (svg, field x, field y)."""
    w, h = (COLS + 6) * p, (ROWS + 3) * p
    fx, fy = x + 3 * p, y + 1.5 * p
    ghosts = dots([(c, r) for c in range(COLS) for r in range(ROWS)], p, fx, fy, GHOST_R, LED_OFF)
    marks = "".join(
        f'<rect x="{mx:.1f}" y="{y + 3 * p:.1f}" width="{p * .22:.1f}" height="{h - 6 * p:.1f}" rx="{p * .11:.1f}" fill="{RIB}"/>'
        for mx in (x + 1.6 * p, x + w - 1.82 * p)
    )
    body = f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{h / 2}" fill="{FRAME}" stroke="{RIB}" stroke-width="1.5"/>'
    return body + marks + ghosts, fx, fy, w, h


def lit(cells, p, fx, fy):
    """Lit dots with the tube's glow under them."""
    return f'<g filter="url(#glow)" opacity=".55">{dots(cells, p, fx, fy, LIT_R * 1.5, GLOW)}</g>{dots(cells, p, fx, fy, LIT_R, LED)}'


GLOW_FILTER = '<filter id="glow" x="-10%" y="-40%" width="120%" height="180%"><feGaussianBlur stdDeviation="{s}"/></filter>'


def dimension(x1, x2, y, label, size):
    """The measure line: a hairline in fog with 45 degree end ticks and its value centred above."""
    t = 5
    ticks = "".join(f'<path d="M{x - t},{y + t}L{x + t},{y - t}" stroke="{FOG}" stroke-width="1"/>' for x in (x1, x2))
    return (
        f'<path d="M{x1},{y}H{x2}" stroke="{FOG}" stroke-width="1"/>{ticks}'
        + text(label, (x1 + x2) / 2, y - size * .6, size, FOG, wght=500, wdth=88, anchor="middle")
    )


def svg(name, w, h, body, title, defs=""):
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, name), "w", encoding="utf-8") as f:
        f.write(
            f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w:g} {h:g}" width="{w:g}" height="{h:g}" role="img" aria-label="{title}">'
            f"<title>{title}</title><defs>{defs}</defs>{body}</svg>\n"
        )
    print(name, os.path.getsize(os.path.join(OUT, name)) // 1024, "kB")


# --- the pieces ----------------------------------------------------------------------------------

def hero():
    p, W = 24, 1200
    tx, ty = (W - (COLS + 6) * p) / 2, 4.5 * p
    body, fx, fy, tw, th = tube(p, tx, ty)
    start = (COLS - 31) // 2  # the wordmark sits at column 2, as on the welcome hero
    cells = [(start + c, r) for r, row in enumerate(WORDMARK) for c, ch in enumerate(row) if ch == "#"]
    left, right = fx + start * p, fx + (start + 31) * p
    # The sweep: left to right in 520 ms, FastOutSlowIn, once per 14.5 s loop.
    clip = (
        f'<clipPath id="sweep"><rect x="{fx}" y="{ty}" height="{th}" width="{COLS * p}">'
        f'<animate attributeName="width" values="0;0;{COLS * p};{COLS * p}" keyTimes="0;0.03;0.066;1" dur="14.5s" '
        f'calcMode="spline" keySplines="0 0 1 1;0.4 0 0.2 1;0 0 1 1" repeatCount="indefinite"/></rect></clipPath>'
    )
    word = f'<g clip-path="url(#sweep)">{lit(cells, p, fx, fy)}</g>'
    measure = dimension(left + .09 * p, right - .09 * p, ty - 1.4 * p, "31 × 7  ·  122 dots, each with 0.18 p of air", 15)
    # Lockup A: cap height 2 p, 900/125 over 400/100.
    size = 2.915 * p
    base = ty + th + 2 * p + 2 * p
    slogan = text("Keeps riding.", left, base, size, PAINT, wght=900, wdth=125, tracking=-0.015) + text(
        "No matter what.", left, base + 3.5 * p, size, PAINT, wght=400, wdth=100
    )
    edge = tx + tw - 1.5 * p
    side = text("YET ANOTHER BIKE APP", edge, base, 15, FOG, wght=500, wdth=88, tracking=0.08, anchor="end") + text(
        "NOT AFFILIATED WITH VANMOOF", edge, base + 3.5 * p, 15, FOG, wght=500, wdth=88, tracking=0.08, anchor="end"
    )
    H = base + 3.5 * p + 3 * p
    back = f'<rect width="{W}" height="{H}" rx="36" fill="{ASPHALT}"/>'
    svg("hero.svg", W, H, back + measure + body + word + slogan + side, "YABA. Keeps riding. No matter what.", GLOW_FILTER.format(s=p * .22) + clip)


def grumble(font):
    """The tube says one word from the bike's lexicon now and then, and is dark in between."""
    p, W = 16, 800
    tx, ty = (W - (COLS + 6) * p) / 2, 1.5 * p
    body, fx, fy, tw, th = tube(p, tx, ty)
    words = ["GEDOE", "BALEN", "NOU JA", "HE HE", "AUW"]
    slot, total = 4.0, 4.0 * len(words)
    layers = ""
    for i, w in enumerate(words):
        a, b = (i * slot + 0.6) / total, (i * slot + 3.3) / total
        e = 0.02 / total  # a hard cut, like the display
        kick = 0.12 / total
        layers += (
            f'<g opacity="0">{lit(centred(render(w, font)), p, fx, fy)}'
            f'<animate attributeName="opacity" values="0;0;1;1;0;0" keyTimes="0;{a:.4f};{a + e:.4f};{b:.4f};{b + e:.4f};1" dur="{total}s" repeatCount="indefinite"/>'
            f'<animateTransform attributeName="transform" type="translate" values="0 0;0 0;{p * .5:.1f} 0;0 0;0 0" '
            f'keyTimes="0;{a:.4f};{a + kick / 2:.4f};{a + kick:.4f};1" dur="{total}s" repeatCount="indefinite"/></g>'
        )
    H = ty + th + 1.5 * p
    back = f'<rect width="{W}" height="{H}" rx="24" fill="{ASPHALT}"/>'
    svg("grumble.svg", W, H, back + body + layers, "The top tube grumbles in Dutch: gedoe, balen, nou ja, he he, auw.", GLOW_FILTER.format(s=p * .22))


def signs():
    """The three region limits as the app's speed signs: white disc, ring in signal."""
    W, H, r = 520, 190, 58
    body = f'<rect width="{W}" height="{H}" rx="24" fill="{ASPHALT}"/>'
    for i, (n, label) in enumerate((("24", "JAPAN"), ("25", "EUROPE"), ("32", "USA"))):
        cx = W / 2 + (i - 1) * 160
        body += (
            f'<circle cx="{cx}" cy="80" r="{r - 7}" fill="#FFFFFF" stroke="{SIGNAL}" stroke-width="14"/>'
            + text(n, cx, 80 + 16, 46, ASPHALT, wght=800, wdth=75, anchor="middle")
            + text(label, cx, 170, 14, FOG, wght=500, wdth=88, tracking=0.08, anchor="middle")
        )
    svg("signs.svg", W, H, body, "Speed regions: Japan 24, Europe 25, USA 32 km/h.")


def icon():
    """The launcher icon: the Y on asphalt."""
    src = open(ICON, encoding="utf-8").read()
    paths = re.findall(r'fillColor="#FF([0-9A-F]{6})"\s+android:pathData="([^"]+)"', src)
    # The launcher mask shows the middle 72 of the 108 dp canvas.
    body = f'<g transform="translate(-18 -18)"><rect x="18" y="18" width="72" height="72" rx="17" fill="{ASPHALT}"/>' + "".join(
        f'<path fill="#{c}" d="{d}"/>' for c, d in paths
    ) + "</g>"
    svg("icon.svg", 72, 72, body, "YABA app icon")


if __name__ == "__main__":
    font = matrix_font()
    hero()
    grumble(font)
    signs()
    icon()
