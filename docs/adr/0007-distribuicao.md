# ADR 0007 — Sem telemetria/backend/updater; distribuição, assinatura e licença

- **Status:** Aceito; licença decidida: Apache-2.0 (proprietário, 2026-11-19)
- **Data:** 2026-11-19
- **Spec:** LOCK WALLET v2.0 + v2.1 + v2.2 (+ prompt 1.2 do proprietário); ver `docs/errata.md`.

## Contexto
Spec §16; v2.1 §9 (comparar MIT × Apache-2.0, não publicar sem aprovação).

## Decisão
Sem Firebase/analytics/crash reporting/backend/updater. `dependenciesInfo` desligado no APK. Release assinado pelo projeto, com SHA-256 do APK, certificado, SBOM e changelog publicados. **Licença: Apache-2.0 (decidida pelo proprietário em 2026-11-19)** (concessão explícita de patentes, NOTICE; compatível com Bouncy Castle (MIT-like), AndroidX (Apache-2.0), Kotlin (Apache-2.0)). `LICENSE` (texto integral) e `NOTICE` na raiz do repositório.

## Alternativas rejeitadas
MIT (sem cláusula de patentes); GPL (atrito com distribuição em lojas).

## Consequências
Sem dados de uso; diagnóstico depende de relatos voluntários.

## Risco residual
Play Store pode re-assinar; canal próprio obrigatório.

## Evidência
Spec §16.

## Teste
`verifyReleaseApk`; ausência de SDKs de telemetria no grafo (verification-metadata).

## Condição para reabrir
Mudança de modelo de distribuição.
