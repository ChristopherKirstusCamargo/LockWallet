# Changelog

Todas as mudanças relevantes de cada versão. Datas no formato AAAA-MM-DD. A versão publicada como **Lock Wallet**
(26/11/2026) é a versão final do projeto e não tem número público.

## Lock Wallet — 2026-11-26 — versão final

### Saldo confidencial
- Ganha aba própria, **Privado**, na barra inferior. A tela inicial não mostra mais o cartão do recurso.
- Revisão de segurança da integração (ADR 0023):
  - a simulação passa a conferir a variação do saldo público da carteira. Uma operação que retiraria mais SOL do que
    o valor revisado mais a taxa é recusada;
  - o resultado preparado pela ponte nativa precisa corresponder ao pedido: mesma operação, mesmo valor e mesmo
    destino público;
  - a chave da API do endpoint fica mascarada; a interface mostra só o host;
  - a retirada de volta para esta mesma carteira passa a ser permitida (atalho “Esta carteira”).

### Proteções de envio (opcionais, em Ajustes › Segurança)
- **Contatos:** até 200 endereços com nome. O envio mostra o nome do contato e oferece atalhos para eles.
- **Somente contatos:** envios de SOL e tokens só para contatos ou carteiras suas. Um contato novo só pode receber
  24 h depois de adicionado.
- **Limite diário de SOL:** janela móvel de 24 h que soma envios de SOL, transferências confidenciais e retiradas
  para terceiros. Stake não conta. O horário vem da rede (o menor entre dois servidores), não do relógio do aparelho;
  sem horário confirmado, o envio é recusado.
- **Confirmar endereço novo:** para um destino nunca usado, digite os 4 últimos caracteres antes de assinar.
  Ligado por padrão.
- Endurecer uma proteção vale na hora. Afrouxar exige o PIN e só vale 24 h depois; a mudança pendente pode ser
  cancelada.
- O PIN continua obrigatório em toda assinatura.

### Segurança
- Toques recebidos com outra janela sobreposta são descartados nas telas de PIN, revisão, saldo confidencial e frase.
- No Android 14 ou mais novo, a janela é marcada como dado sensível para serviços de acessibilidade que não são
  ferramentas de acessibilidade. A Central de Segurança lista os serviços de acessibilidade ativos que não se
  declaram ferramentas de acessibilidade.
- O endereço copiado sai da área de transferência após 2 min, se ainda for o mesmo.
- A detecção de root considera também Magisk, KernelSU, APatch, builds `userdebug`/`eng` e montagens do Magisk.
  Continua sendo um sinal informativo.
- Dependências que não são mais usadas foram removidas da verificação de hashes do Gradle.
- Correções encontradas pelos novos testes de segurança (detalhes em `docs/REVISAO-SEGURANCA.md`, F-11 a F-17):
  - validade do blockhash calculada com a altura de bloco correta mesmo quando um servidor devolve o slot;
  - o limite diário não pode mais ser contornado com muitos envios pequenos;
  - proteções de envio adulteradas no armazenamento passam a falhar fechadas;
  - o aviso de endereço parecido detecta também 3+3 caracteres iguais e diferenças de maiúsculas;
  - resposta adulterada do servidor ao pedir blockhash vira erro de rede tratado;
  - restaurar uma cópia antiga do contador de tentativas de PIN ao lado de outra apagada bloqueia por 24 h;
  - leituras de várias contas vão em lotes de 10, aceitos pelo servidor secundário, e um servidor principal com
    limite de requisições recebe até três novas tentativas com espera crescente.
- Revisão completa em `docs/REVISAO-SEGURANCA.md`.

### Interface
- Saldo maior e centralizado. Toque para alternar entre moeda e SOL; toque duas vezes para ocultar ou mostrar. A
  opção “Ocultar saldo” dos Ajustes foi substituída por esse gesto.
- Tela inicial mais limpa: ações Enviar, Receber, Stake e Ler QR em botões redondos centralizados logo abaixo do
  saldo; atalho da Central de Segurança.
- Barra inferior maior, com quatro abas (Início, Stake, Privado, Ajustes).
- Ajustes: a tela principal é só o cartão do perfil e uma lista curta de categorias (ícone, título e seta):
  Segurança, Moeda, Privacidade, Carteiras, Aparência e Sobre. Cada categoria abre a própria tela, e cada opção
  aparece em um só lugar:
  - **Segurança:** Central de Segurança, PIN, biometria, bloqueio automático, bloquear agora, proteções de envio
    (contatos, somente contatos, limite diário, confirmar endereço novo) e frase de recuperação / backup;
  - **Moeda:** moeda de exibição, formato de valores grandes (Compacto ou Completo) e cotação ao vivo;
  - **Privacidade:** saldo confidencial e servidor da rede;
  - **Carteiras:** carteiras do aparelho, criar e importar;
  - **Aparência:** tema, animações e vibração;
  - **Sobre:** ajuda e informações do app.
  O perfil mostra só identidade, endereço e contas.
- Valores muito grandes: o saldo reduz a fonte para caber sem cortar nem quebrar linha e, a partir de 1 milhão,
  usa notação compacta (por exemplo, R$ 1,25 bi ou 1,25 mi SOL); o valor exato fica no rótulo de acessibilidade e
  aparece com um toque longo no saldo. Na revisão de envio, o valor exato aparece sempre abaixo do valor compacto.
  O mesmo vale para tokens, envio e aba Privado. Em Ajustes › Moeda, “Completo” desliga a notação compacta.
- Valores exibidos (início, tokens, Privado, stake e atividade) com no máximo duas casas decimais, sem arredondar
  para cima (por exemplo, 10,41); um valor diferente de zero menor que 0,01 aparece como “< 0,01”. O valor digitado no
  envio, a revisão e o valor exato do toque longo continuam com todas as casas. Saldo da tela inicial um pouco menor.
- Material de vidro único (“Liquid Glass”) monocromático para cartões, folhas, diálogos e barra inferior: desfoque
  real do conteúdo de trás (RenderEffect), reflexo especular na borda, borda interna e leve profundidade. O contraste
  dos textos sobre o vidro é verificado por testes (WCAG: texto principal ≥ 7:1; secundário ≥ 4,5:1). Para isso, o
  cinza secundário e o verde de alta do tema claro ficaram um pouco mais escuros.
- Ícones vetoriais monocromáticos no lugar de símbolos de texto.
- Tela de envio mais enxuta, com as mesmas verificações.

### Assinatura
- Assinada com a chave de produção (certificado
  `3ce562ce3ac3bce9a87ab8745fb549ef8adfad692b280d13a22fdb7bd35d0b6b`). Atualiza no lugar as instalações assinadas
  com esse certificado.

### Testes
- `ciCheck`: 5.590 testes JVM/Robolectric, 0 falhas, 0 erros, 0 ignorados; mais 4 testes Rust da ponte nativa.
  Por módulo: app 2.767, custody/local 465, core/solana 833, core/crypto 215, data/rpc 1.302, data/price 8.
- Novos grupos de segurança e design (testes de propriedade e matrizes): limite diário, espera de 24 h, adulteração
  das proteções, endereços parecidos, força bruta e adulteração do PIN, divergência na decodificação de transações,
  Base58, valores e QR, backup adulterado, respostas hostis de RPC, lotes no servidor secundário, valores enormes,
  layout em 4 larguras × 4 escalas de fonte × 2 temas e contraste do vidro. Contagens em `docs/REVISAO-SEGURANCA.md`.
- O teste real na Mainnet (simulação sem assinatura nem envio) fica fora da suíte hermética e roda com
  `./gradlew :data:rpc:mainnetTest`.

## [5.0.13] — 2026-11-26 — (versionCode 21)

### Interface
- Visual monocromático em vidro aplicado de forma uniforme: reflexo especular suave nas superfícies de vidro,
  espaçamentos e alturas revisados e transições com mola.
- Tela inicial: a variação do preço tem o rótulo
  “SOL 24 h”; a linha secundária não repete a quantidade quando não há cotação (“Cotação indisponível” ou
  “Cotação desligada”); um ícone de troca indica a alternância moeda/SOL.
- Ajustes reorganizados em **Preferências, Carteiras, Segurança, Privacidade, Experiência e Suporte**, só com opções
  que funcionam. Novas opções: “Bloquear após inatividade” e “Animações”.
- Textos de desenvolvimento e jargão técnico removidos da interface (avisos internos, contagem de dígitos do PIN,
  nomes internos de componentes). A versão não aparece na interface.
- Stake: iniciais dos validadores vêm do nome; comissão e desempenho em linhas que não cortam; modo avançado com
  comissão e quantidade em stake no título.
- Moedas com símbolo próprio (R$, $, €, ¥, £, 元, C$, A$, Fr, ₹); rótulos longos quebram em duas linhas em vez de cortar.
- Folha do saldo confidencial simplificada.
- O botão Voltar do sistema segue a navegação das telas.

### Fotografia de perfil
- GIF animado aceito: até 4 MB, 1024 px e 300 quadros. Metadados e dados extras são removidos antes de salvar.
- A animação pausa fora do primeiro plano e quando “Animações” está desligado. GIF de um quadro vira imagem estática.
- Nenhuma permissão nova.

### Segurança
- Bloqueio por inatividade (1, 2, 5 ou 10 min; padrão 5). Nunca interrompe uma operação em andamento.
- Variante de inspeção com captura de tela liberada só em build debug com pacote próprio e opções explícitas; o
  release sempre usa `FLAG_SECURE`, garantido por teste.
- Excluir a carteira também remove a fotografia.
- Workflows que geravam chaves e publicavam Releases removidos; fica um CI sem assinatura nem publicação.
- Detalhes em `docs/REVISAO-SEGURANCA-5.0.13.md`.

### Assinatura
- Assinada com a chave de produção (certificado
  `3ce562ce3ac3bce9a87ab8745fb549ef8adfad692b280d13a22fdb7bd35d0b6b`), a mesma da 5.0.0.
- Instalações 5.0.1 a 5.0.12 foram assinadas com outras chaves e não atualizam no lugar: faça e confira o backup,
  desinstale e instale a 5.0.13.

### Testes
- Novos: `GifProfilePhotoTest` (6), `ReleaseScreenPolicyTest` (4), `FinalPassUiTest` (5).
- `ciCheck`: 377 testes JVM/Robolectric (1 ignorado), mais 4 testes Rust da ponte nativa.

## [5.0.12] — 2026-11-24 — sugestões da frase acima do teclado (versionCode 20)

- Sugestões da lista oficial BIP-39 em inglês aparecem numa faixa horizontal acima do teclado.
- A sugestão pode ser escolhida sem fechar o teclado.
- Geração e validação continuam restritas à lista oficial BIP-39.
- Assinada com uma chave temporária do CI (certificado `460c1341…1cadf`); não atualiza instalações de outras chaves.

## [5.0.11] — 2026-11-23 — correção da restauração BIP-39 (versionCode 19)

- Confirmada e testada a geração exclusivamente com a lista oficial BIP-39 em inglês, fixada por SHA-256 (2.048 palavras).
- Normalizada a entrada digitada da frase: maiúsculas passam para minúsculas e espaços Unicode são tratados como separadores, sem alterar letras inválidas.
- Adicionadas sugestões locais da lista BIP-39 a partir de três letras; a seleção completa apenas a palavra atual. O teclado continua em modo de senha, sem sugestões externas.
- Incluídos testes da lista inglesa, dos prefixos, da normalização, da restauração e da interface de sugestões.

## [5.0.10] — 2026-11-23 — refinamento visual e endurecimento de segurança (versionCode 18)

- Removido o painel redundante de “Sessão e autenticação” dos Ajustes; as explicações detalhadas permanecem disponíveis no contexto de ajuda.
- Simplificada a folha de saldo confidencial: conexão, estado do endpoint e visibilidade aparecem em blocos curtos, sem longas notas técnicas na tela principal.
- Reorganizado o saldo confidencial com valor responsivo, respeito à opção de ocultar saldo e estado de sincronização sem expor a contagem interna de notas.
- Endereços da operação passaram a ocupar blocos próprios para quebrar linhas corretamente, inclusive na revisão antes da autorização.
- Perfil da carteira ajustado para nomes longos, indicação de armazenamento local e prévia de fotografia com ações alinhadas lado a lado.
- **Token-2022:** leituras de dados de mint usadas na política de extensões exigem respostas idênticas de dois RPCs independentes; indisponibilidade ou divergência bloqueia a decisão de assinatura (fail-closed).
- **Default Ring:** a sessão candidata é registrada antes das chamadas assíncronas; bloqueio ou saída da tela invalida a abertura em andamento e impede que uma sessão confidencial permaneça ativa.
- **Imagens de perfil:** a entrada valida os formatos de imagem suportados antes da decodificação e rejeita dados malformados ou excessivos.
- Mantida a identidade visual Glass monocromática e os fluxos existentes de carteira, privacidade e autenticação.
- **Verificação:** testes da ponte nativa Rust, testes dos módulos Android e testes unitários do app passaram; a build release, a assinatura e o manifesto também foram verificados no GitHub Actions.
- **Migração:** esta versão usa uma nova chave de assinatura Android e não atualiza diretamente uma instalação assinada pela chave anterior (5.0.9). Exporte e verifique a recuperação da carteira antes de desinstalar a versão anterior.

## [5.0.9] — 2026-11-23 — revisão de UX, sessão e perfil local (versionCode 17)

- Alternância do saldo principal entre a moeda fiduciária configurada e a equivalência matemática em SOL, sem alterar a quantidade real de SOL ou tokens.
- Preferência da unidade do saldo persistida; conversão não exibida quando a cotação não é utilizável.
- Ocultação de saldo aplicada ao total, tokens, staking e quantidades exibidas em telas de detalhes e origem de envio.
- Sessão encerrada imediatamente ao sair do primeiro plano; no retorno, a carteira exige nova autenticação por biometria quando a política estiver válida ou por PIN quando a política exigir.
- “Bloquear agora” permanece como bloqueio de emergência distinto: grava a política PIN-only até o próximo PIN correto, sem confundir essa ação com a saída normal do aplicativo.
- Fotografia por carteira via seletor de imagens do Android, com prévia, substituição, remoção e armazenamento local privado sem backup.
- Configurações reorganizadas, tela de bloqueio refinada e orientação visual para alternância do saldo entre moeda fiduciária e SOL.
- Testes novos para conversão, ciclo de vida e processamento de imagens; validação automatizada a ser confirmada pelo workflow desta PR.


Formato: [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/). Versionamento: SemVer.

## [5.0.8] — 2026-11-23 — refinamento visual e entrada do Default Ring na tela inicial (versionCode 16)

- `versionName` permanece `Final`. `versionCode` 16.
- **Testes:** 1 teste novo (`privacyEntryOnHomeOpensTheConfigSheet`) — o cartão da tela inicial abre a folha de configuração quando falta o endpoint e os blocos antigos saíram; teste de versionCode atualizado para 16; suíte completa do módulo app verde (110 testes).
- Nenhuma mudança em segurança, criptografia, geração/armazenamento/derivação de chaves, transações, assinatura ou integração Solana; nenhuma dependência nova; nenhum fluxo removido.
- Certificado de assinatura novo. Não instala por cima da v5.0.7 nem das versões anteriores. Faça backup verificado antes de desinstalar.
- A chave privada e a senha não entram no repositório nem na Release.

## [5.0.7] — 2026-11-23 — endpoint dedicado do Default Ring e endurecimento (versionCode 15)

- `versionName` permanece `Final`. `versionCode` 15.
- **Default Ring (endpoint Helius dedicado — correção):** as operações de saldo confidencial passam a usar um endpoint Helius próprio, configurado em Ajustes → Saldo confidencial — campo novo, validado antes de salvar (HTTPS, host Helius com api-key e Genesis Hash da Mainnet conferidos no próprio endpoint) e separado do servidor da rede comum. Antes, o fluxo usava o RPC principal do app: sem campo dedicado, o usuário era orientado a transformar o RPC comum em endpoint Helius (misturando o tráfego comum e o privado sob a mesma API key) ou recebia erro genérico sem saber onde configurar. Agora o comportamento é fail-closed: sem endpoint configurado, a entrada do Default Ring bloqueia com instrução clara; o tráfego `/v1/spr` (indexador e provador) nunca cai no RPC público comum; salvar ou remover o endpoint encerra qualquer sessão confidencial ativa.
- **Default Ring (migração):** quem já usava um RPC Helius como servidor principal tem a URL migrada automaticamente (uma única vez) para o campo dedicado na primeira leitura; nada muda para o restante do app.
- **Default Ring (mensagens da ponte nativa):** os erros da ponte Rust passam a apontar o lugar da configuração (“Ajustes → Saldo confidencial”) em vez de mencionar o “RPC principal”.
- **UI:** a folha “Saldo confidencial” em Ajustes, antes inacessível (nunca era aberta pelo app), volta a ser o centro do recurso: situação do endpoint, campo de configuração com teste e salvamento, remoção, explicação do fluxo e entrada direta para o Default Ring.
- **Testes:** 9 testes novos (`SprEndpointTest`) cobrem a validação do endpoint, o bloqueio fail-closed sem endpoint, a remoção, a migração legada e o formato da URL; a suíte completa do módulo app fica verde (teste de versionCode atualizado para 15).
- Certificado de assinatura novo. Não instala por cima da v5.0.6 nem das versões anteriores. Faça backup verificado antes de desinstalar.
- A chave privada e a senha não entram no repositório nem na Release.

## [5.0.6] — 2026-11-23 — correções de segurança da auditoria dupla (versionCode 14)

- `versionName` permanece `Final`. `versionCode` 14, para o Android aceitar a atualização quando o certificado for o mesmo.
- **Restauração (IME):** a tela de digitação da frase de recuperação, da chave privada e da senha do Recovery Package agora avisa quando o teclado ativo não é do fabricante do aparelho — um teclado de terceiros recebe cada tecla digitada, inclusive segredos. O aviso orienta trocar para o teclado original ou usar o botão Colar; não bloqueia o fluxo.
- **Default Ring (taxa local):** a taxa exibida e limitada na operação confidencial passa a considerar o teto calculado a partir dos bytes da própria mensagem V1 (custo base + prioridade declarada no cabeçalho), nunca apenas a resposta do RPC. Orçamento ilegível ou acima do limite bloqueia a preparação (`V1MessageFee`, fail-closed). Um RPC desonesto não consegue subnotificar o custo.
- **Default Ring (revisão):** a revisão da transferência confidencial exibe o endereço Shielded do destinatário exatamente como foi digitado, além do endereço público derivado — a única forma de o usuário conferir o destino contra o que colou.
- **Cadeia de suprimentos Rust:** builds nativos (Gradle e CI) passam a exigir `--locked`, complementando o `Cargo.lock` versionado e os pins exatos do Cargo.toml; o build nunca re-resolve dependências.
- **CI:** corrigido o gatilho de push do workflow de integração Privacy Rings (`branches: [main]` estava corrompido como `ain]`, o que impedia o teste da integração nativa em commits diretos na main).
- **Avaliados e deliberadamente não alterados nesta versão:** pinning TLS de RPC (protege contra o cenário raríssimo de CA de sistema comprometida, mas uma rotação de certificado mal pinada deixaria o app sem rede para todos — o gate atual permanece: genesis verificada, análise local dos bytes e dupla simulação) e a migração automática de cofres legados `PIN_ONLY` (o risco de uma re-selagem com bug perdendo acesso a fundos reais supera o ganho contra um atacante já com root; o aviso persistente no início e na Central de Segurança permanece).
- Certificado de assinatura novo. Não instala por cima da v5.0.5 nem das versões anteriores. Faça backup verificado antes de desinstalar.
- A chave privada e a senha não entram no repositório nem na Release.

## [5.0.5] — 2026-11-22 — publicação (versionCode 13)

- `versionName` permanece `Final`. `versionCode` 13, para o Android aceitar a atualização quando o certificado for o mesmo.
- **Saldo confidencial (Default Ring, Solana Privacy Rings):** depósito do saldo público, transferência confidencial de SOL e retirada de volta ao saldo público. O ativo e a quantidade da transferência privada ficam ocultos. Os endereços de origem e destino continuam públicos — confidencialidade de valor, não anonimato de endereços. Depósitos e retiradas também são públicos. Somente SOL, Mainnet e um endpoint Helius HTTPS com API key. SPL e Token-2022 não entram.
- **Segurança:** a derivação das chaves de visualização e de nullifier fica na custódia local e exige PIN; a seed e a chave privada não cruzam a ponte nativa. A assinatura final continua na custódia, com um segundo PIN, e o envio passa pelo coordenador (simulação, taxa e blockhash). O journal não grava valor, casas decimais nem mint dessa operação. Falha de RPC, indexador, prova com TEE ou simulação bloqueia a assinatura; não há fallback para um envio público.
- **Compilação:** a ponte nativa passa a ser construída a partir do diretório do crate; as crates Solana ficam fixadas nas versões do SDK `42de575` (o resolvedor mais novo quebrava o grafo) e a revisão preparada aceita o contexto mutável exigido pela sessão.
- Certificado de assinatura novo. Não instala por cima da v5.0.4 nem das versões anteriores. Faça backup verificado antes de desinstalar.
- A chave privada e a senha não entram no repositório nem na Release.

## [5.0.4] — 2026-11-21 — publicação (versionCode 12)

- `versionName` permanece `Final`. `versionCode` 12, para o Android aceitar a atualização quando o certificado for o mesmo.
- Correção de compilação: o setter privado de `UnlockedWallet.header` saiu da lista de parâmetros do construtor (Kotlin não aceita `private set` ali) e foi para o corpo da classe. O setter continua privado.
- Certificado de assinatura novo. Não instala por cima da v5.0.3 nem das versões 5.0.1/5.0.2. Faça backup verificado antes de desinstalar.
- A chave privada e a senha não entram no repositório nem na Release.

## [5.0.3] — 2026-11-21 — endurecimento de segurança (versionCode 11)

- Inclui as correções de segurança feitas no `main` depois da 5.0.2.
- Certificado temporário novo (`dcc94672…1507`); não instala por cima da 5.0.1/5.0.2.

## [5.0.2] — 2026-11-20 — publicação versionada (versionCode 10)

- Preparação da publicação versionada; mesmo certificado da 5.0.1 (`ed4c0025…8c13`).

## [5.0.1] — 2026-11-20 — correções de segurança

- **Android Keystore:** trata `UnrecoverableKeyException` como indisponibilidade, não como prova de alias ausente; evita colocar carteiras em quarentena por uma falha ambígua.
- **Persistência:** solicita sincronização best-effort do diretório após renomeação atômica.
- Inclui teste de regressão para o limite de contas observadas.

- **Assinatura:** certificado novo (`ed4c0025…8c13`). Não atualiza por cima da 5.0.0; exige backup verificado e reinstalação.

## [5.0.0] — 2026-11-19 — versão final “Lock Wallet” (Mainnet — dinheiro real; sem auditoria externa)

Versão final. O app se chama apenas **Lock Wallet** e **não mostra número de versão** em nenhuma tela (internamente
versionName 5.0.0, versionCode 5). Atualiza a 4.0.0 no lugar (mesmo pacote, mesma chave de assinatura). Invariantes
mantidos: sem assinatura às cegas, analisador + simulação, FLAG_SECURE, sem logs/telemetria/backend, Token-2022
negado por padrão, só stake nativo, PIN errado nunca apaga, só Mainnet.

### Stake: ordem por segurança e retorno (ADR 0022, adendo)
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

### Biometria contra coação (ADR 0021, adendo)
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

### Revisões publicadas como 5.0.0 (versionCode 6 e 7)
- **Excluir carteira** (Ajustes → carteira): confirmação digitando EXCLUIR, ajuda “?”, e PIN obrigatório (nunca só a digital). Remove só a chave cifrada e os dados daquela carteira; a entrada do registro sai primeiro (gravação atômica) e o envelope é sobrescrito e apagado. Se era a ativa, outra é selecionada; se era a última, o app volta ao início. Contadores de PIN intactos.
- Modelo de ameaças T-09 alinhado ao comportamento real.

## [4.0.0] — 2026-11-19 (Mainnet — dinheiro real; sem auditoria externa)

Atualiza a 3.0.0 no lugar (mesmo pacote `com.lockwallet.app`, mesma chave de assinatura, versionCode 4). Nada muda no
cofre, PIN, biometria ou preferências. Invariantes de segurança mantidos sem alteração.

### Stake: validadores recomendados (ADR 0022)
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
  PIN, bypass de bloqueio, componentes exportados/intents, RPC/cotação hostis, transações malformadas, área de
  transferência, captura de tela/sobreposição, backup/extração, depuração/hook, adulteração de arquivos, rollback.

### Novo
- **Desbloquear com a digital** (ADR 0021): chave RSA no Keystore só com biometria forte por uso + CryptoObject;
  PIN após reiniciar, a cada 72 h, após PIN errado e se a biometria do aparelho mudar; frase/exportação/novas
  contas sempre com PIN. Também confirma assinaturas.
- **10 moedas**: USD, EUR, JPY, GBP, CNY, BRL, CAD, AUD, CHF, INR (Coinbase `exchange-rates` numa chamada; Kraken
  como reserva para USD/EUR/GBP/CAD/AUD), validação de plausibilidade por moeda, estados “desatualizado/indisponível”.

### Correções
- **Variação do saldo**: agora é a do que você tem (ponderada pelo valor); saldo zero ⇒ sem variação (antes mostrava a
  variação do preço do SOL mesmo sem SOL).
- Teclado do PIN menor e mais fácil (teclas 54 dp, largura máx. 280 dp), tecla de digital opcional.
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
- **Desfoque real** (RenderEffect) do fundo sob folhas e gaveta; sem RenderEffect, véu mais denso.
- **Temas preto e branco igualmente cuidados**, com troca em fade de 420 ms (todas as cores interpoladas).
- **Movimento**: tokens de mola/easing (Emphasized), transições de tela com paralaxe e leve escala, bloqueio com
  zoom suave, folhas que sobem com escala e se arrastam para fechar (com rolagem aninhada, alça que estica, toque
  háptico ao passar do ponto), barra em pílula com indicador “elástico”, ícones que saltam, rótulos de vidro do tema, shimmer com faixa suave e pausa, botões com brilho e realce ao toque, chave com “squish”.
- **Adaptativo**: barra e menu cabem em 320 dp; margens 16 dp em telas estreitas; títulos compactos com fonte grande;
  rótulos de abas/atalhos/segmentos encolhem em vez de cortar; saldo grande se ajusta à largura.
- **Hápticos**: suave ao abrir folha, tique ao armar o fechamento, confirmação ao ligar uma chave.
- Números animados (odômetro com mola) no saldo, variação, listas e stake.

## [2.0.0] — 2026-11-19 (Mainnet — dinheiro real; sem auditoria externa)

Instala como **app novo** (`com.lockwallet.app`, versionCode 2). A 1.0.0 (só rede de teste) não é migrada.

### Segurança (revisão agressiva; todos corrigidos)
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
- Barra em pílula; menu lateral; transições compartilhadas e molas.
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
- Demais limites da 1.0.0 (QR só endereço, sinais de root heurísticos) continuam.

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
- Sinais de depurador e hooking na Central de Segurança.
- Leitura de QR para endereço de envio (CameraX + ZXing local, permissão de câmera pedida só no uso).
- **Fase I:** testes de fuzz/propriedade nos parsers (mensagem/transação, contas token/stake, LWRP, envelope,
  registro, limitador); revisão das regras R8; re-verificação de dependências; SBOM CycloneDX; SECURITY.md,
  threat model, checklist de release.

### Limites conhecidos
- QR lê só o endereço (valor, memo e transaction requests do Solana Pay são ignorados/recusados).
- O airdrop RPC do Devnet frequentemente falha (limite/410); use https://faucet.solana.com.
- Sinais de root/depurador/hooking são heurísticos e apenas informativos.
