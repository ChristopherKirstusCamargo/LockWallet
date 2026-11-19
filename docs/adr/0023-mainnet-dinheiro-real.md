# ADR 0023 — 2.0.0: Mainnet (dinheiro real), revisão de segurança e novo design

- **Status:** Aceito
- **Data:** 2026-11-19
- **Spec:** v2.0 §17 (gates), §20 (rede), §23.4 (dApps); ADRs 0011, 0019, 0020, 0022; errata E-36..E-45.
- **Decisão do dono:** “liberar rede principal para dinheiro, nada de testnet” após revisão de segurança agressiva.

## Decisão
1. **Somente Mainnet no app.** Devnet/Testnet, faucet e airdrop saem do app (`Cluster.usable` só MAINNET). Regra de CI
   `TEST_CLUSTER` impede que endpoints de teste, `requestAirdrop` ou faucet voltem ao código de produção. Pedidos
   de dApp para outra rede são explicados e recusados (`ERROR_CLUSTER_NOT_SUPPORTED`).
2. **Endpoints padrão:** primário `https://api.mainnet-beta.solana.com`, secundário `https://solana-rpc.publicnode.com`
   (públicos, sem chave, sem telemetria). Leitura com fallback (também em `RPC_ERROR` e rate-limit com espera de
   900 ms); **envio para todos os endpoints** (mesmos bytes). RPC personalizado: só HTTPS, genesis hash conferido
   (Mainnet), nada é salvo se divergir; “Restaurar padrão”.
3. **Taxa e compute budget (transações próprias):** `SetComputeUnitLimit` (unidades da simulação +margem; SOL simples
   = 1 500 CU) + `SetComputeUnitPrice` = p75 de `getRecentPrioritizationFees`, limitado a [20 000, 2 000 000]
   µlamports/CU e a **0,001 SOL** de prioridade no total. Taxa mostrada = max(cálculo local, `getFeeForMessage`).
4. **dApps — teto de taxa:** prioridade > 0,005 SOL (5 000 000 lamports) ⇒ `FEE_TOO_HIGH` (bloqueio) no analisador e
   no firewall; taxa não calculável ⇒ bloqueio. Revisão mostra “Taxa máxima”.
5. **Expiração com evidência (anti pagamento duplo):** EXPIRED só se o primário diz `isBlockhashValid=false` **e**, havendo
   secundário, ele está acessível e confirma: status da assinatura nulo, altura > `lastValidBlockHeight` e blockhash
   inválido. Caso contrário fica UNKNOWN (o usuário não é convidado a reenviar).
6. **Respostas JSON-RPC:** `id` conferido; respostas de outro pedido são rejeitadas.
7. **App:** pacote `com.lockwallet.app`, `versionCode 2` — instala como app **novo** (dados da 1.0.0 não migram; a 1.0.0
   era só rede de teste).
8. **Design 2.0 (ADR 0018 revisado):** dois temas monocromáticos (preto vidro / claro), verde/vermelho só para variação de
   preço; bottom sheets **dentro da janela** (nunca Dialog/Popup: janelas separadas não herdam FLAG_SECURE nem
   `filterTouchesWhenObscured`); ícone “?” em toda opção que gera dúvida (o que é / para que serve / exemplo);
   portão de bloqueio rígido no topo da UI; colar frase limpa a área de transferência.

## Por que liberar
Revisão (CHANGELOG 2.0.0, threat model T-48..T-57) encontrou e corrigiu todos os problemas graves/médios/baixos
conhecidos; `ciCheck` verde; testes adversariais (RPC mentiroso, respostas malformadas, fuzz, corrida, morte de processo,
bloqueio, sobreposição, logs) passam; simulação real na Mainnet com o RPC público validou formato de mensagem e taxa.

## Riscos residuais (honestos)
- **Sem auditoria externa** (spec §17.4 recomenda antes de dinheiro real) e **sem teste em aparelho físico com fundos reais
  neste ambiente**. Recomendação: começar com valores pequenos.
- RPC pode mentir sobre saldo, aluguel e simulação (T-34); o analisador confere bytes locais, não a verdade da rede.
- RPC personalizado sem secundário ⇒ expiração depende só dele.
- Aluguel de ATA Token-2022 estimado; teclado (IME) pode aprender texto (usamos teclado de senha); strings da frase
  em `TextField` (ADR 0013).
