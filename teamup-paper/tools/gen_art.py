"""Draws the paper-cutout sprites and block textures for Team Up! Paper.

Run:  python3 tools/gen_art.py   (from the teamup-paper folder)
Writes PNGs into art/. Every character is drawn from shapes at 4x size,
scaled down for smooth edges, then given a white "sticker" border.
"""
import math, os
from PIL import Image, ImageDraw, ImageFilter

OUT = os.path.join(os.path.dirname(__file__), "..", "art")
os.makedirs(OUT, exist_ok=True)

SS = 4            # supersample
W, H = 64, 80     # base canvas (units); final PNG = 4x this = 256x320
INK = (43, 34, 51, 255)
LW = 2.2          # outline width in units


def rgb(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


class Pen:
    def __init__(self, w=W, h=H):
        self.w, self.h = w, h
        self.img = Image.new("RGBA", (w * SS * 4 // 4 * 1, h * SS), (0, 0, 0, 0))
        self.img = Image.new("RGBA", (w * SS, h * SS), (0, 0, 0, 0))
        self.d = ImageDraw.Draw(self.img)

    def _b(self, x0, y0, x1, y1):
        return [x0 * SS, y0 * SS, x1 * SS, y1 * SS]

    def oval(self, cx, cy, rx, ry, fill, ink=True):
        self.d.ellipse(self._b(cx - rx, cy - ry, cx + rx, cy + ry), fill=fill,
                       outline=INK if ink else None, width=int(LW * SS) if ink else 0)

    def rect(self, x0, y0, x1, y1, fill, r=2, ink=True):
        self.d.rounded_rectangle(self._b(x0, y0, x1, y1), radius=r * SS, fill=fill,
                                 outline=INK if ink else None, width=int(LW * SS) if ink else 0)

    def poly(self, pts, fill, ink=True):
        p = [(x * SS, y * SS) for x, y in pts]
        self.d.polygon(p, fill=fill)
        if ink:
            self.d.line(p + [p[0]], fill=INK, width=int(LW * SS), joint="curve")

    def line(self, pts, color=INK, w=LW):
        self.d.line([(x * SS, y * SS) for x, y in pts], fill=color, width=int(w * SS), joint="curve")

    def limb(self, x0, y0, x1, y1, w, fill):
        """A rounded capsule from (x0,y0) to (x1,y1)."""
        self.line([(x0, y0), (x1, y1)], INK, w + LW * 2)
        self.oval(x0, y0, w / 2 + LW, w / 2 + LW, INK, ink=False)
        self.oval(x1, y1, w / 2 + LW, w / 2 + LW, INK, ink=False)
        self.line([(x0, y0), (x1, y1)], fill, w)
        self.oval(x0, y0, w / 2, w / 2, fill, ink=False)
        self.oval(x1, y1, w / 2, w / 2, fill, ink=False)

    def eye(self, cx, cy, rx=2.6, ry=3.6, look=0.6):
        self.oval(cx, cy, rx, ry, rgb("#ffffff"))
        self.oval(cx + look, cy + 0.4, rx * 0.62, ry * 0.7, rgb("#1e2a44"), ink=False)
        self.oval(cx + look + 0.7, cy - 0.9, rx * 0.25, ry * 0.22, rgb("#ffffff"), ink=False)

    def star(self, cx, cy, r, fill):
        pts = []
        for i in range(10):
            a = -math.pi / 2 + i * math.pi / 5
            rr = r if i % 2 == 0 else r * 0.45
            pts.append((cx + math.cos(a) * rr, cy + math.sin(a) * rr))
        self.poly(pts, fill, ink=True)

    def done(self, name, border=3.2, rot=0):
        img = self.img
        if rot:
            img = img.rotate(rot, resample=Image.BICUBIC, center=(img.width / 2, img.height * 0.55))
        img = img.resize((img.width // SS * 4, img.height // SS * 4), Image.LANCZOS)
        # white sticker border
        a = img.getchannel("A").point(lambda v: 255 if v > 40 else 0)
        rad = int(border * 4)
        grown = a.filter(ImageFilter.MaxFilter(rad * 2 + 1)).filter(ImageFilter.GaussianBlur(1.2))
        sticker = Image.new("RGBA", img.size, (255, 255, 255, 0))
        sticker.putalpha(grown)
        out = Image.alpha_composite(sticker, img)
        out.save(os.path.join(OUT, name + ".png"))


# ---------------------------------------------------------------- Rocco
R_RED, R_DRK = rgb("#e8413b"), rgb("#a92a2f")
SKIN, SKIN_S = rgb("#f7c69c"), rgb("#e09d74")
HAIR = rgb("#6b3d1f")
JEAN, JEAN_D = rgb("#3d74db"), rgb("#2c529c")
SHOE = rgb("#ffffff")
GOLD = rgb("#ffcf33")


def rocco(name, legA=0.0, legB=0.0, armA=0.0, armB=0.0, squash=0.0, rot=0, blink=False, mouth="smile"):
    p = Pen()
    cx, hip = 32, 56
    sy = 1 - squash
    # back arm
    ax, ay = cx - 2, 41
    p.limb(ax, ay, ax + math.sin(armB) * 10, ay + math.cos(armB) * 10, 5.5, R_DRK)
    p.oval(ax + math.sin(armB) * 11, ay + math.cos(armB) * 11, 3.2, 3.2, SKIN_S)
    # legs
    for a, col in ((legB, JEAN_D), (legA, JEAN)):
        fx, fy = cx + math.sin(a) * 11, hip + math.cos(a) * 11 * sy
        p.limb(cx, hip - 2, fx, fy, 6.5, col)
        p.oval(fx + 2.2, fy + 3, 5.2, 3.4, SHOE)
        p.rect(fx - 2.6, fy + 3.6, fx + 7.4, fy + 6.0, rgb("#c7ccd6"), r=1)
    # body (red shirt) + shorts
    p.rect(cx - 9, 37, cx + 9, 56 - squash * 4, R_RED, r=6)
    p.rect(cx - 9, 50 - squash * 4, cx + 9, 58 - squash * 4, JEAN, r=3)
    p.star(cx + 2, 45, 3.6, GOLD)
    # head
    hy = 24 + squash * 5
    p.oval(cx + 1, hy, 14, 13, SKIN)
    p.oval(cx - 9, hy + 2, 4.5, 6.5, HAIR)           # hair at back
    p.oval(cx - 3, hy + 3, 3.2, 3.8, SKIN)           # ear
    p.oval(cx - 3, hy + 3, 1.4, 1.8, SKIN_S, ink=False)
    if blink:
        p.line([(cx + 6, hy + 3), (cx + 10, hy + 3)], INK, 1.6)
    else:
        p.eye(cx + 8, hy + 3, 2.6, 3.6)
    p.oval(cx + 14, hy + 6, 2.8, 2.4, SKIN_S)       # nose
    if mouth == "smile":
        p.d.arc(p._b(cx + 5, hy + 6, cx + 12, hy + 11), 20, 150, fill=INK, width=int(1.6 * SS))
    elif mouth == "open":
        p.oval(cx + 9, hy + 9.5, 2.4, 2.0, rgb("#7a1f2b"))
    elif mouth == "ow":
        p.oval(cx + 9, hy + 9.5, 2.0, 2.6, rgb("#7a1f2b"))
    # cap: dome + brim to the right + a little star
    p.d.chord(p._b(cx - 13, hy - 15, cx + 15, hy + 9), 180, 360, fill=R_RED, outline=INK, width=int(LW * SS))
    p.poly([(cx + 6, hy - 6), (cx + 22, hy - 5), (cx + 21, hy - 2), (cx + 4, hy - 1.5)], R_DRK)
    p.star(cx - 1, hy - 8, 3.2, GOLD)
    # front arm
    ax, ay = cx + 3, 41
    hx, hy2 = ax + math.sin(armA) * 10, ay + math.cos(armA) * 10
    p.limb(ax, ay, hx, hy2, 5.5, R_RED)
    p.oval(hx + math.sin(armA) * 1.5, hy2 + math.cos(armA) * 1.5, 3.4, 3.4, SKIN)
    p.done(name, rot=rot)


# ---------------------------------------------------------------- Blocky
VEST, VEST_D = rgb("#ff8a1f"), rgb("#d06a10")
SHIRT = rgb("#3f7fd9")
PANTS, PANTS_D = rgb("#2f4a9c"), rgb("#233a7c")
BOOT = rgb("#4a3424")
HAT = rgb("#ffd23a")
BSKIN, BSKIN_S = rgb("#e2a77c"), rgb("#c4845a")


def pickaxe(p, gx, gy, ang):
    """Pickaxe held at (gx,gy), pointing angle ang (radians, 0 = up)."""
    tx, ty = gx + math.sin(ang) * 20, gy - math.cos(ang) * 20
    p.limb(gx - math.sin(ang) * 4, gy + math.cos(ang) * 4, tx, ty, 3.2, rgb("#a8743f"))
    nx, ny = math.cos(ang), math.sin(ang)
    head = [(tx - nx * 11 + math.sin(ang) * 1, ty - ny * 11 - math.cos(ang) * 1 + 3 * math.cos(ang)),
            (tx + math.sin(ang) * 3.5, ty - math.cos(ang) * 3.5),
            (tx + nx * 11 + math.sin(ang) * 1, ty + ny * 11 - math.cos(ang) * 1 + 3 * math.cos(ang)),
            (tx - math.sin(ang) * 1.5, ty + math.cos(ang) * 1.5)]
    p.poly(head, rgb("#b8c4cf"))


def blocky(name, legA=0.0, legB=0.0, armA=0.0, pick=0.3, squash=0.0, rot=0):
    p = Pen()
    cx, hip = 31, 56
    sy = 1 - squash
    p.limb(cx - 3, 41, cx - 3 - math.sin(armA) * 8, 51, 6, SHIRT)
    for a, col in ((legB, PANTS_D), (legA, PANTS)):
        fx, fy = cx + math.sin(a) * 11, hip + math.cos(a) * 11 * sy
        p.limb(cx, hip - 2, fx, fy, 7.5, col)
        p.rect(fx - 4, fy, fx + 6.5, fy + 5.5, BOOT, r=2)
    # body: square-ish
    p.rect(cx - 10, 36, cx + 10, 57 - squash * 4, SHIRT, r=3)
    p.rect(cx - 10, 36, cx - 2, 57 - squash * 4, VEST, r=2)
    p.rect(cx + 3, 36, cx + 10, 57 - squash * 4, VEST, r=2)
    p.rect(cx - 10, 47, cx - 2, 49.5, rgb("#e9f3f5"), r=0.5, ink=False)
    p.rect(cx + 3, 47, cx + 10, 49.5, rgb("#e9f3f5"), r=0.5, ink=False)
    p.rect(cx - 10, 53 - squash * 4, cx + 10, 58 - squash * 4, PANTS, r=1.5)
    # head: blocky rounded square
    hy = 24 + squash * 5
    p.rect(cx - 11, hy - 11, cx + 13, hy + 12, BSKIN, r=5)
    p.rect(cx - 11, hy - 4, cx - 4, hy + 6, rgb("#3b2a20"), r=2)         # sideburn/hair
    p.rect(cx + 2, hy + 6.5, cx + 13, hy + 12, rgb("#5a3e2c"), r=3)      # little beard
    p.eye(cx + 6, hy, 2.3, 3.2)
    p.line([(cx + 3, hy - 4.6), (cx + 9, hy - 5.2)], rgb("#3b2a20"), 1.8)  # eyebrow
    p.d.arc(p._b(cx + 5, hy + 2.5, cx + 11, hy + 8), 30, 150, fill=INK, width=int(1.5 * SS))
    # hard hat + lamp
    p.d.chord(p._b(cx - 13, hy - 22, cx + 15, hy + 2), 180, 360, fill=HAT, outline=INK, width=int(LW * SS))
    p.rect(cx - 15, hy - 11, cx + 17, hy - 7.5, HAT, r=1.5)
    p.oval(cx + 13, hy - 14, 3.6, 3.6, rgb("#fff6b0"))
    p.oval(cx + 13, hy - 14, 1.8, 1.8, rgb("#ffffff"), ink=False)
    # front arm + pickaxe
    hx, hy2 = cx + 5 + math.sin(armA) * 6, 46 - math.cos(armA) * 2
    pickaxe(p, hx, hy2, pick)
    p.limb(cx + 4, 40, hx, hy2, 6, SHIRT)
    p.oval(hx, hy2, 3.6, 3.6, BSKIN)
    p.done(name, rot=rot)


# ---------------------------------------------------------------- Sparky (electric puffball)
SPK, SPK_D = rgb("#ffd83b"), rgb("#e8aa1c")
SPARK = rgb("#54c8ff")


def sparky(name, hop=0.0, feetA=0.0, zap=False, rot=0, stage=0):
    p = Pen()
    cx, cy = 32, 50 - hop
    if stage == 1:
        for i in range(3):   # spiky crest
            x = cx - 6 + i * 6
            p.poly([(x - 4, cy - 13), (x + 1, cy - 25 + abs(i - 1) * 3), (x + 4, cy - 13)], rgb("#f5c518"))
    for a in (-0.5, 0.5):  # antennae
        tx, ty = cx + 4 + math.sin(a) * 12, cy - 22 - math.cos(a) * 6
        p.line([(cx + 3 + a * 5, cy - 14), (tx, ty)], INK, 2.6)
        if stage == 1:
            p.poly([(tx - 2, ty - 6), (tx + 4, ty - 6), (tx, ty), (tx + 4, ty), (tx - 3, ty + 8), (tx - 1, ty + 1), (tx - 5, ty + 1)], SPARK)
        else:
            p.oval(tx, ty, 3.8, 3.8, SPARK)
            p.oval(tx - 1, ty - 1, 1.2, 1.2, rgb("#ffffff"), ink=False)
    for a in (feetA, -feetA):
        p.oval(cx - 2 + a * 6, cy + 15, 4.6, 3, rgb("#ff9a3c"))
    if stage == 1:
        p.oval(cx - 21, cy + 2, 7, 6, rgb("#f5c518"))
        p.oval(cx - 25, cy + 1, 3.2, 3.2, SPARK)
        p.oval(cx, cy, 17, 16, rgb("#f5c518"))
    else:
        p.poly([(cx - 17, cy), (cx - 24, cy - 6), (cx - 21, cy + 1), (cx - 27, cy + 2), (cx - 17, cy + 6)], SPK_D)  # zig tail
        p.oval(cx, cy, 17, 16, SPK)
    p.oval(cx + 3, cy + 6, 9, 7, rgb("#fff1a8"), ink=False)
    # blue zigzag belly stripe
    p.line([(cx - 6, cy + 5), (cx - 2, cy + 1), (cx + 2, cy + 6), (cx + 6, cy + 1), (cx + 10, cy + 5)], SPARK, 2.4)
    p.eye(cx + 5, cy - 5, 3.0, 4.0)
    p.eye(cx + 13, cy - 5, 2.4, 3.6)
    p.d.arc(p._b(cx + 6, cy - 1, cx + 13, cy + 4), 20, 160, fill=INK, width=int(1.5 * SS))
    if zap:
        for i in range(6):
            a = i * math.pi / 3
            x0, y0 = cx + math.cos(a) * 20, cy + math.sin(a) * 20
            p.line([(x0, y0), (x0 + math.cos(a + .5) * 6, y0 + math.sin(a + .5) * 6),
                    (x0 + math.cos(a) * 10, y0 + math.sin(a) * 10)], SPARK, 2.2)
    p.done(name, rot=rot)


# ---------------------------------------------------------------- partner creatures (our own designs)
CRIT = {
    "water": {"body": "#8fd3ff", "dark": "#5aa9df", "belly": "#e6f6ff", "shell": ["#2fb5a8", "#1f8f9c", "#1d5f8a"]},
    "grass": {"body": "#8fdc6a", "dark": "#5cb04a", "belly": "#e6f5b0"},
    "fire":  {"body": "#ff8a4a", "dark": "#e0602e", "belly": "#ffd9a0"},
}
STAGE_BODY = [["#8fd3ff", "#5fb8f5", "#3d7fd9"], ["#8fdc6a", "#5cc05a", "#3a9d5d"], ["#ff8a4a", "#e85a3a", "#b83a3a"]]


def flame(p, x, y, h, w=None):
    w = w or h * 0.55
    p.poly([(x - w, y), (x - w * 0.7, y - h * 0.55), (x - w * 0.15, y - h * 0.35), (x, y - h),
            (x + w * 0.25, y - h * 0.45), (x + w * 0.75, y - h * 0.6), (x + w, y)], rgb("#ffb02e"))
    p.poly([(x - w * 0.45, y), (x, y - h * 0.55), (x + w * 0.45, y)], rgb("#ffe36a"), ink=False)


def leaf(p, x, y, length, ang, col="#4fb35a"):
    dx, dy = math.cos(ang), math.sin(ang)
    nx, ny = -dy, dx
    tip = (x + dx * length, y + dy * length)
    mid = (x + dx * length * 0.5, y + dy * length * 0.5)
    p.poly([(x, y), (mid[0] + nx * length * 0.28, mid[1] + ny * length * 0.28), tip,
            (mid[0] - nx * length * 0.28, mid[1] - ny * length * 0.28)], rgb(col))
    p.line([(x, y), tip], rgb("#2f7a3a"), 1.0)


def critter(name, line, stage, pose="idle"):
    """A four-legged partner seen from the side, facing right."""
    p = Pen(84, 80)
    li = ["water", "grass", "fire"].index(line)
    BODY = rgb(STAGE_BODY[li][stage])
    DARK = rgb(CRIT[line]["dark"])
    BELLY = rgb(CRIT[line]["belly"])
    hop = {"jump": 6, "run1": 2, "run2": 0, "act": 1}.get(pose, 0)
    bx, by = 38, 55 - hop          # body centre
    hx, hy = 58, 42 - hop          # head centre
    if pose == "act":
        hx += 3
    # tail
    if line == "fire":
        p.poly([(bx - 14, by - 2), (bx - 26, by - 12 - stage * 2), (bx - 24, by - 8), (bx - 13, by + 4)], BODY)
        if stage >= 1:
            flame(p, bx - 26, by - 10 - stage * 2, 8 + stage * 3)
    elif line == "grass":
        p.poly([(bx - 14, by - 3), (bx - 25, by + 2), (bx - 13, by + 6)], BODY)
    else:
        p.poly([(bx - 15, by + 1), (bx - 21, by + 4), (bx - 14, by + 6)], BODY)
    # legs: far pair first (darker), near pair last
    sw = {"run1": 4, "run2": -4, "jump": 5}.get(pose, 0)
    for (lx, near) in ((bx - 9, False), (bx + 9, False), (bx - 6, True), (bx + 12, True)):
        dx = (sw if (lx < bx) == near else -sw)
        col = BODY if near else DARK
        p.rect(lx - 4 + dx * 0.5, by + 4, lx + 4 + dx, by + 15 - (3 if pose == "jump" and near else 0), col, r=3.5)
    # body
    p.oval(bx, by, 17 + stage, 12 + stage * 0.6, BODY)
    p.oval(bx + 3, by + 5, 11, 5.5, BELLY, ink=False)
    # back features
    if line == "water":
        SH = rgb(CRIT["water"]["shell"][stage])
        p.d.chord(p._b(bx - 18 - stage, by - 16 - stage, bx + 16 + stage, by + 8), 180, 360, fill=SH, outline=INK, width=int(LW * SS))
        for k in range(3):
            p.oval(bx - 9 + k * 9, by - 7 - (2 if k == 1 else 0), 3.4, 2.6, rgb("#7fe0d0"), ink=False)
        if stage == 2:   # two blowholes that spray
            for k in (-6, 6):
                p.rect(bx + k - 2.5, by - 22, bx + k + 2.5, by - 13, rgb("#9aa6b2"), r=1)
                for j in range(3):
                    p.oval(bx + k + (j - 1) * 2.5, by - 26 - j * 2, 2.2, 2.2, rgb("#7fd0ff"), ink=False)
    elif line == "grass":
        n = [0, 3, 5][stage]
        for k in range(n):
            leaf(p, bx - 10 + k * 5, by - 9 - (k % 2), 8 + stage * 2, -math.pi / 2 - 0.4 + k * 0.15)
        for (sx, sy) in ((bx - 6, by - 2), (bx + 4, by - 4), (bx - 1, by + 1)):
            p.oval(sx, sy, 1.8, 1.4, DARK, ink=False)
    elif line == "fire" and stage >= 1:
        for k in range(1 + stage * 2):
            flame(p, bx - 9 + k * (14 / max(1, stage * 2)), by - 10, 6 + stage * 2)
        if stage == 2:   # gliding fins on the side
            p.poly([(bx - 4, by - 2), (bx - 16, by - 14), (bx + 4, by - 6)], rgb("#ff9a3c"))
    # head
    p.oval(hx, hy, 11 + stage * 0.5, 10 + stage * 0.4, BODY)
    p.oval(hx + 6, hy + 4, 5.5, 4, BELLY, ink=False)
    # crest on the head
    if line == "water":
        if stage == 0:
            p.poly([(hx - 1, hy - 9), (hx + 1, hy - 18), (hx + 4, hy - 9)], rgb("#5ac8ff"))
            p.oval(hx + 1.5, hy - 9, 3, 2.4, rgb("#5ac8ff"))
        elif stage == 1:
            p.poly([(hx - 7, hy - 7), (hx - 4, hy - 16), (hx + 2, hy - 17), (hx - 1, hy - 13), (hx + 4, hy - 9)], rgb("#e6f6ff"))
    elif line == "grass":
        if stage == 0:
            p.line([(hx - 1, hy - 9), (hx, hy - 14)], rgb("#2f7a3a"), 1.4)
            leaf(p, hx, hy - 14, 9, -math.pi / 2 + 0.6)
        elif stage == 1:
            leaf(p, hx - 1, hy - 9, 10, -math.pi / 2 - 0.5)
            leaf(p, hx + 1, hy - 9, 10, -math.pi / 2 + 0.5)
            p.oval(hx, hy - 12, 2.6, 3.2, rgb("#ff8fc0"))
        else:
            for k in range(5):
                a = -math.pi / 2 + (k - 2) * 0.55
                p.oval(hx + math.cos(a) * 7, hy - 12 + math.sin(a) * 6, 4.2, 4.2, rgb("#ff7fb5"))
            p.oval(hx, hy - 12, 3.4, 3.4, rgb("#ffd23a"))
    elif line == "fire":
        if stage < 2:
            for k in range(3):
                flame(p, hx - 5 + k * 4, hy - 7, 6 + stage * 3 + (2 if k == 1 else 0))
        else:
            for k in range(7):
                a = math.pi * (0.75 + k * 0.25)
                flame(p, hx + math.cos(a) * 9, hy + math.sin(a) * 9 + 3, 9)
            p.oval(hx, hy, 11.5, 10.4, BODY)
            p.oval(hx + 6, hy + 4, 5.5, 4, BELLY, ink=False)
    # face
    p.eye(hx + 5, hy - 2, 3.0, 3.8, 0.9)
    if pose == "act":
        p.oval(hx + 9, hy + 5, 3, 2.6, rgb("#7a1f2b"))
        if line == "water":
            for j in range(4):
                p.oval(hx + 15 + j * 4, hy + 4 - j * 0.5, 2.4, 2.0, rgb("#7fd0ff"), ink=False)
        elif line == "fire":
            flame(p, hx + 16, hy + 8, 9, 4)
        else:
            p.line([(hx + 10, hy + 5), (hx + 16, hy - 2), (hx + 23, hy + 4), (hx + 30, hy - 3)], rgb("#3a9d3a"), 2.2)
    else:
        p.d.arc(p._b(hx + 5, hy + 2, hx + 11, hy + 7), 20, 160, fill=INK, width=int(1.5 * SS))
    p.done(name)


def partners():
    for line in ("water", "grass", "fire"):
        for st in range(3):
            for pose in ("idle", "run1", "run2", "jump", "act"):
                critter("mon_%s%d_%s" % (line, st, pose), line, st, pose)
    sparky("mon_spark1_idle", 0, 0.3, stage=1)
    sparky("mon_spark1_run1", 3, 0.7, stage=1)
    sparky("mon_spark1_run2", 0, -0.7, stage=1)
    sparky("mon_spark1_jump", 8, 0.4, stage=1)
    sparky("mon_spark1_act", 2, 0.3, zap=True, stage=1)


# ---------------------------------------------------------------- foes
def grumbler(name, step=0.0, squished=False):
    """A grumpy purple rock-blob with one horn."""
    p = Pen()
    cx, cy = 32, 54
    if squished:
        p.oval(cx, 70, 20, 6, rgb("#8a5bc4"))
        p.line([(cx - 6, 68), (cx - 2, 70)], INK, 2)
        p.line([(cx + 6, 68), (cx + 2, 70)], INK, 2)
        p.done(name)
        return
    for s in (-1, 1):
        p.oval(cx + s * 9 + step * s * 3, 72, 6, 4, rgb("#4a2f66"))
    p.poly([(cx - 3, cy - 18), (cx + 2, cy - 31), (cx + 6, cy - 17)], rgb("#f2e7d0"))
    p.oval(cx, cy, 19, 18, rgb("#8a5bc4"))
    p.oval(cx - 6, cy - 7, 5, 3.5, rgb("#a77ddb"), ink=False)
    p.eye(cx + 3, cy - 3, 3, 4, 0.9)
    p.eye(cx + 12, cy - 3, 2.6, 3.6, 0.9)
    p.line([(cx - 1, cy - 10), (cx + 7, cy - 7)], INK, 2.4)
    p.line([(cx + 9, cy - 7), (cx + 16, cy - 10)], INK, 2.4)
    p.d.arc(p._b(cx + 2, cy + 5, cx + 14, cy + 13), 200, 340, fill=INK, width=int(1.8 * SS))
    p.poly([(cx + 4, cy + 7), (cx + 6, cy + 11), (cx + 8, cy + 7)], rgb("#ffffff"), ink=False)
    p.done(name)


def skelly(name, step=0.0, aim=False):
    p = Pen()
    cx = 30
    BONE = rgb("#f1eee4")
    for a in (step, -step):
        p.limb(cx, 52, cx + math.sin(a) * 9, 70, 3.6, BONE)
        p.oval(cx + math.sin(a) * 9 + 2, 72, 4, 2.6, BONE)
    p.rect(cx - 6, 34, cx + 6, 52, BONE, r=3)
    for y in (39, 43, 47):
        p.line([(cx - 4, y), (cx + 4, y)], rgb("#b9b2a2"), 1.6)
    p.oval(cx + 1, 23, 12, 11, BONE)
    p.oval(cx + 5, 22, 3.2, 3.8, INK, ink=False)
    p.oval(cx - 2, 22, 2.6, 3.4, INK, ink=False)
    p.oval(cx + 5, 21, 1, 1, rgb("#ff5a4a"), ink=False)
    for i in range(4):
        p.line([(cx - 2 + i * 3, 30), (cx - 2 + i * 3, 32.5)], INK, 1.2)
    # bow
    bx = cx + (14 if aim else 11)
    p.d.arc(p._b(bx - 10, 28, bx + 6, 58), 270, 90, fill=rgb("#8a5a2b"), width=int(3 * SS))
    p.line([(bx - 2, 29), (bx - (8 if aim else 2), 43), (bx - 2, 57)], rgb("#d8d8d8"), 1.0)
    p.limb(cx + 3, 38, bx - 1, 43, 3.2, BONE)
    if aim:
        p.line([(bx - 8, 43), (bx + 14, 43)], rgb("#7a4a22"), 1.8)
        p.poly([(bx + 14, 40.5), (bx + 19, 43), (bx + 14, 45.5)], rgb("#b8c4cf"))
    p.done(name)


def penguin(name, step=0.0, slide=False, flat=False):
    """A round penguin. It belly-slides at you!"""
    p = Pen()
    BLK, WHT, ORG = rgb("#2b3550"), rgb("#f4f8ff"), rgb("#ff9a2a")
    if flat:
        p.oval(32, 70, 20, 6, BLK)
        p.done(name)
        return
    if slide:   # lying on its belly, flippers back
        p.oval(32, 62, 24, 11, BLK)
        p.oval(36, 66, 18, 6, WHT, ink=False)
        p.oval(52, 58, 9, 8, BLK)
        p.poly([(60, 58), (68, 60), (60, 62)], ORG)
        p.eye(55, 55, 2.0, 2.6, 0.8)
        p.poly([(10, 58), (2, 52), (12, 62)], BLK)
        p.done(name)
        return
    for s_ in (-1, 1):
        p.oval(32 + s_ * 7 + step * s_ * 2, 74, 6, 3, ORG)
    p.oval(32, 54, 16, 19, BLK)
    p.oval(34, 58, 11, 14, WHT, ink=False)
    for s_ in (-1, 1):
        p.oval(32 + s_ * 16, 54 + step * s_ * 2, 4, 10, BLK)
    p.oval(32, 36, 12, 11, BLK)
    p.oval(35, 38, 7, 6, WHT, ink=False)
    p.eye(31, 35, 2.4, 3.2, 0.8)
    p.eye(39, 35, 2.2, 3.0, 0.8)
    p.poly([(36, 41), (46, 43), (36, 45)], ORG)
    p.oval(32, 26, 9, 3.5, rgb("#e8413b"))   # a red winter hat
    p.oval(32, 21, 3.5, 3.5, WHT)
    p.done(name)


def scorpion(name, step=0.0, strike=False, flat=False):
    """A sandy scorpion with a curled tail."""
    p = Pen()
    BODY, DARK = rgb("#d88a3a"), rgb("#a8602a")
    if flat:
        p.oval(32, 70, 22, 5, BODY)
        p.done(name)
        return
    cy = 64
    for i in range(3):   # legs
        for s_ in (-1, 1):
            x = 24 + i * 7
            p.line([(x, cy), (x + s_ * 3 + step * 2, cy + 8)], DARK, 2.2)
    # tail: up over the back; straight forward when it strikes
    pts = [(14, cy - 2), (8, cy - 12), (10, cy - 24), (20, cy - 30)] if not strike else [(14, cy - 2), (10, cy - 14), (22, cy - 26), (36, cy - 28)]
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        p.line([(x0, y0), (x1, y1)], BODY, 6)
    tx, ty = pts[-1]
    p.poly([(tx - 2, ty - 4), (tx + 8, ty), (tx - 2, ty + 4)], rgb("#5a2a14"))
    p.oval(32, cy, 16, 8, BODY)
    for i in range(3):
        p.line([(22 + i * 8, cy - 7), (24 + i * 8, cy + 7)], DARK, 1.4)
    # claws
    for s_ in (-1, 1):
        cx_ = 50 + (4 if strike else 0)
        p.oval(cx_, cy + s_ * 4, 6, 3.5, BODY)
        p.poly([(cx_ + 5, cy + s_ * 4 - 3), (cx_ + 11, cy + s_ * 4), (cx_ + 5, cy + s_ * 4 + 1)], DARK)
    p.eye(42, cy - 6, 2.2, 2.8, 0.8)
    p.eye(47, cy - 6, 2.0, 2.6, 0.8)
    p.done(name)


def krag_jr(name, bob=0.0, hurt=False, angry=False):
    """Krag Jr.: a little green dragon kid riding a hover-pot with a propeller."""
    p = Pen(80, 90)
    cx, cy = 40, 50 + bob
    DR = rgb("#59c25a") if not hurt else rgb("#ffffff")
    # dragon kid
    p.oval(cx + 3, cy - 18, 13, 12, DR)
    for i in range(4):
        x = cx - 6 + i * 5
        p.poly([(x - 3, cy - 28 + i * 0.5), (x, cy - 38 + abs(i - 1.5) * 2), (x + 3, cy - 28)], rgb("#ff8c2a"))
    p.oval(cx + 13, cy - 14, 6, 4.5, rgb("#8be08a"))
    p.oval(cx + 16, cy - 15, 1, 1, INK, ink=False)
    if angry:
        p.line([(cx + 2, cy - 25), (cx + 10, cy - 21)], INK, 2.4)
    p.eye(cx + 6, cy - 19, 2.8, 3.8, 1.0)
    p.d.arc(p._b(cx + 8, cy - 14, cx + 18, cy - 7), 10, 120, fill=INK, width=int(1.8 * SS))
    p.poly([(cx + 11, cy - 11), (cx + 12.5, cy - 8), (cx + 14, cy - 11)], rgb("#ffffff"), ink=False)
    p.limb(cx - 6, cy - 10, cx - 12, cy - 4, 4.5, DR)
    # hover pot
    p.d.chord(p._b(cx - 24, cy - 22, cx + 24, cy + 20), 0, 180, fill=rgb("#8a5bc4"), outline=INK, width=int(LW * SS))
    p.rect(cx - 24, cy - 4, cx + 24, cy + 1.5, rgb("#ffcf33"), r=1.5)
    for s in (-1, 1):
        p.oval(cx + s * 12, cy + 8, 3, 3, rgb("#ffcf33"))
    p.rect(cx - 2, cy + 18, cx + 2, cy + 25, rgb("#7c8794"), r=1)
    p.oval(cx - 9, cy + 26, 9, 2.6, rgb("#c9d3dc"))
    p.oval(cx + 9, cy + 26, 9, 2.6, rgb("#c9d3dc"))
    p.done(name)


# ---------------------------------------------------------------- items & scenery
def coin():
    p = Pen(40, 40)
    p.oval(20, 20, 14, 16, GOLD)
    p.oval(20, 20, 9, 11, rgb("#ffe27a"), ink=False)
    p.star(20, 20, 6.5, rgb("#ffcf33"))
    p.done("coin", border=2)


def heart(name="heart", col="#ff4f6d"):
    p = Pen(40, 40)
    p.poly([(20, 33), (6, 19), (6, 12), (12, 7), (20, 12), (28, 7), (34, 12), (34, 19)], rgb(col))
    p.oval(12, 13, 3, 2, rgb("#ffffff", 200), ink=False)
    p.done(name, border=2)


def spark_shot():
    p = Pen(40, 40)
    p.oval(20, 20, 9, 9, SPARK)
    p.oval(20, 20, 4.5, 4.5, rgb("#ffffff"), ink=False)
    for i in range(5):
        a = i * math.tau / 5
        p.line([(20 + math.cos(a) * 10, 20 + math.sin(a) * 10), (20 + math.cos(a + .4) * 16, 20 + math.sin(a + .4) * 16)], SPARK, 2.2)
    p.done("spark", border=1.5)


def shot_water():
    p = Pen(40, 40)
    p.poly([(20, 4), (30, 22), (20, 34), (10, 22)], rgb("#5ab4ff"))
    p.oval(20, 24, 10, 10, rgb("#5ab4ff"))
    p.oval(16, 20, 3, 4, rgb("#ffffff"), ink=False)
    p.done("shot_water", border=1.5)


def shot_fire():
    p = Pen(40, 40)
    p.oval(20, 22, 12, 12, rgb("#ff7a2a"))
    p.oval(20, 22, 7, 7, rgb("#ffd23a"), ink=False)
    for a in (-0.6, 0.0, 0.6):
        p.poly([(20 + a * 14 - 4, 14), (20 + a * 18, 3), (20 + a * 14 + 4, 14)], rgb("#ff9a2a"))
    p.done("shot_fire", border=1.5)


def arrow():
    p = Pen(48, 16)
    p.line([(4, 8), (38, 8)], rgb("#7a4a22"), 2.4)
    p.poly([(36, 4), (45, 8), (36, 12)], rgb("#b8c4cf"))
    p.poly([(3, 4), (9, 8), (3, 12), (7, 8)], rgb("#ff5a4a"))
    p.done("arrow", border=1.5)


def spikeball():
    p = Pen(48, 48)
    for i in range(10):
        a = i * math.tau / 10
        p.poly([(24 + math.cos(a - .25) * 12, 24 + math.sin(a - .25) * 12), (24 + math.cos(a) * 21, 24 + math.sin(a) * 21),
                (24 + math.cos(a + .25) * 12, 24 + math.sin(a + .25) * 12)], rgb("#e8e8ee"))
    p.oval(24, 24, 13, 13, rgb("#4b4f63"))
    p.oval(20, 20, 4, 3, rgb("#7b8098"), ink=False)
    p.done("spikeball", border=1.5)


def tree(name, crown="#4fb35a", crown2="#7dd36b", h=1.0):
    p = Pen(80, 120)
    p.rect(35, 70, 45, 116, rgb("#8a5a35"), r=3)
    p.oval(40, 50 - 0 * h, 32, 30, rgb(crown))
    p.oval(24, 64, 18, 15, rgb(crown))
    p.oval(57, 62, 18, 16, rgb(crown))
    p.oval(32, 40, 13, 10, rgb(crown2), ink=False)
    p.oval(54, 50, 7, 6, rgb(crown2), ink=False)
    for (x, y) in ((28, 60), (52, 44), (44, 68)):
        p.oval(x, y, 2.4, 2.4, rgb("#ff6a7a"), ink=True)
    p.done(name)


def bush():
    p = Pen(80, 44)
    for x, r in ((22, 15), (40, 19), (58, 15)):
        p.oval(x, 28, r, r * 0.85, rgb("#53b85c"))
    p.oval(36, 20, 7, 4, rgb("#86da74"), ink=False)
    p.done("bush")


def flower(name, petal):
    p = Pen(30, 44)
    p.line([(15, 22), (15, 42)], rgb("#3f9a48"), 2.6)
    p.oval(20, 34, 4, 2.2, rgb("#5cc062"))
    for i in range(5):
        a = i * math.tau / 5
        p.oval(15 + math.cos(a) * 5.5, 16 + math.sin(a) * 5.5, 4.2, 4.2, rgb(petal))
    p.oval(15, 16, 3.4, 3.4, rgb("#ffd23a"))
    p.done(name, border=1.6)


def cloud():
    p = Pen(120, 60)
    for x, y, r in ((30, 36, 18), (55, 26, 24), (82, 32, 20), (100, 40, 13)):
        p.oval(x, y, r, r * 0.9, rgb("#ffffff"), ink=False)
    p.rect(14, 36, 108, 52, rgb("#ffffff"), r=8, ink=False)
    p.oval(60, 48, 46, 5, rgb("#dfeaf7"), ink=False)
    p.done("cloud", border=0.5)


def flag():
    p = Pen(60, 140)
    p.rect(26, 10, 31, 136, rgb("#e6e6ee"), r=2)
    p.oval(28.5, 9, 5, 5, GOLD)
    p.poly([(31, 16), (56, 26), (31, 38)], rgb("#3fc06a"))
    p.star(39, 27, 5, rgb("#ffffff"))
    p.done("flag")


def dennis():
    """Dennis the dog cameo pointing the way."""
    p = Pen(60, 50)
    for x in (16, 24, 36, 44):
        p.limb(x, 34, x, 44, 4, rgb("#c48a4a"))
    p.oval(30, 30, 18, 10, rgb("#d9a05b"))
    p.line([(13, 27), (6, 18)], rgb("#c48a4a"), 3.6)
    p.oval(46, 20, 10, 9, rgb("#d9a05b"))
    p.oval(42, 15, 4, 7, rgb("#8a5a35"))
    p.oval(55, 23, 2.6, 2.2, INK, ink=False)
    p.eye(49, 18, 1.8, 2.4)
    p.done("dennis", border=2)


# ---------------------------------------------------------------- block textures (16x16 pixel, chunky)
import random


def tex(name, base, speck=None, specks=0, top=None, ore=None, seed=1, border=None):
    rnd = random.Random(seed)
    im = Image.new("RGB", (16, 16), base)
    px = im.load()
    for y in range(16):
        for x in range(16):
            r, g, b = base
            v = rnd.randint(-10, 10)
            px[x, y] = (max(0, min(255, r + v)), max(0, min(255, g + v)), max(0, min(255, b + v)))
    if speck:
        for _ in range(specks):
            x, y = rnd.randrange(16), rnd.randrange(16)
            px[x, y] = speck
    if ore:
        for cx, cy in ((4, 4), (11, 6), (6, 11), (12, 12)):
            for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1), (-1, 0)):
                x, y = (cx + dx) % 16, (cy + dy) % 16
                px[x, y] = ore if (dx, dy) != (1, 1) else tuple(max(0, c - 50) for c in ore)
            px[cx, cy] = tuple(min(255, c + 60) for c in ore)
    if top:
        for x in range(16):
            depth = 3 + (1 if rnd.random() < 0.4 else 0) + (1 if x % 5 == 0 else 0)
            for y in range(depth):
                c = top if y < depth - 1 else tuple(max(0, v - 30) for v in top)
                px[x, y] = c
    if border:
        for i in range(16):
            for (x, y) in ((i, 0), (i, 15), (0, i), (15, i)):
                px[x, y] = border
    im.resize((64, 64), Image.NEAREST).save(os.path.join(OUT, "t_" + name + ".png"))


def textures():
    GRASS = (82, 168, 66)
    DIRT = (150, 102, 64)
    STONE = (138, 142, 150)
    tex("grass_top", GRASS, speck=(130, 215, 100), specks=30, seed=2)
    tex("grass_side", DIRT, speck=(120, 80, 50), specks=20, top=GRASS, seed=3)
    tex("dirt", DIRT, speck=(120, 80, 50), specks=24, seed=4)
    tex("stone", STONE, speck=(112, 116, 124), specks=40, seed=5)
    tex("iron", STONE, speck=(112, 116, 124), specks=30, ore=(222, 186, 160), seed=6)
    tex("gold", STONE, speck=(112, 116, 124), specks=30, ore=(255, 210, 60), seed=7)
    tex("diamond", STONE, speck=(112, 116, 124), specks=30, ore=(90, 230, 230), seed=8)
    tex("bedrock", (60, 60, 70), speck=(30, 30, 38), specks=60, seed=9)
    # brick
    im = Image.new("RGB", (16, 16), (196, 92, 58))
    px = im.load()
    for y in range(16):
        for x in range(16):
            if y % 4 == 3 or (x + (4 if (y // 4) % 2 else 0)) % 8 == 7:
                px[x, y] = (232, 205, 178)
    im.resize((64, 64), Image.NEAREST).save(os.path.join(OUT, "t_brick.png"))
    # mystery block: teal crystal box with a white ?, and the used (empty) version
    for name, base, mark in (("mystery", (44, 190, 200), (255, 255, 255)), ("used", (150, 120, 96), None)):
        im = Image.new("RGB", (16, 16), base)
        px = im.load()
        for i in range(16):
            for (x, y) in ((i, 0), (i, 15), (0, i), (15, i)):
                px[x, y] = tuple(max(0, c - 60) for c in base)
        for (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)):
            px[x, y] = tuple(min(255, c + 70) for c in base)
        if mark:
            q = ["..####..", ".##..##.", ".....##.", "....##..", "...##...", "...##...", "........", "...##..."]
            for yy, row in enumerate(q):
                for xx, ch in enumerate(row):
                    if ch == "#":
                        px[4 + xx, 4 + yy] = mark
        im.resize((64, 64), Image.NEAREST).save(os.path.join(OUT, "t_" + name + ".png"))


def hill(name, col, col2, w=200, h=110):
    p = Pen(w, h)
    p.d.chord(p._b(4, 8, w - 4, h * 2 - 10), 180, 360, fill=rgb(col), outline=INK, width=int(LW * SS))
    for i in range(3):
        x = w * (0.25 + i * 0.25)
        p.oval(x, h * 0.55 + (i % 2) * 10, 6, 4, rgb(col2), ink=False)
    p.oval(w * 0.35, h * 0.25, w * 0.12, 6, rgb(col2), ink=False)
    p.done(name, border=1)


def sign():
    p = Pen(70, 70)
    p.rect(32, 30, 38, 68, rgb("#8a5a35"), r=1.5)
    p.rect(4, 6, 66, 38, rgb("#c98d55"), r=4)
    p.line([(10, 16), (60, 16)], rgb("#a8743f"), 1.2)
    p.line([(10, 28), (60, 28)], rgb("#a8743f"), 1.2)
    p.done("sign", border=1.6)


ATLAS = ["grass_top", "grass_side", "dirt", "stone", "iron", "gold", "diamond", "bedrock", "brick", "mystery", "used",
         "planks", "wool_red", "wool_blue", "wool_yellow", "wool_white", "window", "iron_block", "gold_block", "diamond_block", "stone_brick",
         "snow_top", "snow_side", "ice", "sand", "sandstone",
         "log_top", "log_side", "leaves", "wool_green", "wool_black", "road"]


def world_textures():
    """Ground for the other worlds: snow, ice, sand and sandstone."""
    SNOW = (238, 244, 252)
    DIRT = (150, 102, 64)
    tex("snow_top", SNOW, speck=(214, 226, 242), specks=26, seed=31)
    tex("snow_side", DIRT, speck=(120, 80, 50), specks=20, top=SNOW, seed=32)
    # ice: light blue with white streaks
    rnd = random.Random(33)
    im = Image.new("RGB", (16, 16), (150, 210, 240))
    px = im.load()
    for y in range(16):
        for x in range(16):
            v = rnd.randint(-6, 6)
            px[x, y] = (150 + v, 210 + v, 240)
    for k in range(3):
        for i in range(5):
            x, y = 2 + k * 5 + i, 3 + k * 4 + i // 2
            if x < 16 and y < 16:
                px[x, y] = (235, 248, 255)
    for i in range(16):
        px[i, 0] = (200, 236, 255)
        px[0, i] = (200, 236, 255)
    im.resize((64, 64), Image.NEAREST).save(os.path.join(OUT, "t_ice.png"))
    tex("sand", (236, 206, 140), speck=(214, 180, 112), specks=40, seed=34)
    # sandstone: layered bands
    im = Image.new("RGB", (16, 16))
    px = im.load()
    for y in range(16):
        for x in range(16):
            band = [(222, 186, 120), (210, 170, 106), (228, 196, 132), (204, 162, 98)][(y // 4) % 4]
            v = rnd.randint(-5, 5)
            px[x, y] = tuple(max(0, min(255, c + v)) for c in band)
            if y % 4 == 3:
                px[x, y] = (180, 140, 82)
    im.resize((64, 64), Image.NEAREST).save(os.path.join(OUT, "t_sandstone.png"))


def city_textures():
    """More building blocks for the big home: logs, leaves, green/black wool and road."""
    rnd = random.Random(41)
    # log side: brown bark stripes
    im = Image.new("RGB", (16, 16))
    px = im.load()
    for y in range(16):
        for x in range(16):
            base = (112, 80, 46) if (x * 7 // 16 + (y // 5)) % 3 else (94, 66, 38)
            v = rnd.randint(-8, 8)
            px[x, y] = tuple(max(0, min(255, c + v)) for c in base)
        for x in (2, 7, 12):
            px[x, y] = (78, 54, 30)
    im.resize((64, 64), Image.NEAREST).save(os.path.join(OUT, "t_log_side.png"))
    # log top: tree rings
    im = Image.new("RGB", (16, 16))
    px = im.load()
    for y in range(16):
        for x in range(16):
            d = max(abs(x - 7.5), abs(y - 7.5))
            if d > 6.5:
                c = (100, 72, 42)
            else:
                c = (204, 164, 104) if int(d) % 2 == 0 else (178, 136, 82)
            v = rnd.randint(-5, 5)
            px[x, y] = tuple(max(0, min(255, k + v)) for k in c)
    im.resize((64, 64), Image.NEAREST).save(os.path.join(OUT, "t_log_top.png"))
    # leaves: bumpy greens
    im = Image.new("RGB", (16, 16))
    px = im.load()
    for y in range(16):
        for x in range(16):
            r = rnd.random()
            c = (52, 140, 56) if r < 0.45 else ((74, 170, 70) if r < 0.8 else (36, 108, 44))
            px[x, y] = c
    im.resize((64, 64), Image.NEAREST).save(os.path.join(OUT, "t_leaves.png"))
    for name, col in (("wool_green", (76, 176, 72)), ("wool_black", (52, 52, 62))):
        im = Image.new("RGB", (16, 16), col)
        px = im.load()
        for y in range(16):
            for x in range(16):
                v = rnd.randint(-10, 10) + (8 if (x * 3 + y * 5) % 7 == 0 else 0)
                px[x, y] = tuple(max(0, min(255, c + v)) for c in col)
        im.resize((64, 64), Image.NEAREST).save(os.path.join(OUT, "t_" + name + ".png"))
    # road: dark grey with a yellow dash down the middle
    im = Image.new("RGB", (16, 16))
    px = im.load()
    for y in range(16):
        for x in range(16):
            v = rnd.randint(-7, 7)
            px[x, y] = (72 + v, 74 + v, 80 + v)
    for y in range(2, 14):
        px[7, y] = (250, 206, 60)
        px[8, y] = (250, 206, 60)
    im.resize((64, 64), Image.NEAREST).save(os.path.join(OUT, "t_road.png"))


def build_textures():
    """Blocks for building at home."""
    rnd = random.Random(11)
    # planks: warm wood boards
    im = Image.new("RGB", (16, 16))
    px = im.load()
    for y in range(16):
        for x in range(16):
            board = y // 4
            base = [(196, 146, 88), (182, 132, 76), (204, 154, 96), (188, 138, 82)][board]
            v = rnd.randint(-8, 8) + (6 if (x + board * 5) % 9 == 0 else 0)
            c = tuple(max(0, min(255, b + v)) for b in base)
            if y % 4 == 3 or (x + board * 7) % 16 == 0:
                c = (120, 82, 44)
            px[x, y] = c
    im.resize((64, 64), Image.NEAREST).save(os.path.join(OUT, "t_planks.png"))
    # wool: soft colored squares with a stitched look
    for name, col in (("wool_red", (222, 66, 60)), ("wool_blue", (62, 110, 220)), ("wool_yellow", (250, 206, 60)), ("wool_white", (236, 236, 240))):
        im = Image.new("RGB", (16, 16), col)
        px = im.load()
        for y in range(16):
            for x in range(16):
                v = rnd.randint(-10, 10) + (8 if (x * 3 + y * 5) % 7 == 0 else 0)
                px[x, y] = tuple(max(0, min(255, c + v)) for c in col)
        im.resize((64, 64), Image.NEAREST).save(os.path.join(OUT, "t_" + name + ".png"))
    # window: wooden frame, light blue glass with a shine
    im = Image.new("RGB", (16, 16), (150, 210, 245))
    px = im.load()
    for i in range(16):
        for (x, y) in ((i, 0), (i, 15), (0, i), (15, i), (i, 7), (i, 8), (7, i), (8, i)):
            px[x, y] = (150, 100, 56)
    for k in range(3):
        px[3 + k, 3 + k - 1 if k else 3] = (235, 248, 255)
        px[10 + k, 10 + k - 1 if k else 10] = (235, 248, 255)
    im.resize((64, 64), Image.NEAREST).save(os.path.join(OUT, "t_window.png"))
    # shiny ore blocks
    for name, col in (("iron_block", (220, 220, 226)), ("gold_block", (255, 206, 58)), ("diamond_block", (96, 232, 230))):
        im = Image.new("RGB", (16, 16), col)
        px = im.load()
        for y in range(16):
            for x in range(16):
                v = 18 if (x < 2 or y < 2) else (-26 if (x > 13 or y > 13) else rnd.randint(-6, 6))
                if (x + y) % 6 == 0 and 2 < x < 13:
                    v += 12
                px[x, y] = tuple(max(0, min(255, c + v)) for c in col)
        im.resize((64, 64), Image.NEAREST).save(os.path.join(OUT, "t_" + name + ".png"))
    # stone bricks
    im = Image.new("RGB", (16, 16), (150, 152, 158))
    px = im.load()
    for y in range(16):
        for x in range(16):
            v = rnd.randint(-8, 8)
            c = (150 + v, 152 + v, 158 + v)
            if y % 8 == 7 or (x + (4 if (y // 8) % 2 else 0)) % 8 == 7:
                c = (104, 106, 112)
            px[x, y] = c
    im.resize((64, 64), Image.NEAREST).save(os.path.join(OUT, "t_stone_brick.png"))


def atlas():
    """All block textures side by side, 64px each, plus 2px padding copied from each edge."""
    tile, pad = 64, 4
    im = Image.new("RGB", ((tile + pad * 2) * len(ATLAS), tile + pad * 2))
    for i, n in enumerate(ATLAS):
        t = Image.open(os.path.join(OUT, "t_" + n + ".png")).convert("RGB")
        big = t.resize((tile + pad * 2, tile + pad * 2), Image.NEAREST)
        im.paste(big, (i * (tile + pad * 2), 0))
        im.paste(t, (i * (tile + pad * 2) + pad, pad))
    im.save(os.path.join(OUT, "atlas.png"))


# ---------------------------------------------------------------- build everything
def main():
    # Rocco
    rocco("rocco_idle", 0.12, -0.12, 0.25, -0.25)
    rocco("rocco_blink", 0.12, -0.12, 0.25, -0.25, blink=True)
    rocco("rocco_run1", 0.75, -0.6, -0.9, 0.9, mouth="open")
    rocco("rocco_run2", -0.6, 0.75, 0.9, -0.9, mouth="open")
    rocco("rocco_jump", 0.5, -0.9, 2.6, -1.2, mouth="open")
    rocco("rocco_fall", -0.3, 0.3, 2.2, 2.0)
    rocco("rocco_slam", 1.4, 1.2, 1.9, 1.7, squash=0.18, mouth="ow")
    rocco("rocco_hurt", 0.4, -0.4, 2.8, -2.6, mouth="ow")
    rocco("rocco_win", 0.15, -0.15, 3.0, -0.3, mouth="open")
    # Blocky
    blocky("blocky_idle", 0.1, -0.1, 0.0, 1.25)
    blocky("blocky_run1", 0.7, -0.55, 0.4, 1.4)
    blocky("blocky_run2", -0.55, 0.7, -0.4, 1.0)
    blocky("blocky_jump", 0.5, -0.9, 0.2, 1.8)
    blocky("blocky_mine1", 0.2, -0.2, -0.6, -0.9)
    blocky("blocky_mine2", 0.3, -0.3, 0.8, 2.0)
    blocky("blocky_hurt", 0.4, -0.4, 0.4, 2.2)
    # Sparky
    sparky("sparky_idle", 0, 0.3)
    sparky("sparky_run1", 3, 0.7)
    sparky("sparky_run2", 0, -0.7)
    sparky("sparky_jump", 8, 0.4)
    sparky("sparky_zap", 2, 0.3, zap=True)
    partners()
    # foes
    grumbler("grumbler_1", 1)
    grumbler("grumbler_2", -1)
    grumbler("grumbler_flat", squished=True)
    skelly("skelly_1", 0.4)
    skelly("skelly_2", -0.4)
    skelly("skelly_aim", 0.1, aim=True)
    penguin("penguin_1", 1); penguin("penguin_2", -1); penguin("penguin_slide", slide=True); penguin("penguin_flat", flat=True)
    scorpion("scorpion_1", 1); scorpion("scorpion_2", -1); scorpion("scorpion_strike", strike=True); scorpion("scorpion_flat", flat=True)
    krag_jr("kragjr_1", 0)
    krag_jr("kragjr_2", 2)
    krag_jr("kragjr_angry", 0, angry=True)
    krag_jr("kragjr_hurt", 0, hurt=True)
    # bits
    shot_water(); shot_fire(); coin(); heart(); heart("heart_empty", "#5b4b63"); spark_shot(); arrow(); spikeball()
    tree("tree_a"); tree("tree_b", "#3b9d52", "#62c76a"); tree("tree_c", "#7fbf3f", "#a7dc5c")
    bush(); flower("flower_pink", "#ff8fc0"); flower("flower_blue", "#7fb8ff"); flower("flower_white", "#ffffff")
    cloud(); flag(); dennis()
    hill("hill_snow", "#eef4fc", "#ffffff", 240, 140); hill("hill_sand", "#e8c47e", "#f2d69a", 260, 120); hill("hill_a", "#7cc96a", "#9bde84"); hill("hill_b", "#5fb46a", "#82cc7d", 260, 150); hill("hill_c", "#a4d36f", "#c3e690", 160, 80)
    sign()
    textures()
    build_textures()
    world_textures()
    city_textures()
    atlas()
    print("art written to", os.path.abspath(OUT))


if __name__ == "__main__":
    main()
