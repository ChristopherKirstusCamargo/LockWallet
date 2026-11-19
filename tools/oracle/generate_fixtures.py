#!/usr/bin/env python3
"""LOCK WALLET — independent oracle fixture generator (Phase A).

Implementations used (none of them share code with the Kotlin implementation):
  * python-mnemonic (Trezor, reference BIP-39 implementation)          -> entropy / mnemonic / seed
  * bip_utils Bip32Slip10Ed25519 (pure Python SLIP-0010 + PyNaCl)       -> oracle #1 for keys/addresses
  * solders Keypair.from_seed_and_derivation_path (Rust solana-sdk,
    ed25519-dalek-bip32)                                                -> oracle #2 for keys/addresses
  * bip_utils Base58Encoder and solders Pubkey                          -> Base58 oracle

Usage:  python generate_fixtures.py <output-dir>
The output is deterministic (fixed RNG seed), so CI can regenerate and `git diff --exit-code`.
"""
import hashlib, json, os, random, sys
from importlib.metadata import version

from mnemonic import Mnemonic
from bip_utils import Bip32Slip10Ed25519, Base58Encoder, Base58Decoder
from solders.keypair import Keypair
from solders.pubkey import Pubkey
import solders.system_program, solders.compute_budget, solders.token
from solders.token.associated import get_associated_token_address

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
GEN_DATE = "2026-11-19"
VERSIONS = {p: version(p) for p in ("mnemonic", "bip_utils", "solders", "PyNaCl")}


def meta(fid, purpose, source):
    return {"id": fid, "purpose": purpose, "source": source, "generator": "tools/oracle/generate_fixtures.py",
            "oracle_versions": VERSIONS, "generated": GEN_DATE, "platform": "CPython %d.%d" % sys.version_info[:2]}


def dump(name, obj):
    with open(os.path.join(OUT, name), "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2, sort_keys=False)
        f.write("\n")


# ---------------------------------------------------------------- spec §23.1
SPEC_MNEMONIC = "neither lonely flavor argue grass remind eye tag avocado spot unusual intact"
SPEC_EXPECTED = {  # transcribed from the spec PDF §23.1, NOT from any oracle
    "m/44'/501'/0'/0'": ("5vftMkHL72JaJG6ExQfGAsT2uGVHpRR7oTNUPMs68Y2N", "PRIMARY", 0),
    "m/44'/501'/1'/0'": ("GcXbfQ5yY3uxCyBNDPBbR5FjumHf89E7YHXuULfGDBBv", "PRIMARY", 1),
    "m/44'/501'/0'": ("ZtSqp8BQkKFvahawCS9Mf15gzFuedeWWDkYap3qQEe4", "LEGACY_LEDGER", 0),
}
mn = Mnemonic("english")
assert mn.check(SPEC_MNEMONIC)
seed = Mnemonic.to_seed(SPEC_MNEMONIC, passphrase="")
master = Bip32Slip10Ed25519.FromSeed(seed)
rows = []
for path, (expected, profile, account) in SPEC_EXPECTED.items():
    k = master.DerivePath(path)
    priv = k.PrivateKey().Raw().ToBytes()
    pub = k.PublicKey().RawCompressed().ToBytes()[1:]
    kp = Keypair.from_seed_and_derivation_path(seed, path)
    a1, a2 = Base58Encoder.Encode(pub), str(kp.pubkey())
    if not (a1 == a2 == expected) or bytes(kp.secret()) != priv:
        sys.exit("ORACLE DISAGREEMENT on %s: bip_utils=%s solders=%s spec=%s" % (path, a1, a2, expected))
    rows.append({"path": path, "profile": profile, "account_index": account,
                 "private_key_hex": priv.hex(), "chain_code_hex": k.ChainCode().ToHex(),
                 "public_key_hex": pub.hex(), "address": expected,
                 "address_oracle1_bip_utils": a1, "address_oracle2_solders": a2})
dump("lockwallet-spec-23-1.json", {
    "meta": meta("LW-SPEC-23.1", "Spec §23.1 public test mnemonic; NEVER fund these addresses.",
                 "LOCK WALLET Especificação Mestre v2.0 §23.1 (expected addresses transcribed from the PDF)"),
    "mnemonic": SPEC_MNEMONIC, "passphrase": "",
    "entropy_hex": bytes(mn.to_entropy(SPEC_MNEMONIC)).hex(), "seed_hex": seed.hex(),
    "master_private_key_hex": master.PrivateKey().Raw().ToHex(),
    "master_chain_code_hex": master.ChainCode().ToHex(),
    "derivations": rows})

# ------------------------------------------- extra interoperability vectors
rng = random.Random(0x10C4A11E7)
extra = []
for n_bytes in (16, 20, 24, 28, 32):
    for _ in range(3):
        ent = bytes(rng.getrandbits(8) for _ in range(n_bytes))
        words = mn.to_mnemonic(ent)
        s = Mnemonic.to_seed(words, "")
        m = Bip32Slip10Ed25519.FromSeed(s)
        ders = []
        for acct in (0, 1, 7, 2147483647):
            for profile, path in (("PRIMARY", "m/44'/501'/%d'/0'" % acct), ("LEGACY_LEDGER", "m/44'/501'/%d'" % acct)):
                pub = m.DerivePath(path).PublicKey().RawCompressed().ToBytes()[1:]
                a1 = Base58Encoder.Encode(pub)
                a2 = str(Keypair.from_seed_and_derivation_path(s, path).pubkey())
                if a1 != a2:
                    sys.exit("ORACLE DISAGREEMENT " + path)
                ders.append({"profile": profile, "account_index": acct, "path": path, "address": a1})
        extra.append({"entropy_hex": ent.hex(), "mnemonic": words, "seed_hex": s.hex(), "derivations": ders})
dump("oracle-derivations.json", {
    "meta": meta("LW-ORACLE-DERIV-1", "Interop: entropy->mnemonic->seed->SLIP-0010->address for all 5 entropy sizes,"
                 " both profiles, boundary account index 2^31-1. Random test entropy, never funded.",
                 "python-mnemonic + bip_utils + solders (two independent derivation oracles agree)"),
    "vectors": extra})

# ------------------------------------------------------------------ Base58
b58 = []
for n in list(range(0, 10)) + [31, 32, 32, 32, 33, 64]:
    for lead in (0, 1, 3):
        if lead > n:
            continue
        raw = bytes(lead) + bytes(rng.getrandbits(8) for _ in range(n - lead))
        enc = Base58Encoder.Encode(raw) if raw else ""
        if raw:
            assert Base58Decoder.Decode(enc) == raw
        if len(raw) == 32:
            assert str(Pubkey(raw)) == enc
        b58.append({"hex": raw.hex(), "base58": enc})
dump("base58-oracle.json", {"meta": meta("LW-ORACLE-B58-1", "Base58 (Bitcoin alphabet) round-trip incl. leading zeros",
                                         "bip_utils Base58Encoder; 32-byte cases cross-checked with solders Pubkey"),
                            "vectors": b58})

# ------------------------------------------------------------- program IDs
ATA_ID = Pubkey.from_string("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")
owner, mint = Keypair.from_seed(bytes(range(32))).pubkey(), Keypair.from_seed(bytes(range(1, 33))).pubkey()
ata_expected, _ = Pubkey.find_program_address([bytes(owner), bytes(solders.token.ID), bytes(mint)], ATA_ID)
assert get_associated_token_address(owner, mint) == ata_expected, "ATA program id mismatch with solders"
dump("solana-program-ids.json", {
    "meta": meta("LW-ORACLE-PROGRAM-IDS-1", "Program IDs of the MVP allowlist (v2.1 §5)",
                 "solders constants; ATA id proven by re-deriving solders' get_associated_token_address"),
    "programs": {"system": str(solders.system_program.ID), "spl_token_legacy": str(solders.token.ID),
                 "associated_token": str(ATA_ID), "compute_budget": str(solders.compute_budget.ID)},
    "ata_check": {"owner": str(owner), "mint": str(mint), "ata": str(ata_expected)}})
print("ok:", sorted(os.listdir(OUT)))

# ------------------------------------------------- LWRP v1 structure (v2.1 §3)
# Independent Python implementation of the 48-byte payload header and 46-byte file header (struct, BE).
import struct


def lwpl(kind, lang, profile, account, mat_len, flags, pub, material):
    return b"LWPL" + struct.pack(">BBBBIHH", 1, kind, lang, profile, account, mat_len, flags) + pub + material


def lwrp_header(salt, m, t, p, nonce):
    return b"LWRP" + bytes([1, 1]) + salt + struct.pack(">III", m, t, p) + nonce


pos, neg = [], []
for n_bytes in (16, 20, 24, 28, 32):
    ent = bytes(rng.getrandbits(8) for _ in range(n_bytes))
    s = Mnemonic.to_seed(mn.to_mnemonic(ent), "")
    for profile, path_fmt in ((1, "m/44'/501'/%d'/0'"), (2, "m/44'/501'/%d'")):
        acct = rng.choice([0, 1, 5, 2147483647])
        pub = bytes(Keypair.from_seed_and_derivation_path(s, path_fmt % acct).pubkey())
        pos.append({"id": "LWPL-POS-BIP39-%d-P%d" % (n_bytes, profile), "kind": 1, "profile": profile,
                    "account_index": acct, "material_hex": ent.hex(), "pubkey_hex": pub.hex(),
                    "payload_hex": lwpl(1, 1, profile, acct, n_bytes, 0, pub, ent).hex()})
kp = Keypair.from_seed(bytes(rng.getrandbits(8) for _ in range(32)))
kp_secret, kp_pub = bytes(kp), bytes(kp.pubkey())
assert kp_secret[32:] == kp_pub
pos.append({"id": "LWPL-POS-KEYPAIR", "kind": 2, "profile": 0, "account_index": 0, "material_hex": kp_secret.hex(),
            "pubkey_hex": kp_pub.hex(), "payload_hex": lwpl(2, 0, 0, 0, 64, 0, kp_pub, kp_secret).hex()})

b = pos[0]; ent = bytes.fromhex(b["material_hex"]); pub = bytes.fromhex(b["pubkey_hex"])
other_pub = bytes.fromhex(pos[2]["pubkey_hex"])
bad_kp = kp_secret[:32] + other_pub
def N(i, err, payload, stage="parse"): neg.append({"id": i, "expected_error": err, "stage": stage, "payload_hex": payload.hex()})
N("LWPL-NEG-MAGIC", "BAD_PAYLOAD_MAGIC", b"LWPX" + lwpl(1, 1, 1, 0, 16, 0, pub, ent)[4:])
N("LWPL-NEG-VERSION", "BAD_PAYLOAD_VERSION", lwpl(1, 1, 1, 0, 16, 0, pub, ent)[:4] + b"\x02" + lwpl(1, 1, 1, 0, 16, 0, pub, ent)[5:])
N("LWPL-NEG-KIND-0", "BAD_MATERIAL_KIND", lwpl(0, 1, 1, 0, 16, 0, pub, ent))
N("LWPL-NEG-KIND-3", "BAD_MATERIAL_KIND", lwpl(3, 1, 1, 0, 16, 0, pub, ent))
N("LWPL-NEG-LANG", "BAD_LANGUAGE", lwpl(1, 2, 1, 0, 16, 0, pub, ent))
N("LWPL-NEG-PROFILE-0", "BAD_DERIVATION_PROFILE", lwpl(1, 1, 0, 0, 16, 0, pub, ent))
N("LWPL-NEG-PROFILE-3", "BAD_DERIVATION_PROFILE", lwpl(1, 1, 3, 0, 16, 0, pub, ent))
N("LWPL-NEG-ACCOUNT-2^31", "BAD_ACCOUNT_INDEX", lwpl(1, 1, 1, 0x80000000, 16, 0, pub, ent))
N("LWPL-NEG-ACCOUNT-MAX", "BAD_ACCOUNT_INDEX", lwpl(1, 1, 1, 0xFFFFFFFF, 16, 0, pub, ent))
N("LWPL-NEG-LEN-17", "BAD_MATERIAL_LENGTH", lwpl(1, 1, 1, 0, 17, 0, pub, ent + b"\x00"))
N("LWPL-NEG-LEN-64-BIP39", "BAD_MATERIAL_LENGTH", lwpl(1, 1, 1, 0, 64, 0, pub, ent * 4))
N("LWPL-NEG-FLAGS-1", "UNKNOWN_FLAGS", lwpl(1, 1, 1, 0, 16, 1, pub, ent))
N("LWPL-NEG-FLAGS-8000", "UNKNOWN_FLAGS", lwpl(1, 1, 1, 0, 16, 0x8000, pub, ent))
N("LWPL-NEG-TRAILING", "LENGTH_MISMATCH", lwpl(1, 1, 1, 0, 16, 0, pub, ent) + b"\x00")
N("LWPL-NEG-TRUNCATED", "LENGTH_MISMATCH", lwpl(1, 1, 1, 0, 16, 0, pub, ent)[:-1])
N("LWPL-NEG-SHORT-HEADER", "LENGTH_MISMATCH", lwpl(1, 1, 1, 0, 16, 0, pub, ent)[:47])
N("LWPL-NEG-KP-LANG", "BAD_LANGUAGE", lwpl(2, 1, 0, 0, 64, 0, kp_pub, kp_secret))
N("LWPL-NEG-KP-PROFILE", "BAD_DERIVATION_PROFILE", lwpl(2, 0, 1, 0, 64, 0, kp_pub, kp_secret))
N("LWPL-NEG-KP-ACCOUNT", "BAD_ACCOUNT_INDEX", lwpl(2, 0, 0, 1, 64, 0, kp_pub, kp_secret))
N("LWPL-NEG-KP-LEN-32", "BAD_MATERIAL_LENGTH", lwpl(2, 0, 0, 0, 32, 0, kp_pub, kp_secret[:32]))
N("LWPL-NEG-PUBKEY-MISMATCH", "PUBKEY_MISMATCH", lwpl(1, 1, 1, 0, 16, 0, other_pub, ent), "verify")
N("LWPL-NEG-PROFILE-SWAP", "PUBKEY_MISMATCH", lwpl(1, 1, 2, 0, 16, 0, pub, ent), "verify")
N("LWPL-NEG-KP-INCONSISTENT", "INCONSISTENT_KEYPAIR", lwpl(2, 0, 0, 0, 64, 0, other_pub, bad_kp), "verify")
N("LWPL-NEG-KP-PUBKEY", "PUBKEY_MISMATCH", lwpl(2, 0, 0, 0, 64, 0, other_pub, kp_secret), "verify")

salt, nonce = bytes(range(16)), bytes(range(100, 112))
hdr = lwrp_header(salt, 19456, 2, 1, nonce)
assert len(hdr) == 46
files = []
def F(i, err, data): files.append({"id": i, "expected_error": err, "file_hex": data.hex()})
for n in (16, 20, 24, 28, 32, 64):
    F("LWRP-POS-SIZE-%d" % (110 + n), None, hdr + bytes(48 + n + 16))
for size in (0, 45, 46, 61, 62, 125, 127, 143, 173, 175, 1024, 65536):
    F("LWRP-NEG-SIZE-%d" % size, "BAD_SIZE", (hdr + bytes(max(0, size - 46)))[:size])
body = bytes(48 + 16 + 16)
F("LWRP-NEG-MAGIC", "BAD_MAGIC", b"LWRX" + hdr[4:] + body)
F("LWRP-NEG-VERSION", "BAD_VERSION", hdr[:4] + b"\x02" + hdr[5:] + body)
F("LWRP-NEG-KDF-ID", "BAD_KDF_ID", hdr[:5] + b"\x02" + hdr[6:] + body)
for (m, t, p) in ((19455, 2, 1), (19456, 1, 1), (19456, 2, 2), (0, 2, 1), (19456, 0, 1), (19456, 2, 0),
                  (0xFFFFFFFF, 2, 1), (4194304, 2, 1), (19456, 2, 255)):
    F("LWRP-NEG-KDF-%d-%d-%d" % (m, t, p), "KDF_PROFILE_NOT_ALLOWED", lwrp_header(salt, m, t, p, nonce) + body)

dump("lwrp-v1-structure.json", {
    "meta": meta("LW-LWRP-V1-STRUCT", "LWRP v1 file header (46 B) + payload (48 B + material) structural vectors, "
                 "positive and negative; independent Python struct implementation; no encryption.",
                 "Spec v2.0 §10.2-10.3 + v2.1 §2-§3 + errata E-01 (exact sizes 110+material_length)"),
    "header_hex_compat_v1": hdr.hex(), "payloads_positive": pos, "payloads_negative": neg, "files": files})
print("ok lwrp")

# ------------------------------------------------- Phase B: Argon2id, AES-GCM, LWRP v1 encrypted, LWLE v1 envelope
# Independent implementations: libsodium Argon2id (PyNaCl, p=1 fixed) and pycryptodome AES-GCM.
import unicodedata
import nacl.pwhash.argon2id as sodium_argon2id
from Crypto.Cipher import AES
VERSIONS["pycryptodome"] = version("pycryptodome")

M_KIB, T_COST = 19456, 2


def argon2id(password, salt):
    return sodium_argon2id.kdf(32, password, salt, opslimit=T_COST, memlimit=M_KIB * 1024)


def gcm_enc(key, nonce, aad, pt):
    c = AES.new(key, AES.MODE_GCM, nonce=nonce, mac_len=16)
    c.update(aad)
    ct, tag = c.encrypt_and_digest(pt)
    return ct + tag


brng = random.Random(0x10C4B)
rb = lambda n: bytes(brng.getrandbits(8) for _ in range(n))

a2 = []
for pw in (b"480917", b"000000000000", "correct horse battery staple ção".encode("utf-8")):
    salt = rb(16)
    a2.append({"password_hex": pw.hex(), "salt_hex": salt.hex(), "m_kib": M_KIB, "t": T_COST, "p": 1, "out_hex": argon2id(pw, salt).hex()})
dump("argon2id-v13.json", {"meta": meta("LW-ORACLE-ARGON2ID-1", "Argon2id v0x13 32-byte output, allowlisted profile 0x01 (m=19456 KiB, t=2, p=1)",
                                      "libsodium crypto_pwhash_argon2id via PyNaCl"), "cases": a2})

gc = []
for n in (0, 1, 16, 33, 64):
    key, nonce, aad, pt = rb(32), rb(12), rb(n % 40), rb(n)
    gc.append({"key_hex": key.hex(), "nonce_hex": nonce.hex(), "aad_hex": aad.hex(), "pt_hex": pt.hex(), "ct_hex": gcm_enc(key, nonce, aad, pt).hex()})
dump("aes-256-gcm.json", {"meta": meta("LW-ORACLE-AESGCM-1", "AES-256-GCM, 96-bit nonce, 128-bit tag", "pycryptodome"), "cases": gc})

enc_cases = []
def lwrp_case(cid, kind, profile, account, material, pub, password_text):
    salt, nonce = rb(16), rb(12)
    pw = unicodedata.normalize("NFC", password_text).encode("utf-8")
    key = argon2id(pw, salt)
    hdr = lwrp_header(salt, M_KIB, T_COST, 1, nonce)
    payload = lwpl(kind, 1 if kind == 1 else 0, profile, account, len(material), 0, pub, material)
    f = hdr + gcm_enc(key, nonce, hdr, payload)
    assert len(f) == 110 + len(material)
    enc_cases.append({"id": cid, "password": password_text, "kind": kind, "profile": profile, "account_index": account,
                      "material_hex": material.hex(), "pubkey_hex": pub.hex(), "salt_hex": salt.hex(), "nonce_hex": nonce.hex(), "file_hex": f.hex()})

ent12, ent24 = rb(16), rb(32)
s12 = Mnemonic.to_seed(mn.to_mnemonic(ent12), ""); s24 = Mnemonic.to_seed(mn.to_mnemonic(ent24), "")
pub12 = bytes(Keypair.from_seed_and_derivation_path(s12, "m/44'/501'/0'/0'").pubkey())
pub24 = bytes(Keypair.from_seed_and_derivation_path(s24, "m/44'/501'/3'").pubkey())
kp2 = Keypair.from_seed(rb(32))
lwrp_case("LWRP-ENC-12W-P1-0", 1, 1, 0, ent12, pub12, "lunar orbit candle mosaic velvet harbor tiger sketch")
# NFD input (e + combining acute) must normalise to NFC before UTF-8.
lwrp_case("LWRP-ENC-24W-P2-3", 1, 2, 3, ent24, pub24, unicodedata.normalize("NFD", "senha forte com acentuação é 2026"))
lwrp_case("LWRP-ENC-KEYPAIR", 2, 0, 0, bytes(kp2), bytes(kp2.pubkey()), "keypair export password 0001")
dump("lwrp-v1-encrypted.json", {"meta": meta("LW-LWRP-V1-ENC", "Complete encrypted LWRP v1 files (Argon2id + AES-256-GCM, AAD = 46-byte header)",
                                           "Spec §10 + ADR 0004; libsodium Argon2id + pycryptodome AES-GCM + solders pubkeys"), "cases": enc_cases})

# LWLE v1 (ADR 0001 + E-23): header(114) | seed_nonce | u32 len | seed_ct | dekpin_nonce | hw_iv | u16 64 | blob(64)
def lwle_header(vault_id, material_id, kind, profile, account, pub, kdf, pin_salt, alias_id, auth, mlen, flags=0, version_=1, suite=1, schema=1):
    return (b"LWLE" + bytes([version_, suite]) + vault_id + material_id + bytes([kind, schema, profile]) + struct.pack(">I", account) +
            pub + bytes([kdf]) + pin_salt + alias_id + bytes([auth, flags]) + struct.pack(">H", mlen))


def lwle(c, header=None):
    h = header or lwle_header(c["vault_id"], c["material_id"], c["kind"], c["profile"], c["account"], c["pub"], 1, c["pin_salt"], c["alias_id"], c["auth"], len(c["material"]))
    seed_ct = gcm_enc(c["dek"], c["seed_nonce"], b"lock-local-seed-v1\x00" + h, c["material"])
    dek_pin = gcm_enc(c["pin_key"], c["dekpin_nonce"], b"lock-local-pin-v1\x00" + h, c["dek"])
    blob = gcm_enc(c["kek"], c["hw_iv"], b"lock-local-hw-v1\x00" + h, dek_pin)
    return h + c["seed_nonce"] + struct.pack(">I", len(seed_ct)) + seed_ct + c["dekpin_nonce"] + c["hw_iv"] + struct.pack(">H", len(blob)) + blob


lwle_cases = []
for cid, kind, profile, account, material, pub, auth in (
        ("LWLE-12W-P1-0", 1, 1, 0, ent12, pub12, 2), ("LWLE-24W-P2-3", 1, 2, 3, ent24, pub24, 1),
        ("LWLE-KEYPAIR", 2, 0, 0, bytes(kp2), bytes(kp2.pubkey()), 2)):
    c = {"vault_id": rb(16), "material_id": rb(16), "kind": kind, "profile": profile, "account": account, "pub": pub,
         "pin_salt": rb(16), "alias_id": rb(16), "auth": auth, "material": material, "dek": rb(32), "seed_nonce": rb(12),
         "dekpin_nonce": rb(12), "hw_iv": rb(12), "kek": rb(32), "pin": "480917"}
    c["pin_key"] = argon2id(c["pin"].encode("ascii"), c["pin_salt"])
    env = lwle(c)
    assert len(env) == len(material) + 236
    lwle_cases.append({"id": cid, **{k: (v.hex() if isinstance(v, bytes) else v) for k, v in c.items()}, "envelope_hex": env.hex()})

c0 = {k: (bytes.fromhex(v) if k not in ("id", "kind", "profile", "account", "auth", "pin") and isinstance(v, str) else v) for k, v in lwle_cases[0].items()}
good = bytes.fromhex(lwle_cases[0]["envelope_hex"])
H = lambda **kw: lwle_header(**{**dict(vault_id=c0["vault_id"], material_id=c0["material_id"], kind=1, profile=1, account=0, pub=c0["pub"], kdf=1,
                                       pin_salt=c0["pin_salt"], alias_id=c0["alias_id"], auth=2, mlen=16), **kw})
lneg = []
def LN(i, err, data): lneg.append({"id": i, "expected_error": err, "envelope_hex": data.hex()})
LN("LWLE-NEG-EMPTY", "BAD_SIZE", b"")
LN("LWLE-NEG-TRUNC-1", "BAD_SIZE", good[:-1])
LN("LWLE-NEG-TRAILING-1", "LENGTH_MISMATCH", good + b"\x00")
LN("LWLE-NEG-TOO-BIG", "BAD_SIZE", good + bytes(100))
LN("LWLE-NEG-MAGIC", "BAD_MAGIC", b"LWLX" + good[4:])
LN("LWLE-NEG-VERSION-2", "UNSUPPORTED_VERSION", good[:4] + b"\x02" + good[5:])
LN("LWLE-NEG-VERSION-0", "UNSUPPORTED_VERSION", good[:4] + b"\x00" + good[5:])
LN("LWLE-NEG-SUITE", "BAD_SUITE", lwle(c0, H(suite=2)))
LN("LWLE-NEG-SCHEMA", "BAD_SCHEMA", lwle(c0, H(schema=2)))
LN("LWLE-NEG-KIND", "BAD_MATERIAL_KIND", lwle(c0, H(kind=3)))
LN("LWLE-NEG-PROFILE-0", "BAD_DERIVATION_PROFILE", lwle(c0, H(profile=0)))
LN("LWLE-NEG-PROFILE-3", "BAD_DERIVATION_PROFILE", lwle(c0, H(profile=3)))
LN("LWLE-NEG-ACCOUNT-2^31", "BAD_ACCOUNT_INDEX", lwle(c0, H(account=0x80000000)))
LN("LWLE-NEG-KDF-2", "BAD_KDF_PROFILE", lwle(c0, H(kdf=2)))
LN("LWLE-NEG-AUTH-0", "BAD_AUTH_MODE", lwle(c0, H(auth=0)))
LN("LWLE-NEG-AUTH-3", "BAD_AUTH_MODE", lwle(c0, H(auth=3)))
LN("LWLE-NEG-FLAGS", "UNKNOWN_FLAGS", lwle(c0, H(flags=1)))
LN("LWLE-NEG-MLEN-17", "BAD_MATERIAL_LENGTH", lwle(c0, H(mlen=17)))
LN("LWLE-NEG-MLEN-MISMATCH", "LENGTH_MISMATCH", lwle(c0, H(mlen=20)))
bad_len = bytearray(good); bad_len[126:130] = struct.pack(">I", 31); LN("LWLE-NEG-SEEDCT-LEN", "LENGTH_MISMATCH", bytes(bad_len))
bad_hw = bytearray(good); bad_hw[len(good) - 66:len(good) - 64] = struct.pack(">H", 63); LN("LWLE-NEG-HWCT-LEN", "LENGTH_MISMATCH", bytes(bad_hw))
dump("lwle-v1.json", {"meta": meta("LW-LWLE-V1", "Local envelope LWLE v1 (ADR 0001 + errata E-23): positive envelopes (software KEK stands in for "
                                                 "the Android Keystore key) and structural negatives",
                                   "Independent Python struct + libsodium Argon2id + pycryptodome AES-GCM + solders"),
                      "positive": lwle_cases, "negative": lneg})
print("ok phase B fixtures")
