#!/usr/bin/env python3
"""Generate a CycloneDX 1.5 JSON SBOM for the LOCK WALLET release APK.

Input: output of `./gradlew :app:dependencies --configuration releaseRuntimeClasspath -q` (file or stdin)
       + gradle/verification-metadata.xml (sha256 of the exact artifacts Gradle verified).
Offline, deterministic, stdlib only. Usage:
  tools/sbom.py deps.txt --version 1.0.0 --apk-sha256 <hex> -o dist/LockWallet-1.0.0.cdx.json
"""
import argparse, json, re, sys, uuid, xml.etree.ElementTree as ET
from pathlib import Path

LINE = re.compile(r"^[\s|\\+`-]*([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+):?([^\s]*)(?:\s*->\s*([^\s]+))?")

def resolved(text):
    out = {}
    for raw in text.splitlines():
        if not re.match(r"^[\s|\\+`-]+", raw) or "project :" in raw:
            continue
        m = LINE.match(raw)
        if not m:
            continue
        g, a, v, arrow = m.groups()
        ver = arrow or v
        ver = ver.strip("()*c ").split("{")[0]
        if not ver or ver in ("(*)", "(c)", "(n)"):
            continue
        out[(g, a)] = ver
    return out

def hashes(meta):
    ns = {"v": "https://schema.gradle.org/dependency-verification"}
    res = {}
    root = ET.parse(meta).getroot()
    for comp in root.iterfind(".//v:components/v:component", ns):
        key = (comp.get("group"), comp.get("name"), comp.get("version"))
        for art in comp.iterfind("v:artifact", ns):
            n = art.get("name")
            s = art.find("v:sha256", ns)
            if s is None or n.endswith(("-sources.jar", "-javadoc.jar")):
                continue
            # Prefer the binary; metadata-only components (KMP roots, relocations) fall back to .module/.pom.
            rank = 0 if n.endswith((".jar", ".aar")) else 1 if n.endswith(".module") else 2
            res.setdefault(key, []).append((rank, n, s.get("value")))
    for k in res:
        res[k] = [(n, v) for _, n, v in sorted(res[k])]
    return res

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("deps", nargs="?")
    ap.add_argument("--meta", default="gradle/verification-metadata.xml")
    ap.add_argument("--version", required=True)
    ap.add_argument("--apk-sha256")
    ap.add_argument("-o", "--out", required=True)
    a = ap.parse_args()
    text = Path(a.deps).read_text() if a.deps else sys.stdin.read()
    deps = resolved(text)
    hs = hashes(a.meta)
    comps, missing = [], []
    for (g, n), v in sorted(deps.items()):
        purl = f"pkg:maven/{g}/{n}@{v}"
        c = {"type": "library", "bom-ref": purl, "group": g, "name": n, "version": v, "purl": purl}
        h = hs.get((g, n, v))
        if h:
            c["hashes"] = [{"alg": "SHA-256", "content": h[0][1]}]
        elif not n.endswith("-bom") and n != "bom":
            missing.append(purl)
        comps.append(c)
    app = {"type": "application", "bom-ref": "lockwallet-app", "name": "LockWallet", "version": a.version,
           "licenses": [{"license": {"id": "Apache-2.0"}}]}
    if a.apk_sha256:
        app["hashes"] = [{"alg": "SHA-256", "content": a.apk_sha256}]
    bom = {
        "bomFormat": "CycloneDX", "specVersion": "1.5",
        "serialNumber": "urn:uuid:" + str(uuid.uuid5(uuid.NAMESPACE_URL, f"lockwallet/{a.version}/{a.apk_sha256}")),
        "version": 1,
        "metadata": {"component": app, "tools": [{"name": "lockwallet tools/sbom.py"}]},
        "components": comps,
        "dependencies": [{"ref": "lockwallet-app", "dependsOn": [c["bom-ref"] for c in comps]}],
    }
    Path(a.out).write_text(json.dumps(bom, indent=2, sort_keys=False) + "\n")
    print(f"SBOM: {len(comps)} components, {len(comps)-len(missing)} with SHA-256; without hash: {len(missing)}")
    for m in missing:
        print("  no-hash:", m)

if __name__ == "__main__":
    main()
