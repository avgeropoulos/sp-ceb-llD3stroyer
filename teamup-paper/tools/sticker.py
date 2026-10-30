"""Gives rendered pictures (art/decor_*.png) a white sticker border and trims them.
Run after tools/render_decor.gd:  python3 tools/sticker.py"""
import glob, os
from PIL import Image, ImageFilter

ART = os.path.join(os.path.dirname(__file__), "..", "art")
for path in sorted(glob.glob(os.path.join(ART, "decor_*.png"))):
    im = Image.open(path).convert("RGBA")
    box = im.getchannel("A").point(lambda v: 255 if v > 20 else 0).getbbox()
    if not box:
        continue
    pad = 14
    im = im.crop((max(0, box[0] - pad), max(0, box[1] - pad), min(im.width, box[2] + pad), min(im.height, box[3] + pad)))
    a = im.getchannel("A").point(lambda v: 255 if v > 40 else 0)
    grown = a.filter(ImageFilter.MaxFilter(13)).filter(ImageFilter.GaussianBlur(1.2))
    sticker = Image.new("RGBA", im.size, (255, 255, 255, 0))
    sticker.putalpha(grown)
    out = Image.alpha_composite(sticker, im)
    out.save(path)
    print("sticker", os.path.basename(path), out.size)
