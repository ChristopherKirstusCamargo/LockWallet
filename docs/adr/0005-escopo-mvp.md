# ADR 0005 — Escopo, fases e ordem de entrega

- **Status:** Aceito (atualizado com o prompt 1.2)
- **Data:** 2026-11-19
- **Spec:** LOCK WALLET v2.0 + v2.1 + v2.2 (+ prompt 1.2 do proprietário); ver `docs/errata.md`.

## Contexto
Spec §2 e §18 (MVP Devnet A–E; staking F; Seed Vault G; dApps H; auditoria I). O proprietário quer o produto completo numa entrega única (staking nativo, Seed Vault, dApps/MWA, Lock Vault/Connect).

## Decisão
Uma entrega final, construída por gates internos na ordem A→I; cada gate só avança com testes verdes. Mainnet permanece desligada em todo build até auditoria externa (requisito inegociável §2.3). Ver `docs/scope-debate.md`.

## Alternativas rejeitadas
Pular gates para ganhar tempo; habilitar mainnet antes de auditoria.

## Consequências
A entrega é maior e chega de uma vez; funções de fases posteriores herdam parser/custódia já testados.

## Risco residual
Funções de fases posteriores não têm auditoria até a fase I.

## Evidência
Requisito de produto, 2026-11-19; spec §18.

## Teste
Guarda `verifyForbiddenApis` impede endpoint mainnet no código.

## Condição para reabrir
Decisão formal do proprietário após auditoria.
