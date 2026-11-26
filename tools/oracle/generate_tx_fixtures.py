#!/usr/bin/env python3
"""Phase D/E/F oracle (independent of the Kotlin code): legacy messages built with solders 0.29.0,
stake/token instruction bytes hand-encoded with `struct`, every positive transaction EXECUTED in LiteSVM
(real System/Token/Token-2022/ATA/Stake/Vote programs) to prove the instruction layouts are valid.
Writes fixtures/vectors/tx-oracle.json. Deterministic (fixed seeds, fixed fixture blockhash)."""
import json, os, struct, hashlib
from solders.pubkey import Pubkey
from solders.keypair import Keypair
from solders.hash import Hash
from solders.message import Message
from solders.transaction import Transaction
from solders.instruction import Instruction, AccountMeta
from solders.system_program import transfer, TransferParams, create_account, CreateAccountParams
from solders.token.associated import get_associated_token_address as ata
from solders.litesvm import LiteSVM

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "fixtures", "vectors", "tx-oracle.json")
P = Pubkey.from_string
SYSTEM = P("11111111111111111111111111111111"); TOKEN = P("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
T22 = P("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"); ATA = P("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")
CB = P("ComputeBudget111111111111111111111111111111"); STAKE = P("Stake11111111111111111111111111111111111111")
STAKE_CFG = P("StakeConfig11111111111111111111111111111111"); VOTE = P("Vote111111111111111111111111111111111111111")
RENT = P("SysvarRent111111111111111111111111111111111"); CLOCK = P("SysvarC1ock11111111111111111111111111111111")
HIST = P("SysvarStakeHistory1111111111111111111111111"); META = P("metaqbxxUerdq28cj1RbAWkYQm3ybzjb6a8bt518x1s")

def kp(i): return Keypair.from_seed(hashlib.sha256(b"lockwallet-oracle-%d" % i).digest())
PAYER, BOB, MINT_AUTH, NODE, VOTEKP = kp(1), kp(2), kp(3), kp(4), kp(5)
MINT, MINT22 = kp(6), kp(7)
FIXED_BH = Hash(hashlib.sha256(b"fixture-blockhash").digest())

def m(*a, s=False, w=False): return AccountMeta(a[0], s, w)
# --- independent instruction encoders (struct) -------------------------------------------------
def ix_transfer(f, t, lam): return Instruction(SYSTEM, struct.pack("<IQ", 2, lam), [m(f, s=True, w=True), m(t, w=True)])
def ix_seed(f, to, seed, lam, space, owner):
    sb = seed.encode()
    return Instruction(SYSTEM, struct.pack("<I", 3) + bytes(f) + struct.pack("<Q", len(sb)) + sb + struct.pack("<QQ", lam, space) + bytes(owner),
                       [m(f, s=True, w=True), m(to, w=True)])
def ix_tchecked(prog, src, mint, dst, owner, amt, dec): return Instruction(prog, struct.pack("<BQB", 12, amt, dec), [m(src, w=True), m(mint), m(dst, w=True), m(owner, s=True)])
def ix_close(prog, acc, dst, owner): return Instruction(prog, bytes([9]), [m(acc, w=True), m(dst, w=True), m(owner, s=True)])
def ix_ata(payer, a, owner, mint, prog): return Instruction(ATA, bytes([1]), [m(payer, s=True, w=True), m(a, w=True), m(owner), m(mint), m(SYSTEM), m(prog)])
def ix_cu_limit(u): return Instruction(CB, struct.pack("<BI", 2, u), [])
def ix_cu_price(p): return Instruction(CB, struct.pack("<BQ", 3, p), [])
def ix_init(st, auth): return Instruction(STAKE, struct.pack("<I", 0) + bytes(auth) + bytes(auth) + struct.pack("<qQ", 0, 0) + bytes(32), [m(st, w=True), m(RENT)])
def ix_delegate(st, vote, staker): return Instruction(STAKE, struct.pack("<I", 2), [m(st, w=True), m(vote), m(CLOCK), m(HIST), m(STAKE_CFG), m(staker, s=True)])
def ix_deact(st, staker): return Instruction(STAKE, struct.pack("<I", 5), [m(st, w=True), m(CLOCK), m(staker, s=True)])
def ix_withdraw(st, to, w_, lam): return Instruction(STAKE, struct.pack("<IQ", 4, lam), [m(st, w=True), m(to, w=True), m(CLOCK), m(HIST), m(w_, s=True)])
def ix_split(st, dst, staker, lam): return Instruction(STAKE, struct.pack("<IQ", 3, lam), [m(st, w=True), m(dst, w=True), m(staker, s=True)])
def ix_merge(dst, src, staker): return Instruction(STAKE, struct.pack("<I", 7), [m(dst, w=True), m(src, w=True), m(CLOCK), m(HIST), m(staker, s=True)])

def msg_hex(ixs, payer=PAYER.pubkey(), bh=FIXED_BH): return bytes(Message.new_with_blockhash(ixs, payer, bh)).hex()

# --- LiteSVM execution ---------------------------------------------------------------------------
svm = LiteSVM()
for k in (PAYER, BOB, MINT_AUTH, NODE): svm.airdrop(k.pubkey(), 50_000_000_000)
def run(ixs, signers, expect_ok=True):
    tx = Transaction(signers, Message.new_with_blockhash(ixs, signers[0].pubkey(), svm.latest_blockhash()), svm.latest_blockhash())
    r = svm.send_transaction(tx)
    ok = type(r).__name__ == "TransactionMetadata"
    if ok != expect_ok: raise SystemExit("LiteSVM unexpected result for %s: %s" % ([str(i.program_id) for i in ixs], r))
    svm.expire_blockhash()
    return r

def init_mint(prog, mint_kp, decimals, size=82, pre=()):
    lam = svm.minimum_balance_for_rent_exemption(size)
    ixs = [create_account(CreateAccountParams(from_pubkey=PAYER.pubkey(), to_pubkey=mint_kp.pubkey(), lamports=lam, space=size, owner=prog))] + list(pre)
    # InitializeMint2 (20): decimals, mint authority, COption freeze (none)
    ixs.append(Instruction(prog, struct.pack("<BB", 20, decimals) + bytes(MINT_AUTH.pubkey()) + bytes([0]), [m(mint_kp.pubkey(), w=True)]))
    run(ixs, [PAYER, mint_kp])
def mint_to(prog, mint, dst, amt): run([Instruction(prog, struct.pack("<BQ", 7, amt), [m(mint, w=True), m(dst, w=True), m(MINT_AUTH.pubkey(), s=True)])], [PAYER, MINT_AUTH])

cases, results = [], {}
def case(cid, ixs, execute=None):
    cases.append({"id": cid, "message_hex": msg_hex(ixs)})
    if execute: execute()

payer, bob = PAYER.pubkey(), BOB.pubkey()
# D: SOL
case("sol_transfer", [ix_transfer(payer, bob, 1_234_567)], lambda: run([ix_transfer(payer, bob, 1_234_567)], [PAYER]))
case("sol_transfer_cb", [ix_cu_limit(450), ix_cu_price(0), ix_transfer(payer, bob, 5000)], lambda: run([ix_cu_limit(450), ix_cu_price(0), ix_transfer(payer, bob, 5000)], [PAYER]))

# E: legacy token: mint, payer ATA funded, transfer with CreateIdempotent for bob, close empty account
init_mint(TOKEN, MINT, 6)
mint = MINT.pubkey(); pa, ba = ata(payer, mint, TOKEN), ata(bob, mint, TOKEN)
run([ix_ata(payer, pa, payer, mint, TOKEN)], [PAYER]); mint_to(TOKEN, mint, pa, 5_000_000)
tok = [ix_ata(payer, ba, bob, mint, TOKEN), ix_tchecked(TOKEN, pa, mint, ba, payer, 1_500_000, 6)]
case("token_transfer_create_ata", tok, lambda: run(tok, [PAYER]))
run([ix_ata(payer, ba, bob, mint, TOKEN)], [PAYER])  # idempotent: existing ATA succeeds again
run([ix_tchecked(TOKEN, pa, mint, ba, payer, 1, 7)], [PAYER], expect_ok=False)  # wrong decimals rejected by program
run([ix_tchecked(TOKEN, pa, mint, ba, payer, 3_500_000, 6)], [PAYER])
close = [ix_close(TOKEN, pa, payer, payer)]
case("token_close_empty", close, lambda: run(close, [PAYER]))

# E: Token-2022 mint with MetadataPointer (ext 18) initialised in-program, ATA has ImmutableOwner
mint22 = MINT22.pubkey()
ptr = Instruction(T22, bytes([39, 0]) + bytes(MINT_AUTH.pubkey()) + bytes(mint22), [m(mint22, w=True)])  # MetadataPointer::Initialize
size22 = 165 + 1 + 4 + 64
init_mint(T22, MINT22, 9, size=size22, pre=[ptr])
pa22, ba22 = ata(payer, mint22, T22), ata(bob, mint22, T22)
run([ix_ata(payer, pa22, payer, mint22, T22)], [PAYER]); mint_to(T22, mint22, pa22, 10**9)
t22 = [ix_ata(payer, ba22, bob, mint22, T22), ix_tchecked(T22, pa22, mint22, ba22, payer, 250_000_000, 9)]
case("token2022_transfer_create_ata", t22, lambda: run(t22, [PAYER]))
acct22 = svm.get_account(pa22); mint22_acc = svm.get_account(mint22)
legacy_mint_acc = svm.get_account(mint); legacy_bob_acc = svm.get_account(ba)

# F: stake. Vote account (VoteInit, 3762 bytes) so DelegateStake really executes.
vote = VOTEKP.pubkey()
vlam = svm.minimum_balance_for_rent_exemption(3762)
run([create_account(CreateAccountParams(from_pubkey=payer, to_pubkey=vote, lamports=vlam, space=3762, owner=VOTE)),
     Instruction(VOTE, struct.pack("<I", 0) + bytes(NODE.pubkey()) + bytes(NODE.pubkey()) + bytes(NODE.pubkey()) + bytes([7]),
                 [m(vote, w=True), m(RENT), m(CLOCK), m(NODE.pubkey(), s=True)])], [PAYER, VOTEKP, NODE])
stake_rent = svm.minimum_balance_for_rent_exemption(200)
s0 = Pubkey.create_with_seed(payer, "stake:0", STAKE); s1 = Pubkey.create_with_seed(payer, "stake:1", STAKE); s2 = Pubkey.create_with_seed(payer, "stake:2", STAKE)
create = [ix_seed(payer, s0, "stake:0", stake_rent + 3_000_000_000, 200, STAKE), ix_init(s0, payer), ix_delegate(s0, vote, payer)]
case("stake_create_delegate", create, lambda: run(create, [PAYER]))
split = [ix_seed(payer, s1, "stake:1", stake_rent, 200, STAKE), ix_split(s0, s1, payer, 1_000_000_000)]
case("stake_split", split, lambda: run(split, [PAYER]))
case("stake_deactivate", [ix_deact(s0, payer)], lambda: run([ix_deact(s0, payer)], [PAYER]))
# merge two initialized (undelegated) accounts, then withdraw everything
mk = lambda s, seed: [ix_seed(payer, s, seed, stake_rent + 500_000_000, 200, STAKE), ix_init(s, payer)]
run(mk(s2, "stake:2"), [PAYER])
s3 = Pubkey.create_with_seed(payer, "stake:3", STAKE); run(mk(s3, "stake:3"), [PAYER])
merge = [ix_merge(s2, s3, payer)]
case("stake_merge", merge, lambda: run(merge, [PAYER]))
total = svm.get_balance(s2)
wd = [ix_withdraw(s2, payer, payer, total)]
case("stake_withdraw", wd, lambda: run(wd, [PAYER]))
stake_acc = svm.get_account(s0)

# --- analyzer negatives (must all block) ----------------------------------------------------
neg = []
def N(cid, finding, hexmsg): neg.append({"id": cid, "finding": finding, "message_hex": hexmsg})
N("unknown_program", "UNKNOWN_PROGRAM", msg_hex([Instruction(META, b"\x00", [m(payer, s=True, w=True)])]))
N("system_assign", "UNKNOWN_INSTRUCTION", msg_hex([Instruction(SYSTEM, struct.pack("<I", 1) + bytes(TOKEN), [m(payer, s=True, w=True)])]))
N("token_set_authority", "UNKNOWN_INSTRUCTION", msg_hex([Instruction(TOKEN, bytes([6, 2, 1]) + bytes(bob), [m(pa, w=True), m(payer, s=True)])]))
N("token_approve", "UNKNOWN_INSTRUCTION", msg_hex([Instruction(TOKEN, struct.pack("<BQ", 4, 1), [m(pa, w=True), m(bob), m(payer, s=True)])]))
N("token_transfer_unchecked", "UNKNOWN_INSTRUCTION", msg_hex([Instruction(TOKEN, struct.pack("<BQ", 3, 1), [m(pa, w=True), m(ba, w=True), m(payer, s=True)])]))
N("stake_authorize", "UNKNOWN_INSTRUCTION", msg_hex([Instruction(STAKE, struct.pack("<I", 1) + bytes(bob) + struct.pack("<I", 1), [m(s0, w=True), m(CLOCK), m(payer, s=True)])]))
N("extra_signer", "EXTRA_SIGNER", msg_hex([ix_transfer(payer, bob, 1), ix_transfer(bob, payer, 1)]))
N("transfer_trailing_byte", "MALFORMED", msg_hex([Instruction(SYSTEM, struct.pack("<IQ", 2, 1) + b"\x00", [m(payer, s=True, w=True), m(bob, w=True)])]))
N("transfer_short", "MALFORMED", msg_hex([Instruction(SYSTEM, struct.pack("<I", 2) + b"\x01", [m(payer, s=True, w=True), m(bob, w=True)])]))
N("init_foreign_withdrawer", "AUTHORITY_NOT_WALLET", msg_hex([ix_seed(payer, s0, "stake:0", 1, 200, STAKE),
    Instruction(STAKE, struct.pack("<I", 0) + bytes(payer) + bytes(bob) + struct.pack("<qQ", 0, 0) + bytes(32), [m(s0, w=True), m(RENT)])]))
N("init_lockup", "LOCKUP_OR_CUSTODIAN", msg_hex([ix_seed(payer, s0, "stake:0", 1, 200, STAKE),
    Instruction(STAKE, struct.pack("<I", 0) + bytes(payer) + bytes(payer) + struct.pack("<qQ", 0, 99) + bytes(bob), [m(s0, w=True), m(RENT)])]))
N("init_not_created", "STAKE_ACCOUNT_NOT_CREATED_HERE", msg_hex([ix_init(s0, payer)]))
N("seed_wrong_address", "BAD_DERIVED_ADDRESS", msg_hex([ix_seed(payer, s1, "stake:0", 1, 200, STAKE)]))
N("seed_wrong_owner", "UNKNOWN_INSTRUCTION", msg_hex([ix_seed(payer, Pubkey.create_with_seed(payer, "x", TOKEN), "x", 1, 165, TOKEN)]))
N("ata_wrong_address", "BAD_DERIVED_ADDRESS", msg_hex([ix_ata(payer, pa, bob, mint, TOKEN)]))
N("ata_create_non_idempotent", "UNKNOWN_INSTRUCTION", msg_hex([Instruction(ATA, b"", [m(payer, s=True, w=True), m(ba, w=True), m(bob), m(mint), m(SYSTEM), m(TOKEN)])]))
N("close_to_third_party", "CLOSE_TO_THIRD_PARTY", msg_hex([ix_close(TOKEN, pa, bob, payer)]))
N("cu_limit_too_high", "COMPUTE_LIMIT_TOO_HIGH", msg_hex([ix_cu_limit(1_400_001), ix_transfer(payer, bob, 1)]))
N("cu_duplicate", "DUPLICATE_COMPUTE_BUDGET", msg_hex([ix_cu_limit(1000), ix_cu_limit(1000), ix_transfer(payer, bob, 1)]))
N("writable_unused", "UNEXPECTED_WRITABLE", msg_hex([Instruction(SYSTEM, struct.pack("<IQ", 2, 1), [m(payer, s=True, w=True), m(bob, w=True), m(MINT.pubkey(), w=True)])]))
N("delegate_wrong_config", "BAD_ACCOUNTS", msg_hex([Instruction(STAKE, struct.pack("<I", 2), [m(s0, w=True), m(vote), m(CLOCK), m(HIST), m(RENT), m(payer, s=True)])]))
N("withdraw_with_custodian", "BAD_ACCOUNTS", msg_hex([Instruction(STAKE, struct.pack("<IQ", 4, 1), [m(s0, w=True), m(payer, w=True), m(CLOCK), m(HIST), m(payer, s=True), m(bob)])]))
v0 = bytes([0x80]) + bytes.fromhex(msg_hex([ix_transfer(payer, bob, 1)])) + b"\x00"
N("versioned_v0", "UNSUPPORTED_FORMAT", v0.hex())
N("garbage", "MALFORMED", "01000102")

# --- derivations --------------------------------------------------------------------------
owners = [kp(100 + i).pubkey() for i in range(6)]
pdas = [{"owner": str(o), "mint": str(mint), "token_program": str(TOKEN), "ata": str(ata(o, mint, TOKEN))} for o in owners] + \
       [{"owner": str(o), "mint": str(mint22), "token_program": str(T22), "ata": str(ata(o, mint22, T22))} for o in owners]
seeds = [{"base": str(payer), "seed": "stake:%d" % i, "owner": str(STAKE), "address": str(Pubkey.create_with_seed(payer, "stake:%d" % i, STAKE))} for i in range(4)]
meta = [{"mint": str(x), "metadata": str(Pubkey.find_program_address([b"metadata", bytes(META), bytes(x)], META)[0])} for x in (mint, mint22)]
curve = [{"bytes_hex": bytes(k.pubkey()).hex(), "on_curve": True} for k in (kp(200 + i) for i in range(8))] + \
        [{"bytes_hex": bytes(p["ata"] and P(p["ata"])).hex(), "on_curve": False} for p in pdas[:6]]

doc = {
    "meta": {"fixture_id": "LW-TX-ORACLE-1", "purpose": "Phase D/E/F codec, builders, analyzer, PDA, token/stake state",
             "source": "solders 0.29.0 Message + hand-encoded struct instructions, all positives executed in LiteSVM"},
    "keys": {"payer": str(payer), "payer_seed_hex": hashlib.sha256(b"lockwallet-oracle-1").hexdigest(), "bob": str(bob),
             "mint": str(mint), "mint22": str(mint22), "vote": str(vote), "fixture_blockhash": str(FIXED_BH),
             "payer_ata": str(pa), "bob_ata": str(ba), "payer_ata22": str(pa22), "bob_ata22": str(ba22),
             "stake0": str(s0), "stake1": str(s1), "stake2": str(s2), "stake3": str(s3), "stake_rent": stake_rent},
    "positive": cases, "negative": neg,
    "signature": {"message_hex": cases[0]["message_hex"],
                  "signature_hex": bytes(PAYER.sign_message(bytes.fromhex(cases[0]["message_hex"]))).hex()},
    "ata": pdas, "with_seed": seeds, "metaplex_pda": meta, "curve": curve,
    "accounts": {"mint_legacy_hex": bytes(legacy_mint_acc.data).hex(), "bob_ata_legacy_hex": bytes(legacy_bob_acc.data).hex(),
                 "mint22_hex": bytes(mint22_acc.data).hex(), "payer_ata22_hex": bytes(acct22.data).hex(),
                 "stake0_hex": bytes(stake_acc.data).hex()},
}
with open(OUT, "w") as f: json.dump(doc, f, indent=1)
print("wrote", OUT, len(cases), "positive (executed)", len(neg), "negative")
