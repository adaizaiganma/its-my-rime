"""Build offline Latin + Traditional Chinese UI fonts for Android 9 and newer.

Run with Python and fonttools==4.63.0. Sources are pinned and SHA-256 checked.
The checked-in TTFs are build inputs; this script is not part of Gradle builds.
"""

import argparse
import hashlib
import json
from pathlib import Path
import tempfile
from urllib.parse import quote
from urllib.request import urlopen

from fontTools import subset
from fontTools.merge import Merger
from fontTools.ttLib import TTFont
from fontTools.ttLib.scaleUpem import scale_upem
from fontTools.varLib.instancer import instantiateVariableFont

ROOT = Path(__file__).resolve().parents[1]
REVISION = "7085eb89a950e85db5b166b7a58d414544b4140c"
SOURCES = {
    "cormorantgaramond": (
        "CormorantGaramond[wght].ttf",
        "b20b7d9626dd956b2c5e558692ad328b1f19e3275e2782db4fa07670d83f35e0",
        "60700d351cac4650c51f3f9db318d2a420f8b45052dba2715eb5fec41f0f6956",
    ),
    "inter": (
        "Inter[opsz,wght].ttf",
        "29160a80ff49ddcab2c97711247e08b1fab27a484a329ce8b813d820dc559031",
        "5b9321a4298cfeb6b34354164a1c3afc3db114569984c502b9b35d988fd58c57",
    ),
    "notosanstc": (
        "NotoSansTC[wght].ttf",
        "864727d210d54f2537bbe23b3a839436c3992af72de9322af5270897246bd44f",
        "1c05c68c34f9708415aada51f17e1b0092d2cea709bf4a94cd38114f9e73d7d9",
    ),
    "notoseriftc": (
        "NotoSerifTC[wght].ttf",
        "0077e18f57c6908f4a000969880940bdb0dad057c0e8d98b49dc364c3d1b09c6",
        "5e0da210fb04058a8c0087985d2d456b931c2579811a49655721d3cf0c36b6d6",
    ),
}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_file(cache, family, filename, expected_hash):
    path = cache / (family + "-OFL.txt" if filename == "OFL.txt" else filename)
    url = f"https://raw.githubusercontent.com/google/fonts/{REVISION}/ofl/{family}/{quote(filename)}"
    if not path.exists():
        with urlopen(url, timeout=120) as response:
            path.write_bytes(response.read())
    if digest(path) != expected_hash:
        raise ValueError(f"Source hash mismatch: {filename}")
    return path, url


def static_font(path, weight):
    font = TTFont(path, recalcTimestamp=False)
    axes = {axis.axisTag: axis.defaultValue for axis in font["fvar"].axes}
    axes["wght"] = weight
    instantiateVariableFont(font, axes, inplace=True)
    # The app uses horizontal text. Remove unused vertical metrics and hinting.
    for table in ("DSIG", "STAT", "BASE", "vhea", "vmtx", "VORG", "fpgm", "prep", "cvt ", "gasp"):
        if table in font:
            del font[table]
    if font["head"].unitsPerEm != 1000:
        scale_upem(font, 1000)
    return font


def keep_characters(font, characters):
    options = subset.Options()
    options.hinting = False
    options.layout_features = ["*"]
    options.name_IDs = ["*"]
    options.name_languages = ["*"]
    options.notdef_outline = True
    sub = subset.Subsetter(options=options)
    sub.populate(unicodes=characters)
    sub.subset(font)


def merge_pair(latin, chinese, family, weight, output, chinese_characters=None):
    latin_characters = set(latin.getBestCmap())
    original_chinese = set(chinese.getBestCmap())
    required_chinese = original_chinese if chinese_characters is None else original_chinese & chinese_characters
    # Latin uses Inter/Cormorant; Chinese punctuation and Han characters use Noto.
    # A single cmap avoids device-dependent glyph fallback on Android 9.
    keep_characters(chinese, required_chinese - latin_characters)
    keep_characters(latin, latin_characters)
    copyright_text = " / ".join(font["name"].getDebugName(0) or "" for font in (latin, chinese))
    with tempfile.TemporaryDirectory() as directory:
        inputs = [Path(directory) / "latin.ttf", Path(directory) / "chinese.ttf"]
        latin.save(inputs[0])
        chinese.save(inputs[1])
        merged = Merger().merge([str(path) for path in inputs])
    style = "Medium" if weight == 500 else "Regular"
    postscript = family.replace(" ", "") + "-" + style
    names = {
        0: copyright_text,
        1: family,
        2: style,
        3: postscript + ";1.000",
        4: family + " " + style,
        6: postscript,
        16: family,
        17: style,
    }
    for name_id, value in names.items():
        merged["name"].removeNames(nameID=name_id)
        merged["name"].setName(value, name_id, 3, 1, 0x409)
    merged["OS/2"].usWeightClass = weight
    merged["head"].macStyle = 0
    merged["hhea"].lineGap = 0
    # Latin and Chinese share the same baseline and line metrics.
    merged["OS/2"].sTypoAscender = merged["hhea"].ascent
    merged["OS/2"].sTypoDescender = merged["hhea"].descent
    merged["OS/2"].sTypoLineGap = 0
    merged["OS/2"].fsSelection = (1 << 6) | (1 << 7)
    merged["head"].created = merged["head"].modified = 3863635200
    merged.recalcTimestamp = False
    merged.save(output)
    coverage = set(TTFont(output).getBestCmap())
    if not (latin_characters | required_chinese) <= coverage:
        raise ValueError(f"Character coverage lost: {output.name}")
    print(f"{output.name}: {len(coverage):,} characters, {output.stat().st_size:,} bytes", flush=True)
    return {"file": output.name, "sha256": digest(output), "characters": len(coverage)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=ROOT / "tmp/font-sources")
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    fonts_dir = ROOT / "app/src/main/res/font"
    notices_dir = ROOT / "app/src/main/assets/fonts"
    fonts_dir.mkdir(parents=True, exist_ok=True)
    notices_dir.mkdir(parents=True, exist_ok=True)
    source_paths = {}
    source_records = []
    for family, (filename, font_hash, license_hash) in SOURCES.items():
        source_paths[family], url = source_file(args.cache, family, filename, font_hash)
        license_file, _ = source_file(args.cache, family, "OFL.txt", license_hash)
        (notices_dir / (family + "-OFL.txt")).write_bytes(license_file.read_bytes())
        source_records.append({"family": family, "url": url, "sha256": font_hash, "license": "SIL OFL 1.1"})
    outputs = []
    for weight, style in ((400, "regular"), (500, "medium")):
        print(f"Building body font {weight}…", flush=True)
        outputs.append(merge_pair(
            static_font(source_paths["inter"], weight),
            static_font(source_paths["notosanstc"], weight),
            "Rime UI Sans", weight, fonts_dir / f"rime_sans_{style}.ttf",
        ))
    settings_source = (ROOT / "app/src/main/java/com/kingzcheung/xime/MainActivity.kt").read_text(encoding="utf-8")
    settings_source += (ROOT / "app/src/main/res/values/strings.xml").read_text(encoding="utf-8")
    # Serif is only used by fixed headings. Input fields always use the full sans.
    heading_characters = {ord(char) for char in settings_source if ord(char) >= 0x3000}
    heading_characters.update(map(ord, "，。！？：；「」『』（）《》〈〉、"))
    print("Building display font…", flush=True)
    outputs.append(merge_pair(
        static_font(source_paths["cormorantgaramond"], 500),
        static_font(source_paths["notoseriftc"], 400),
        "Rime UI Display", 400, fonts_dir / "rime_display_regular.ttf", heading_characters,
    ))
    metadata = {"google_fonts_revision": REVISION, "sources": source_records, "outputs": outputs}
    (notices_dir / "SOURCES.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
