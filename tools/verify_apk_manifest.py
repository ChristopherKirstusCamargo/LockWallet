#!/usr/bin/env python3
"""Inspect the compiled AndroidManifest of a built APK (spec §16, test #15; v2.2 SEC-08/SEC-09).
Fails (exit 1) unless: allowBackup=false, dataExtractionRules set, usesCleartextTraffic=false,
networkSecurityConfig set, not debuggable (release), only allowlisted permissions, and the launcher
activity is the only exported component."""
import argparse, re, subprocess, sys

ALLOWED_PERMISSION_SUFFIXES = (".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",  # androidx.core signature perm
                               "android.permission.USE_BIOMETRIC", "android.permission.HIDE_OVERLAY_WINDOWS",
                               "android.permission.INTERNET",  # HTTPS only (NSC: cleartext off)
                               "com.solanamobile.seedvault.ACCESS_SEED_VAULT",
                               "android.permission.CAMERA")  # Phase H: QR, runtime-requested at use  # Phase G: runtime-granted, asked at use
EXPECTED_EXPORTED = {"com.lockwallet.app.MainActivity", "com.lockwallet.app.connect.LockConnectActivity"}  # ADR 0022

ap = argparse.ArgumentParser()
ap.add_argument("--aapt2", required=True)
ap.add_argument("--apk", required=True)
ap.add_argument("--release", action="store_true")
a = ap.parse_args()
tree = subprocess.run([a.aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", a.apk],
                      check=True, capture_output=True, text=True).stdout

problems = []
def attr(block, name):
    m = re.search(r'A: http://schemas.android.com/apk/res/android:%s\(0x[0-9a-f]+\)=([^\s]+)' % name, block)
    return m.group(1) if m else None

app = tree.split("E: application", 1)[1]
app_attrs = app.split("E: ", 1)[0]
if attr(app_attrs, "allowBackup") not in ("false", "(type 0x12)0x0"): problems.append("allowBackup is not false")
if not attr(app_attrs, "dataExtractionRules"): problems.append("dataExtractionRules missing")
if attr(app_attrs, "usesCleartextTraffic") not in ("false", "(type 0x12)0x0"): problems.append("usesCleartextTraffic is not false")
if not attr(app_attrs, "networkSecurityConfig"): problems.append("networkSecurityConfig missing")
if a.release and attr(app_attrs, "debuggable") not in (None, "false", "(type 0x12)0x0"): problems.append("release is debuggable")

perms = re.findall(r'E: uses-permission.*?\n\s+A: http://schemas.android.com/apk/res/android:name\(0x[0-9a-f]+\)="([^"]+)"', tree)
for p in perms:
    if not p.endswith(ALLOWED_PERMISSION_SUFFIXES): problems.append("unexpected permission " + p)

exported = set()
for kind in ("activity", "activity-alias", "service", "receiver", "provider"):
    for blk in re.split(r"\n\s+E: ", tree):
        if not blk.startswith(kind + " "): continue
        head = blk.split("\n      E:")[0]
        name = re.search(r'android:name\(0x[0-9a-f]+\)="([^"]+)"', head)
        exp = attr(head, "exported")
        if exp in ("true", "(type 0x12)0xffffffff"):
            exported.add(name.group(1) if name else "?")
if exported != EXPECTED_EXPORTED: problems.append("exported components %s != %s" % (sorted(exported), sorted(EXPECTED_EXPORTED)))

if problems:
    print("APK manifest verification FAILED:\n  " + "\n  ".join(problems)); sys.exit(1)
print("APK manifest OK: backup off, extraction rules, cleartext off, NSC, permissions=%s, exported=%s" % (perms, sorted(exported)))
