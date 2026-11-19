# Fixtures (v2.1 §9)

Todos em `fixtures/vectors/`, gerados/extraídos por `tools/oracle/` (Python, versões fixadas em `requirements.txt`). O CI regenera e exige `git diff` vazio. Nenhum endereço destes vetores pode receber fundos.

| Arquivo | ID | Origem | Uso |
|---|---|---|---|
| `bip39-trezor-vectors.json` | TREZOR-BIP39-EN | trezor/python-mnemonic `vectors.json` (MIT), verbatim | 24 vetores entropia→frase→seed |
| `slip10-ed25519-official.json` | SLIP10-ED25519-OFFICIAL | slip-0010.md TV1/TV2, conferido com bip_utils | chave, chain code, pubkey, fingerprint |
| `lockwallet-spec-23-1.json` | LW-SPEC-23.1 | spec §23.1 + 2 oracles | cada etapa + 3 endereços |
| `oracle-derivations.json` | LW-ORACLE-DERIV-1 | python-mnemonic + bip_utils + solders | 15 entropias (5 tamanhos, incl. 3 de 24 palavras), 2 perfis, índices 0/1/7/2^31−1 |
| `base58-oracle.json` | LW-ORACLE-B58-1 | bip_utils + solders | Base58 com zeros à esquerda |
| `solana-program-ids.json` | LW-ORACLE-PROGRAM-IDS-1 | solders | allowlist MVP |
| `lwrp-v1-structure.json` | LW-LWRP-V1-STRUCT | implementação Python `struct` independente | 11 payloads positivos, 24 negativos, 36 arquivos |
| `argon2id-v13.json` | LW-ORACLE-ARGON2ID-1 | libsodium (PyNaCl) | 3 senhas, perfil 0x01 (m 19456, t 2, p 1); + vetor RFC 9106 no teste |
| `aes-256-gcm.json` | LW-ORACLE-AESGCM-1 | pycryptodome (+ JDK no teste) | 5 casos, AAD/plaintext de 0 a 64 B |
| `lwrp-v1-encrypted.json` | LW-LWRP-V1-ENC | libsodium + pycryptodome + solders | 12 palavras, 24 palavras (senha NFD), keypair |
| `lwle-v1.json` | LW-LWLE-V1 | Python `struct` + libsodium + pycryptodome (KEK em software no lugar do Keystore) | 3 envelopes positivos byte a byte, 21 negativos estruturais |
| `tx-oracle.json` | LW-ORACLE-TX-1 | solders 0.29.0 + LiteSVM (execução real dos programas System/Token/Token-2022/ATA/Stake) | 10 mensagens positivas byte a byte e executadas, 24 negativas, ATA/createWithSeed/Metaplex PDA, curva, dumps de contas; sha256 `0c352f05…751b9f` |
