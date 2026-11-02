#!/usr/bin/env python3
"""SwiftKey vs Kud Keys on an emulator. Research only (.github/workflows/swiftkey-compare.yaml).

Types the cases in cases.py with `adb shell input tap` on each keyboard, into the TypeTest app's plain
EditText, and records the suggestion strip (crop + accessibility text), the field text and timings.
Output: $OUT/{setup,<kb>}/..., $OUT/results.json, $OUT/log.txt.
"""
import json, os, re, shlex, subprocess, sys, time
import xml.etree.ElementTree as ET
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cases import ADDON_CASES, CASES, SPEED_SENTENCE  # noqa: E402

OUT = os.path.abspath(os.environ.get("OUT", "compare-out"))
APKS = os.environ.get("APKS", "apks")
ONLY = os.environ.get("ONLY_CASES", "")  # comma list of case ids, for quick iterations
os.makedirs(OUT, exist_ok=True)
LOG = open(os.path.join(OUT, "log.txt"), "a", buffering=1)

KUD_PKG = "com.kudcrafts.kudkeys.debug"
KUD_IME = KUD_PKG + "/helium314.keyboard.latin.LatinIME"
SK_PKG = "com.touchtype.swiftkey"
TT_PKG = "com.kudcrafts.typetest"


def log(*a):
    s = time.strftime("%H:%M:%S ") + " ".join(str(x) for x in a)
    print(s, flush=True)
    LOG.write(s + "\n")


def run(cmd, timeout=120, check=False):
    try:
        p = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        log("TIMEOUT", cmd)
        return ""
    if check and p.returncode:
        raise RuntimeError(f"{cmd}: {p.stdout}{p.stderr}")
    return p.stdout + (p.stderr if p.returncode else "")


def sh(c, timeout=60):
    return run("adb shell " + shlex.quote(c), timeout)


def screencap(path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    run(f"adb exec-out screencap -p > {shlex.quote(path)}", 30)
    return path


# ---------- accessibility dump (all windows, IME included) via uiautomator2 ----------
U2 = None


def u2():
    global U2
    if U2 is None:
        try:
            import uiautomator2
            U2 = uiautomator2.connect()
            U2.dump_hierarchy()
            log("uiautomator2 ok")
        except Exception as e:  # noqa
            log("uiautomator2 unavailable:", repr(e)[:300])
            U2 = False
    return U2 or None


def dump(path=None):
    d = u2()
    xml = ""
    if d:
        try:
            xml = d.dump_hierarchy()
        except Exception as e:  # noqa
            log("dump failed", repr(e)[:200])
    if not xml:
        run("adb shell uiautomator dump /sdcard/ui.xml", 30)
        xml = run("adb exec-out cat /sdcard/ui.xml", 30)
    if path:
        open(path, "w").write(xml)
    return xml


def nodes(xml):
    out = []
    try:
        root = ET.fromstring(xml)
    except Exception:
        return out
    for n in root.iter("node"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if not m:
            continue
        b = tuple(int(x) for x in m.groups())
        out.append({"text": n.get("text", ""), "desc": n.get("content-desc", ""), "pkg": n.get("package", ""),
                    "cls": n.get("class", ""), "rid": n.get("resource-id", ""), "click": n.get("clickable") == "true",
                    "b": b})
    return out


def tap(x, y):
    sh(f"input tap {int(x)} {int(y)}")


def tap_node(n):
    b = n["b"]
    tap((b[0] + b[2]) / 2, (b[1] + b[3]) / 2)


def find(ns, pattern, avoid=r"sign ?in|microsoft account|google|log ?in|create account"):
    rx = re.compile(pattern, re.I)
    av = re.compile(avoid, re.I)
    for n in ns:
        label = (n["text"] or n["desc"]).strip()
        if label and rx.search(label) and not av.search(label):
            return n
    return None


# ---------- field text, read from TypeTest's logcat lines ----------
def field_text():
    out = run("adb logcat -d -s TT:I", 30)
    last = None
    for line in out.splitlines():
        i = line.find("TT|")
        if i >= 0:
            parts = line[i:].split("|", 2)
            if len(parts) == 3:
                last = parts[2]
    if last in (None, "<cleared>"):
        return ""
    return last.replace("\\n", "\n")


def field_events():
    out = run("adb logcat -d -s TT:I", 30)
    ev = []
    for line in out.splitlines():
        i = line.find("TT|")
        if i >= 0:
            parts = line[i:].split("|", 2)
            if len(parts) == 3:
                ev.append((int(parts[1]), parts[2]))
    return ev


def dismiss_dialogs():
    """System 'isn't responding' dialogs (slow emulator boot) cover the keyboard: press Wait."""
    for _ in range(3):
        ns = nodes(dump())
        if not any("isn't responding" in (n["text"] or "") for n in ns):
            return
        w = find(ns, r"^wait$")
        log("ANR dialog:", [n["text"] for n in ns if "responding" in (n["text"] or "")])
        if w:
            tap_node(w)
        else:
            sh("input keyevent BACK")
        time.sleep(1.5)


def focused():
    return TT_PKG in sh("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")


def open_field():
    sh(f"am start -n {TT_PKG}/.MainActivity")
    time.sleep(2)


def clear_field():
    run("adb logcat -c")
    sh(f"am broadcast -a {TT_PKG}.CLEAR -n {TT_PKG}/.ClearReceiver")
    time.sleep(1.0)


def ime_frame():
    out = sh("dumpsys window InputMethod")
    open(os.path.join(OUT, "dumpsys_window_ime.txt"), "a").write(out + "\n----\n")
    m = re.search(r"\bframe=\[(\d+),(\d+)\]\[(\d+),(\d+)\]", out)
    t = re.search(r"touchable region=SkRegion\(\((\d+),(\d+),(\d+),(\d+)\)", out)
    return (tuple(int(x) for x in m.groups()) if m else None, tuple(int(x) for x in t.groups()) if t else None)


def screen_size():
    m = re.search(r"(\d+)x(\d+)", sh("wm size"))
    return int(m.group(1)), int(m.group(2))


# ---------- setup ----------
def install_all():
    for apk in sorted(os.listdir(APKS)):
        p = os.path.join(APKS, apk)
        if apk.endswith(".apk"):
            log("install", apk, run(f"adb install -r -g {shlex.quote(p)}", 300).strip()[-200:])
    sk = os.path.join(APKS, "swiftkey")
    if os.path.isdir(sk):
        splits = [os.path.join(sk, f) for f in sorted(os.listdir(sk)) if f.endswith(".apk")]
        if splits:
            r = run("adb install-multiple -r -g " + " ".join(shlex.quote(s) for s in splits), 600)
            log("install swiftkey splits", len(splits), r.strip()[-300:])
            if "Success" not in r:
                # retry with base + the splits that match this device
                keep = [s for s in splits if not re.search(r"config\.(armeabi|x86(?!_64)|mips)", s)]
                keep = [s for s in keep if not re.search(r"config\.(ldpi|mdpi|hdpi|xhdpi|tvdpi|xxxhdpi)\.apk", s)]
                r = run("adb install-multiple -r -g " + " ".join(shlex.quote(s) for s in keep), 600)
                log("retry swiftkey splits", len(keep), r.strip()[-300:])
    log("ime list", sh("ime list -a -s"))


def ime_id(pkg):
    for line in sh("ime list -a -s").split():
        if line.startswith(pkg + "/"):
            return line.strip()
    return None


def select_ime(ime):
    sh(f"ime enable {ime}")
    sh(f"ime set {ime}")
    time.sleep(1)
    log("current ime", sh("settings get secure default_input_method").strip())


def explore(tag, steps, accept, stop=None, max_steps=25):
    """Screenshot + dump each screen; tap the first match from `accept`. Returns the last node list."""
    dismiss_dialogs()
    d = os.path.join(OUT, "setup")
    os.makedirs(d, exist_ok=True)
    ns = []
    idle = 0
    last = []
    for i in range(max_steps):
        time.sleep(2)
        xml = dump(os.path.join(d, f"{tag}_{steps[0]:02d}.xml"))
        screencap(os.path.join(d, f"{tag}_{steps[0]:02d}.png"))
        ns = nodes(xml)
        labels = [n["text"] or n["desc"] for n in ns if (n["text"] or n["desc"])]
        log(tag, steps[0], "|", " · ".join(labels)[:600])
        steps[0] += 1
        if stop and find(ns, stop):
            log(tag, "stop pattern found")
            return ns
        hit = None
        for pat in accept:
            hit = find(ns, pat)
            if hit:
                break
        if not hit:
            idle += 1
            if idle >= 2:
                return ns
            continue
        idle = 0
        label = hit["text"] or hit["desc"]
        last.append(label)
        if last[-3:] == [label] * 3:
            log(tag, "same tap three times; stopping:", label)
            return ns
        log(tag, "tap", repr(label))
        tap_node(hit)
    return ns


ACCEPT = [r"^let.s set up", r"^not now$", r"^skip$", r"^no,? thanks$", r"^maybe later$", r"^i agree$", r"^agree", r"^accept",
          r"^continue$", r"^got it$", r"^ok$", r"^next$", r"^allow$", r"^finish", r"^done$", r"^start",
          r"^enable swiftkey$", r"^select swiftkey$", r"^try it", r"^close$", r"^dismiss$"]


def setup_swiftkey(ime):
    step = [0]
    select_ime(ime)  # enabled and selected first, so onboarding skips its "enable" steps
    sh(f"monkey -p {SK_PKG} -c android.intent.category.LAUNCHER 1")
    time.sleep(5)
    explore("sk_onboard", step, ACCEPT, stop=r"^languages$")
    # back in our field with SwiftKey up: accept whatever the keyboard itself asks
    select_ime(ime)
    open_field()
    ns = explore("sk_inkb", step, [r"^change$"], max_steps=3)
    if not find(ns, r"indonesian|all languages|languages"):
        explore("sk_inkb", step, [r"^continue$", r"^got it$", r"^ok$", r"^accept", r"^agree", r"^not now$",
                                  r"^no,? thanks$", r"^dismiss$", r"^close$"], max_steps=6)
        sh(f"monkey -p {SK_PKG} -c android.intent.category.LAUNCHER 1")
        time.sleep(4)
        ns = explore("sk_settings", step, ACCEPT, stop=r"^languages$", max_steps=10)
        n = find(ns, r"^languages$")
        if not n:
            log("SwiftKey: no Languages entry")
            return
        tap_node(n)
        time.sleep(3)
    languages()
    select_ime(ime)


def languages():
    ns = nodes(dump(os.path.join(OUT, "setup", "sk_lang_main.xml")))
    add = find(ns, r"^add languages?$")
    if add:
        tap_node(add)
        time.sleep(4)
    for i in range(70):
        xml = dump(os.path.join(OUT, "setup", f"sk_lang_{i:02d}.xml"))
        ns = nodes(xml)
        if i % 5 == 0:
            screencap(os.path.join(OUT, "setup", f"sk_lang_{i:02d}.png"))
        n = find(ns, r"^(indonesian|bahasa indonesia)")
        if n:
            screencap(os.path.join(OUT, "setup", "sk_lang_found.png"))
            log("tap", n["text"] or n["desc"], n["b"])
            tap_node(n)
            break
        w, h = screen_size()
        sh(f"input swipe {w//2} {int(h*0.85)} {w//2} {int(h*0.25)} 300")
        time.sleep(1.2)
    # let the language pack download
    for i in range(12):
        time.sleep(5)
        xml = dump(os.path.join(OUT, "setup", f"sk_lang_dl_{i:02d}.xml"))
        ns = nodes(xml)
        screencap(os.path.join(OUT, "setup", f"sk_lang_dl_{i:02d}.png"))
        hit = find(ns, r"^(ok|got it|continue|download|enable)$")
        if hit:
            log("lang dialog tap", hit["text"] or hit["desc"])
            tap_node(hit)
    # scroll back to the top: record the enabled languages
    for _ in range(15):
        w, h = screen_size()
        sh(f"input swipe {w//2} {int(h*0.3)} {w//2} {int(h*0.85)} 300")
    time.sleep(1)
    dump(os.path.join(OUT, "setup", "sk_lang_final.xml"))
    screencap(os.path.join(OUT, "setup", "sk_lang_final.png"))
    sh("input keyevent BACK")
    time.sleep(1)
    sh("input keyevent BACK")


# ---------- calibration ----------
def calibrate(kb):
    dismiss_dialogs()
    km = calibrate_once(kb)
    if "r1" not in km:
        log(kb, "calibration found no row 1; retrying")
        dismiss_dialogs()
        open_field()
        km = calibrate_once(kb)
    return km


def calibrate_once(kb):
    """Tap scans find the key rows and row-1 columns; returns the key map."""
    d = os.path.join(OUT, kb)
    os.makedirs(d, exist_ok=True)
    W, H = screen_size()
    clear_field()
    tap(W // 2, 300)  # focus
    time.sleep(1.5)
    frame, touch = ime_frame()
    screencap(os.path.join(d, "keyboard.png"))
    dump(os.path.join(d, "keyboard.xml"))
    log(kb, "ime frame", frame, "touchable", touch)
    top = (touch or frame or (0, int(H * 0.55), W, H))[1]
    bottom = (touch or frame or (0, 0, W, H))[3]
    # vertical scan at x = 0.52 W, bottom-up, until a few taps above row 1
    x = int(W * 0.52)
    samples = []
    clear_field()
    prev = ""
    seen_r1 = 0
    above = 0
    for y in range(bottom - 6, top, -14):
        tap(x, y)
        time.sleep(0.35)
        t = field_text()
        delta = t[len(prev):] if t.startswith(prev) else "<" + t + ">"
        if delta == "" and not focused():
            log(kb, "lost focus at y", y)
            open_field()
            clear_field()
            t = ""
        samples.append((y, delta))
        prev = t
        if len(delta) == 1 and delta.lower() in "qwertyuiop":
            seen_r1 += 1
        elif seen_r1:
            above += 1
            if above >= 3:
                break
        if len(t) > 25:
            clear_field()
            prev = ""
    rows = {"r1": set("qwertyuiop"), "r2": set("asdfghjkl"), "r3": set("zxcvbnm"), "r4": {" "}}
    km = {"W": W, "H": H, "top": top, "bottom": bottom, "yscan": samples}
    for r, letters in rows.items():
        ys = [y for y, dl in samples if len(dl) == 1 and dl.lower() in letters]
        if r == "r4" and "r3_span" in km:
            ys = [y for y in ys if y > km["r3_span"][1]]
        if ys:
            km[r] = (min(ys) + max(ys)) // 2
            km[r + "_span"] = (min(ys), max(ys))
    words = [y for y, dl in samples if len(dl.strip()) > 1 and "r1" in km and y < km["r1"]]
    if words:
        km["strip"] = (min(words) + max(words)) // 2
    # horizontal scan of row 1
    if "r1" in km:
        clear_field()
        prev = ""
        xs = []
        for xx in range(6, W - 4, 18):
            tap(xx, km["r1"])
            time.sleep(0.3)
            t = field_text()
            delta = t[len(prev):] if t.startswith(prev) else "<" + t + ">"
            xs.append((xx, delta))
            prev = t
            if len(t) > 25:
                clear_field()
                prev = ""
        log(kb, "xscan", xs)
        cols = {}
        for xx, dl in xs:
            if len(dl) == 1 and dl.lower() in "qwertyuiop":
                cols.setdefault(dl.lower(), []).append(xx)
        km["cols"] = {k: (min(v) + max(v)) // 2 for k, v in cols.items()}
        if len(km["cols"]) >= 8:
            order = "qwertyuiop"
            pts = [(order.index(k), v) for k, v in km["cols"].items()]
            n = len(pts)
            mx = sum(p[0] for p in pts) / n
            my = sum(p[1] for p in pts) / n
            kw = sum((p[0] - mx) * (p[1] - my) for p in pts) / sum((p[0] - mx) ** 2 for p in pts)
            km["kw"] = kw
            km["left"] = my - mx * kw - kw / 2
    return km


def keymap(km, override):
    km = dict(km)
    km.update(override or {})
    W = km["W"]
    kw = km.get("kw", W / 10)
    left = km.get("left", 0)
    pos = {}
    for i, c in enumerate("qwertyuiop"):
        pos[c] = (left + (i + 0.5) * kw, km["r1"])
    for i, c in enumerate("asdfghjkl"):
        pos[c] = (left + (i + 1.0) * kw, km["r2"])
    for i, c in enumerate("zxcvbnm"):
        pos[c] = (left + (i + 2.0) * kw, km["r3"])
    pos[" "] = (left + km.get("space_f", 0.525) * kw * 10, km["r4"])
    pos[","] = (left + km.get("comma_f", 0.25) * kw * 10, km["r4"])
    pos["."] = (left + km.get("period_f", 0.80) * kw * 10, km["r4"])
    pitch = km["r2"] - km["r1"]
    strip_bottom = int(km["r1"] - pitch * 0.5)
    strip = km.get("strip_y", strip_bottom - 55)
    slots = km.get("slots_f", (0.2, 0.5, 0.8))
    pos["<l>"] = (W * slots[0], strip)
    pos["<c>"] = (W * slots[1], strip)
    pos["<r>"] = (W * slots[2], strip)
    km["strip"] = strip
    km["strip_box"] = km.get("strip_box", (0, strip - 75, W, strip_bottom))
    return pos, km


def type_seq(pos, s):
    for ch in s:
        if ch in pos:
            tap(*pos[ch])


def verify(kb, pos):
    clear_field()
    type_seq(pos, "qwertyuiopasdfghjklzxcvbnm")
    time.sleep(0.8)
    a = field_text()
    clear_field()
    type_seq(pos, "a,b.c")
    time.sleep(0.8)
    b = field_text()
    ok = a.lower() == "qwertyuiopasdfghjklzxcvbnm" and "," in b and "." in b
    log(kb, "verify", repr(a), repr(b), "OK" if ok else "MISMATCH")
    return {"alphabet": a, "punct": b, "ok": ok}


# ---------- cases ----------
def strip_texts(ns, km, ime_pkg):
    x0, y0, x1, y1 = km["strip_box"]
    out = []
    for n in ns:
        b = n["b"]
        if n["pkg"] != ime_pkg:
            continue
        label = n["text"] or n["desc"]
        if not label:
            continue
        cy = (b[1] + b[3]) / 2
        if y0 - 40 <= cy <= y1 + 20:
            out.append({"t": label, "x": (b[0] + b[2]) // 2, "w": b[2] - b[0], "rid": n["rid"].split("/")[-1]})
    out.sort(key=lambda e: e["x"])
    return out


def snapshot(kb, case_id, k, km, ime_pkg):
    d = os.path.join(OUT, kb, "cases")
    os.makedirs(d, exist_ok=True)
    time.sleep(0.7)
    t0 = time.time()
    full = screencap(os.path.join(d, f"{case_id}_{k}_full.png"))
    try:
        im = Image.open(full)
        im.crop(km["strip_box"]).save(os.path.join(d, f"{case_id}_{k}_strip.png"))
    except Exception as e:  # noqa
        log("crop failed", e)
    xml = dump(os.path.join(d, f"{case_id}_{k}.xml"))
    return {"k": k, "field": field_text(), "strip": strip_texts(nodes(xml), km, ime_pkg),
            "capture_s": round(time.time() - t0, 2)}


def run_cases(kb, pos, km, ime_pkg, only=None):
    res = []
    for cid, kind, dsl in CASES:
        if ONLY and cid not in ONLY.split(","):
            continue
        if only is not None and cid not in only:
            continue
        clear_field()
        snaps = []
        typed = ""
        k = 0
        i = 0
        t_start = time.time()
        while i < len(dsl):
            ch = dsl[i]
            if ch == "|":
                s = snapshot(kb, cid, k, km, ime_pkg)
                s["typed"] = typed
                snaps.append(s)
                k += 1
            elif ch == "<":
                j = dsl.index(">", i)
                tok = dsl[i:j + 1]
                tap(*pos[tok])
                typed += tok
                i = j
            else:
                tap(*pos[ch])
                typed += ch
            i += 1
        time.sleep(0.6)
        final = field_text()
        r = {"id": cid, "kind": kind, "dsl": dsl, "snaps": snaps, "final": final,
             "secs": round(time.time() - t_start, 1)}
        log(kb, cid, "final", repr(final), "|", " / ".join(
            "[" + ", ".join(e["t"] for e in s["strip"]) + "]→" + repr(s["field"]) for s in snaps))
        res.append(r)
    return res


def speed_run(kb, pos, km):
    """Type one sentence as fast as adb allows while recording the screen; field change timestamps logged."""
    d = os.path.join(OUT, kb)
    clear_field()
    cmds = "; ".join(f"input tap {int(pos[c][0])} {int(pos[c][1])}" for c in SPEED_SENTENCE if c in pos)
    rec = subprocess.Popen("adb shell screenrecord --time-limit 40 --bit-rate 6000000 /sdcard/speed.mp4",
                           shell=True)
    time.sleep(1.5)
    t0 = time.time()
    sh(cmds, timeout=120)
    typing_s = time.time() - t0
    time.sleep(2.5)
    rec.terminate()
    run("adb shell pkill -INT screenrecord")
    time.sleep(2)
    run(f"adb pull /sdcard/speed.mp4 {shlex.quote(os.path.join(d, 'speed.mp4'))}", 60)
    ev = field_events()
    log(kb, "speed typing", round(typing_s, 1), "s; final", repr(field_text()))
    return {"typing_s": round(typing_s, 2), "events": ev, "final": field_text()}


def kud_addon_pass(pos, km):
    """Fresh Kud Keys data + the synthetic add-on dict in the Indonesian dictionary dir, then the ADDON_CASES."""
    src = os.path.join(APKS, "synthetic", "swiftkey_user.dict")
    if not os.path.exists(src):
        log("no synthetic dict; skipping add-on pass")
        return None
    sh(f"pm clear {KUD_PKG}")
    select_ime(KUD_IME)
    open_field()
    clear_field()
    tap(km["W"] // 2, 300)
    time.sleep(3)
    listing = sh(f"run-as {KUD_PKG} sh -c 'ls -la /data/user_de/0/{KUD_PKG}/files/dicts/ /data/user_de/0/{KUD_PKG}/files/dicts/*; ls -la files/dicts/ 2>&1'")
    log("kud dict dirs before:", listing)
    run(f"adb push {shlex.quote(src)} /data/local/tmp/swiftkey_user.dict")
    sh("chmod 644 /data/local/tmp/swiftkey_user.dict")
    dirs = sorted(set(re.findall(r"\b(id|in|in_ID|id_ID|id-ID)\b", listing))) or ["id"]
    for base in (f"/data/user_de/0/{KUD_PKG}/files/dicts", f"/data/user/0/{KUD_PKG}/files/dicts"):
        if "No such file" in sh(f"run-as {KUD_PKG} ls {base}"):
            continue
        for d in dirs:
            sh(f"run-as {KUD_PKG} sh -c 'mkdir -p {base}/{d} && cp /data/local/tmp/swiftkey_user.dict {base}/{d}/swiftkey_user.dict'")
        log("kud dict dir after:", base, sh(f"run-as {KUD_PKG} ls -laR {base}"))
    sh(f"am force-stop {KUD_PKG}")
    time.sleep(1)
    select_ime(KUD_IME)
    open_field()
    clear_field()
    tap(km["W"] // 2, 300)
    time.sleep(3)
    return {"cases": run_cases("kudkeys_addon", pos, km, KUD_PKG, only=ADDON_CASES)}


def main():
    phase = sys.argv[1] if len(sys.argv) > 1 else "all"
    overrides = {}
    ov_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "layout.json")
    if os.path.exists(ov_path):
        overrides = json.load(open(ov_path))
    run("adb wait-for-device")
    sh("settings put secure show_ime_with_hard_keyboard 1")
    sh("cmd uimode night yes")
    sh("settings put system screen_off_timeout 1800000")
    sh("svc power stayon true")
    sh("input keyevent WAKEUP")
    sh("settings put global hide_error_dialogs 1")
    time.sleep(20)  # let the launcher and system settle after a cold boot
    install_all()
    results = {"device": {"size": sh("wm size").strip(), "density": sh("wm density").strip(),
                          "sdk": sh("getprop ro.build.version.sdk").strip()},
               "versions": {}}
    for pkg in (KUD_PKG, SK_PKG):
        m = re.search(r"versionName=(\S+)", sh(f"dumpsys package {pkg}"))
        results["versions"][pkg] = m.group(1) if m else None
    log("versions", results["versions"])
    sk_ime = None
    if results["versions"].get(SK_PKG):
        sh(f"monkey -p {SK_PKG} -c android.intent.category.LAUNCHER 1")
        for _ in range(20):
            time.sleep(3)
            sk_ime = ime_id(SK_PKG)
            if sk_ime:
                break
        log("swiftkey ime", sk_ime, "| ime list:", sh("ime list -a -s"))
        sk_ime = sk_ime or SK_PKG + "/com.touchtype.KeyboardService"
    kbs = []
    if sk_ime:
        setup_swiftkey(sk_ime)
        kbs.append(("swiftkey", sk_ime, SK_PKG))
    else:
        log("SwiftKey not installed; Kud Keys only")
    kbs.insert(0, ("kudkeys", KUD_IME, KUD_PKG))
    for kb, ime, pkg in kbs:
        try:
            select_ime(ime)
            open_field()
            dismiss_dialogs()
            km = calibrate(kb)
            pos, km = keymap(km, overrides.get(kb))
            log(kb, "keymap", json.dumps({k: v for k, v in km.items() if k not in ("yscan",)}))
            kr = {"keymap": {k: v for k, v in km.items()}, "pos": pos, "verify": verify(kb, pos)}
            if phase in ("all", "cases"):
                kr["cases"] = run_cases(kb, pos, km, pkg)
                kr["speed"] = speed_run(kb, pos, km)
            results[kb] = kr
            if kb == "kudkeys" and phase in ("all", "cases"):
                results["kudkeys_addon"] = kud_addon_pass(pos, km)
        except Exception as e:  # noqa
            import traceback
            log(kb, "FAILED", traceback.format_exc())
            results[kb] = {"error": repr(e)}
        json.dump(results, open(os.path.join(OUT, "results.json"), "w"), indent=1, ensure_ascii=False)
    log("done")


if __name__ == "__main__":
    main()
