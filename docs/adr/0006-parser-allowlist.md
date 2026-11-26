# ADR 0006 — Parser por allowlist, transações desconhecidas e Token-2022

- **Status:** Aceito (contrato); implementação no Gate A4
- **Data:** 2026-11-19
- **Spec:** LOCK WALLET v2.0 + v2.1 + v2.2 (+ prompt 1.2 do proprietário); ver `docs/errata.md`.

## Contexto
Spec §13.5, §14; v2.1 §5; revisão: v2.0 aceitava metadata simples Token-2022, v2.1 nega tudo.

## Decisão
Allowlist fechada: System Transfer (u32 2 LE + u64), SPL Token legado TransferChecked (12 + u64 + u8), ATA CreateIdempotent (0x01), Compute Budget SetComputeUnitLimit (0x02+u32) e SetComputeUnitPrice (0x03+u64), uma de cada, antes das instruções de negócio. Somente mensagens legacy; v0/v1 recusadas com o texto “formato não suportado pela Lock Wallet (decisão de escopo)” — a rede aceita v1. **Token-2022: tudo bloqueado** (errata E-08; v2.1 prevalece). A fase F adiciona instruções (Stake) somente por extensão versionada desta allowlist, cada uma com fixtures contra oracle.

## Alternativas rejeitadas
Blocklist; reconhecer só o program ID; interpretar formato desconhecido como legacy.

## Consequências
Programas fora da allowlist são recusados até modelagem (sem blind signing).

## Risco residual
Programas allowlisted podem ter bugs próprios.

## Evidência
IDs conferidos contra solders (G6-IDS).

## Teste
Gate A4: vetores positivos/negativos por instrução vs Solana SDK/solders.

## Condição para reabrir
Nova instrução ou formato v1 com parser e fixtures.
