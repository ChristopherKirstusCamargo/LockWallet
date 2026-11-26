# Verification log (spec §18.1–18.2, v2.2 §15.1)

Acesso em 2026-11-19 (America/Campo_Grande).

| ID | Gate | Fonte (URL · título) | Versão/data | Fato confirmado | Consequência |
|---|---|---|---|---|---|
| G0-1 | 0 | https://developer.android.com/google/play/requirements/target-sdk · Meet Google Play's target API level requirement; https://support.google.com/googleplay/android-developer/answer/11926878 | vigente 2026-11 | Desde 31/08/2026 apps novos e updates precisam target API 36 (prorrogação só até 01/11/2026) | targetSdk 36 |
| G0-2 | 0 | `sdkmanager --list` (Google repository) | 2026-11-19 | `platforms;android-37.x` disponível; AGP 8.13.2 recomenda compileSdk ≤ 36; AARs AndroidX ≥ 2026-08 exigem compileSdk 37 + AGP 9.1 | compileSdk 36 + Compose BOM 2026.06.00; migrar para AGP 9/API 37 em passo próprio |
| G0-3 | 0 | Robolectric 4.18.1 (erro de execução) | 4.18.1 | Sandbox SDK 36 exige Java 21 | testes Robolectric em sandbox 35 |
| G-BKP-1 | 0 | https://developer.android.com/identity/data/autobackup · Back up user data with Auto Backup | vigente | “allowBackup=false … doesn't disable device-to-device transfers” em alguns fabricantes | `dataExtractionRules` excluindo tudo |
| G1-1 | 1 | https://developer.android.com/privacy-and-security/keystore · Android Keystore system | vigente | StrongBox: RSA 2048, AES 128/256, ECDSA/ECDH P-256, HMAC-SHA256, 3DES (sem Ed25519); `KeyInfo.getSecurityLevel()` informa TEE/STRONGBOX; `FEATURE_STRONGBOX_KEYSTORE` | Ed25519 em software; hardware só protege a KEK (ADR 0001) |
| G1-2 | 1/2 | mesma página, seção user authentication | vigente | Chave só biométrica é invalidada por novo cadastro por padrão; modo por operação via `setUserAuthenticationParameters` + BiometricPrompt | ADR 0002 (quarentena, nunca apagar) |
| G3-1 | 3 | https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html | vigente | Argon2id mínimo m=19 MiB, t=2, p=1 | perfil `COMPAT_V1` |
| G4-1 | 4 | https://github.com/bitcoin/bips/blob/master/bip-0039.mediawiki · BIP-0039 | master 2026-11-19 | PBKDF2-HMAC-SHA512, 2048 iterações, salt "mnemonic"+passphrase, NFKD, checksum ENT/32 | `Bip39.kt` |
| G4-2 | 4 | https://raw.githubusercontent.com/bitcoin/bips/master/bip-0039/english.txt | sha256 `2f5eed53…24dbda` | 2048 palavras ordenadas | hash fixado no código |
| G4-3 | 4 | https://github.com/satoshilabs/slips/blob/master/slip-0010.md · SLIP-0010 | sha256 `dbd5a324…ed1ce7` | ed25519: chave HMAC "ed25519 seed"; só hardened; ser_P = 0x00‖A | `Slip10Ed25519.kt`; vetores TV1/TV2 |
| G4-4 | 4 | https://github.com/trezor/python-mnemonic vectors.json | master, sha256 `fa3b937b…4fa4`… | 24 vetores ingleses, passphrase "TREZOR" | fixture |
| G4-5 | 4 | Oracles: python-mnemonic 0.21, bip_utils 2.12.3, solders 0.29.0 (Rust solana-sdk), PyNaCl 1.6.2 | PyPI 2026-11-19 | Os três endereços do §23.1 reproduzidos pelos dois oracles e pelo Kotlin | fixtures `lockwallet-spec-23-1.json` |
| G6-IDS | 6/A4 | solders 0.29.0 constantes + re-derivação de ATA | 0.29.0 | System `111…1`, Token `TokenkegQfe…5DA`, ATA `ATokenGPvb…8knL`, ComputeBudget `ComputeBudget111…1` | `ProgramIds.kt` |
| G-SUP-1 | — | https://www.bouncycastle.org/download/bouncy-castle-java/ | 1.87, 11/11/2026 | release de segurança (12 CVEs, nenhum nas primitivas usadas) | bcprov 1.87 |
| G-SUP-2 | — | Maven Central `bcprov-jdk18on-1.87.jar(.sha256/.asc)` | — | sha256 `12d2d3dd…65b9aa` igual; PGP “Good signature” chave `7B121B76…E3A748C0` | verification-metadata |
| G-SUP-3 | — | https://services.gradle.org/distributions/gradle-8.14.3-bin.zip.sha256 e wrapper.jar.sha256 | 8.14.3 | dist `bd711022…f3531`, wrapper JAR `7d3a4ac4…6172` | wrapper |

| G2-BIO | 2/B | https://dl.google.com/dl/android/maven2/androidx/biometric/biometric/maven-metadata.xml | 19/11/2026 | 1.1.0 é a última estável (1.4.0 em rc); `setAllowedAuthenticators(BIOMETRIC_STRONG)` + `CryptoObject(Cipher)` | `MainActivity.authenticate` |
| G2-FRAG | 2/B | maven.google.com fragment 1.8.9 aar-metadata | 19/11/2026 | minCompileSdk 34 (compatível com 36) | libs.versions.toml |
| G-PRICE-1 | C | `curl https://api.coinbase.com/v2/prices/SOL-USD/spot`, `…SOL-BRL/spot`, `https://api.kraken.com/0/public/Ticker?pair=SOLUSD` | 19/11/2026 | públicos, sem chave; formatos `{"data":{"amount","base","currency"}}` e `result.SOLUSD.c[0]`; Coinbase envia cookie Cloudflare (ignorado: sem CookieHandler) | `Price.kt`, `PriceTest` |
| G-QR | C | Maven Central `com.google.zxing:core` metadata | 19/11/2026 | 3.5.4 última versão; só codificação QR, sem rede | `Receive` |
| G5-1 | 5/D | `getGenesisHash` em https://api.devnet.solana.com, https://api.testnet.solana.com e no RPC público da mainnet | 19/11/2026 | Devnet `EtWTRABZ…PkrZBG`, Testnet `4uhcVJyU…k2NsNY`, Mainnet `5eykt4Us…dw2N9d` (só para recusar) | `Cluster` |
| G5-2 | 5/D | `DevnetLiveTest.realDevnetAcceptsOurMessagesInSimulation` (opt-in `-Plockwallet.devnet=true`) | 19/11/2026 | Devnet real simula OK nossa transferência e create+init+delegate de stake (fee payer = identidade de validador, sigVerify off) | mensagens aceitas pela rede |
| G5-3 | 5/D | `requestAirdrop` Devnet/Testnet | 19/11/2026 | HTTP 410 “The RPC faucet is closed on this cluster. Request test SOL at https://faucet.solana.com” | E-28; teste de envio real financiado fica para um humano (faucet com captcha) |
| G6-1 | 6/E | https://github.com/solana-program/token-2022 `interface/src/extension/mod.rs` | main 19/11/2026 | ExtensionType 0–28 (inclui PermissionedBurn 28); padding de conta até 165, tipo de conta no byte 165, TLV u16/u16 | `TokenPolicy`, `TokenState` |
| G6-2 | 6/E | solders 0.29.0 + LiteSVM: CreateIdempotent, TransferChecked, CloseAccount (Token e Token-2022 com MetadataPointer) | 19/11/2026 | executados com sucesso | `tx-oracle.json` |
| G6-3 | 6/F | LiteSVM com Stake/Vote nativos: createWithSeed+Initialize+Delegate, Split, Deactivate, Merge, Withdraw | 19/11/2026 | executados; StakeStateV2 = 200 bytes | `StakeState`, `StakeIx` |

Pendentes (portões das próximas fases): 2 (teste em aparelho físico: KEK inutilizável fora do BiometricPrompt), 5 (envio real financiado na Devnet — faucet exige humano).
