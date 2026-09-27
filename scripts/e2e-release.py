#!/usr/bin/env python3
"""End-to-end check of a *release* (R8-minified) APK on the emulator, driven like a user through UI Automator.

Robolectric tests run unminified code, so they can't catch what R8 breaks (serializers, navigation routes,
reflection). This script installs the release APK, signs in to the disposable test NPM, visits every screen,
creates / disables / deletes a proxy host through the UI (verified against the NPM API), restarts the app to
check the stored session, and fails on any crash.

Usage (emulator running: scripts/emulator.sh start; test NPM running: scripts/dev-npm.sh up):
    ./gradlew :app:assembleRelease
    python3 scripts/e2e-release.py [path/to/app-release.apk]
Screenshots go to build/e2e/.
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

PKG = "io.github.revocx35.npmmobile"
SDK = os.environ.get("ANDROID_HOME", "/opt/android-sdk")
ADB = os.path.join(SDK, "platform-tools", "adb")
NPM_HOST_URL = os.environ.get("NPM_IT_URL", "http://127.0.0.1:18081")  # as seen from this machine
NPM_APP_URL = NPM_HOST_URL.replace("127.0.0.1", "10.0.2.2").replace("localhost", "10.0.2.2")  # from the emulator
EMAIL = os.environ.get("NPM_IT_EMAIL", "admin@test.local")
PASSWORD = os.environ.get("NPM_IT_PASSWORD", "TestPassw0rd!")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "build", "e2e")
steps = 0


def adb(*args, check=True, capture=True):
    r = subprocess.run([ADB, *args], capture_output=capture, text=capture)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {r.stderr if capture else ''}")
    return r.stdout if capture else ""


def step(msg):
    global steps
    steps += 1
    print(f"[{steps:02d}] {msg}", flush=True)


# ---- UI Automator helpers -----------------------------------------------------------------------------

def dump():
    """The current UI tree. While windows are switching UI Automator can return "null root node": that's treated
    as an empty screen, so the callers' timeouts decide when to give up."""
    out = adb("exec-out", "uiautomator", "dump", "/dev/tty", check=False)
    start = out.find("<?xml")
    end = out.rfind("</hierarchy>")
    if start >= 0 and end > 0:
        return ET.fromstring(out[start:end + len("</hierarchy>")])
    time.sleep(1)
    return ET.fromstring("<hierarchy/>")


def nodes(root):
    return root.iter("node")


def matches(n, text=None, contains=None, rid=None, desc=None):
    if text is not None and n.get("text") != text:
        return False
    if contains is not None and contains not in (n.get("text") or "") + (n.get("content-desc") or ""):
        return False
    if rid is not None and n.get("resource-id") != rid:
        return False
    if desc is not None and n.get("content-desc") != desc:
        return False
    return True


def find(timeout=20, **kw):
    deadline = time.time() + timeout
    while True:
        check_alive()
        for n in nodes(dump()):
            if matches(n, **kw):
                return n
        if time.time() > deadline:
            shot("FAILED")
            raise AssertionError(f"not found on screen: {kw}")
        time.sleep(1)


def exists(**kw):
    return any(matches(n, **kw) for n in nodes(dump()))


def center(n):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(timeout=20, **kw):
    x, y = center(find(timeout=timeout, **kw))
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(0.8)


def type_into(value, **kw):
    tap(**kw)
    adb("shell", "input", "text", value.replace(" ", "%s"))
    time.sleep(0.5)


def key(code):
    adb("shell", "input", "keyevent", str(code))
    time.sleep(0.6)


def back():
    key(4)


def shot(name):
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, f"{steps:02d}_{name}.png"), "wb") as f:
        f.write(subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True).stdout)


def check_alive():
    crashes = adb("logcat", "-b", "crash", "-d", check=False)
    if PKG in crashes:
        print(crashes[-4000:])
        raise AssertionError("the app crashed (see the crash log above)")


def launch(expect, attempts=3):
    """Starts the app and waits for [expect]. Retried: right after `pm clear`/install, Android sometimes kills the
    freshly started process while it removes the old task ("failed to attach"), leaving the home screen."""
    for i in range(attempts):
        time.sleep(2)
        adb("shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity")
        try:
            return find(contains=expect, timeout=30)
        except AssertionError:
            if i == attempts - 1:
                raise


# ---- NPM API (to verify what the UI did) ---------------------------------------------------------------

def api(method, path, body=None, token=None):
    req = urllib.request.Request(f"{NPM_HOST_URL}/api/{path}", method=method,
                                 data=json.dumps(body).encode() if body is not None else None)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read() or b"null")


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else "app/build/outputs/apk/release/app-release.apk"
    token = api("POST", "tokens", {"identity": EMAIL, "secret": PASSWORD})["token"]
    domain = f"e2e-{int(time.time())}.it-test.invalid"
    try:
        run(apk, token, domain)
    finally:
        # Never leave test hosts behind, even when a step failed.
        for h in api("GET", "nginx/proxy-hosts", token=token):
            if domain in h["domain_names"]:
                api("DELETE", f"nginx/proxy-hosts/{h['id']}", token=token)


def run(apk, token, domain):

    step(f"install {apk}")
    adb("install", "-r", apk)
    adb("shell", "pm", "clear", PKG)  # also revokes runtime permissions, so the permission prompt is tested too
    adb("logcat", "-b", "crash", "-c", check=False)
    launch("Sign in to Nginx Proxy Manager")

    step("sign in")
    shot("login")
    type_into(NPM_APP_URL, rid="server")
    type_into(EMAIL, rid="email")
    type_into(PASSWORD, rid="password")
    back()  # hide keyboard
    tap(rid="signIn")
    if int(adb("shell", "getprop", "ro.build.version.sdk").strip()) >= 37:
        # 10.0.2.2 is a LAN address: on Android 17 the app must ask for local network access before connecting.
        step("local network permission prompt (Android 17+)")
        find(contains="nearby devices", timeout=20)  # system dialog: Android words it as "Nearby devices"
        shot("permission")
        tap(text="Allow")
    find(text="Needs attention", timeout=40)
    shot("dashboard")

    step("hosts: all four tabs")
    tap(rid="tab-Hosts")
    for kind in ["Redirection", "Stream", "Dead", "Proxy"]:
        tap(rid=f"hosttab-{kind}")
        find(rid=f"hostlist-{kind}")
    shot("hosts")

    step("create a proxy host through the editor")
    tap(rid="addHost")
    find(text="New proxy host")
    type_into(domain, rid="domainInput")
    key(66)  # enter: turns the text into a domain chip
    type_into("10.9.8.7", rid="forwardHost")
    type_into("8088", rid="forwardPort")
    back()
    for tab in ["Locations", "SSL", "Advanced", "Details"]:
        tap(rid=f"edittab-{tab}")
    shot("new_host")
    tap(rid="save")
    find(contains=domain, timeout=60)
    host = next(h for h in api("GET", "nginx/proxy-hosts", token=token) if h["domain_names"] == [domain])
    assert host["forward_host"] == "10.9.8.7" and host["forward_port"] == 8088, host
    shot("host_created")

    step("open it again, then disable it from the list")
    tap(contains=domain)
    find(text="Edit proxy host")
    back()
    tap(rid=f"hostmenu-{host['id']}")
    tap(text="Disable")  # menus are popup windows: they don't get the test-tag resource ids
    for _ in range(30):
        if not api("GET", f"nginx/proxy-hosts/{host['id']}", token=token)["enabled"]:
            break
        time.sleep(1)
    else:
        raise AssertionError("host was not disabled")

    step("delete it")
    tap(rid=f"hostmenu-{host['id']}")
    tap(text="Delete")
    find(contains="Delete proxy host?")
    tap(text="Delete")
    for _ in range(30):
        if all(h["id"] != host["id"] for h in api("GET", "nginx/proxy-hosts", token=token)):
            break
        time.sleep(1)
    else:
        raise AssertionError("host was not deleted")

    step("certificates")
    tap(rid="tab-SSL")
    find(text="SSL certificates")
    shot("certificates")
    tap(rid="addCert")
    tap(contains="Let's Encrypt")
    find(text="Test HTTP reachability")
    back()
    tap(rid="addCert")
    tap(contains="Custom certificate")
    find(contains="Pick PEM files")
    back()

    step("more: every screen")
    tap(rid="tab-More")
    for entry, marker in [
        ("Access lists", "Access lists protect proxy hosts"),
        ("Users", "Administrator"),
        ("Audit log", "Audit log"),
        ("Default site", "What NPM shows"),
        ("Servers & accounts", "Add a server"),
        ("Appearance", "System default"),
        ("Crash reports", "Reports stay on this device"),
        ("About", "Source code"),
    ]:
        tap(text=entry)
        find(contains=marker)
        shot(entry.split()[0].lower())
        back()
        find(text="More")
    tap(rid="profileCard")
    find(text="Two-factor authentication")
    shot("profile")
    back()

    step("restart the app: the stored session must survive")
    adb("shell", "am", "force-stop", PKG)
    launch("Needs attention")
    shot("restarted")

    check_alive()
    print(f"OK: {steps} steps passed, screenshots in {os.path.normpath(OUT)}")


if __name__ == "__main__":
    main()
