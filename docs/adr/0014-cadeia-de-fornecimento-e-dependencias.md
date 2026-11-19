# ADR 0014 — Cadeia de fornecimento, versões fixadas e biblioteca criptográfica

- **Status:** Aceito (acréscimo à spec)
- **Data:** 2026-11-19
- **Spec:** LOCK WALLET v2.0 + v2.1 + v2.2 (+ prompt 1.2 do proprietário); ver `docs/errata.md`.

## Contexto
Spec §6.4 e §16: implementação identificada, versão, hash, licença e política de atualização.

## Decisão
- **Cripto:** Bouncy Castle `org.bouncycastle:bcprov-jdk18on:1.87` (lançado 2026-11-11), API *lightweight* (sem registro/resolução de provider JCA). SHA-256 do JAR `12d2d3dd82a9ec15e92ec7c7943ee64f6097c6dc12c07e984f1693b77565b9aa` (= Maven Central), assinatura PGP válida da chave `7B12 1B76 A7ED 6CE6 E60A D517 84E9 13A8 E3A7 48C0` (Legion of the Bouncy Castle, Maven signer). Licença: Bouncy Castle License (MIT). Usos: SHA-256/512, HMAC, PBKDF2, RFC 8032 Ed25519; Fase B: Argon2 (v0x13) e GCM. AES-GCM do envelope usa Android Keystore.
- **Tudo fixado** em `gradle/libs.versions.toml`; **Gradle dependency verification** (`gradle/verification-metadata.xml`, sha256 de todo artefato) — build falha se um hash mudar. Wrapper Gradle 8.14.3 com `distributionSha256Sum` e JAR do wrapper conferido (`7d3a4ac4…`). Actions do CI fixadas por commit.
- Toolchain: AGP 8.13.2, Kotlin 2.2.21, Compose BOM 2026.06.00 (o mais novo cujas AARs aceitam compileSdk 36/AGP 8.13; 2026.08+ exige compileSdk 37 + AGP 9.1), core-ktx 1.18.0, activity-compose 1.13.0.
- Atualização: mensal ou imediata para CVE; regenerar verification-metadata em commit separado com revisão do diff.

## Alternativas rejeitadas
TweetNaCl/libsodium via JNI (código nativo extra); Tink (não cobre SLIP-0010/Argon2id da forma exigida); provider JCA "BC" por nome (ambíguo no Android).

## Consequências
Atualizar dependência exige atualizar hashes conscientemente.

## Risco residual
Hash fixa o artefato, não prova ausência de bug upstream.

## Evidência
G-SUP-1..3 no verification-log.

## Teste
`./gradlew ciCheck` com verificação estrita.

## Condição para reabrir
CVE no BC; migração para AGP 9/compileSdk 37 (próximo passo planejado).
