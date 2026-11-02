"""Gives the rendered partner pictures (art/mon_*.png from tools/render_mons.gd) a white sticker
border, like the hand-drawn paper art. The size stays the same so the feet line up.
Also writes art/mon_<form>_face.png (the idle picture trimmed) for the menus.
Run after tools/render_mons.gd:  python3 tools/sticker_mons.py [line line ...]"""
import glob, os, sys
from PIL import Image, ImageFilter

ART = os.path.join(os.path.dirname(__file__), "..", "art")
only = sys.argv[1:]
paths = [p for p in sorted(glob.glob(os.path.join(ART, "mon_*.png"))) if not p.endswith("_face.png")]
if only:
    paths = [p for p in paths if any(os.path.basename(p).startswith("mon_" + l) for l in only)]
for path in paths:
    im = Image.open(path).convert("RGBA")
    a = im.getchannel("A").point(lambda v: 255 if v > 40 else 0)
    grown = a.filter(ImageFilter.MaxFilter(11)).filter(ImageFilter.GaussianBlur(1.2))
    sticker = Image.new("RGBA", im.size, (255, 255, 255, 0))
    sticker.putalpha(grown)
    out = Image.alpha_composite(sticker, im)
    out.save(path)
    if path.endswith("_idle.png"):   # a trimmed copy for the menus' face pictures
        box = out.getchannel("A").point(lambda v: 255 if v > 20 else 0).getbbox()
        if box:
            out.crop(box).save(path[:-len("_idle.png")] + "_face.png")
print("stickered", len(paths))
