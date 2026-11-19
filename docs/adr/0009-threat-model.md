# ADR 0009 — Threat model e adversários

- **Status:** Aceito — ver `docs/threat-model.md`
- **Data:** 2026-11-19
- **Spec:** LOCK WALLET v2.0 + v2.1 + v2.2 (+ prompt 1.2 do proprietário); ver `docs/errata.md`.

## Contexto
Spec §3, v2.2 §9/SEC-03 e revisões externas.

## Decisão
Matriz viva em `docs/threat-model.md`, atualizada antes de cada gate que adiciona superfície (RPC, staking, Seed Vault, dApps, AIRGAP).

## Alternativas rejeitadas
Lista de controles sem adversário.

## Consequências
Cada controle tem teste e dono (gate).

## Risco residual
Ver matriz.

## Evidência
Spec §3; v2.2 §9.

## Teste
Revisão por gate.

## Condição para reabrir
Nova superfície.
