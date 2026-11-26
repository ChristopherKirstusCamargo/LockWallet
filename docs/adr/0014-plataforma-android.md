# ADR 0014 — Gate 0 e endurecimento de plataforma

- **Status:** Aceito
- **Data:** 2026-11-19
- **Spec:** LOCK WALLET v2.0 + v2.1 + v2.2 (+ prompt 1.2 do proprietário); ver `docs/errata.md`.

## Contexto
Spec §2.1 (targetSdk via Gate 0), §4.1, §16; v2.2 §8.3–8.4.

## Decisão
minSdk 31; **targetSdk 36 / compileSdk 36** (Play exige API 36 para apps novos e updates desde 31/08/2026; API 37 já existe mas exige AGP 9 e comportamento ainda não testado). Manifest: `allowBackup=false` **e** `dataExtractionRules` excluindo todos os domínios de cloud-backup e device-transfer (Android documenta que `allowBackup=false` não impede D2D em alguns OEMs); `usesCleartextTraffic=false`; NSC só com CAs do sistema (sem CAs do usuário, inclusive debug); nenhuma permissão na Fase A (INTERNET entra com `:data:rpc`); só a activity launcher exportada (receiver do profileinstaller removido); R8 remove `android.util.Log`. applicationId `com.lockwallet.devnet` (mainnet seria outro app id por decisão formal).

## Alternativas rejeitadas
Pinning de certificado agora (conflita com RPC editável; reavaliar na Fase D).

## Consequências
APK release inspecionado automaticamente (`verifyReleaseApk`).

## Risco residual
Robolectric roda sandbox API 35 (SDK 36 exige Java 21).

## Evidência
G0-1, G-BKP-1.

## Teste
`verifyReleaseApk`, `ManifestAndWindowSecurityTest`, lint (`AllowBackup`, `DataExtractionRules`… como erro).

## Condição para reabrir
Novo requisito Play ou migração para API 37.
