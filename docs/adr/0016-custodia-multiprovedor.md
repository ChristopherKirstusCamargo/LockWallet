# ADR 0016 — Arquitetura de custódia multiprovedor, Lock Vault / Lock Connect e rede

- **Status:** Aceito (contrato; prompt 1.2)
- **Data:** 2026-11-19
- **Spec:** LOCK WALLET v2.0 + v2.1 + v2.2 (+ prompt 1.2 do proprietário); ver `docs/errata.md`.

## Contexto
Prompt 1.2: escolha de custódia por carteira, Seed Vault + Local, múltiplas carteiras, Lock Vault × Lock Connect, Transaction Security Analyzer, Network Manager, staking nativo, MWA.

## Decisão
- `KeyCustodyProvider` (em `:custody:local`, Fase B): `capabilities()`, `create(len: 12|24)`, `restore(material)`, `publicKeys()`, `sign(validatedMessage): Signature64` — recebe somente bytes já aprovados pelo analisador; nunca expõe seed/chave. Implementações: `LocalSecureProvider` (ADR 0001) e `SeedVaultProvider` (módulo `:custody:seedvault`, Fase G). Provider gravado por carteira; sem migração automática.
- **Lock Vault** (guarda; sem dApps) e **Lock Connect** (dApps; **seed separada**, nunca derivada da mesma frase) são tipos de carteira distintos no modelo de dados.
- **Transaction Security Analyzer** = parser allowlist de `:core:solana` (ADR 0007) + verificação de estado via RPC; nenhuma assinatura sem resumo derivado dos bytes.
- **Solana Network Manager** em `:data:rpc` (primário/secundário/custom, genesis hash fixado, backoff, commitments, divergência bloqueia).
- Staking nativo (Stake Program; nunca líquido) e MWA entram como extensões versionadas da allowlist nas fases F e H, com módulos próprios criados só nessas fases.

## Alternativas rejeitadas
Um único provider fixo; dApps na mesma seed do cofre.

## Consequências
Interfaces estáveis permitem adicionar Seed Vault/dApps sem tocar no envelope.

## Risco residual
Superfícies novas (MWA, staking) exigem atualizar threat model antes.

## Evidência
Prompt 1.2; spec §23.2–23.4.

## Teste
Testes de contrato por provider (fakes só em test source set).

## Condição para reabrir
Mudança no SDK Seed Vault ou spec MWA.
