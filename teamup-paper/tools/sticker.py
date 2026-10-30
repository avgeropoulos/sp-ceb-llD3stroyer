"""Gives rendered pictures (art/decor_*.png) a white sticker border and trims them.
Paint masks (art/decor_<id>_mask.png) are trimmed exactly like their picture, with no border,
so the two line up pixel for pixel.
Run after tools/render_decor.gd:  python3 tools/sticker.py [id id ...]   (ids: only those decorations)"""
import glob, os, sys
from PIL import Image, ImageFilter

ART = os.path.join(os.path.dirname(__file__), "..", "art")
PROPS = ["pine_a", "pine_b", "snowman", "snowpile", "cactus_a", "cactus_b", "palm", "desert_rock", "rocket"]
only = sys.argv[1:]
if only:
    paths = [os.path.join(ART, "decor_%s.png" % i) for i in only]
else:
    paths = [p for p in sorted(glob.glob(os.path.join(ART, "decor_*.png"))) if not p.endswith("_mask.png")]
    paths += [os.path.join(ART, p + ".png") for p in PROPS]
for path in paths:
    im = Image.open(path).convert("RGBA")
    box = im.getchannel("A").point(lambda v: 255 if v > 20 else 0).getbbox()
    if not box:
        continue
    pad = 14
    crop = (max(0, box[0] - pad), max(0, box[1] - pad), min(im.width, box[2] + pad), min(im.height, box[3] + pad))
    im = im.crop(crop)
    a = im.getchannel("A").point(lambda v: 255 if v > 40 else 0)
    grown = a.filter(ImageFilter.MaxFilter(13)).filter(ImageFilter.GaussianBlur(1.2))
    sticker = Image.new("RGBA", im.size, (255, 255, 255, 0))
    sticker.putalpha(grown)
    out = Image.alpha_composite(sticker, im)
    out.save(path)
    mask = path[:-4] + "_mask.png"
    if os.path.exists(mask):
        m = Image.open(mask).convert("RGBA")
        if m.size != (crop[2] - crop[0], crop[3] - crop[1]):   # a fresh render: trim it like the picture
            m = m.crop(crop)
        m.save(mask)
    print("sticker", os.path.basename(path), out.size)
