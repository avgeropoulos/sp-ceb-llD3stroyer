"""Builds the app cover from the 3D renders made by tools/render_cover.gd:
    python3 tools/make_cover.py <render_dir>
Writes cover/icon.png (512, the app icon), cover/icon_fg.png + cover/icon_bg.png (432, Android's
adaptive icon layers: the launcher crops them into a circle/squircle, so Rocco stays in the middle
66%) and cover/splash.png (1920x1080, the loading screen). Font: Poppins Bold (SIL Open Font License)."""
import os, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

src = sys.argv[1] if len(sys.argv) > 1 else "."
here = os.path.dirname(os.path.abspath(__file__))
out = os.path.join(here, "..", "cover")
os.makedirs(out, exist_ok=True)
FONT = os.path.join(here, "fonts", "Poppins-Bold.ttf")


def title(img, text, cx, y, size, fill, outline=(43, 34, 51), stroke=None):
    d = ImageDraw.Draw(img)
    f = ImageFont.truetype(FONT, size)
    stroke = stroke or max(4, size // 9)
    w = d.textlength(text, font=f)
    # a soft drop shadow, then the outlined letters
    sh = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(sh).text((cx - w / 2 + size * 0.05, y + size * 0.07), text, font=f, fill=(0, 0, 0, 110),
                            stroke_width=stroke, stroke_fill=(0, 0, 0, 110))
    img.alpha_composite(sh.filter(ImageFilter.GaussianBlur(size * 0.04)))
    d.text((cx - w / 2, y), text, font=f, fill=fill, stroke_width=stroke, stroke_fill=outline)


def fit_fg(fg, box):
    """Crops the transparent render to its content and scales it into a box (w, h)."""
    bb = fg.getbbox()
    c = fg.crop(bb)
    k = min(box[0] / c.width, box[1] / c.height)
    return c.resize((int(c.width * k), int(c.height * k)), Image.LANCZOS)


bg = Image.open(os.path.join(src, "icon_bg.png")).convert("RGBA")
fg = Image.open(os.path.join(src, "icon_fg.png")).convert("RGBA")

# Android adaptive layers (432 px; only the middle ~288 px is always visible)
abg = bg.resize((432, 432), Image.LANCZOS)
abg.save(os.path.join(out, "icon_bg.png"))
afg = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
r = fit_fg(fg, (300, 300))
afg.alpha_composite(r, ((432 - r.width) // 2, (432 - r.height) // 2 + 6))
afg.save(os.path.join(out, "icon_fg.png"))

# the classic square icon: sky, Rocco + star, and TEAM UP! across the bottom
icon = bg.resize((512, 512), Image.LANCZOS)
r = fit_fg(fg, (380, 380))
icon.alpha_composite(r, ((512 - r.width) // 2, 18))
title(icon, "TEAM UP!", 256, 392, 84, (255, 210, 58))
mask = Image.new("L", (512, 512), 0)
ImageDraw.Draw(mask).rounded_rectangle((0, 0, 511, 511), 96, fill=255)
icon.putalpha(mask)
icon.save(os.path.join(out, "icon.png"))

# the splash / loading screen
sp = Image.open(os.path.join(src, "cover_scene.png")).convert("RGBA").resize((1920, 1080), Image.LANCZOS)
title(sp, "ROCCO & BLOCKY", 960, 40, 150, (255, 210, 58))
title(sp, "TEAM UP! 3D", 960, 205, 120, (255, 74, 58), stroke=14)
sp.convert("RGB").save(os.path.join(out, "splash.png"))
print("cover written to", os.path.abspath(out))
