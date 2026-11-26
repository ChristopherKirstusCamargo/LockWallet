# ADR 0018 — Transaction Security Analyzer: allowlist fechada para as Fases D/E/F

Status: aceito (2026-11-19). Complementa ADR 0006 (parser/allowlist) e ADR 0010 (máquina de estados).

## Contexto
A spec proíbe assinatura às cegas: o usuário aprova exatamente o que os bytes dizem. As Fases D (SOL), E (SPL/Token-2022) e F (stake nativo) precisam de instruções novas.

## Decisão
1. O analisador (`core/solana/analyzer`) decodifica **somente** mensagens legacy (v0/v1 recusadas: E-19) e só aceita esta lista fechada:
   System Transfer(2), CreateAccountWithSeed(3 — dono obrigatório Stake, espaço 200, endereço re-derivado);
   ComputeBudget SetComputeUnitLimit(2)/SetComputeUnitPrice(3) (no máximo um de cada; o app não os emite por padrão);
   Token/Token-2022 TransferChecked(12) e CloseAccount(9); ATA CreateIdempotent(1);
   Stake Initialize(0)/Delegate(2)/Split(3)/Withdraw(4)/Deactivate(5)/Merge(7).
2. Exatamente 1 assinador, que é o fee payer = a carteira; toda autoridade = a carteira; sem lockup nem custodiante; nenhuma conta gravável sem necessidade; sysvars em posição fixa; destinos de Initialize/Split criados na mesma transação.
3. Com contexto (lido da rede antes da revisão): mint/decimais/programa/dono de cada conta de token, política Token-2022 (ADR/E-26), conta congelada, delegado, lista de validadores (`getVoteAccounts`).
4. A custódia (`UnlockedWallet.signApproved`) **re-executa o analisador estático** e confere o hash da mensagem revisada antes de assinar; qualquer divergência ⇒ `NOT_APPROVED`.
5. O texto mostrado ao usuário é gerado das operações decodificadas (`TxText`), não da intenção do app.

## Consequências
Qualquer instrução fora da lista ⇒ bloqueio (não aviso). Oracle: `tools/oracle/generate_tx_fixtures.py` gera as mensagens com solders e as **executa** no LiteSVM (10 positivas, 24 negativas) — `fixtures/vectors/tx-oracle.json`.

## Fase E (tokens) — regras de app
- Lista de tokens só de dados on-chain (`getTokenAccountsByOwner` nos dois programas + `getMultipleAccounts` das mints); nome/símbolo de Metaplex (legacy) ou TokenMetadata dentro da própria mint (Token-2022, ponteiro = a mint), sempre rotulados "não verificado"; avatar monocromático local (nenhuma imagem remota).
- Envio: origem e mint relidas da rede no momento da revisão; destino = carteira ⇒ ATA derivada; ATA inexistente ⇒ `CreateIdempotent` na mesma tx, mostrado na revisão com o depósito; conta de token colada só se for da mesma mint; destino congelado ⇒ recusa.
- Fechar conta: só vazia (ou wSOL), não congelada, sem outra autoridade de fechamento; depósito volta à carteira.

## Fase F (stake nativo) — regras de app
- Nunca stake líquido. Contas `createWithSeed(carteira, "stake:N", Stake)` (1 assinador; descobertas sem `getProgramAccounts`, que é tentado só como complemento).
- Criar = CreateAccountWithSeed(valor + reserva de aluguel do RPC para 200 bytes) + Initialize(staker = withdrawer = carteira, sem lockup) + Delegate. Valor ≥ `getStakeMinimumDelegation`.
- Validadores só de `getVoteAccounts` (comissão, stake ativo, inadimplência); sem APY inventado; inadimplente/comissão alta ⇒ aviso; validador fora da lista ⇒ bloqueio (analisador com contexto relido no momento da revisão).
- Desativar (ativo/ativando), sacar tudo (inativo/não delegado), dividir (nova conta com reserva pré-paga; mínimo nos dois lados), unir (mesmo estado e validador; a simulação é o árbitro final). Contas com outra autoridade, lockup ou custodiante: só leitura (nunca trocamos autoridades — T-24).
- Ciclo de vida por época (ativando/ativo/desativando/inativo) é estimativa sem StakeHistory e é rotulado assim; recompensa da última época por `getInflationReward`.
