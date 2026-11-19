# Arquitetura

```
:app ──► :custody:local ──► :core:crypto
  │            └──────────► :core:solana
  ├──► :data:rpc ─────────► :core:solana
  ├──► :data:price (JVM, sem dependências; preço público HTTPS — ADR 0017)
  └──► :core:solana
```
`:app` não enxerga `:core:crypto` (guarda `verifyModuleBoundaries`). Módulos futuros só são criados na sua fase (spec §5): `:custody:seedvault` (G), `:feature:staking` (F), `:connect:mwa` (H), `:core:airgap`/`:data:transport-android` (J).

| Módulo | Hoje (Fase A) | Próximo |
|---|---|---|
| `:core:crypto` (JVM) | BIP-39, SLIP-0010, `SecretBytes`, LWRP v1 (estrutura) | Argon2id, AES-GCM, LWRP cifrado (B) |
| `:core:solana` (JVM) | Base58, `SolanaPublicKey`, `ProgramIds`, máquina de estados | codec legacy, parser allowlist / Transaction Security Analyzer (A4) |
| `:custody:local` (Android) | envelope LWLE v1, KEK Keystore, PIN + limitador, LWRP, `KeyCustodyProvider`/`LocalSecureProvider`, `DeviceSecurityCapabilityManager`, SAF | assinatura com o analisador (D) |
| `:data:rpc` (JVM) | vazio | Solana Network Manager (D) |
| `:data:price` (JVM) | Coinbase → Kraken, estado fresco/desatualizado/indisponível | — |
| `:app` | tela de fundação, FLAG_SECURE | cerimônias (C) |

Guardas de CI (`./gradlew ciCheck`): fronteiras de módulos, APIs proibidas (log, print, provider JCA, mainnet, Float/Double em valores, Android/rede no core), testes JVM + Robolectric, assembleDebug, inspeção do manifest do APK release (aapt2), lint release.
