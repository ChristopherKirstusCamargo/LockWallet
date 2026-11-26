# ADR 0015 — Arquitetura de custódia, analisador e rede

- **Status:** Aceito (contrato; prompt 1.2)
- **Data:** 2026-11-19
- **Spec:** LOCK WALLET v2.0 + v2.1 + v2.2 (+ prompt 1.2 do proprietário); ver `docs/errata.md`.

## Contexto
Prompt 1.2: contrato de custódia, múltiplas carteiras, Transaction Security Analyzer, Network Manager e staking nativo.

## Decisão
- `KeyCustodyProvider` (em `:custody:local`, Fase B): `capabilities()`, `create(len: 12|24)`, `restore(material)`, `publicKeys()`, `sign(validatedMessage): Signature64` — recebe somente bytes já aprovados pelo analisador; nunca expõe seed/chave. Implementação: `LocalSecureProvider` (ADR 0001). O provider é gravado por carteira; sem migração automática.
- **Transaction Security Analyzer** = parser allowlist de `:core:solana` (ADR 0006) + verificação de estado via RPC; nenhuma assinatura sem resumo derivado dos bytes.
- **Solana Network Manager** em `:data:rpc` (primário/secundário/custom, genesis hash fixado, backoff, commitments, divergência bloqueia).
- Staking nativo (Stake Program; nunca líquido) entra como extensão versionada da allowlist na fase F.

## Alternativas rejeitadas
Um único provider fixo, sem interface (dificultaria testes de contrato e a revisão da fronteira de custódia).

## Consequências
A interface estável isola a custódia do restante do app; a assinatura só recebe bytes já analisados.

## Risco residual
Superfícies novas exigem atualizar o threat model antes.

## Evidência
Prompt 1.2; spec §23.2.

## Teste
Testes de contrato por provider (fakes só em test source set).

## Condição para reabrir
Novo provedor de custódia ou mudança no contrato de assinatura.
