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
    p.rect(cx - 10, 51 - squash * 4, cx + 10, 53.5 - squash * 4, rgb("#6a4a2e"), r=0.5, ink=False)   # belt
    p.rect(cx - 10, 53 - squash * 4, cx + 10, 58 - squash * 4, PANTS, r=1.5)
    # head: blocky rounded square
    hy = 24 + squash * 5
    p.rect(cx - 11, hy - 11, cx + 13, hy + 12, BSKIN, r=5)
    p.rect(cx - 11, hy - 4, cx - 4, hy + 6, rgb("#3b2a20"), r=2)         # sideburn/hair
    p.rect(cx + 2, hy + 6.5, cx + 13, hy + 12, rgb("#5a3e2c"), r=3)      # little beard
    p.eye(cx + 6, hy, 2.3, 3.2)
    p.line([(cx + 3, hy - 4.6), (cx + 9, hy - 5.2)], rgb("#3b2a20"), 1.8)  # eyebrow
    p.d.arc(p._b(cx + 5, hy + 2.5, cx + 11, hy + 8), 30, 150, fill=INK, width=int(1.5 * SS))
    # blocky brown hair on top (no hat)
    p.rect(cx - 12, hy - 13, cx + 14, hy - 5, rgb("#3b2a20"), r=3)
    p.rect(cx - 12, hy - 8, cx - 3, hy + 2, rgb("#3b2a20"), r=2)
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


# ---------------------------------------------------------------- the newer partner lines
BEAST = {   # body colour per stage, belly, accent (same as toys.gd BEAST)
    "rock": (("#b59a74", "#9a7b5a", "#7d6248"), "#e8d8b8", "#6b5a48"),
    "ghost": (("#e8e4ff", "#c8b8f5", "#a890f0"), "#ffffff", "#5a3f9e"),
    "bird": (("#8cc8f0", "#5ab0e8", "#3a7fc8"), "#fff4d6", "#ffb02e"),
    "mind": (("#f7b0d8", "#e88ac4", "#c45fa8"), "#fff0f8", "#7a3fd0"),
    "ice": (("#d6f2fc", "#a8e2f4", "#7fc8e8"), "#ffffff", "#4aa8d8"),
    "bug": (("#a6d65c", "#7cb342", "#4f8a2a"), "#e6f5b0", "#3a3a48"),
    "dragon": (("#8a9cf0", "#5a6fd6", "#3a48b0"), "#ffe0a0", "#ffcf33"),
    "fairy": (("#ffd0e8", "#ffaad6", "#ff8fc8"), "#ffffff", "#ffd23a"),
}
NEW_LINES = ["rock", "ghost", "bird", "mind", "ice", "bug", "dragon", "fairy"]


def _darker(c, k=40):
    return tuple(max(0, v - k) for v in c[:3]) + (255,)


def beast(name, line, stage, pose="idle"):
    """One of the newer partners seen from the side, facing right (like critter())."""
    p = Pen(84, 80)
    cols, belly, acc = BEAST[line]
    BODY, BELLY, ACC = rgb(cols[stage]), rgb(belly), rgb(acc)
    DARK = _darker(BODY)
    hop = {"jump": 6, "run1": 2, "run2": 0, "act": 1}.get(pose, 0)
    if line == "ghost":
        hop += 6 + (2 if pose == "run1" else 0)
    bx, by = 38, 55 - hop
    hx, hy = 58, 42 - hop
    if pose == "act":
        hx += 3
    sw = {"run1": 4, "run2": -4, "jump": 5}.get(pose, 0)
    g = stage   # grows with each stage
    # --- behind the body: tails and far wings
    if line == "rock":
        p.poly([(bx - 15, by + 2), (bx - 26, by + 8), (bx - 14, by + 7)], ACC)
    elif line == "bird":
        for k in range(3):
            p.poly([(bx - 14, by - 2 + k * 2), (bx - 28 - g * 2, by - 8 + k * 5), (bx - 24, by + 2 + k * 3)], [BODY, ACC, BODY][k])
    elif line == "mind":
        for k in range(stage + 1):
            a = -0.9 + k * 0.45
            tx, ty = bx - 22 + k * 2, by - 14 + k * 5
            p.oval(tx, ty, 9, 6, BODY)
            p.oval(tx - 6, ty - 3, 4, 3.4, BELLY, ink=False)
    elif line == "ice":
        p.poly([(bx - 18, by + 3), (bx - 30, by - 3), (bx - 29, by + 9)], BODY)
    elif line == "dragon":
        p.poly([(bx - 14, by - 2), (bx - 30, by - 14 - g * 2), (bx - 26, by - 9), (bx - 13, by + 5)], BODY)
        p.poly([(bx - 30, by - 14 - g * 2), (bx - 36, by - 18 - g * 2), (bx - 31, by - 9 - g * 2)], ACC)
    elif line == "fairy":
        p.oval(bx - 16, by - 2, 5, 5, rgb("#ffffff"))
    elif line == "ghost" and stage >= 1:
        p.poly([(bx - 10, by + 8), (bx - 24, by + 14), (bx - 30, by + 6), (bx - 22, by + 10), (bx - 12, by)], BODY)
        if stage == 2:
            flame(p, bx - 28, by + 4, 8)
    elif line == "bug":
        pass
    # far wing
    wing = line == "bird" or (line == "dragon" and stage >= 1) or (line in ("fairy", "bug") and stage == 2)
    flap = {"jump": -8, "run1": -3, "run2": 3}.get(pose, 0)
    WING = {"fairy": rgb("#ffffff"), "bug": rgb("#cfe8ff"), "dragon": ACC if stage == 2 else rgb("#b8c4ff")}.get(line, DARK)
    if wing:
        ws = 14 + g * 3
        p.poly([(bx - 2, by - 8), (bx - 14, by - 18 - ws * 0.6 + flap), (bx + 8, by - 10)], _darker(WING, 25))
    # --- legs: far pair first (darker), near pair last
    if line == "bird":
        for (lx, near) in ((bx - 2, False), (bx + 4, True)):
            dx = sw * (1 if near else -1) * 0.5
            col = ACC if near else _darker(ACC)
            p.line([(lx, by + 6), (lx + dx, by + 16)], col, 2.2)
            p.line([(lx + dx - 2, by + 16), (lx + dx + 5, by + 16)], col, 2.2)
    elif line == "ice":
        for (lx, near) in ((bx + 8, False), (bx + 12, True), (bx - 10, True)):
            col = BODY if near else DARK
            p.oval(lx + (sw * 0.4 if near else 0), by + 9, 6, 3, col)
    elif line != "ghost":
        LEG = ACC if line == "bug" else BODY
        for (lx, near) in ((bx - 9, False), (bx + 9, False), (bx - 6, True), (bx + 12, True)):
            dx = (sw if (lx < bx) == near else -sw)
            col = LEG if near else _darker(LEG)
            p.rect(lx - 4 + dx * 0.5, by + 4, lx + 4 + dx, by + 15 - (3 if pose == "jump" and near else 0), col, r=3.5)
    # --- body
    if line == "ghost":
        pts = [(bx - 15, by - 10), (bx - 15, by + 8)]
        for k in range(5):
            x = bx - 15 + k * 7.5
            pts.append((x + 3.7, by + 14 - (k % 2) * 2))
            pts.append((x + 7.5, by + 8))
        pts += [(bx + 15, by - 10)]
        p.poly(pts, BODY)
        p.oval(bx, by - 10, 15, 12, BODY)
        p.rect(bx - 13, by - 10, bx + 13, by + 7, BODY, r=0, ink=False)
        p.oval(bx + 13, by - 2, 4, 5, BODY)   # little arm
        if stage == 2:
            for k in (-7, 5):
                p.poly([(bx + k - 4, by - 18), (bx + k, by - 30), (bx + k + 4, by - 18)], ACC)
    elif line == "ice":
        p.oval(bx, by + 1, 21 + g, 10 + g * 0.6, BODY)
        p.oval(bx + 4, by + 5, 14, 4.5, BELLY, ink=False)
        if stage == 2:
            for k in range(4):
                p.poly([(bx - 9 + k * 6, by - 8), (bx - 6 + k * 6, by - 19), (bx - 3 + k * 6, by - 8)], rgb("#bff4ff"))
    elif line == "bug":
        p.oval(bx, by, 17 + g, 11 + g * 0.6, ACC)
        p.d.chord(p._b(bx - 18 - g, by - 14 - g, bx + 17 + g, by + 8), 180, 360, fill=BODY, outline=INK, width=int(LW * SS))
        p.line([(bx - 1, by - 13 - g), (bx - 1, by - 3)], _darker(BODY, 60), 1.4)
        for (sx, sy) in ((bx - 9, by - 7), (bx + 7, by - 8)):
            p.oval(sx, sy, 2.6, 2, BELLY, ink=False)
    elif line == "bird":
        p.oval(bx + 2, by - 2, 14 + g, 12 + g * 0.6, BODY)
        p.oval(bx + 6, by + 2, 9, 7, BELLY, ink=False)
    else:
        p.oval(bx, by, 17 + g, 12 + g * 0.6, BODY)
        p.oval(bx + 3, by + 5, 11, 5.5, BELLY, ink=False)
    # back features
    if line == "rock":
        for k in range(3):
            p.d.chord(p._b(bx - 17 + k * 10, by - 14 - g, bx - 3 + k * 10, by + 6), 180, 360, fill=ACC, outline=INK, width=int(LW * SS))
        if stage >= 1:
            for k in range(1 + stage * 2):
                x = bx - 12 + k * (22 / (stage * 2))
                p.poly([(x - 2.5, by - 12 - g), (x, by - 19 - g * 2), (x + 2.5, by - 12 - g)], BELLY)
    elif line == "dragon":
        for k in range(2 + stage):
            x = bx - 10 + k * (20 / (1 + stage))
            p.poly([(x - 3, by - 10), (x, by - 16 - g), (x + 3, by - 10)], ACC)
    elif line == "mind" and stage >= 1:
        for k in range(4):
            p.oval(bx + 12 + k * 2.5, by - 6 + k * 3, 3.6, 3.6, BELLY)
    elif line == "fairy" and stage >= 1:
        p.oval(bx + 9, by - 9, 4, 3, rgb("#ff4f8a"))
        p.oval(bx + 15, by - 9, 4, 3, rgb("#ff4f8a"))
        p.oval(bx + 12, by - 9, 2, 2, rgb("#ff4f8a"))
    # near wing
    if wing:
        ws = 14 + g * 3
        p.poly([(bx + 2, by - 6), (bx - 10, by - 16 - ws * 0.6 + flap), (bx - 18, by - 10 - ws * 0.3 + flap * 0.5), (bx + 10, by - 4)], WING)
    # --- head
    if line == "ghost":
        hx, hy = bx + 4, by - 12
    elif line == "ice":
        hx, hy = bx + 21, by - 6
    elif line == "bird":
        hx, hy = bx + 16, by - 15
    if line == "fairy":   # ears behind the head
        el = 16 + g * 3
        p.oval(hx - 6, hy - 8 - el * 0.5, 4, el * 0.55, BODY)
        p.oval(hx + 1, hy - 9 - el * 0.5, 4.2, el * 0.58, BODY)
        p.oval(hx + 1, hy - 9 - el * 0.5, 2, el * 0.4, rgb("#ff8fc8"), ink=False)
    if line == "mind":
        es = 8 + g * 1.5
        p.poly([(hx - 8, hy - 6), (hx - 6, hy - 9 - es * 1.4), (hx + 1, hy - 8)], BODY)
        p.poly([(hx - 1, hy - 7), (hx + 3, hy - 10 - es * 1.4), (hx + 8, hy - 7)], BODY)
        p.poly([(hx + 1, hy - 8), (hx + 3, hy - 6 - es), (hx + 6, hy - 8)], BELLY, ink=False)
    if line == "dragon":
        for k in (-4, 2):
            p.poly([(hx + k - 2, hy - 8), (hx + k - 6, hy - 16 - g * 2), (hx + k + 2, hy - 8)], rgb("#fff2d0"))
    if line != "ghost":
        r = 9.5 if line == "bird" else 11 + stage * 0.5
        p.oval(hx, hy, r, r - 1, BODY)
        if line in ("rock", "mind", "dragon", "fairy", "ice"):
            p.oval(hx + 6, hy + 4, 5.5, 4, BELLY, ink=False)
    # head features
    if line == "rock":
        p.oval(hx + 11, hy + 2, 3.5, 3, ACC)
        if stage >= 1:
            p.d.chord(p._b(hx - 11, hy - 12, hx + 7, hy + 2), 180, 360, fill=ACC, outline=INK, width=int(LW * SS))
        if stage == 2:
            p.poly([(hx + 9, hy - 1), (hx + 19, hy - 9), (hx + 13, hy + 2)], BELLY)
    elif line == "ghost":
        if stage >= 1:
            for k in range(3):
                p.poly([(hx - 9 + k * 6, hy - 8), (hx - 7 + k * 6, hy - 17 - (k == 1) * 3), (hx - 4 + k * 6, hy - 8)], BODY)
    elif line == "bird":
        p.poly([(hx + 7, hy - 2), (hx + 16, hy + 1), (hx + 7, hy + 4)], ACC)
        for k in range(1 + stage):
            p.poly([(hx - 3 - k * 3, hy - 7), (hx - 9 - k * 4, hy - 16 - k * 2), (hx - k * 3, hy - 8)], [ACC, rgb("#e8413b"), rgb("#ffd23a")][k % 3])
    elif line == "mind":
        p.oval(hx + 4, hy - 6, 2.2, 2.6, ACC)
    elif line == "ice":
        for k in range(2):
            p.line([(hx + 9, hy + 3 + k * 2), (hx + 17, hy + 1 + k * 4)], rgb("#9aa6b2"), 1.0)
        if stage >= 1:
            p.poly([(hx + 5, hy + 6), (hx + 7, hy + 14), (hx + 9, hy + 6)], rgb("#ffffff"))
        if stage == 2:
            for k in range(3):
                p.poly([(hx - 7 + k * 5, hy - 8), (hx - 5 + k * 5, hy - 17), (hx - 2 + k * 5, hy - 8)], rgb("#bff4ff"))
    elif line == "bug":
        p.line([(hx + 2, hy - 8), (hx + 8, hy - 18)], ACC, 1.4)
        p.oval(hx + 8, hy - 18, 1.8, 1.8, ACC)
        if stage >= 1:
            hl = 8 + stage * 5
            p.poly([(hx + 4, hy - 6), (hx + 12 + hl * 0.4, hy - 8 - hl), (hx + 10, hy - 2)], ACC)
    elif line == "dragon":
        p.oval(hx + 9, hy + 3, 5, 4, BODY)
    elif line == "fairy" and stage == 2:
        p.star(hx + 2, hy - 7, 3.4, ACC)
    # face
    ex, ey = (hx + 6, hy - 3) if line == "ghost" else (hx + 5, hy - 2)
    p.eye(ex, ey, 3.0, 3.8, 0.9)
    if line == "ghost":
        p.eye(ex - 8, ey, 2.6, 3.4, 0.9)
    if pose == "act":
        p.oval(hx + 9, hy + 5, 3, 2.6, rgb("#7a1f2b"))
        fx_ = hx + 17
        if line == "rock":
            p.oval(fx_ + 4, hy + 4, 5, 4.5, rgb("#8a7a6a"))
        elif line == "ghost":
            p.oval(fx_ + 4, hy + 3, 5, 5, rgb("#5a3f9e"))
            p.oval(fx_ + 3, hy + 2, 2, 2, rgb("#b98cff"), ink=False)
        elif line == "bird":
            for j in range(3):
                p.d.arc(p._b(fx_ - 2 + j * 4, hy - 4, fx_ + 6 + j * 4, hy + 10), 300, 60, fill=rgb("#ffffff"), width=int(1.8 * SS))
        elif line == "mind":
            p.oval(fx_ + 4, hy + 2, 5, 5, rgb("#e05fb0"))
            p.oval(fx_ + 3, hy + 1, 2, 2, rgb("#ffffff"), ink=False)
        elif line == "ice":
            p.line([(hx + 10, hy + 5), (fx_ + 12, hy + 5)], rgb("#7fd8f0"), 3.2)
            p.line([(hx + 10, hy + 5), (fx_ + 12, hy + 5)], rgb("#ffffff"), 1.2)
        elif line == "bug":
            p.line([(hx + 10, hy + 5), (fx_ + 4, hy + 1), (fx_ + 12, hy + 6)], rgb("#f4f4f4"), 1.6)
        elif line == "dragon":
            flame(p, fx_, hy + 8, 9, 4)
        elif line == "fairy":
            p.star(fx_ + 3, hy + 2, 4.5, rgb("#ff8fc8"))
    else:
        p.d.arc(p._b(hx + 5, hy + 2, hx + 11, hy + 7), 20, 160, fill=INK, width=int(1.5 * SS))
    p.done(name)


def new_partners():
    for line in NEW_LINES:
        for st in range(3):
            for pose in ("idle", "run1", "run2", "jump", "act"):
                beast("mon_%s%d_%s" % (line, st, pose), line, st, pose)


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



def new_shots():
    """Shots of the newer partners: rock, shadow ball, psy orb, ice shard, sparkle."""
    p = Pen(40, 40)
    p.oval(20, 22, 13, 11, rgb("#8a7a6a"))
    p.oval(15, 18, 3, 2.4, rgb("#b0a090"), ink=False)
    p.oval(25, 26, 2.4, 2, rgb("#6b5a48"), ink=False)
    p.done("shot_rock", border=1.5)
    p = Pen(40, 40)
    p.oval(20, 21, 12, 12, rgb("#5a3f9e"))
    p.oval(20, 21, 7, 7, rgb("#2b1f50"), ink=False)
    p.oval(16, 17, 2.5, 2.5, rgb("#b98cff"), ink=False)
    p.done("shot_shadow", border=1.5)
    p = Pen(40, 40)
    p.oval(20, 21, 12, 12, rgb("#e05fb0"))
    p.oval(20, 21, 7, 7, rgb("#ffc0e8"), ink=False)
    p.oval(17, 18, 2.5, 2.5, rgb("#ffffff"), ink=False)
    p.done("shot_orb", border=1.5)
    p = Pen(40, 40)
    p.poly([(20, 3), (31, 20), (20, 37), (9, 20)], rgb("#9ae6fa"))
    p.poly([(20, 8), (25, 20), (20, 30), (15, 20)], rgb("#e8faff"), ink=False)
    p.done("shot_ice", border=1.5)
    p = Pen(40, 40)
    p.star(20, 21, 15, rgb("#ff8fc8"))
    p.oval(20, 21, 4, 4, rgb("#ffffff"), ink=False)
    p.done("shot_sparkle", border=1.5)


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


def star():
    """The star you collect in the open worlds: a puffy yellow star with eyes."""
    p = Pen(64, 64)
    pts = []
    for i in range(10):
        a = -math.pi / 2 + math.tau * i / 10
        r = 29 if i % 2 == 0 else 13
        pts.append((32 + math.cos(a) * r, 34 + math.sin(a) * r))
    p.poly(pts, rgb("#ffd23a"))
    p.oval(32, 30, 9, 7, rgb("#fff3a0"), ink=False)
    p.eye(27, 34, 2.4, 4.2)
    p.eye(37, 34, 2.4, 4.2)
    p.done("star", border=2)


def power_items():
    """Power-ups (pw_<kind>.png): flowers, mushrooms, the rainbow star, caps and the propeller."""
    W_ = rgb("#ffffff")
    for kind, petal, mid in (("fire", "#ff5a3a", "#ffd23a"), ("ice", "#6ec8ff", "#e9f8ff")):
        p = Pen(64, 64)
        p.limb(32, 40, 32, 60, 4, rgb("#3fae4a"))
        p.oval(24, 52, 8, 3.5, rgb("#4cc85a"))
        p.oval(40, 52, 8, 3.5, rgb("#4cc85a"))
        for i in range(6):
            a = math.tau * i / 6
            p.oval(32 + math.cos(a) * 10, 26 + math.sin(a) * 10, 8, 8, rgb(petal))
        p.oval(32, 26, 8.5, 8.5, rgb(mid))
        p.oval(29, 26, 1.4, 2.6, rgb("#1e2a44"), ink=False)
        p.oval(35, 26, 1.4, 2.6, rgb("#1e2a44"), ink=False)
        p.done("pw_" + kind, border=2)
    for kind, cap, k in (("mushroom", "#e8413b", 1.0), ("mini", "#3f7fd9", 0.75), ("life", "#3fc06a", 1.0)):
        p = Pen(64, 64)
        cy = 62 - 24 * k
        p.rect(32 - 10 * k, cy, 32 + 10 * k, 60, rgb("#fff3dc"), r=4 * k)
        p.d.chord(p._b(32 - 24 * k, cy - 22 * k, 32 + 24 * k, cy + 18 * k), 180, 360, fill=rgb(cap), outline=INK, width=int(LW * SS))
        for dx, dy, r in ((0, -14, 5), (-13, -6, 4), (13, -6, 4)):
            p.oval(32 + dx * k, cy + dy * k, r * k, r * 0.8 * k, W_, ink=False)
        p.rect(29 - 1 * k, cy + 4 * k, 30 + 0.5 * k, cy + 10 * k, rgb("#1e2a44"), r=1, ink=False)
        p.rect(34 - 0.5 * k, cy + 4 * k, 35 + 1 * k, cy + 10 * k, rgb("#1e2a44"), r=1, ink=False)
        p.done("pw_" + kind, border=2)
    # SUPER STAR: rainbow-edged star
    p = Pen(64, 64)
    for r, col in ((30, "#ff5a8a"), (26, "#ffb020"), (22, "#ffe14a")):
        p.star(32, 34, r, rgb(col))
    p.eye(27, 34, 2.2, 4)
    p.eye(37, 34, 2.2, 4)
    p.done("pw_star", border=2)
    # WING CAP and METAL CAP
    for kind, cap, brim in (("wing", "#e8413b", "#c22f2a"), ("metal", "#c8d0d8", "#98a2ac")):
        p = Pen(64, 64)
        if kind == "wing":
            for sx in (-1, 1):
                for i in range(3):
                    p.oval(32 + sx * (20 + i * 6), 30 - i * 4, 8, 4, W_)
        p.d.chord(p._b(14, 18, 50, 56), 180, 360, fill=rgb(cap), outline=INK, width=int(LW * SS))
        p.rect(10, 35, 54, 41, rgb(brim), r=3)
        p.oval(32, 28, 5, 5, W_)
        if kind == "metal":
            p.line([(22, 26), (26, 22)], W_, 2.5)
        p.done("pw_" + kind, border=2)
    # PROPELLER cap
    p = Pen(64, 64)
    p.d.chord(p._b(16, 30, 48, 62), 180, 360, fill=rgb("#ffb020"), outline=INK, width=int(LW * SS))
    p.rect(30, 16, 34, 32, rgb("#8a8e96"), r=1)
    p.poly([(8, 14), (32, 16), (8, 20)], rgb("#3f7fd9"))
    p.poly([(56, 14), (32, 16), (56, 20)], rgb("#3f7fd9"))
    p.oval(32, 16, 3.5, 3.5, rgb("#ffd23a"))
    p.done("pw_propeller", border=2)


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
         "log_top", "log_side", "leaves", "wool_green", "wool_black", "road",
         "swamp_top", "swamp_side", "mud", "moon_top", "moon_rock"]


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


def spooky_moon_textures():
    """Ground for the Spooky Swamp (dark mossy grass, mud) and the Moon Base (grey dust, moon rock)."""
    MOSS = (98, 128, 90)
    MUD = (84, 62, 58)
    tex("swamp_top", MOSS, speck=(120, 92, 150), specks=22, seed=51)
    tex("swamp_side", MUD, speck=(60, 44, 42), specks=20, top=MOSS, seed=52)
    tex("mud", MUD, speck=(60, 44, 42), specks=30, seed=53)
    DUST = (196, 198, 206)
    ROCK = (150, 152, 162)
    tex("moon_top", DUST, speck=(232, 234, 240), specks=24, seed=54)
    # moon rock with little craters
    rnd = random.Random(55)
    im = Image.new("RGB", (16, 16), ROCK)
    px = im.load()
    for y in range(16):
        for x in range(16):
            v = rnd.randint(-8, 8)
            px[x, y] = tuple(max(0, min(255, c + v)) for c in ROCK)
    for cx, cy, r in ((4, 4, 2), (11, 9, 3), (5, 12, 1)):
        for y in range(16):
            for x in range(16):
                d = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
                if d <= r:
                    px[x, y] = (118, 120, 130)
                elif d <= r + 1 and y < cy:
                    px[x, y] = (178, 180, 190)
    im.resize((64, 64), Image.NEAREST).save(os.path.join(OUT, "t_moon_rock.png"))


def banana():
    """A banana peel for a funny cutscene."""
    p = Pen(64, 40)
    p.poly([(6, 30), (20, 22), (32, 20), (44, 22), (58, 30), (52, 34), (40, 28), (32, 27), (24, 28), (12, 34)], rgb("#ffd84a"))
    p.poly([(28, 26), (22, 12), (30, 22), (34, 10), (36, 24)], rgb("#ffe680"))
    p.oval(6, 31, 2.4, 2.4, rgb("#6a4a2e"), ink=False)
    p.done("banana", border=2)


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



# ---------------------------------------------------------------- more bad guys (all face right, feet at y=76)
def buzzer(name, wing=0.0, dive=False):
    """A round striped bee with a stinger."""
    p = Pen()
    cx, cy = 32, 44
    for s_ in (-1, 1):   # wings
        p.oval(cx - 2 + s_ * 3, cy - 15 - wing * 3, 7, 10 - wing * 3, rgb("#dff4ff", 220))
    p.poly([(cx - 20, cy + 1), (cx - 29, cy + 4), (cx - 20, cy + 6)], rgb("#3a2a20"))
    p.oval(cx, cy + 2, 18, 14, rgb("#ffd23a"))
    for x in (-10, -2, 6):
        p.line([(cx + x, cy - 10), (cx + x - 2, cy + 14)], rgb("#3a2a20"), 3.4)
    p.eye(cx + 11, cy - 2, 3.2, 4.2, 1.0)
    if dive:
        p.line([(cx + 8, cy - 8), (cx + 15, cy - 6)], INK, 2.2)
    p.line([(cx + 10, cy - 12), (cx + 14, cy - 20)], INK, 1.6)
    p.oval(cx + 14, cy - 21, 2, 2, INK, ink=False)
    p.d.arc(p._b(cx + 9, cy + 3, cx + 17, cy + 9), 20, 160, fill=INK, width=int(1.5 * SS))
    p.done(name)


def hopper(name, jump=False):
    """A big green frog with a wide grin."""
    p = Pen()
    G, GL = rgb("#4cb048"), rgb("#9be07a")
    cy = 60 if not jump else 52
    if jump:
        p.limb(24, cy + 8, 14, cy + 22, 6, G)
        p.limb(40, cy + 8, 50, cy + 22, 6, G)
    else:
        for s_ in (-1, 1):
            p.oval(32 + s_ * 13, 72, 9, 4, G)
    p.oval(32, cy, 20, 15, G)
    p.oval(34, cy + 5, 12, 8, GL, ink=False)
    for s_ in (-1, 1):
        p.oval(32 + s_ * 9, cy - 14, 7, 7, G)
        p.eye(32 + s_ * 9 + 1, cy - 14, 3.6, 4.2, 0.8)
    p.d.arc(p._b(20, cy - 4, 46, cy + 8), 15, 165, fill=INK, width=int(2 * SS))
    p.oval(18, cy, 2.2, 1.6, rgb("#ff9db0"), ink=False)
    p.oval(46, cy, 2.2, 1.6, rgb("#ff9db0"), ink=False)
    p.done(name)


def boohoo(name, shy=False):
    """A round shy ghost. It covers its face when you look at it."""
    p = Pen()
    cx, cy = 32, 44
    W_ = rgb("#f4f6ff")
    pts = []
    for i in range(25):
        a = math.pi + math.pi * i / 24
        pts.append((cx + math.cos(a) * 19, cy + math.sin(a) * 20))
    for i in range(7):
        pts.append((cx + 19 - i * 6.3, cy + 14 + (4 if i % 2 == 0 else 0)))
    p.poly(pts, W_)
    if shy:
        for s_ in (-1, 1):
            p.oval(cx + s_ * 6, cy - 2, 7, 6, W_)
        p.oval(cx - 12, cy + 4, 3, 2, rgb("#ff9db0"), ink=False)
        p.oval(cx + 12, cy + 4, 3, 2, rgb("#ff9db0"), ink=False)
    else:
        p.eye(cx + 3, cy - 4, 3.2, 4.6, 1.0)
        p.eye(cx + 12, cy - 4, 2.8, 4.2, 1.0)
        p.oval(cx + 8, cy + 7, 6, 5, rgb("#7a1f2b"))
        p.poly([(cx + 4, cy + 3), (cx + 6, cy + 7), (cx + 8, cy + 3)], rgb("#ffffff"), ink=False)
        p.oval(cx - 18, cy + 2, 5, 4, W_)
    p.done(name)


def bomber(name, step=0.0, lit=False):
    """A walking black bomb with a wind-up key."""
    p = Pen()
    cx, cy = 32, 52
    for s_ in (-1, 1):
        p.oval(cx + s_ * 8 + step * s_ * 3, 73, 7, 4, rgb("#ff9a2a"))
    p.rect(cx - 21, cy - 4, cx - 15, cy + 4, rgb("#c4c8d0"), r=1)
    p.oval(cx - 24, cy, 4, 6, rgb("#c4c8d0"))
    p.oval(cx, cy, 18, 18, rgb("#2b2f3c") if not lit else rgb("#6a2f3c"))
    p.oval(cx - 7, cy - 8, 5, 3, rgb("#5a6070"), ink=False)
    p.rect(cx - 3, cy - 23, cx + 3, cy - 16, rgb("#9aa0aa"), r=1)
    p.line([(cx, cy - 23), (cx + 4, cy - 30)], rgb("#c49a6a"), 2)
    if lit:
        p.star(cx + 5, cy - 32, 6, rgb("#ffd23a"))
        p.eye(cx + 6, cy - 2, 3.0, 4.2, 1.2)
        p.eye(cx + 14, cy - 2, 2.6, 3.8, 1.2)
        p.oval(cx + 10, cy + 8, 4, 3, rgb("#7a1f2b"))
    else:
        p.eye(cx + 6, cy - 2, 2.6, 3.6, 0.9)
        p.eye(cx + 14, cy - 2, 2.2, 3.2, 0.9)
    p.done(name)


def chompy(name, bite=False):
    """A big iron ball with teeth, on a chain."""
    p = Pen()
    cx, cy = 32, 50
    p.oval(cx, cy, 25, 24, rgb("#2b2f3c"))
    p.oval(cx - 9, cy - 11, 7, 4, rgb("#5a6070"), ink=False)
    if bite:
        p.poly([(cx + 2, cy - 4), (cx + 26, cy - 14), (cx + 26, cy + 16), (cx + 2, cy + 6)], rgb("#a8243a"))
        for i in range(4):
            p.poly([(cx + 6 + i * 5, cy - 6 - i * 2), (cx + 9 + i * 5, cy + 1 - i), (cx + 12 + i * 5, cy - 8 - i * 2)], rgb("#ffffff"), ink=False)
            p.poly([(cx + 6 + i * 5, cy + 7 + i * 2), (cx + 9 + i * 5, cy + 1 + i), (cx + 12 + i * 5, cy + 9 + i * 2)], rgb("#ffffff"), ink=False)
    else:
        p.line([(cx + 4, cy + 6), (cx + 24, cy + 4)], INK, 2.4)
        for i in range(4):
            p.poly([(cx + 6 + i * 5, cy + 5), (cx + 8.5 + i * 5, cy + 10), (cx + 11 + i * 5, cy + 5)], rgb("#ffffff"))
    p.eye(cx + 8, cy - 10, 5, 5.5, 1.6)
    p.done(name)


def spiky(name, step=0.0):
    """A red shell covered in white spikes (don't stomp it!)."""
    p = Pen()
    cx, cy = 32, 60
    for s_ in (-1, 1):
        p.oval(cx + s_ * 10 + step * s_ * 3, 73, 6, 4, rgb("#ffcf8a"))
    p.oval(cx + 16, cy + 4, 7, 7, rgb("#ffcf8a"))
    p.eye(cx + 18, cy + 2, 2.2, 3, 0.8)
    for i in range(5):
        a = math.pi + math.pi * (i + 0.5) / 5
        x, y = cx + math.cos(a) * 17, cy + math.sin(a) * 15
        p.poly([(x - 4, y + 2), (cx + math.cos(a) * 27, cy + math.sin(a) * 25), (x + 4, y + 2)], rgb("#ffffff"))
    p.d.chord(p._b(cx - 18, cy - 16, cx + 18, cy + 16), 180, 360, fill=rgb("#e8413b"), outline=INK, width=int(LW * SS))
    p.rect(cx - 19, cy - 1, cx + 19, cy + 4, rgb("#ffe8c8"), r=2)
    p.done(name)


def bat(name, flap=0.0, hang=False):
    """A little purple bat."""
    p = Pen()
    cx, cy = 32, (40 if not hang else 30)
    if hang:
        p.oval(cx, cy, 10, 14, rgb("#5b3a8a"))
        p.line([(cx - 3, cy - 14), (cx - 3, cy - 20)], INK, 2)
        p.line([(cx + 3, cy - 14), (cx + 3, cy - 20)], INK, 2)
        p.oval(cx - 4, cy + 4, 2, 1.4, INK, ink=False)
        p.oval(cx + 4, cy + 4, 2, 1.4, INK, ink=False)
        p.done(name)
        return
    for s_ in (-1, 1):
        y_ = cy - 6 - flap * 8
        p.poly([(cx + s_ * 6, cy - 2), (cx + s_ * 28, y_), (cx + s_ * 22, cy + 4), (cx + s_ * 14, cy + 2), (cx + s_ * 10, cy + 6)], rgb("#5b3a8a"))
    p.oval(cx, cy, 10, 10, rgb("#7a52b0"))
    for s_ in (-1, 1):
        p.poly([(cx + s_ * 3, cy - 8), (cx + s_ * 8, cy - 18), (cx + s_ * 9, cy - 6)], rgb("#7a52b0"))
    p.eye(cx - 3, cy - 1, 2.4, 3.0, 0.6)
    p.eye(cx + 4, cy - 1, 2.4, 3.0, 0.6)
    p.poly([(cx - 2, cy + 5), (cx - 1, cy + 8), (cx, cy + 5)], rgb("#ffffff"), ink=False)
    p.poly([(cx + 2, cy + 5), (cx + 3, cy + 8), (cx + 4, cy + 5)], rgb("#ffffff"), ink=False)
    p.done(name)


def mummy(name, step=0.0):
    """A wobbly bandaged mummy with an arm out."""
    p = Pen()
    cx = 30
    BAND, LINE = rgb("#ece2c6"), rgb("#bfb08a")
    for a in (step, -step):
        p.limb(cx, 54, cx + math.sin(a) * 8, 72, 7, BAND)
    p.rect(cx - 10, 30, cx + 10, 56, BAND, r=4)
    for y in (35, 41, 47, 52):
        p.line([(cx - 9, y), (cx + 9, y + 2)], LINE, 1.4)
    p.limb(cx + 4, 36, cx + 22, 38 + step * 3, 6, BAND)
    p.oval(cx + 1, 20, 12, 12, BAND)
    p.line([(cx - 10, 16), (cx + 12, 19)], LINE, 1.4)
    p.line([(cx - 10, 26), (cx + 12, 23)], LINE, 1.4)
    p.oval(cx + 3, 20, 3.4, 3.4, rgb("#1e1a14"), ink=False)
    p.oval(cx + 9, 20, 3.0, 3.0, rgb("#1e1a14"), ink=False)
    p.oval(cx + 3.6, 19.6, 1.3, 1.3, rgb("#ffe14a"), ink=False)
    p.oval(cx + 9.4, 19.6, 1.2, 1.2, rgb("#ffe14a"), ink=False)
    p.line([(cx - 8, 54), (cx - 16, 66)], BAND, 2)   # loose bandage
    p.done(name)


def roller(name, spin=0.0):
    """A rolling snowball with an angry face."""
    p = Pen()
    cx, cy = 32, 52
    p.oval(cx, cy, 23, 23, rgb("#f4f8ff"))
    for i in range(5):
        a = spin + i * 1.25
        p.oval(cx + math.cos(a) * 14, cy + math.sin(a) * 14, 3, 2, rgb("#d4e2f2"), ink=False)
    p.eye(cx + 6, cy - 5, 3.2, 4.2, 1.2)
    p.eye(cx + 15, cy - 5, 2.8, 3.8, 1.2)
    p.line([(cx + 2, cy - 12), (cx + 9, cy - 9)], INK, 2.2)
    p.line([(cx + 13, cy - 9), (cx + 19, cy - 12)], INK, 2.2)
    p.d.arc(p._b(cx + 4, cy + 4, cx + 18, cy + 12), 200, 340, fill=INK, width=int(1.8 * SS))
    p.done(name)


def cactusman(name, throw=False):
    """A grumpy cactus with a flower hat that throws needles."""
    p = Pen()
    cx = 32
    G = rgb("#4caa5c")
    p.rect(cx - 10, 24, cx + 10, 76, G, r=9)
    for y in (32, 44, 56, 68):
        for x in (-5, 4):
            p.line([(cx + x, y), (cx + x + 2, y - 2)], rgb("#fff6c0"), 1.2)
    if throw:
        p.limb(cx + 9, 44, cx + 24, 30, 7, G)
        p.limb(cx - 9, 48, cx - 18, 40, 7, G)
    else:
        p.limb(cx + 9, 46, cx + 18, 36, 7, G)
        p.limb(cx - 9, 50, cx - 18, 42, 7, G)
    p.eye(cx - 1, 36, 2.6, 3.4, 0.9)
    p.eye(cx + 6, 36, 2.4, 3.2, 0.9)
    p.line([(cx - 4, 31), (cx + 2, 33)], INK, 2)
    p.line([(cx + 4, 33), (cx + 9, 31)], INK, 2)
    p.d.arc(p._b(cx - 2, 42, cx + 8, 48), 200, 340, fill=INK, width=int(1.6 * SS))
    for i in range(5):
        a = i * math.tau / 5
        p.oval(cx + math.cos(a) * 4, 21 + math.sin(a) * 4, 3.4, 3.4, rgb("#ff7aa8"))
    p.oval(cx, 21, 2.2, 2.2, rgb("#ffd23a"), ink=False)
    p.done(name)


def foes2():
    buzzer("buzzer_1", 0); buzzer("buzzer_2", 1); buzzer("buzzer_dive", 0.5, dive=True)
    hopper("hopper_1"); hopper("hopper_jump", jump=True)
    boohoo("boohoo_1"); boohoo("boohoo_shy", shy=True)
    bomber("bomber_1", 1); bomber("bomber_2", -1); bomber("bomber_lit", 0.5, lit=True)
    chompy("chompy_1"); chompy("chompy_bite", bite=True)
    spiky("spiky_1", 1); spiky("spiky_2", -1)
    bat("bat_hang", hang=True); bat("bat_1", 0); bat("bat_2", 1)
    mummy("mummy_1", 0.4); mummy("mummy_2", -0.4)
    roller("roller_1", 0); roller("roller_2", 0.6)
    cactusman("cactusman_1"); cactusman("cactusman_throw", throw=True)

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
    new_partners()
    # foes
    grumbler("grumbler_1", 1)
    grumbler("grumbler_2", -1)
    grumbler("grumbler_flat", squished=True)
    skelly("skelly_1", 0.4)
    skelly("skelly_2", -0.4)
    skelly("skelly_aim", 0.1, aim=True)
    penguin("penguin_1", 1); penguin("penguin_2", -1); penguin("penguin_slide", slide=True); penguin("penguin_flat", flat=True)
    foes2()
    scorpion("scorpion_1", 1); scorpion("scorpion_2", -1); scorpion("scorpion_strike", strike=True); scorpion("scorpion_flat", flat=True)
    krag_jr("kragjr_1", 0)
    krag_jr("kragjr_2", 2)
    krag_jr("kragjr_angry", 0, angry=True)
    krag_jr("kragjr_hurt", 0, hurt=True)
    # bits
    shot_water(); shot_fire(); new_shots(); coin(); heart(); heart("heart_empty", "#5b4b63"); spark_shot(); arrow(); spikeball()
    tree("tree_a"); tree("tree_b", "#3b9d52", "#62c76a"); tree("tree_c", "#7fbf3f", "#a7dc5c")
    bush(); flower("flower_pink", "#ff8fc0"); flower("flower_blue", "#7fb8ff"); flower("flower_white", "#ffffff")
    cloud(); flag(); dennis()
    hill("hill_snow", "#eef4fc", "#ffffff", 240, 140); hill("hill_sand", "#e8c47e", "#f2d69a", 260, 120); hill("hill_a", "#7cc96a", "#9bde84"); hill("hill_b", "#5fb46a", "#82cc7d", 260, 150); hill("hill_c", "#a4d36f", "#c3e690", 160, 80)
    sign()
    star()
    power_items()
    textures()
    build_textures()
    world_textures()
    city_textures()
    spooky_moon_textures()
    banana()
    atlas()
    print("art written to", os.path.abspath(OUT))


if __name__ == "__main__":
    main()
