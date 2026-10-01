"""Generate the offline emoji picker from Unicode's emoji-test.txt.

Usage: python tools/generate_emoji_catalog.py SOURCE CATALOG_OUTPUT VARIANTS_OUTPUT
Only fully-qualified entries are included, in the source's CLDR order.
"""

from __future__ import annotations

import hashlib
import sys
from collections import defaultdict
from pathlib import Path


# The nine main Unicode groups are also the familiar Gboard-style tabs. Recent is
# added by the UI, since it is a personal history rather than a Unicode group.
CATEGORY_ORDER = [
    ("表情", "😀"),
    ("人物", "🧑"),
    ("動物", "🐻"),
    ("食物", "🍎"),
    ("旅行", "🚗"),
    ("活動", "⚽"),
    ("物件", "💡"),
    ("符號", "🔣"),
    ("旗幟", "🏳️"),
]
GROUP_NAMES = dict(zip(
    ("Smileys & Emotion", "People & Body", "Animals & Nature", "Food & Drink",
     "Travel & Places", "Activities", "Objects", "Symbols", "Flags"),
    (name for name, _ in CATEGORY_ORDER),
))
SKIN_TONES = range(0x1F3FB, 0x1F400)
PERSON, MAN, WOMAN = 0x1F9D1, 0x1F468, 0x1F469
MALE, FEMALE, ZWJ = 0x2642, 0x2640, 0x200D
# Unicode uses older single-codepoint emoji for these pair defaults, but ZWJ
# sequences for their mixed-tone forms. Map both encodings to one picker entry.
PAIR_ALIASES = {
    (0x1FAF1, ZWJ, 0x1FAF2): (0x1F91D,),  # handshake
    (PERSON, ZWJ, 0x1F430, ZWJ, PERSON): (0x1F46F,),  # bunny ears
    (MAN, ZWJ, 0x1F430, ZWJ, MAN): (0x1F46F, ZWJ, MALE),
    (WOMAN, ZWJ, 0x1F430, ZWJ, WOMAN): (0x1F46F, ZWJ, FEMALE),
    (PERSON, ZWJ, 0x1FAEF, ZWJ, PERSON): (0x1F93C,),  # wrestling
    (MAN, ZWJ, 0x1FAEF, ZWJ, MAN): (0x1F93C, ZWJ, MALE),
    (WOMAN, ZWJ, 0x1FAEF, ZWJ, WOMAN): (0x1F93C, ZWJ, FEMALE),
    (WOMAN, ZWJ, 0x1F91D, ZWJ, WOMAN): (0x1F46D,),  # holding hands
    (WOMAN, ZWJ, 0x1F91D, ZWJ, MAN): (0x1F46B,),
    (MAN, ZWJ, 0x1F91D, ZWJ, MAN): (0x1F46C,),
    (PERSON, ZWJ, 0x2764, ZWJ, 0x1F48B, ZWJ, PERSON): (0x1F48F,),  # kiss
    (PERSON, ZWJ, 0x2764, ZWJ, PERSON): (0x1F491,),  # couple with heart
}


def normalized(codepoints: tuple[int, ...]) -> tuple[int, ...]:
    return tuple(cp for cp in codepoints if cp not in SKIN_TONES and cp not in {0xFE0E, 0xFE0F})


def gender_root(codepoints: tuple[int, ...]) -> tuple[tuple[int, ...], str] | None:
    """Fold explicit gender variants with an existing neutral emoji.

    Requiring exactly one person component avoids conflating distinct families or
    couples, whose member order and relationship carry meaning of their own.
    """
    value = normalized(codepoints)
    people = {PERSON, MAN, WOMAN, 0x1F466, 0x1F467}
    if value and value[0] in {MAN, WOMAN} and sum(cp in people for cp in value) == 1:
        return (PERSON,) + value[1:], "male" if value[0] == MAN else "female"
    signs = [(i, cp) for i, cp in enumerate(value) if cp in {MALE, FEMALE}]
    if len(signs) == 1:
        i, sign = signs[0]
        if i > 0 and value[i - 1] == ZWJ:
            return value[:i - 1] + value[i + 1:], "male" if sign == MALE else "female"
    return None


def tone_row(base: str, tones: dict[str, list[str]]) -> list[str]:
    choices = tones.get(base, [])
    return choices[:2] + [base] + choices[2:]


def main(source: Path, output: Path, variants_output: Path) -> None:
    raw = source.read_bytes()
    entries: list[tuple[str, str, tuple[int, ...]]] = []
    group = ""
    for line in raw.decode("utf-8").splitlines():
        if line.startswith("# group: "):
            group = line.removeprefix("# group: ")
        elif "; fully-qualified" in line:
            codepoints = tuple(int(codepoint, 16) for codepoint in line.split(";", 1)[0].split())
            entries.append((GROUP_NAMES[group], "".join(map(chr, codepoints)), codepoints))

    base_by_codepoints = {normalized(codepoints): emoji for _, emoji, codepoints in entries
                          if not any(cp in SKIN_TONES for cp in codepoints)}
    ordered_bases = list(base_by_codepoints.values())
    by_tone: dict[str, dict[int, str]] = defaultdict(dict)
    for _, emoji, codepoints in entries:
        tones = {cp for cp in codepoints if cp in SKIN_TONES}
        if len(tones) == 1:
            base = base_by_codepoints.get(normalized(codepoints))
            if base is not None:
                by_tone[base].setdefault(next(iter(tones)), emoji)
    complete_tones = {base: [choices[tone] for tone in SKIN_TONES]
                      for base, choices in by_tone.items()
                      if all(tone in choices for tone in SKIN_TONES)}
    pair_variants: dict[str, list[str]] = defaultdict(list)
    for _, emoji, codepoints in entries:
        if not any(cp in SKIN_TONES for cp in codepoints):
            continue
        key = normalized(codepoints)
        base = base_by_codepoints.get(key) or base_by_codepoints.get(PAIR_ALIASES.get(key, ()))
        if base is not None:
            pair_variants[base].append(emoji)

    genders: dict[str, dict[str, str]] = defaultdict(dict)
    for _, emoji, codepoints in entries:
        if any(cp in SKIN_TONES for cp in codepoints):
            continue
        root = gender_root(codepoints)
        if root is not None:
            neutral = base_by_codepoints.get(root[0])
            if neutral is not None and neutral != emoji:
                genders[neutral][root[1]] = emoji

    # Pair families take priority, followed by gender+tone families. Individual
    # tone rows remain as fallbacks when a representative is unsupported.
    variant_rows: list[tuple[str, list[str]]] = []
    for base in ordered_bases:
        choices = pair_variants.get(base, [])
        if len(choices) > 5:
            variant_rows.append((base, [base] + choices))
    for base in ordered_bases:
        genders_for_base = genders.get(base, {})
        if "male" in genders_for_base and "female" in genders_for_base:
            # Put the neutral row closest to the touched key. Sliding upward
            # crosses the male row and then the female row.
            choices = tone_row(genders_for_base["female"], complete_tones)
            choices += tone_row(genders_for_base["male"], complete_tones)
            choices += tone_row(base, complete_tones)
            variant_rows.append((base, choices))
    for base in ordered_bases:
        if base in complete_tones:
            variant_rows.append((base, tone_row(base, complete_tones)))

    rows: dict[str, list[str]] = defaultdict(list)
    for category, emoji, _ in entries:
        rows[category].append(emoji)

    header = [
        "# Unicode emoji-test.txt, Emoji 18.0, fully-qualified entries in CLDR order",
        "# https://www.unicode.org/Public/emoji/latest/emoji-test.txt",
        f"# Source SHA-256: {hashlib.sha256(raw).hexdigest()}",
        "# Copyright © 2026 Unicode, Inc.; Unicode License v3 in THIRD_PARTY.md",
    ]
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(header + [
        f"{name}\t{icon}\t{' '.join(rows[name])}" for name, icon in CATEGORY_ORDER
    ]) + "\n", encoding="utf-8")
    variants_output.parent.mkdir(parents=True, exist_ok=True)
    variants_output.write_text("\n".join(header + [
        f"{base}\t{' '.join(choices)}" for base, choices in variant_rows
    ]) + "\n", encoding="utf-8")
    print(f"Generated {len(entries)} emoji in {len(CATEGORY_ORDER)} categories, "
          f"{sum(len(v) > 5 for v in pair_variants.values())} pair families, "
          f"{len(genders)} gender families and {len(complete_tones)} skin-tone fallbacks")
    for name, _ in CATEGORY_ORDER:
        print(f"  {name}: {len(rows[name])}")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit("Usage: python tools/generate_emoji_catalog.py SOURCE CATALOG_OUTPUT VARIANTS_OUTPUT")
    main(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]))
