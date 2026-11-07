#!/usr/bin/env python3
"""Side-by-side PNGs (SwiftKey left, Kud Keys right) from run.py's layouts output.

    python3 sbs.py <layouts dir> [--sk <swiftkey dir>] [--kud <kudkeys dir>] [--out <dir>]

Pages: <out>/<page>.png. Long-press popups: <out>/lp_<page>.png (one pair per key that pops up on either side,
paired by label, else by position). Also <out>/keys_<page>.txt: key labels and widths per row, both sides.
"""
import json, os, sys
from PIL import Image, ImageDraw

GAP = 24


def norm(label):
    return label.split(", ")[0].strip() if label else label


def load(d):
    p = os.path.join(d, "pages.json")
    return json.load(open(p)) if os.path.exists(p) else {}


def fix(path, d):
    # pages.json stores CI paths; re-root them on the local directory
    if "/layouts/" in path:
        return os.path.join(d, path.rsplit("/layouts/", 1)[1].split("/", 1)[1])
    return path


def top_of(page, h):
    t = page.get("touch")
    if t:
        return t[1]
    ys = [k["b"][1] for k in page.get("keys", [])]
    return (min(ys) - 260) if ys else int(h * 0.55)


def pair(a, b, top, label_a="SwiftKey", label_b="Kud Keys", caption=None):
    w = a.width + b.width + GAP
    im = Image.new("RGB", (w, a.height - top + 40), "white")
    im.paste(a.crop((0, top, a.width, a.height)), (0, 40))
    im.paste(b.crop((0, top, b.width, b.height)), (a.width + GAP, 40))
    dr = ImageDraw.Draw(im)
    dr.text((10, 8), label_a + (f"  {caption}" if caption else ""), fill="black")
    dr.text((a.width + GAP + 10, 8), label_b, fill="black")
    return im


def rows_txt(keys):
    rows = []
    for k in sorted(keys, key=lambda k: (k["b"][1], k["b"][0])):
        if rows and abs(rows[-1][0]["b"][1] - k["b"][1]) <= 12:
            rows[-1].append(k)
        else:
            rows.append([k])
    out = []
    for r in rows:
        r.sort(key=lambda k: k["b"][0])
        out.append(f"y{r[0]['b'][1]}-{r[0]['b'][3]}: " + "  ".join(f"{norm(k['label'])}[{k['b'][0]}..{k['b'][2]}]" for k in r))
    return "\n".join(out)


def main(layouts, sk_dir=None, kud_dir=None, out=None):
    sk_dir = sk_dir or os.path.join(layouts, "swiftkey")
    kud_dir = kud_dir or os.path.join(layouts, "kudkeys")
    out = out or os.path.join(layouts, "sbs")
    os.makedirs(out, exist_ok=True)
    S, K = load(sk_dir), load(kud_dir)
    for name in S:
        if name not in K:
            print("only SwiftKey has page", name)
            continue
        a = Image.open(fix(S[name]["png"], sk_dir)).convert("RGB")
        b = Image.open(fix(K[name]["png"], kud_dir)).convert("RGB")
        top = min(top_of(S[name], a.height), top_of(K[name], b.height)) - 10
        pair(a, b, top, caption=name).save(os.path.join(out, f"{name}.png"))
        open(os.path.join(out, f"keys_{name}.txt"), "w").write(
            "SwiftKey\n" + rows_txt(S[name]["keys"]) + "\n\nKud Keys\n" + rows_txt(K[name]["keys"]) + "\n")
        la, lb = S[name].get("longpress") or [], K[name].get("longpress") or []
        if not (la or lb):
            continue
        used, pairs = set(), []
        for e in la:
            m = next((f for f in lb if norm(f["label"]) == norm(e["label"]) and id(f) not in used), None)
            if not m:
                cx, cy = (e["b"][0] + e["b"][2]) / 2, (e["b"][1] + e["b"][3]) / 2
                m = min((f for f in lb if id(f) not in used),
                        key=lambda f: abs((f["b"][0] + f["b"][2]) / 2 - cx) + abs((f["b"][1] + f["b"][3]) / 2 - cy),
                        default=None)
            if m:
                used.add(id(m))
            if e.get("popup") or (m and m.get("popup")):
                pairs.append((e, m))
        for f in lb:
            if id(f) not in used and f.get("popup"):
                pairs.append((None, f))
        tiles = []
        for e, f in pairs:
            ia = Image.open(fix(e["png"], sk_dir)).convert("RGB") if e else Image.new("RGB", a.size, "gray")
            ib = Image.open(fix(f["png"], kud_dir)).convert("RGB") if f else Image.new("RGB", b.size, "gray")
            t = top
            for x in (e, f):
                if x and x.get("diff_bbox"):
                    t = min(t, x["diff_bbox"][1] - 10)
            cap = f"{norm(e['label']) if e else '-'}  |  {norm(f['label']) if f else '-'}"
            tiles.append(pair(ia, ib, t, caption=cap))
        if not tiles:
            continue
        scale = 0.5
        tw = int(max(t.width for t in tiles) * scale)
        cols = 2
        rows = (len(tiles) + cols - 1) // cols
        th = int(max(t.height for t in tiles) * scale)
        sheet = Image.new("RGB", (cols * (tw + 16), rows * (th + 16)), "white")
        for i, t in enumerate(tiles):
            sheet.paste(t.resize((int(t.width * scale), int(t.height * scale))), ((i % cols) * (tw + 16), (i // cols) * (th + 16)))
        sheet.save(os.path.join(out, f"lp_{name}.png"))
    for name in K:
        if name not in S:
            print("only Kud Keys has page", name)


if __name__ == "__main__":
    args = sys.argv[1:]
    opts = {}
    while len(args) > 1 and args[-2].startswith("--"):
        opts[args[-2][2:]] = args[-1]
        args = args[:-2]
    main(args[0], opts.get("sk"), opts.get("kud"), opts.get("out"))
