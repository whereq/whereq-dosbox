#!/usr/bin/env python3
"""make-icons.py - generate the Android launcher icons from the WhereQ logo.

The logo in ../resources/ is a 4096x4096 artwork that carries two parts:

  * the top half is the ">_" command prompt mark - white on a dark navy
    background, which is what the launcher shows;
  * the bottom half is the "WD" wordmark with the blue swoosh.

From that one artwork this script produces every asset the launcher needs:

  ic_launcher.png            legacy square icon  (API 21-25), full artwork
  ic_launcher_round.png      legacy round icon   (API 21-25), full artwork
  ic_launcher_foreground.png adaptive foreground (API 26+), the ">_" mark
                             on transparency, inside the 72/108 safe zone
  colors.xml                 ic_launcher_background, sampled from the logo

The adaptive foreground is the mark on its own: the wordmark turns into
unreadable grey mush at 48dp, while the prompt mark stays sharp.

Usage:
    ./android/make-icons.py [--source FILE] [--check]

    --source FILE   logo to use (default: the largest PNG in resources/)
    --check         verify that the generated assets are up to date, do not
                    write anything (exit 1 if they are not)

Requires Python 3 with Pillow:
    python3 -m pip install --user pillow
"""

import argparse
import io
import os
import sys

try:
    from PIL import Image, ImageChops, ImageDraw
except ImportError:
    sys.exit("Pillow is required: python3 -m pip install --user pillow")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RES = os.path.join(ROOT, "resources")
RES_OUT = os.path.join(HERE, "app", "src", "main", "res")

# density -> (legacy icon size, adaptive canvas size)
DENSITIES = {
    "mdpi":    (48, 108),
    "hdpi":    (72, 162),
    "xhdpi":   (96, 216),
    "xxhdpi":  (144, 324),
    "xxxhdpi": (192, 432),
}

# the adaptive icon is 108x108dp with a 72x72dp safe zone in the middle
SAFE_ZONE = 72.0 / 108.0

# the mark lives in the top half of the artwork; the wordmark is below it
MARK_TOP = 0.0
MARK_BOTTOM = 0.56


def find_source(explicit=None):
    """Pick the logo to work from: the explicit file, else the largest PNG."""
    if explicit:
        if not os.path.isfile(explicit):
            sys.exit("no such logo: %s" % explicit)
        return explicit

    candidates = []
    for name in sorted(os.listdir(RES)):
        if not name.lower().endswith(".png"):
            continue
        path = os.path.join(RES, name)
        try:
            with Image.open(path) as im:
                area = im.size[0] * im.size[1]
                # a real PNG wins over a JPEG that only carries the .png name
                rank = (1 if im.format == "PNG" else 0, area)
                candidates.append((rank, path))
        except Exception:
            continue
    if not candidates:
        sys.exit("no PNG found in %s - pass --source" % RES)
    candidates.sort(reverse=True)
    return candidates[0][1]


def dominant_background(im):
    """The navy the artwork sits on - used as the adaptive background."""
    small = im.convert("RGB").resize((64, 64), Image.LANCZOS)
    # the corners carry the flat background, the middle carries the artwork
    corners = [small.getpixel(p) for p in ((0, 0), (63, 0), (0, 63), (63, 63))]
    r = sum(c[0] for c in corners) // 4
    g = sum(c[1] for c in corners) // 4
    b = sum(c[2] for c in corners) // 4
    return (r, g, b)


def extract_mark(im):
    """Cut the '>_' mark out of the artwork and return it as white on alpha."""
    w, h = im.size
    region = im.convert("RGB").crop((0, int(h * MARK_TOP), w, int(h * MARK_BOTTOM)))
    grey = region.convert("L")

    # the mark is the bright part; the navy background is dark
    mask = grey.point(lambda v: 255 if v > 140 else 0)
    bbox = mask.getbbox()
    if not bbox:
        sys.exit("could not find the mark in the logo")
    region = region.crop(bbox)

    # turn the luminance into the alpha channel and paint the mark white, so
    # the glow around it fades out instead of ending in a hard edge
    alpha = region.convert("L").point(lambda v: 0 if v < 90 else min(255, (v - 90) * 4))
    mark = Image.new("RGBA", region.size, (255, 255, 255, 0))
    mark.putalpha(alpha)
    return mark


def fit(mark, box):
    """Scale the mark so that it fits into a box of box x box pixels."""
    w, h = mark.size
    scale = min(box / float(w), box / float(h))
    size = (max(1, int(round(w * scale))), max(1, int(round(h * scale))))
    return mark.resize(size, Image.LANCZOS)


def circular(im):
    """Cut a circle out of a square image."""
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).ellipse((0, 0) + im.size, fill=255)
    out = im.convert("RGBA")
    out.putalpha(mask)
    return out


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    data.save(path, optimize=True)
    return path


def encode(data):
    """The bytes an image would be written as, for comparison and writing."""
    buf = io.BytesIO()
    data.save(buf, format="PNG", optimize=True)
    return buf.getvalue()


def write_bytes(path, blob):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(blob)
    return path


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--source", help="logo to use")
    ap.add_argument("--check", action="store_true",
                    help="only report whether the assets are up to date")
    args = ap.parse_args()

    src = find_source(args.source)
    print("logo: %s" % os.path.relpath(src, ROOT))

    im = Image.open(src).convert("RGB")
    print("size: %dx%d" % im.size)

    bg = dominant_background(im)
    print("adaptive background: #%02X%02X%02X" % bg)

    mark = extract_mark(im)
    print("mark: %dx%d" % mark.size)

    written = []
    changed = []

    for density, (legacy, canvas) in sorted(DENSITIES.items()):
        ddir = os.path.join(RES_OUT, "mipmap-%s" % density)

        # legacy square: the whole artwork, full bleed
        square = im.resize((legacy, legacy), Image.LANCZOS)
        p = os.path.join(ddir, "ic_launcher.png")
        written.append((p, square.convert("RGB")))

        # legacy round: the same artwork in a circle
        p = os.path.join(ddir, "ic_launcher_round.png")
        written.append((p, circular(im.resize((legacy, legacy), Image.LANCZOS))))

        # adaptive foreground: the mark, inside the 72/108 safe zone
        fg = Image.new("RGBA", (canvas, canvas), (255, 255, 255, 0))
        scaled = fit(mark, int(canvas * SAFE_ZONE))
        fg.paste(scaled, ((canvas - scaled.size[0]) // 2,
                          (canvas - scaled.size[1]) // 2), scaled)
        p = os.path.join(ddir, "ic_launcher_foreground.png")
        written.append((p, fg))

    # the adaptive background colour
    colors = os.path.join(RES_OUT, "values", "colors.xml")
    colors_text = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<resources>\n'
        '    <!-- sampled from the WhereQ logo by android/make-icons.py -->\n'
        '    <color name="ic_launcher_background">#%02X%02X%02X</color>\n'
        '</resources>\n' % bg
    )

    # the logo the README shows - the full artwork, but small enough for a
    # web page (the 4k original in resources/ is far too heavy to inline)
    readme_logo = os.path.join(RES, "whereq-dosbox-logo.png")
    logo_blob = encode(im.resize((512, 512), Image.LANCZOS).convert("RGB"))
    written.append((readme_logo, logo_blob))

    for path, data in written:
        blob = data if isinstance(data, bytes) else encode(data)
        old = None
        if os.path.exists(path):
            with open(path, "rb") as fh:
                old = fh.read()
        if old != blob:
            changed.append(os.path.relpath(path, ROOT))
        if not args.check:
            write_bytes(path, blob)

    colors_blob = colors_text.encode("utf-8")
    if not args.check:
        write_bytes(colors, colors_blob)
        print("wrote %d assets + colors.xml" % len(written))
    else:
        if os.path.exists(colors):
            with open(colors, "rb") as fh:
                if fh.read() != colors_blob:
                    changed.append(os.path.relpath(colors, ROOT))
        else:
            changed.append(os.path.relpath(colors, ROOT))
        print("out of date: %s" % (", ".join(changed) if changed else "nothing"))

    return 1 if (args.check and changed) else 0


if __name__ == "__main__":
    sys.exit(main())
