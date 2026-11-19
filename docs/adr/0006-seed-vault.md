# ADR 0006 — Seed Vault somente com disponibilidade real (Fase G)

- **Status:** Aceito
- **Data:** 2026-11-19
- **Spec:** LOCK WALLET v2.0 + v2.1 + v2.2 (+ prompt 1.2 do proprietário); ver `docs/errata.md`.

## Contexto
Spec §23.3; prompt 1.2 (`SeedVaultProvider` quando `SeedVault.isAvailable` real).

## Decisão
`SeedVaultProvider` implementa a mesma interface `KeyCustodyProvider` (ADR 0016) e só é exibido se o SDK oficial confirmar disponibilidade em aparelho físico. Simulador apenas em testes. Sem quiz/LWRP/revelação de frase para carteiras Seed Vault (a frase não é exposta ao app). Sem migração automática de custódia.

## Alternativas rejeitadas
Simular Seed Vault; módulo stub criado antes da fase G.

## Consequências
Em aparelhos sem Seed Vault, a opção não aparece.

## Risco residual
Recuperação depende do fluxo do Seed Vault/fabricante.

## Evidência
Spec §23.3 (Gate 7 a verificar na fase G).

## Teste
Fase G: aparelho físico + simulador oficial.

## Condição para reabrir
Mudança no contrato oficial do SDK.
