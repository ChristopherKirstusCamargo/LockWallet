#!/usr/bin/env python3
"""Extract the official SLIP-0010 ed25519 test vectors from slip-0010.md and cross-check them
against bip_utils. Usage: python extract_slip10.py <slip-0010.md> <out.json>
Source: https://github.com/satoshilabs/slips/blob/master/slip-0010.md"""
import hashlib, json, re, sys
from bip_utils import Bip32Slip10Ed25519
src = open(sys.argv[1], "rb").read()
md = src.decode("utf-8")
vecs = []
for n in (1, 2):
    sec = re.search(r"### Test vector %d for ed25519\n(.*?)(?=\n### )" % n, md, re.S).group(1)
    seed = re.search(r"Seed \(hex\): ([0-9a-f]+)", sec).group(1)
    chains = []
    for m in re.finditer(r"\* Chain (.+?)\n\s+\* fingerprint: ([0-9a-f]+)\n\s+\* chain code: ([0-9a-f]+)\n"
                         r"\s+\* private: ([0-9a-f]+)\n\s+\* public: ([0-9a-f]+)", sec):
        path = m.group(1).replace("<sub>H</sub>", "'").strip()
        k = Bip32Slip10Ed25519.FromSeed(bytes.fromhex(seed)).DerivePath(path)
        assert k.PrivateKey().Raw().ToHex() == m.group(4) and k.ChainCode().ToHex() == m.group(3), path
        assert k.PublicKey().RawCompressed().ToHex() == m.group(5), path
        chains.append({"path": path, "fingerprint": m.group(2), "chain_code": m.group(3),
                       "private": m.group(4), "public": m.group(5)})
    assert len(chains) == 6
    vecs.append({"id": "SLIP10-ED25519-TV%d" % n, "seed": seed, "chains": chains})
json.dump({"meta": {"id": "SLIP10-ED25519-OFFICIAL", "purpose": "Official SLIP-0010 ed25519 test vectors 1 and 2",
                    "source": "https://github.com/satoshilabs/slips/blob/master/slip-0010.md",
                    "source_sha256": hashlib.sha256(src).hexdigest(), "retrieved": "2026-11-19",
                    "cross_checked_with": "bip_utils 2.12.3"},
           "vectors": vecs}, open(sys.argv[2], "w"), indent=2)
print("ok")
