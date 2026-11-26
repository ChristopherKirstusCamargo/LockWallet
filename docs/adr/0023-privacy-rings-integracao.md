# ADR 0023 — Solana Privacy Rings (Helius)

- **Status:** Aceito. Ativo na versão final (Mainnet, só SOL).
- **Data:** 2026-11-22, revisado em 2026-11-26.

## Contexto

A Lock Wallet é uma carteira Android de autocustódia, sem backend próprio, com custódia local cifrada e um analisador
de transações com allowlist fechada: nada é assinado só porque veio de um SDK.

O Default Ring da Solana Privacy Rings (Helius) é um anel confidencial permissionless. Numa transferência privada, o
ativo e a quantidade são cifrados; os endereços de origem e destino continuam públicos. Depósitos (do saldo público
para o confidencial) e retiradas (de volta ao público) revelam endereço, ativo e valor. **É confidencialidade de
valor, não anonimato.** Custom Rings anônimos não fazem parte da integração.

## Decisão

### Escopo

- Só Mainnet e só SOL. SPL e Token-2022 ficam fora.
- Operações: depósito, transferência confidencial entre Shielded Addresses e retirada pública (inclusive de volta
  para a própria carteira).
- Endpoint Helius **dedicado**, configurado pelo usuário na aba **Privado**: HTTPS, host `helius-rpc.com` (ou
  subdomínio), `api-key` não vazia e genesis hash da Mainnet conferido no próprio endpoint antes de salvar. O tráfego
  `/v1/spr` (indexador e provador) nunca usa o RPC comum nem o secundário, e o RPC comum nunca é promovido a endpoint
  de privacidade. Sem endpoint, a operação é recusada antes de pedir o PIN. Salvar ou remover o endpoint encerra a
  sessão confidencial.
- A URL fica nas preferências locais (sem backup do Android). A interface mostra só o host, nunca a chave; o campo de
  edição é mascarado.

### Ponte nativa e custódia

- O crate `native/privacy-ring` fixa o SDK Rust no commit
  `helius-labs/privacy-rings@42de575202f07adf6cfe9194ae9227445797bd8e` (toolchain 1.98.1, `Cargo.lock`, build
  `--locked`) e é empacotado como JNI para `arm64-v8a` e `x86_64`.
- A custódia local assina a mensagem de derivação `TSPP/derive/v1` só depois de validar a carteira e exigir o PIN.
  Só essa assinatura determinística cruza a fronteira JNI, nunca a semente ou a chave privada. O lado Rust confere a
  assinatura contra a chave pública, deriva as chaves de visualização e de nullifier e as mantém só em memória
  enquanto a sessão está aberta. Sair da tela, bloquear o app ou cancelar fecha o handle nativo, descarta operações
  preparadas e zera os buffers.
- A ponte usa `UtxoScan`, `LocalShieldedKeys`, `ConfidentialTransaction` e `Submission::finish_unsigned_sync`. O
  SDK não assina nem transmite. As chaves de visualização não são enviadas a nenhum serviço.
- Provas: provador remoto Helius com `SignedTeePolicy::for_network(Mainnet)`; política ausente, expirada ou inválida
  bloqueia.

### Revisão, assinatura e envio

- O resultado preparado pela ponte precisa corresponder ao pedido: mesma operação, mesmo valor e, quando houver,
  mesmo destino público. Na transferência, o destino digitado é obrigatório. Qualquer diferença descarta a mensagem.
- A mensagem Solana V1 tem caminho próprio (não é convertida em Legacy), com limite de 4.096 bytes. A taxa exibida e
  limitada é calculada a partir dos bytes da mensagem (custo base + prioridade declarada), nunca só pela resposta do
  RPC.
- O `TxCoordinator` confere Mainnet, blockhash, taxa e simulação antes da autorização. A simulação precisa devolver o
  saldo da carteira depois da operação: se a queda passar de (valor do depósito, ou zero) + taxa + 0,01 SOL de margem
  de aluguel, a operação é recusada; sem o saldo simulado, também.
- A assinatura final acontece em `:custody:local`, com PIN próprio e validação nativa de bytes, hash, operação e uso
  único. O registro assinado é gravado antes do broadcast; os mesmos bytes são enviados aos endpoints configurados.
- As proteções de envio valem aqui: transferências confidenciais e retiradas para terceiros contam no limite diário e
  respeitam “Somente contatos”.
- O journal guarda só dados públicos e operacionais (assinatura, estado, taxa, blockhash); valor, ativo e mint ficam
  nulos. O saldo confidencial é consultado de novo no indexador e decifrado localmente; não é gravado.

### Interface

- Aba própria **Privado** na barra inferior: estado do recurso, o que fica visível em cada operação, configuração
  do endpoint e acesso às operações.
- Os formulários de envio comuns indicam que a transferência é pública. A revisão diferencia depósito, transferência
  confidencial e retirada pública, sem usar “anônimo” ou “totalmente privado”.

## Falhas

Erros de RPC, indexador, prova/TEE, simulação, blockhash ou custódia bloqueiam a assinatura. Não há fallback para
envio público nem saldo presumido como zero.

## Riscos residuais

- O endpoint Helius vê os endereços envolvidos, os horários e o IP do aparelho.
- O provador remoto depende da política TEE da Helius.
- Endpoint e servidor secundário em conluio podem mentir na simulação.
- Sem auditoria externa e sem teste com fundos reais em aparelho físico neste ambiente.

## Testes

`cargo test --locked --manifest-path native/privacy-ring/Cargo.toml --lib`; `CoordinatorTest` (taxa V1, variação de
saldo, saldo simulado ausente); testes de fluxo e de interface da aba Privado.
