#!/usr/bin/env python3
"""Download Pokémon Showdown gen5ani animated GIFs for every sprite id the app uses.

Reads the already-generated data (species.json, megas.json) so it needs no source CSVs, and the
Showdown pokedex (cached under tools/apicache/ps-pokedex.js) to resolve each dex number / mega forme
to its gen5ani filename. GIFs are saved to:
    app/src/main/assets/anim/front/<id>.gif
    app/src/main/assets/anim/back/<id>.gif
Only ids that actually have a gen5ani GIF are written; the app falls back to the static PNG for the
rest (Gen 6+ species and most Mega formes have no gen5ani art).

Attribution: animated sprites are from Pokémon Showdown's gen5ani set (play.pokemonshowdown.com).
"""
import json
import os
import re
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets")
APICACHE = os.path.join(ROOT, "tools", "apicache")
POKEDEX = os.path.join(APICACHE, "ps-pokedex.js")
BASE = "https://play.pokemonshowdown.com/sprites"
UA = {"User-Agent": "Mozilla/5.0"}


def load_pokedex():
    if not os.path.exists(POKEDEX):
        os.makedirs(APICACHE, exist_ok=True)
        urllib.request.urlretrieve(BASE.rsplit("/", 1)[0] + "/data/pokedex.js", POKEDEX)
    txt = open(POKEDEX, encoding="utf-8").read()
    entries = re.findall(r'(\w+):\{num:(\d+),name:"([^"]+)"(.*?)\}(?=,\w+:\{|\};?$)', txt)
    out = []
    for key, num, name, rest in entries:
        f = re.search(r'forme:"([^"]+)"', rest)
        out.append({"key": key, "num": int(num), "name": name, "forme": f.group(1) if f else ""})
    return out


def g5name(display_name):
    """Showdown display name -> gen5ani filename (lowercase; Mega-X/Y -> megax/megay; strip spaces/punct)."""
    s = display_name.lower().replace("\u2019", "").replace("'", "").replace(".", "").replace(":", "")
    s = s.replace("mega-x", "megax").replace("mega-y", "megay").replace(" ", "")
    return s


def fetch(url, dest):
    try:
        req = urllib.request.Request(url, headers=UA)
        with urllib.request.urlopen(req, timeout=30) as r:
            if r.status != 200:
                return False
            blob = r.read()
    except Exception:
        return False
    if not blob or blob[:3] != b"GIF":
        return False
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest, "wb") as f:
        f.write(blob)
    return True


def save_anim(sprite_id, g5):
    """Try to fetch front/back gen5ani for one sprite id. Returns (front_ok, back_ok)."""
    fo = fetch("%s/gen5ani/%s.gif" % (BASE, g5), os.path.join(OUT, "anim", "front", "%d.gif" % sprite_id))
    bo = fetch("%s/gen5ani-back/%s.gif" % (BASE, g5), os.path.join(OUT, "anim", "back", "%d.gif" % sprite_id))
    return fo, bo


def main():
    dex = load_pokedex()
    base_name = {}       # dex num -> base species display name
    forme_name = {}      # (dex num, forme) -> display name
    for e in dex:
        if not e["forme"]:
            base_name.setdefault(e["num"], e["name"])
        forme_name[(e["num"], e["forme"])] = e["name"]

    species = json.load(open(os.path.join(OUT, "data", "species.json"), encoding="utf-8"))
    megas = json.load(open(os.path.join(OUT, "data", "megas.json"), encoding="utf-8"))
    sets = json.load(open(os.path.join(OUT, "data", "sets.json"), encoding="utf-8"))
    used = {s["species"] for s in sets if s["species"] != 0}

    # (sprite_id, gen5ani_name). Base species keyed by dex id; megas by their sprite id + Mega forme.
    targets = {}
    for did in sorted(used):
        n = base_name.get(did)
        if n:
            targets[did] = g5name(n)
    for m in megas:
        variant = m["nameKo"][-1] if m["nameKo"] and m["nameKo"][-1] in "XY" else ""
        forme = "Mega-" + variant if variant else "Mega"
        n = forme_name.get((m["species"], forme))
        if n:
            targets[m["sprite"]] = g5name(n)

    front_ok = back_ok = 0
    missing = []
    for i, (sid, g5) in enumerate(sorted(targets.items())):
        fo, bo = save_anim(sid, g5)
        front_ok += fo
        back_ok += bo
        if not fo:
            missing.append((sid, g5))
        if (i + 1) % 25 == 0:
            print("  %d/%d (front %d, back %d)" % (i + 1, len(targets), front_ok, back_ok))
        time.sleep(0.02)
    print("targets=%d front_gifs=%d back_gifs=%d no_front=%d"
          % (len(targets), front_ok, back_ok, len(missing)))
    print("no gen5ani (fall back to PNG):", [m[1] for m in missing][:40])


if __name__ == "__main__":
    sys.exit(main())
