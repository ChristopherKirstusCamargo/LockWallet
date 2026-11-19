# ADR 0020 — Network Manager, cliente RPC e coordenador de transações

Status: aceito (2026-11-19).

## Decisão
- `:data:rpc`: JSON-RPC 2.0 só HTTPS, sem redirecionamento, respostas limitadas em bytes, JSON estrito (profundidade 32, inteiros exatos — nada de ponto flutuante). Nenhum segredo sai do aparelho: só endereços públicos e transações já assinadas.
- Redes: Devnet e Testnet (presets públicos da Solana Labs). **Mainnet visível e bloqueada** (`Cluster.locked`, `NetworkConfig` recusa construir; guarda de CI proíbe URLs mainnet no código). Toda URL (preset ou personalizada) é conferida por `getGenesisHash` (cache 10 min); genesis de outra rede ⇒ recusa; genesis da mainnet ⇒ "bloqueada".
- RPC secundário opcional: usado só em falha de transporte e para reconciliar (nunca para "escolher" a resposta mais conveniente).
- Coordenador (`TxCoordinator`): construída → analisada → taxa (`getFeeForMessage`, obrigatória) → simulada (`simulateTransaction`, obrigatória; falha/indisponível ⇒ bloqueio) → autorizada (PIN/biometria) → **SIGNED gravado em disco (journal atômico) antes do 1º envio** → enviada → confirmada/finalizada/falhou/expirada.
- Erro de `sendTransaction` (mesmo preflight) ⇒ UNKNOWN, nunca FAILED: um RPC hostil não pode induzir reenvio. Rebroadcast só dos MESMOS bytes enquanto `blockHeight ≤ lastValidBlockHeight`. EXPIRED só com evidência (altura > lvbh, status nulo com histórico, secundário sem divergência). Enquanto houver tx não resolvida da carteira, nova tx ⇒ BUSY (sem envio em dobro).
- Blockhash vencido entre revisão e PIN ⇒ nada é assinado; "Revisar de novo" monta uma tx nova e mostra de novo.

## Consequências
Sem backend próprio, sem telemetria. O operador do RPC vê IP e endereços públicos (dito na tela Rede).
