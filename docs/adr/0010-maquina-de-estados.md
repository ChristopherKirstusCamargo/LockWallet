# ADR 0010 — Máquina de estados unificada, bytes assinados e expiração

- **Status:** Aceito — implementado (`TransactionStateMachine`)
- **Data:** 2026-11-19
- **Spec:** LOCK WALLET v2.0 + v2.1 + v2.2 (+ prompt 1.2 do proprietário); ver `docs/errata.md`.

## Contexto
v2.0 §13.1 não tinha transição para Expired nem definia Cancelled; v2.2 §7.1 tinha outra máquina (errata E-05).

## Decisão
Uma máquina só (`core:solana/tx`). Estados: DRAFT, PREPARED, SIMULATED, AWAITING_AUTHORIZATION, SIGNED, SUBMITTED, PROCESSED, CONFIRMED, FINALIZED, FAILED, EXPIRED, UNKNOWN, CANCELLED. CANCELLED só antes da assinatura. SIGNED → SUBMITTED/UNKNOWN. EXPIRED só a partir de SUBMITTED/UNKNOWN com evidência: altura > lastValidBlockHeight **e** busca histórica (`searchTransactionHistory=true`) sem inclusão **e** sem divergência entre RPCs. Retransmissão apenas dos mesmos bytes enquanto altura ≤ lastValidBlockHeight. Nova transferência só após FAILED/EXPIRED/CANCELLED e nova confirmação. Mapeamento AIRGAP: INTENT→DRAFT, READY_TO_RELAY→SIGNED(+flag), CONSUMED/INVALIDATED reservados (J6). Expiração = 150 **blocos** após o blockhash, medida por lastValidBlockHeight (não slots nem tempo).

## Alternativas rejeitadas
Duas máquinas; timer de 60 s.

## Consequências
Persistência (Fase D) aplica a tabela com compare-and-set e unicidade (genesis, assinatura).

## Risco residual
RPCs podem mentir em conjunto.

## Evidência
Solana RPC docs (Gate 5, a revalidar na Fase D).

## Teste
`TransactionStateMachineTest`.

## Condição para reabrir
Novo provider de autorização (J6).
