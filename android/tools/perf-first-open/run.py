#!/usr/bin/env python3
"""Research only (perf/instant-first-open): before/after Perfetto traces of the keyboard opening on an emulator.

For each APK in $APKS/{before,after}.apk: install, select the IME, one throw-away open, then N first opens (IME
process force-stopped and restarted by `ime set`, keyboard never shown) and N warm opens (home -> TypeTest), plus
N "after a private field" opens (Settings search, which is a private field, -> TypeTest). Traces and the IME's log
lines go to $OUT/<variant>/<kind>-<i>.{pftrace,log}."""
import os, subprocess, sys, time

APKS = os.environ.get("APKS", "apks")
OUT = os.environ.get("OUT", "perf-out")
N = int(os.environ.get("N", "6"))
PKG = "com.kudcrafts.kudkeys.debug"
IME = PKG + "/helium314.keyboard.latin.LatinIME"
TT = "com.kudcrafts.typetest/.MainActivity"
HERE = os.path.dirname(os.path.abspath(__file__))


def sh(cmd, check=False):
    r = subprocess.run(["adb", "shell", cmd], capture_output=True, text=True)
    if check and r.returncode:
        raise RuntimeError(f"{cmd}: {r.stderr}")
    return r.stdout


def adb(*a):
    return subprocess.run(["adb", *a], capture_output=True, text=True).stdout


def home():
    sh("am start -a android.intent.action.MAIN -c android.intent.category.HOME")


def ime_ok():
    return IME in sh("dumpsys input_method | grep mCurId")


def select_ime():
    for _ in range(5):
        sh(f"ime enable {IME}")
        sh(f"ime set {IME}")
        time.sleep(4)
        if ime_ok():
            return True
    return False


def trace(name, opener):
    sh("logcat -c")
    sh(f"perfetto --txt -c /data/local/tmp/kcfg.txt -o /data/misc/perfetto-traces/{name}.pftrace --background")
    time.sleep(1)
    opener()
    time.sleep(8)
    shown = "mInputShown=true" in sh("dumpsys input_method")
    adb("pull", f"/data/misc/perfetto-traces/{name}.pftrace", os.path.join(OUT, name + ".pftrace"))
    sh(f"rm /data/misc/perfetto-traces/{name}.pftrace")
    log = sh("logcat -d -s KeyboardSwitcher:V KudWarmup:V LatinIME:I Settings:I InputMethodService:I")
    with open(os.path.join(OUT, name + ".log"), "w") as f:
        f.write(f"ime={ime_ok()} shown={shown}\n" + log)
    print(name, "ime ok" if ime_ok() else "IME NOT SELECTED", "shown" if shown else "NOT SHOWN", flush=True)


def open_tt():
    sh(f"am start -S -W -n {TT}")


def open_private():
    # Settings search: a search field (private: incognito look)
    sh("am start -W -a android.settings.APP_SEARCH_SETTINGS")


def run_variant(v):
    apk = os.path.join(APKS, v + ".apk")
    adb("uninstall", PKG)
    print(adb("install", "-r", "-g", apk), flush=True)
    sh(f"cmd package compile -m speed -f {PKG}")
    if not select_ime():
        print("could not select the IME", flush=True)
    os.makedirs(os.path.join(OUT, v), exist_ok=True)
    home(); time.sleep(2); open_tt(); time.sleep(6); home(); time.sleep(2)  # throw-away first open (dictionaries etc.)
    for i in range(N):
        home(); time.sleep(1)
        sh(f"am force-stop {PKG}"); time.sleep(2)
        select_ime(); time.sleep(4)  # IME process starting; KudWarmup runs here in "after"
        trace(f"{v}/first-{i}", open_tt)
    for i in range(N):
        home(); time.sleep(3)
        trace(f"{v}/warm-{i}", open_tt)
    for i in range(N):
        home(); time.sleep(1)
        open_private(); time.sleep(4)
        if i == 0:
            with open(os.path.join(OUT, v, "private-field.png"), "wb") as f:
                f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout)
        trace(f"{v}/afterprivate-{i}", open_tt)
    adb("uninstall", PKG)


def main():
    os.makedirs(OUT, exist_ok=True)
    adb("root"); time.sleep(3); adb("wait-for-device")
    sh("setprop persist.traced.enable 1"); time.sleep(2)
    adb("push", os.path.join(HERE, "cfg.txt"), "/data/local/tmp/kcfg.txt")
    adb("install", "-r", os.path.join(APKS, "typetest.apk"))
    for v in sys.argv[1:] or ["before", "after"]:
        run_variant(v)


if __name__ == "__main__":
    main()
