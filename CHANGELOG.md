# Changelog

Formato: [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/). Versionamento: SemVer.

## [Unreleased]

## [5.0.1] — 2026-11-20 — endurecimento de segurança

- **Lock Connect:** elimina o truncamento silencioso das contas observadas. Se carteira + contas distintas ultrapassarem o limite suportado pela simulação (16), a transação é bloqueada antes da assinatura. Teste de regressão incluído.
- **Android Keystore:** `UnrecoverableKeyException` passa a indicar indisponibilidade, não ausência comprovada do alias; evita quarentena indevida das carteiras.
- **Persistência:** sincronização best-effort do diretório após renomeação atômica.
- **Publicação:** CI executa `ciCheck`, compila Release, assina o APK e publica APK + SHA-256 + certificado na página GitHub Releases.
- **Migração de assinatura:** 5.0.1 usa um novo certificado RSA-4096. O Android não permite instalar por cima da 5.0.0 com certificado diferente. Antes de remover a versão antiga, faça backup e valide a frase de recuperação ou Recovery Package.

## [Unreleased] — endurecimento de segurança

- Lock Connect: bloquear a assinatura quando o conjunto de contas que precisa de verificação pós-simulação exceder o limite suportado; nunca truncar silenciosamente a lista observada.
- Keystore: tratar `UnrecoverableKeyException` como indisponibilidade, não como prova de alias ausente; preservar carteiras em vez de colocá-las automaticamente em quarentena.
- Armazenamento: tentar sincronizar a entrada do diretório após a renomeação atômica, quando o sistema de arquivos permitir.
- Documentação: alinhar o SHA-256 publicado do APK ao arquivo `release/lockwallet.apk.sha256`.

## 5.0.0 (versionCode 7)

### Segurança (auditoria estática externa)
- **Lock Connect:** transação de dApp com instrução não decodificada agora exige simulação em dois RPCs independentes, com os estados posteriores da carteira e das contas de token comparados; divergência ou resposta de um só servidor bloqueia. A confirmação extra para instruções não decodificadas continua. A revisão informa que a simulação é uma estimativa de servidores externos, não uma garantia.
- **Seed Vault:** a verificação adicional opcional de `signTransactionRequest` passa a ser cumulativa com o analisador (só pode restringir, nunca liberar).
- Modelo de ameaças T-09 alinhado ao comportamento real.

## 5.0.0 (versionCode 6)

- **Excluir carteira** (Ajustes → carteira): confirmação digitando EXCLUIR, ajuda “?”, e PIN obrigatório (nunca só a digital). Remove só a chave cifrada e os dados daquela carteira; a entrada do registro sai primeiro (gravação atômica) e o envelope é sobrescrito e apagado. Se era a ativa, outra é selecionada; se era a última, o app volta ao início. Contadores de PIN intactos.

## [5.0.0] — 2026-11-19 — versão final “Lock Wallet” (Mainnet — dinheiro real; sem auditoria externa)

Versão final. O app se chama apenas **Lock Wallet** e **não mostra número de versão** em nenhuma tela (internamente
versionName 5.0.0, versionCode 5). Atualiza a 4.0.0 no lugar (mesmo pacote, mesma chave de assinatura). Invariantes
mantidos: sem assinatura às cegas, analisador + simulação, FLAG_SECURE, sem logs/telemetria/backend, Token-2022
negado por padrão, só stake nativo, PIN errado nunca apaga, só Mainnet.

### Stake: ordem por segurança e retorno (ADR 0025, adendo)
- Bloco recomendado ordenado: **mais seguro primeiro**, depois **maior retorno estimado** — comissão efetiva on-chain
  (maior entre % e bps) e desempenho das 3 últimas épocas = recompensa por SOL em stake (o `epochCredits` registra
  lamports desde o Alpenglow) contra a mediana da rede pesada por stake. MEV não entra (não há dado on-chain).
- Cada linha mostra comissão, desempenho (%), stake ativo e o índice de retorno; nova folha “?” explica a ordem.
- Inseguros (fora do ar, superminoria, comissão subiu) continuam escondidos/bloqueados — segurança sempre vence.

### Saldo com o SOL em stake
- O saldo central soma **SOL livre + todas as suas contas de stake** (ativando, ativo, desativando, sacável).
- Detalhe “Disponível / Em stake” e, quando a rede responde de forma consistente, a **recompensa da última época**
  (`getInflationReward`). Recompensa acumulada não é mostrada (não dá para calcular com confiança).
- Variação da carteira usa o total; **“Enviar máximo” usa só o Disponível**.

### Biometria contra coação (ADR 0024, adendo)
- Prazo sem PIN configurável **1/4/24/72 h, padrão 24 h** (antes 72 h fixas); aumentar exige o PIN.
- **“Bloquear agora”** nos Ajustes: bloqueia e exige o PIN no próximo desbloqueio.

### Segurança (rodada final) — ver `docs/RELATORIO-SEGURANCA.md`
- **Médio — contador de boot ausente** (alguns fabricantes): a biometria podia voltar após reiniciar sem o PIN. Agora
  contador desconhecido ⇒ PIN.
- **Médio — linha sentinela u64::MAX** em `epochCredits` (existe na resposta real da Mainnet, num validador
  inadimplente) fazia a leitura estrita recusar a lista inteira de validadores. Linha sentinela agora é ignorada.
- **Baixo — unidade do Alpenglow**: `epochCredits` passou a registrar lamports (cresce com o stake); o desempenho
  agora é por SOL em stake e épocas ≤ 1064 são ignoradas.
- **Baixo — corrida ao trocar carteira/rede**: resposta atrasada da carteira anterior era gravada na atual. Contador de
  geração descarta respostas antigas.
- **Baixo — estouro com valor gigante** na checagem saldo + taxa (a rede recusaria; nada era perdido). Checagem por
  subtração e teto de 10⁹ SOL.
- **Baixo — “1.000” lido como 1 SOL**: valor com ponto de milhar agora é recusado como ambíguo.
- Testes novos de casos de aparelho: disco que não grava ao contar tentativa de PIN, relógio voltando, tela de 320 dp
  com fonte 200 %, nenhuma versão na interface; leitura estrita de `epochCredits`/bps.
- Relatório renomeado para `docs/RELATORIO-SEGURANCA.md`.

### Publicação
- README em pt-BR e inglês, `SECURITY.md` atualizado, histórico do git varrido (sem segredos/keystore).

## [4.0.0] — 2026-11-19 (Mainnet — dinheiro real; sem auditoria externa)

Atualiza a 3.0.0 no lugar (mesmo pacote `com.lockwallet.app`, mesma chave de assinatura, versionCode 4). Nada muda no
cofre, PIN, biometria ou preferências. Invariantes de segurança mantidos sem alteração.

### Stake: validadores recomendados (ADR 0025)
- “Novo stake” abre com um **bloco de 6 validadores recomendados** (Shinobi Systems, Solana Compass, Chainflow,
  Overclock, Stakeconomy, Nansen), escolhidos por critérios objetivos (histórico ≥ 2 anos, sem inadimplência, comissão
  ≤ 5 %, fora da superminoria, operador conhecido, datacenters diferentes) e conferidos on-chain via `getVoteAccounts`.
- Comissão, stake ativo e estado **ao vivo** (relidos a cada 60 s e de novo na revisão, fora da thread principal):
  fora do ar/ausente ⇒ some do bloco com aviso; comissão subiu ⇒ marcado e bloqueado; na superminoria ⇒ “concentrado”.
- **Avançado**: lista completa, busca e colar endereço de voto, com aviso forte; escolha fora do bloco ganha aviso na
  revisão. Só stake nativo. Explicações em três folhas “?” novas.

### Segurança (rodada 4.0)
- **Baixo — resposta de RPC contraditória no bloco curado:** a mesma conta de voto repetida (uma inadimplente, outra
  saudável) podia ser lida como saudável. Agora duplicata/identidade trocada/comissão impossível ⇒ escondido.
- **Baixo — estouro no cálculo de concentração** com stakes absurdos de RPC hostil: cálculo em ponto flutuante.
- Relatório de segurança em linguagem simples: `docs/RELATORIO-SEGURANCA-v3.md` (hoje `docs/RELATORIO-SEGURANCA.md`).

### Design 4.0 (“vidro de verdade”)
- Vidro mais fino e transparente: painéis 6 % (antes 8 %), folhas/menus ~78 % (antes 92–94 %) sobre o conteúdo
  **desfocado** (desfoque de fundo 28 dp, antes 22 dp); bordas de luz mais nítidas para manter o contorno; véus mais leves;
  brilho ambiente um pouco mais forte para o vidro ter o que mostrar. Sem RenderEffect, folhas voltam a ~94 %.
- Legibilidade travada por teste (`GlassContrastTest`): texto ≥ 15:1 em painéis, ≥ 7:1 em folhas mesmo com a cor oposta
  atrás; texto secundário ≥ 4,5:1; tudo em cinza puro (identidade monocromática). Cinza secundário do tema claro mais
  escuro (#636368).
- Movimento: linhas do bloco recomendado entram em cascata (só na fase de desenho), seleção com anel/fundo animados e
  check com mola, ponto “no ar” respirando, “Avançado” abre/fecha com mola de tamanho + fade; nenhuma rede ou cálculo na
  thread principal.
- Stake ativo dos validadores com separador de milhar (antes vazava casas decimais).

## [3.0.0] — 2026-11-19 (Mainnet — dinheiro real; sem auditoria externa)

Atualiza a 2.0.0 no lugar (mesmo pacote `com.lockwallet.app`, mesma chave de assinatura, versionCode 3). Cofre,
carteiras, PIN e preferências são mantidos sem migração de formato; a biometria chega desligada.

### Segurança (revisão agressiva)
- **Médio — sessão biométrica podia ler a frase/exportar (só a UI impedia):** a custódia agora recusa
  `words()`, `exportLwrp()`, `toPending()` e `pinVerification()` de sessão aberta por biometria (`AUTH_REQUIRED`).
- **Médio — estado da política biométrica editável:** o arquivo de estado (janela de 72 h, boot) era conferido só
  por um hash sem chave; editar e recalcular o hash estenderia a janela. Agora HMAC-SHA256 com chave Keystore
  (não exportável); arquivo forjado ⇒ biometria desligada, PIN segue funcionando.
- **Baixo — RNG quebrado:** geração de entropia BIP-39 recusa saída constante (falha fechada).
- **Baixo (UX com risco de perda) — senha gerada do LWRP cortada na tela:** a senha de 8 palavras era desenhada numa
  linha só e saía da tela em celulares comuns; o usuário podia anotar uma senha incompleta e perder o backup. Agora
  grade numerada que se adapta (1 coluna em 320 dp / fonte grande), texto que encolhe para caber; testes de layout
  em 320 dp × fonte 1,3 e 2,0 e prova de que as palavras mostradas são exatamente a senha usada.
- Revisados sem achados novos: geração de semente (SecureRandom, checksum, 12/24 palavras, SLIP-0010), força bruta do
  PIN, bypass de bloqueio, componentes exportados/intents, MWA, RPC/cotação hostis, transações malformadas, área de
  transferência, captura de tela/sobreposição, backup/extração, depuração/hook, adulteração de arquivos, rollback.

### Novo
- **Desbloquear com a digital** (ADR 0024): chave RSA no Keystore só com biometria forte por uso + CryptoObject;
  PIN após reiniciar, a cada 72 h, após PIN errado e se a biometria do aparelho mudar; frase/exportação/novas
  contas sempre com PIN. Também confirma assinaturas.
- **10 moedas**: USD, EUR, JPY, GBP, CNY, BRL, CAD, AUD, CHF, INR (Coinbase `exchange-rates` numa chamada; Kraken
  como reserva para USD/EUR/GBP/CAD/AUD), validação de plausibilidade por moeda, estados “desatualizado/indisponível”.

### Correções
- **Variação do saldo**: agora é a do que você tem (ponderada pelo valor); saldo zero ⇒ sem variação (antes mostrava a
  variação do preço do SOL mesmo sem SOL).
- Teclado do PIN menor e mais fácil (teclas 54 dp, largura máx. 280 dp), tecla de digital opcional.
- Ajustes: removido “Apps conectados” (redundante com a aba dApps); “Sobre” com a versão real (testada em CI).
- Textos explicativos fora das telas: tudo passou para as folhas “?”.

### Marca e abertura
- Nome do app é só **Lock Wallet** (o rótulo antigo ainda dizia “(Devnet)”); teste garante rótulo exato e nenhum
  texto visível com “devnet/testnet”.
- Ícone novo: cadeado monocromático com corpo de vidro fosco (brilho vertical, borda de luz, sombra suave, fechadura
  vazada), ícone adaptativo (fundo + frente + camada monocromática para ícones temáticos do Android 13+), versão
  redonda; testado em várias máscaras e tamanhos.
- Abertura: splash do Android 12+ com o fundo do sistema (claro/escuro) e o cadeado, mantido no mínimo 450 ms (sem
  “piscar”) e saída suave (cadeado sobe levemente e some, tela dissolve no app).

### Design (passe “gourmet”)
- **Vidro em camadas**: preenchimento translúcido + brilho suave no topo + grão fosco “lã” (textura 96×96 gerada uma
  vez) + borda de luz (clara em cima, sumindo embaixo); usado em cartões, linhas, botões, chips, gaveta, barra e folhas.
- **Desfoque real** (RenderEffect) do fundo sob folhas, menu “+” e gaveta; sem RenderEffect, véu mais denso.
- **Temas preto e branco igualmente cuidados**, com troca em fade de 420 ms (todas as cores interpoladas).
- **Movimento**: tokens de mola/easing (Emphasized), transições de tela com paralaxe e leve escala, bloqueio com
  zoom suave, folhas que sobem com escala e se arrastam para fechar (com rolagem aninhada, alça que estica, toque
  háptico ao passar do ponto), barra em pílula com indicador “elástico”, ícones que saltam, “+” com halo, menu com
  rótulos de vidro do tema, shimmer com faixa suave e pausa, botões com brilho e realce ao toque, chave com “squish”.
- **Adaptativo**: barra e menu cabem em 320 dp; margens 16 dp em telas estreitas; títulos compactos com fonte grande;
  rótulos de abas/atalhos/segmentos encolhem em vez de cortar; saldo grande se ajusta à largura.
- **Hápticos**: suave ao abrir folha, tique ao armar o fechamento, confirmação ao ligar uma chave.
- Números animados (odômetro com mola) no saldo, variação, listas e stake.

## [2.0.0] — 2026-11-19 (Mainnet — dinheiro real; sem auditoria externa)

Instala como **app novo** (`com.lockwallet.app`, versionCode 2). A 1.0.0 (só rede de teste) não é migrada.

### Segurança (revisão agressiva; todos corrigidos)
- **Alto — dreno de taxa por dApp:** dApp podia pedir taxa de prioridade ilimitada. Agora > 0,005 SOL ⇒ bloqueio
  (`FEE_TOO_HIGH`) no analisador e no firewall; taxa não calculável ⇒ bloqueio; revisão mostra a taxa máxima.
- **Médio/alto — expiração falsa ⇒ pagamento duplo:** um RPC primário hostil podia declarar a transação expirada e o
  usuário reenviaria. EXPIRED agora exige `isBlockhashValid=false` do primário **e** confirmação do secundário
  (assinatura desconhecida, altura > lastValidBlockHeight, blockhash inválido); senão fica “desconhecido”.
- **Médio:** crash quando a simulação devolvia contas extras; sem taxa de prioridade (transações não pousavam);
  envio só para o primário (agora para todos); fallback ignorava `RPC_ERROR`; sem espera em rate-limit (429);
  exceção inesperada em operação derrubava o app (agora mensagem amigável); estado de navegação podia desenhar telas
  de carteira com o app bloqueado (portão rígido de bloqueio).
- **Médio/baixo:** frase colada ficava na área de transferência (agora é apagada ao colar).
- **Baixo:** `id` JSON-RPC não conferido; RPC podia subestimar a taxa (agora max(local, RPC)); URL do faucet aceita
  em “abrir link” (só explorer.solana.com agora).
- **Design-segurança:** folhas/avisos desenhados na própria janela (diálogos/popups são janelas separadas que não
  herdam FLAG_SECURE nem o filtro de sobreposição).

### Mainnet
- Só rede principal; Devnet/Testnet/faucet/airdrop removidos (regra de CI `TEST_CLUSTER`).
- RPC padrão `api.mainnet-beta.solana.com` + secundário `solana-rpc.publicnode.com`; RPC personalizado HTTPS com
  genesis conferido e “Restaurar padrão”.
- Compute budget automático (limite pela simulação; p75 de `getRecentPrioritizationFees`, 20 000–2 000 000 µlamports/CU,
  teto 0,001 SOL) em envio de SOL, tokens (com criação de ATA) e stake.
- Variação de 24 h do SOL (Coinbase stats), atualização da cotação a cada 6 s.

### Design 2.0
- Início estilo Phantom: saldo central animado, variação verde/vermelha, Enviar/Depositar, lista de ativos, shimmer.
- Barra em pílula + botão “+” que abre ações sobre fundo desfocado; menu lateral; transições compartilhadas e molas.
- Onboarding estilo Uniswap (órbita, cartões de escolha, colar frase, biometria com “Saltar” e confirmação).
- Definições agrupadas; dois temas monocromáticos (preto vidro / claro); vibração tátil opcional; ocultar saldo.
- Ícone “?” em ~60 pontos de dúvida com “O que é / Para que serve / Exemplo” e índice de ajuda; textos sem jargão;
  valores em formato brasileiro (vírgula decimal).
- Logos de tokens não são baixados (rastreamento/phishing); iniciais monocromáticas.

### Limites conhecidos
- **Sem auditoria externa; não testado em aparelho físico com fundos reais neste ambiente.** Comece com valores pequenos.
- O RPC pode mentir sobre saldo, aluguel e simulação; com RPC personalizado sem secundário a expiração depende só dele.
- Aluguel de ATA Token-2022 estimado; o teclado pode aprender texto (usamos teclado de senha); frase em `TextField`.
- Sem notificações (exigiriam backend). Sem swap/compra.
- Demais limites da 1.0.0 (Lock Connect legacy/1 assinante, QR só endereço, sinais de root heurísticos) continuam.

## [1.0.0] — 2026-11-19 (primeiro release; somente Devnet/Testnet, sem auditoria)

### Adicionado
- **Fases A–C:** estrutura modular (`:core`, `:custody`, `:data`, `:app`), verificação de dependências sha256,
  criação/restauração de carteira (BIP-39), cofre local Argon2id + AES-GCM + Android Keystore, PIN + biometria,
  bloqueio e limitador de tentativas (nunca apaga), exportação LWRP verificada, Central de Segurança,
  interface monocromática, preço público do SOL (Coinbase/Kraken), QR de recebimento.
- **Fase D:** cliente RPC estrito, gerenciador de rede (Devnet/Testnet; mainnet travada), analisador com allowlist,
  coordenador de transações (simulação, taxa, expiração, journal), envio de SOL, airdrop, atividade.
- **Fase E:** SPL Token e Token-2022 (extensões negadas por padrão), lista de tokens on-chain com metadados
  marcados como não verificados, envio com criação de ATA, fechamento de contas vazias.
- **Fase F:** staking nativo (criar+delegar, desativar, sacar, dividir, mesclar, épocas e recompensas).
- **Fase G:** custódia via **Seed Vault** (SDK 0.4.0) quando o aparelho oferece; escolha de custódia por carteira;
  assinatura Seed Vault pelo analisador com vínculo de hash e verificação Ed25519; sinais de depurador e hooking.
- **Fase H:** **Lock Connect** (Mobile Wallet Adapter 2.x, lado carteira, associação local): autorizar/reautorizar/
  desautorizar, lista de sessões por dApp com revogação, assinar transações, assinar e enviar, assinar mensagens e
  Sign-In With Solana — tudo pelo firewall de programas + simulação obrigatória + checagem de estado; recusa de
  mainnet e de cluster divergente; aba dApps; leitura de QR para endereço de envio (CameraX + ZXing local,
  permissão de câmera pedida só no uso).
- **Fase I:** testes de fuzz/propriedade nos parsers (mensagem/transação, contas token/stake, LWRP, envelope,
  registro, limitador); revisão das regras R8; re-verificação de dependências; SBOM CycloneDX; SECURITY.md,
  threat model (T-45..T-47), checklist de release; listagem do Seed Vault fora da thread principal.

### Limites conhecidos
- Não testado em aparelho com Seed Vault real nem com dApp real (testes usam fakes do SDK/protocolo).
- Lock Connect: só transações legacy com um único assinante (a carteira); v0, multiassinante e associação remota
  não são suportados; sign-and-send limitado a 1 transação por pedido; sem verificação Digital Asset Links
  (a identidade do dApp é declarada e mostrada como tal).
- QR lê só o endereço (valor, memo e transaction requests do Solana Pay são ignorados/recusados).
- O airdrop RPC do Devnet frequentemente falha (limite/410); use https://faucet.solana.com.
- Sinais de root/depurador/hooking são heurísticos e apenas informativos.
