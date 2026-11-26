# Errata consolidada v2.0 / v2.1 / v2.2 (+ prompt 1.2)

Precedência: requisitos inegociáveis v2.0 → v2.1 → v2.2 → ADRs → docs oficiais. Cada linha é uma decisão fechada.

| ID | Conflito / texto antigo | Resolução decidida | Onde |
|---|---|---|---|
| E-01 | LWRP v2.0: tamanho 62 B–1 MiB × payload fixo v2.1 | Tamanho exato = 110 + material_length ∈ {126,130,134,138,142,174}; validado antes da KDF | ADR 0004, `Lwrp.kt` |
| E-02 | account_index u32 sem limite | Rejeitar ≥ 2^31 (índice hardened) em LWRP, envelope e derivação | ADR 0003/0004 |
| E-03 | Entropia só 16/32 B (v2.0 §6.1) | 16/20/24/28/32 B na restauração/LWRP; criação 12 ou 24 palavras (prompt 1.2) | ADR 0003 |
| E-04 | Senha LWRP 8 caracteres; PIN “recomendar 8 dígitos” como proteção | Senha ≥ 16 code points NFC/UTF-8 ≤ 256 B, sugerida 8 palavras (88 bits); PIN é conveniência | ADR 0002/0004 |
| E-05 | Duas máquinas de estado (v2.0 §13.1 sem Expired/Cancelled; v2.2 §7.1) | Máquina única com mapeamento AIRGAP; EXPIRED só com evidência | ADR 0010, `TransactionState.kt` |
| E-06 | “getFeeForMessage + somar priority fee” (v2.0 §12.6) | Revogado: fee = getFeeForMessage da mensagem final (já com Compute Budget); null ⇒ bloquear; teto CU 1.400.000 a revalidar; priority fee padrão 0 | ADR 0006/0010 |
| E-07 | Passphrase “quando suportada” | Sem passphrase no MVP; API interna só para vetores | ADR 0003 |
| E-08 | Token-2022 “metadata simples” aceito (v2.0) × tudo bloqueado (v2.1) | v2.1 prevalece: Token-2022 inteiramente bloqueado no MVP | ADR 0006 |
| E-09 | Simulação obrigatória (v2.0 §13.4) × assinatura offline sem RPC (v2.2) | Online: simulação obrigatória (máquina exige SIMULATED). Offline (AIRGAP, fase J): assinador mostra “não simulado”, só SOL nativo, confirmação reforçada; o relay simula antes do envio | ADR 0010 |
| E-10 | Fee payer definido pelo coordenador (v2.2 §4.2) × deve ser a carteira assinadora (§2.3) | Assinador verifica fee payer = carteira ativa; nunca aceita o informado | ADR 0006 |
| E-11 | Assinador offline “valida cluster” — mensagem legacy não contém genesis hash | Assinador não consegue distinguir devnet/mainnet pelo blockhash; mitigação: cluster fixo no build (devnet-only app id), contexto de blockhash rotulado como externo e confirmação reforçada | ADR 0014, threat model T-15 |
| E-12 | “150 slots” | Validade = blockhash recente nos últimos 150 **blocos**; usar lastValidBlockHeight | ADR 0010 |
| E-13 | “Portão A/C” × portões numerados 0–8 | Letras = fases; números = portões de pesquisa; A1–A6 = gates v2.1 | este doc |
| E-14 | Primeira tarefa: Fase A × Gate A1 × J0 | Ordem: Fase A (inclui A1/A2 contratual) → A3–A6 → B…I; J0 só após A1–A6 | ADR 0005 |
| E-15 | Interface `SolanaRpc` sem sendTransaction/getSignatureStatuses/getFeeForMessage | Interface final (Gate A5) inclui os três + getGenesisHash | `:data:rpc` (Fase D) |
| E-16 | ADRs V22-AIR-01..10 citados, não definidos | Criados no Gate J0 (após A1–A6) | ADR 0005 |
| E-17 | J6 (programa substituto de durable nonce usa mensagens v1) × legacy-only | J6 exige parser v1 aprovado como pré-requisito | ADR 0006 |
| E-18 | Durable Nonce | Em descontinuação (SIMD-0437 relatado ativo na mainnet desde 2026-09-03, revisão externa — a revalidar no Gate J6); não usar | v2.2 §3.2 |
| E-19 | Mensagem de recusa v0/v1 | “Formato não suportado pela Lock Wallet (decisão de escopo)” — a rede aceita v1 | ADR 0006 |
| E-20 | Compute Budget program ID | `ComputeBudget111111111111111111111111111111` conferido contra solders | `ProgramIds.kt` |
| E-21 | Backoff v2.0 (1 min…24 h após 5 erros) × prompt 1.2 (5 erros ⇒ 1 h) | 5 erros ⇒ 1 h, depois dobra até 24 h; nunca apaga carteira | ADR 0009 |
| E-22 | ADRs em `architecture/decisions` (prompt 1.2) × `docs/adr` (spec) | `docs/adr` (spec normativa) | — |
| E-23 | ADR 0001 (Fase A) gravava `dek_pin‖tag` em claro além do wrap pela KEK | Campo removido; `dek_pin` só dentro de `dek_pin_blob`; tamanho = material_length + 236 | ADR 0001, `LocalEnvelope.kt` |
| E-24 | Prompt 1.2: “nenhuma rede antes da Fase D” × exigência nova de preço ao vivo | `INTERNET` entra na Fase C só para preço público HTTPS (ADR 0016); RPC continua na Fase D | ADR 0016 |
| E-25 | Restauração multi-conta de uma vez × auth-per-use (1 operação por biometria) | Escolhe-se 1 conta por vez; “Adicionar conta (mesma frase)” cria as seguintes | ADR 0002 |
| E-26 | E-08 (“Token-2022 todo bloqueado no MVP”) × Fase E (portão Token-2022 do §18) | Token-2022 suportado com **default deny**: mint só com MetadataPointer(18)/TokenMetadata(19); conta só com ImmutableOwner(7); qualquer outra extensão (transfer hook, permanent delegate, transfer fee, confidential, pausable, default state, non-transferable, etc.) é exibida com o risco e bloqueia o envio; extensão desconhecida ⇒ bloqueio | `TokenPolicy`, ADR 0018 |
| E-27 | Spec: staking fora do MVP × requisito de produto (Fase F) | Só stake **nativo** (nunca líquido), contas derivadas `createWithSeed(owner,"stake:N",Stake)`, autoridades = a carteira, sem lockup | ADR 0018 |
| E-28 | Airdrop Devnet pelo RPC público | Em 2026-11-19 o faucet do RPC público responde HTTP 410 “The RPC faucet is closed on this cluster” (Devnet e Testnet). O botão tenta `requestAirdrop`; em 410 orienta a usar faucet.solana.com (copiar endereço / abrir) | verification log G5-3 |
| E-29 | Prompt: “Devnet only” × Fase D “Devnet/Testnet” | Ambas, cada uma conferida pelo genesis hash; Mainnet bloqueada | ADR 0019 |
| E-30 | Segunda carteira pede 2 autenticações | Mantido: são 2 operações de Keystore distintas com auth-per-use; unir exigiria afrouxar a validade da KEK (não é “trivialmente seguro”) | ADR 0002 |
| E-35 | QR de endereço | CameraX + ZXing local (sem Google Play services), CAMERA pedida só ao tocar “Ler QR”; só o endereço é lido; pedidos de transação Solana Pay recusados | ADR 0014 |
| E-36 | E-29 (“Devnet/Testnet; Mainnet bloqueada”) × decisão do projeto 2.0.0 | Só Mainnet; Devnet/Testnet/faucet/airdrop removidos do app; regra de CI `TEST_CLUSTER` | ADR 0020 |
| E-37 | E-28 (airdrop) | Removido (não existe na Mainnet) | ADR 0020 |
| E-39 | Taxa de prioridade ausente na 1.0.0 (não pousa na Mainnet congestionada) | Compute budget automático, p75, teto 0,001 SOL | ADR 0020 |
| E-41 | EXPIRED decidido só pelo RPC primário | Exige evidência do secundário (anti pagamento duplo) | ADR 0020 |
| E-42 | Pacote da 1.0.0 × 2.0.0 | `com.lockwallet.app`, instala como app novo, sem migração | ADR 0020 |
| E-43 | ADR 0017 (UI 1.0) × redesign 2.0 | Dois temas monocromáticos, sheets na própria janela, ajuda “?” em pt-BR, animações | ADR 0020 |
| E-44 | Notificações push | Não implementadas: exigiriam backend/terceiros (vedado); explicado na Ajuda | ADR 0007 |
| E-45 | Logos de tokens | Não carregados (URL do metadado é controlada pelo emissor: rastreio/phishing); iniciais monocromáticas | ADR 0020 |
| E-46 | “Biometria nunca substitui o PIN” (v1/v2) × requisito de produto 3.0.0 | Biometria opcional para desbloquear/assinar, com PIN após reboot, 72 h, erros e invalidação; frase/exportação/criação só com PIN | ADR 0021 |
| E-47 | Moedas USD/BRL × 10 moedas | USD, EUR, JPY, GBP, CNY, BRL, CAD, AUD, CHF, INR; Coinbase + Kraken (5 pares) | ADR 0016 |
| E-48 | Variação de 24 h = variação do preço do SOL | Variação da carteira ponderada pelo valor; saldo zero ⇒ sem variação | CHANGELOG 3.0.0 |
| E-50 | Seletor de validador por busca (Fase F) × requisito de produto 4.0.0 | Bloco curado de 6 validadores conferidos on-chain; busca/colar endereço em “Avançado” com avisos; só nativo | ADR 0022 |
| E-51 | Vidro 3.0 (folhas ~92–94 %) × requisito de produto 4.0.0 “mais transparente” | Painéis 6 %, folhas ~78 % sobre desfoque 28 dp; contraste travado por teste | CHANGELOG 4.0.0 |
| E-52 | Bloco curado ordenado pela lista fixa × requisito de produto (final) | Segurança primeiro; depois retorno estimado (comissão efetiva on-chain × desempenho = recompensa por SOL em stake, pós-Alpenglow); MEV não entra (RPC não informa) | ADR 0022 |
| E-53 | Saldo = só SOL livre × requisito de produto (final) | Saldo central = livre + todo stake seu; detalhe Disponível/Em stake; recompensa da última época só se consistente; “Máximo” usa só o livre | CHANGELOG 5.0.0 |
| E-54 | Biometria: janela fixa de 72 h × requisito de produto (final) | Padrão 24 h, escolhas 1/4/24/72 h, aumentar exige PIN, “Bloquear agora” | ADR 0021 |
| E-55 | Versão visível em “Sobre” × requisito de produto (final) | Nenhuma versão na interface; versionName 5.0.0 só interno | CHANGELOG 5.0.0 |
