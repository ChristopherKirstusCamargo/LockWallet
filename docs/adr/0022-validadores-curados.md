# ADR 0022 — Validadores recomendados (bloco curado) e modo avançado no stake

- Estado: aceito (4.0.0)
- Contexto: requisito de produto — “Novo stake” não começa por uma busca em ~1 000 validadores, e sim por um bloco com pelo
  menos cinco opções consolidadas e seguras; a lista completa/busca/colar endereço fica atrás de “Avançado”.
  Continua **só stake nativo** (programa Stake da Solana; nunca stake líquido, pool ou token de recibo).

## Critérios objetivos (todos exigidos na revisão)

| # | Critério | Como foi medido | Limite |
|---|---|---|---|
| C1 | Histórico longo | época do primeiro stake (`first_epoch_with_stake`, Stakewiz) | ≤ época 350 (≈ 2 anos ou mais; época atual 1091) |
| C2 | No ar, sem inadimplência | `getVoteAccounts` (lista `current`, não `delinquent`); Stakewiz `uptime` e `vote_success` | presente em `current`; uptime ≥ 99,9 %; votos ≥ 99,8 % |
| C3 | Comissão razoável e estável | `commission` do `getVoteAccounts` (porcentagem) | ≤ 5 % |
| C4 | Não concentra a rede | fora da superminoria (menor grupo dos maiores que soma > 1/3 do stake ativo), calculada por nós sobre o `getVoteAccounts`; Stakewiz `above_halt_line=false` | fora; peso ≤ 1 % |
| C5 | Operador conhecido | site e identidade pública do operador; nome on-chain coerente | sim |
| C6 | Diversidade | país/datacenter (ASN) diferentes entre si; concentração de ASN (Stakewiz) | < 5 % por ASN |

Excluídos por critério (exemplos): Helius, Figment, Galaxy, Binance, Staking Facilities (superminoria ou peso > 1 %);
Everstake, P2P.org, Kiln, Coinbase, Kraken (comissão 5–10 % e/ou peso alto); Laine e Block Logic (bons, mas ambos no
mesmo ASN TeraSwitch com ~27 % de concentração); pools/LSTs (JitoSOL etc.) por serem stake líquido.

## Lista escolhida (conferida on-chain em 2026-11-19, época 1091)

| Validador | Conta de voto | Identidade (nó) | Comissão | Stake ativo | % da rede | Desde | Local |
|---|---|---|---|---|---|---|---|
| Shinobi Systems | `BLADE1qNA1uNjRgER6DtUFf7FU3c1TWLLdpPeEcKatZ2` | `Ninja1spj6n9t5hVYgF3PdnYz2PLnkt7rvaw3firmjs` | 0 % | 1 381 585 SOL | 0,32 % | época 160 | EUA (Hurricane Electric) |
| Solana Compass | `EARNynHRWg6GfyJCmrrizcZxARB3HVzcaasvNa8kBS72` | `CMPSSdrTnRQBiBGTyFpdCc3VMNuLWYWaSkE8Zh5z6gbd` | 0 % | 1 451 664 SOL | 0,34 % | época 242 | Alemanha |
| Chainflow | `CAf8jfgqhia5VNrEF4A7Y9VLD3numMq9DVSceq7cPhNY` | `2mMGsb5uy1Q4Dvezr8HK2E8SJoChcb2X7b61tJPaVHHd` | 5 % | 547 696 SOL | 0,13 % | época 67 | Suécia |
| Overclock | `AS3nKBQfKs8fJ8ncyHrdvo4FDT6S8HMRhD75JjCcyr1t` | `C1ocKDYMCm2ooWptMMnpd5VEB2Nx4UMJgRuYofysyzcA` | 0 % | 1 000 565 SOL | 0,23 % | época 318 | EUA (Datacamp) |
| Stakeconomy | `GNZ1PAAS33davY4Q1BMEpZEpVBtRtGvSpcTH5wYVkkVt` | `4QNekaDqrLmUENqkVhGCJrgHziPxkX9kridbKwunx9su` | 2 % | 164 317 SOL | 0,04 % | época 174 | Luxemburgo |
| Nansen | `2NxEEbhqqj1Qptq5LXLbDTP5tLa9f7PqkU8zNgxbGU9P` | `CoG8d9Fp2TFJRkAmrPMiPsGhQWHzdTTVoegEp9svRgmJ` | 0 % | 1 610 989 SOL | 0,38 % | época 302 | Holanda |

Corte da superminoria no momento: 4 091 807 SOL (todos os seis bem abaixo). Os seis estavam em `current` (nenhum em
`delinquent`) com créditos de voto completos nas últimas épocas.

Por que cada um:
- **Shinobi Systems** — operador independente veterano (desde a época 160, ≈ início de 2021), autor do stakeview.app; 0 %, ASN sem concentração.
- **Solana Compass** — operador e site educativo conhecido da comunidade; 0 %, 99,99 % no ar.
- **Chainflow** — operador profissional multi-rede de longa data, na Solana desde a época 67 (um dos mais antigos fora da
  superminoria); 5 % estável.
- **Overclock** — Overclock Labs, contribuidor do ecossistema; 0 %, 100 % no ar.
- **Stakeconomy** — operador europeu desde a época 174; 2 %, 100 % no ar.
- **Nansen** — empresa de análise on-chain conhecida publicamente; 0 %, sem inadimplência.

## Regras ao vivo (no app)

1. A lista é **constante no código** (`Curated.LIST`): nome, conta de voto, identidade do nó e comissão de referência.
   Não há servidor nem lista baixada (sem backend).
2. A saúde é **relida do `getVoteAccounts`** ao abrir “Novo stake” e a cada 60 s enquanto a tela está aberta (fora da
   thread principal), e **de novo na revisão** — a decisão nunca usa o que está na tela.
3. Estado por validador:
   - **fora do ar** (lista `delinquent`) ou **ausente** ⇒ some do bloco; aviso “N validadores recomendados ocultos”.
   - **identidade do nó diferente** da revisada, **vote account repetida** na resposta, **comissão impossível** ⇒
     tratado como ausente (resposta contraditória/hostil falha fechado).
   - **comissão subiu** (> referência + 2 pontos, ou > 10 %) ⇒ aparece marcado “comissão subiu” e **não pode ser
     escolhido**; a revisão recusa com mensagem clara.
   - **entrou na superminoria** ⇒ marcado “concentrado” (ainda pode ser escolhido; explicação na “?”).
4. **Avançado**: abre a lista completa (40 primeiros por stake), busca por prefixo e colar endereço de voto, com aviso
   forte; escolha fora do bloco gera aviso extra também na revisão (“fora da lista recomendada”). O analisador e as
   regras anteriores (validador precisa existir na rede, aviso de inadimplência e de comissão ≥ 10 %) continuam.
5. Explicações só nas folhas “?” (Validadores recomendados, Concentração, Modo avançado).

## Fontes (acesso em 2026-11-19, America/Campo_Grande)

- `getVoteAccounts` (commitment `finalized`) em `https://solana-rpc.publicnode.com` — contas de voto, identidade,
  comissão, stake ativo, `current`/`delinquent`, `epochCredits`. (O RPC público da Solana Foundation respondeu 429 no
  momento.)
- Stakewiz API pública `https://api.stakewiz.com/validators` — nome on-chain, site, primeira época com stake, uptime,
  taxa de votos, ASN/país, flag de superminoria (`above_halt_line`).
- Sites dos operadores: shinobi-systems.com, solanacompass.com/staking, solana.chainflow.io, overclock.one,
  stakeconomy.com, nansen.ai.

## Revisão

Reavaliar a lista a cada versão: rodar `getVoteAccounts`, conferir C1–C6 e atualizar a tabela e `Curated.LIST`
(teste `CuratedValidatorsTest` falha se a lista tiver < 5 entradas, chaves inválidas/repetidas ou comissão de
referência acima do teto).

## Consequências

- Usuário comum escolhe entre seis opções revisadas; o rendimento não depende de achar um validador numa lista longa.
- Um validador pode subir a comissão **depois** que você delegou: o app mostra a comissão atual na conta de stake,
  mas não move o stake sozinho (mover exige desativar e delegar de novo — decisão do usuário).
- Curadoria é opinião informada, não garantia: está documentada aqui e no relatório de segurança.

## Adendo (versão final) — ordem por segurança e retorno

Ordem do bloco: (1) **nível de segurança** — saudável antes de “concentrado”; fora do ar, ausente, identidade trocada
ou comissão subiu ficam escondidos/bloqueados e nunca sobem por retorno; (2) **retorno estimado conhecido** antes de
desconhecido; (3) **índice de retorno** maior primeiro; desempates: comissão efetiva menor, créditos maiores, posição
na lista.

- **Comissão efetiva** = maior entre `commission` (%) e `inflationRewardsCommissionBps` de `getVoteAccounts`.
- **Desempenho** — desde o Alpenglow (época 1064, 21/10/2026) o campo `epochCredits` da conta de voto registra
  **lamports de recompensa** (parte do votante + do líder), que crescem com o stake; não são mais créditos de voto.
  Por isso o desempenho é **recompensa ÷ stake ativo** em cada uma das 3 últimas épocas completas, dividido pela
  **mediana ponderada por stake** da rede (validadores saudáveis, mínimo 3 amostras), limitado a 0..1. Épocas até
  1064 (outra unidade; linha sentinela u64::MAX na migração) são ignoradas. Dado ausente, negativo ou época repetida
  ⇒ “sem dado” (nunca vantagem). Stake ativo atual aproxima o snapshot da época anterior (igual para todos).
- **Índice** = (1 − comissão efetiva) × desempenho. É uma estimativa relativa, não promessa de rendimento.
- **MEV não entra**: a comissão de MEV (Jito) não é exposta por RPC padrão; inventar ou buscar em serviço de terceiros
  violaria “sem backend/sem dados inventados”. Explicado na folha “?” da ordem.
