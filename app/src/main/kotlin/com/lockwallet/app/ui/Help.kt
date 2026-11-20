package com.lockwallet.app.ui

/**
 * Every "?" in the app opens one of these: what it is, what it is for, and one example — plain pt-BR, no jargon
 * without explanation. Keep each part short enough to read in a few seconds.
 */
object Help {
    private fun t(title: String, what: String, why: String, example: String) = HelpTopic(title, what, why, example)

    // ------------------------------------------------------------ wallet basics
    val BALANCE = t("Saldo total",
        "A soma do que esta conta tem em SOL, convertida para a moeda escolhida pela cotação do momento.",
        "Mostrar de relance quanto você tem. O valor em reais muda com o preço do SOL; a quantidade de SOL só muda quando você envia ou recebe.",
        "Você tem 2 SOL e o SOL vale R$ 900: o saldo mostra R$ 1.800. Se o preço subir 5%, mostra R$ 1.890 — sem você ter recebido nada.")
    val CHANGE_24H = t("Variação em 24 horas",
        "Quanto o preço do SOL subiu (verde) ou caiu (vermelho) nas últimas 24 horas, aplicado ao seu saldo.",
        "Entender por que o valor em reais mudou, sem confundir com dinheiro entrando ou saindo.",
        "“+R$ 36,00 · +2,0%” quer dizer que o SOL subiu 2% desde ontem. Sua quantidade de SOL continua a mesma.")
    val ACCOUNT = t("Contas",
        "Cada conta é um endereço Solana separado, com saldo próprio. Várias contas podem nascer da mesma frase de recuperação.",
        "Separar usos — por exemplo, uma conta para guardar e outra para o dia a dia — sem precisar de outra frase.",
        "“Conta 1” para poupança e “Conta 2” para pagar amigos. Restaurando a frase em outro aparelho, as duas voltam.")
    val ADDRESS = t("Endereço",
        "Seu endereço público na rede Solana. É como uma chave Pix: pode ser compartilhado à vontade.",
        "Receber SOL e tokens. Quem tem o endereço consegue enviar para você e ver o saldo, mas nunca mexer nele.",
        "Para receber de um amigo, toque em Receber, mostre o QR ou copie o endereço e mande para ele.")
    val TOKENS = t("Tokens",
        "Outras moedas que vivem na rede Solana (como USDC). Cada uma fica numa “conta de token” ligada à sua carteira.",
        "Ver e enviar o que você tem além do SOL. A Lock Wallet só mostra a quantidade; não inventa preço para tokens.",
        "Você recebeu 50 USDC: aparece uma linha “USDC · 50”. Para enviar, toque nela e depois em Enviar.")
    val UNVERIFIED_NAME = t("Nome não verificado",
        "O nome e o símbolo do token são escritos por quem criou o token. Qualquer um pode criar um token chamado “USDC”.",
        "Evitar golpes com tokens falsos que copiam nomes famosos.",
        "Apareceu “USDC” que você não esperava? Confira o endereço do token (mint) num explorador antes de confiar.")
    val HIDDEN_TOKENS = t("Tokens ocultos",
        "Tokens que a política de segurança não mostra na lista principal, como os de padrão novo com recursos arriscados.",
        "Proteger você de tokens que podem ser congelados, cobrar taxas escondidas ou rodar código ao serem enviados.",
        "Alguém mandou um token “prêmio” para sua conta: ele fica oculto e a Lock Wallet não deixa assiná-lo.")
    val TOKEN_POLICY = t("Política de tokens",
        "Regras fixas que dizem quais tokens a carteira aceita movimentar. Tokens comuns são aceitos; os de recursos perigosos, não.",
        "Impedir que um token com taxas ocultas, “ganchos” de código ou dono que pode tomá-lo de volta mova seu dinheiro de forma inesperada.",
        "Um token que cobra 50% de taxa ao transferir é bloqueado antes de você assinar qualquer coisa.")
    val FREEZE = t("Emissor pode congelar",
        "Quem criou o token guardou o poder de congelar contas. Uma conta congelada não consegue enviar esse token.",
        "Saber o risco: tokens de empresas (como stablecoins) costumam ter isso por exigência legal.",
        "O USDC tem esse poder. Se o emissor congelar sua conta de USDC, você não consegue enviá-lo até ser liberado.")
    val MINT_AUTH = t("Emissor pode criar mais",
        "Quem criou o token ainda pode fabricar novas unidades quando quiser.",
        "Entender que a quantidade total não é fixa — o valor de cada unidade pode cair se muitas forem criadas.",
        "Um token de meme com essa permissão pode ter um bilhão de unidades novas criadas amanhã.")
    val DELEGATE = t("Delegado",
        "Outra conta recebeu permissão para movimentar parte deste token por você.",
        "Avisar que alguém além de você pode mexer nesse saldo — geralmente sobra de um aplicativo antigo.",
        "Você usou um app de leilão que pediu permissão sobre 10 tokens: ele aparece aqui como delegado.")
    val CLOSE_TOKEN = t("Fechar conta de token",
        "Apaga uma conta de token vazia e devolve para você o depósito que a rede exige para mantê-la.",
        "Recuperar alguns centavos de SOL presos em contas de tokens que você não usa mais.",
        "Você zerou seu saldo de um token: feche a conta e recebe de volta cerca de 0,002 SOL.")

    // ------------------------------------------------------------ sending
    val NETWORK_FEE = t("Taxa de rede",
        "O valor cobrado pela rede Solana para processar a transação. É pago em SOL e fica com a rede, não com a Lock Wallet.",
        "Toda transação paga taxa. A Lock Wallet calcula o valor máximo a partir da própria transação, sem confiar só no servidor.",
        "Enviar SOL costuma custar menos de R$ 0,05. O valor exato aparece na revisão antes de você assinar.")
    val PRIORITY_FEE = t("Taxa de prioridade",
        "Um pequeno valor extra, por unidade de processamento, para a transação entrar na fila mais rápido quando a rede está cheia.",
        "Sem ela, em horários de pico a transação pode expirar sem ser processada. A Lock Wallet usa um valor moderado, limitado a 0,001 SOL.",
        "Em um dia movimentado, a taxa total de um envio pode ir de 0,000005 para 0,00002 SOL — ainda centavos.")
    val FEE_CAP = t("Limite de taxa",
        "Um teto: transações que pagariam mais de 0,005 SOL de prioridade são bloqueadas.",
        "Impedir que um app malicioso esvazie sua conta disfarçando o roubo de “taxa”.",
        "Um site pede para você assinar algo com 2 SOL de taxa escondida: a Lock Wallet recusa e explica o motivo.")
    val RENT = t("Saldo mínimo da rede",
        "A rede Solana exige um saldo mínimo para manter uma conta existindo (menos de 0,001 SOL para uma carteira comum).",
        "Evitar transações que falhariam: uma conta não pode ficar com menos que o mínimo, a não ser que vá a zero.",
        "Você tem 0,01 SOL e quer enviar 0,0095: sobraria menos que o mínimo, então a carteira sugere enviar o máximo.")
    val NEW_ACCOUNT = t("Conta nova",
        "O endereço de destino ainda não existe na rede — nunca recebeu nada.",
        "Endereços novos são normais, mas um erro de digitação também cria um “endereço novo”. Por isso pedimos uma conferência extra.",
        "Primeira vez enviando para um amigo? Confira com ele os primeiros e últimos 4 caracteres antes de confirmar.")
    val FIRST_SEND = t("Primeiro envio",
        "Você nunca enviou para este endereço a partir deste aparelho.",
        "Transações na blockchain não podem ser desfeitas. Conferir o endereço inteiro uma vez evita perder dinheiro por engano.",
        "O endereço do seu amigo é 7xKX…AsU. Compare grupo por grupo com o que ele mandou antes de marcar a confirmação.")
    val POISONING = t("Endereço parecido",
        "Um golpe comum: o golpista cria um endereço com o mesmo começo e fim de um que você já usou e manda uma transação mínima para aparecer no seu histórico.",
        "Evitar que você copie o endereço errado do histórico achando que é o certo.",
        "Você costuma pagar 7xKX…AsU e aparece 7xKX…AsU com o meio diferente: é falso. Use sempre o endereço da fonte original.")
    val ATA = t("Conta de token do destinatário",
        "Para receber um token, cada carteira precisa de uma conta específica para ele. Se o destinatário ainda não tem, ela é criada no envio.",
        "Fazer o envio funcionar mesmo para quem nunca teve esse token. Você paga um pequeno depósito, que fica com o destinatário.",
        "Enviando USDC para quem nunca teve USDC: o envio custa cerca de 0,002 SOL a mais para criar a conta dele.")
    val SEND_MAX = t("Enviar o máximo",
        "Calcula quanto dá para enviar deixando só o necessário para a taxa.",
        "Esvaziar uma conta sem sobrar poeira, nem faltar taxa.",
        "Você tem 1,5 SOL: “Máximo” preenche 1,499985 SOL, e a conta fica com zero depois da taxa.")
    val QR = t("Ler QR",
        "Usa a câmera para ler um endereço de um QR code. Só o endereço é usado; valor e mensagem do QR são ignorados.",
        "Evitar erros de digitação ao copiar endereços longos.",
        "Seu amigo mostra o QR de Receber no celular dele; você aponta a câmera e o endereço aparece preenchido.")
    val SIMULATION = t("Simulação",
        "Antes de assinar, a transação é testada na rede sem ser enviada, para ver se daria certo e o que mudaria no seu saldo.",
        "Pegar erros antes de gastar taxa e confirmar que o efeito é o esperado.",
        "A simulação mostra “−0,5 SOL” num envio de 0,5 SOL. Se mostrasse “−5 SOL”, algo estaria errado e você não deveria assinar.")
    val ANALYZER = t("Análise da transação",
        "A Lock Wallet lê byte a byte o que vai ser assinado e traduz cada instrução para português.",
        "Garantir que você assina exatamente o que vê — nunca uma transação “às cegas”.",
        "Num envio aparece “Transferir 0,5 SOL de sua carteira para 7xKX…AsU”. Se houvesse qualquer outra instrução, ela também apareceria.")
    val TX_STATUS = t("Estado da transação",
        "Assinada → enviada → processada → confirmada → finalizada. “Finalizada” significa que não volta mais atrás.",
        "Saber quando o dinheiro realmente chegou. Se a rede não responder, a transação fica “sem confirmação” até termos certeza.",
        "Após enviar, em poucos segundos aparece “Confirmada”. Se ficar “sem confirmação”, não reenvie: a carteira verifica sozinha.")
    val EXPIRY = t("Validade da transação",
        "Cada transação carrega uma referência recente da rede e só vale por cerca de um minuto.",
        "Se ela não for processada nesse tempo, perde a validade e nunca mais pode ser processada — então é seguro tentar de novo.",
        "Você revisou e foi tomar café: ao voltar, a carteira pede para revisar de novo porque a validade acabou.")
    val EXPLORER = t("Explorador",
        "Um site público que mostra todas as transações da rede Solana.",
        "Conferir de forma independente que a transação aconteceu, ou mostrar o comprovante para alguém.",
        "Seu amigo diz que não recebeu: abra a transação no explorador e mande o link para ele.")

    // ------------------------------------------------------------ staking
    val STAKE = t("Stake",
        "Você empresta SOL para um validador (um computador que mantém a rede) e recebe recompensas por isso. O SOL continua seu.",
        "Ganhar rendimento sobre o SOL parado, ajudando a segurança da rede. Só o stake nativo da Solana é usado — sem intermediários.",
        "Fazendo stake de 10 SOL a ~7% ao ano, depois de um ano você teria cerca de 10,7 SOL.")
    val VALIDATOR = t("Validador",
        "Um operador que roda a rede Solana. Você escolhe para quem delegar; ele nunca pode pegar seu SOL.",
        "A escolha afeta o rendimento: validadores fora do ar não pagam recompensas, e cada um cobra uma comissão diferente.",
        "Prefira validadores ativos com comissão baixa (ex.: 5%) e evite os que aparecem como “fora do ar”.")
    val TOTAL_BALANCE = t("Saldo total (com stake)",
        "O número grande soma o SOL disponível na carteira com todo o SOL nas suas contas de stake — ativo, ativando, desativando e já sacável. As recompensas do stake entram sozinhas na conta de stake, então também estão somadas.",
        "Ver de verdade quanto você tem. Para enviar, só conta o “Disponível”: o SOL em stake precisa ser desativado e sacado antes. “Última época” é a recompensa que a rede informou para a época passada; se a rede não responder de forma confiável, a carteira não mostra nada em vez de chutar.",
        "Você tem 2 SOL disponíveis e 10 SOL em stake que renderam 0,004 SOL na última época: o saldo mostra 12,004 SOL, mas “Máximo” no envio usa só os 2 SOL.")
    val BIO_WINDOW = t("Prazo da digital",
        "O tempo máximo que a digital pode substituir o PIN. Passado esse prazo, a carteira pede o PIN de novo.",
        "Quanto menor o prazo, menor o risco de alguém usar seu dedo à força. Diminuir vale na hora; aumentar pede o PIN, para que ninguém estenda o prazo só com a sua digital.",
        "Com 4 h: você digita o PIN de manhã e usa a digital até o almoço; depois disso, PIN de novo.")
    val LOCKDOWN = t("Bloquear agora",
        "Fecha a carteira na hora e desliga a digital até você digitar o PIN de novo.",
        "Use se achar que alguém pode forçar você a encostar o dedo: depois de tocar aqui, só o PIN abre a carteira. Nada é apagado.",
        "Vai entrar num lugar arriscado? Toque em “Bloquear agora”; para voltar a usar a digital, basta abrir uma vez com o PIN.")
    val CURATED = t("Validadores recomendados",
        "Uma lista curta de validadores com anos de histórico, quase 100% de tempo no ar, comissão baixa e estável e operador conhecido. A lista foi revisada à mão e conferida na rede.",
        "Escolher bem evita perder rendimento. A carteira confere cada um de novo na rede: se um sai do ar ele some da lista; se aumenta a comissão, fica bloqueado até nova revisão.",
        "Você abre Novo stake e vê seis opções. Uma delas saiu do ar ontem: hoje ela simplesmente não aparece, e o aviso “1 validador recomendado oculto” explica o porquê.")
    val CURATED_ORDER = t("Ordem dos recomendados",
        "Os recomendados aparecem do mais seguro para o menos seguro e, entre os igualmente seguros, do que mais devolve para você para o que menos devolve. “Retorno” é a parte da recompensa que fica com você: desconta a comissão do validador e o desempenho dele — quanto ele recebeu da rede por SOL em stake nas 3 últimas épocas, comparado com a maior parte da rede (100% = igual ou melhor).",
        "Ajudar você a escolher sem precisar comparar números. Segurança sempre vem antes: um validador que saiu do ar, subiu a comissão ou ficou grande demais nunca passa na frente por pagar mais. Os dados vêm direto da rede Solana (comissão e recompensas registradas na conta de voto); a comissão sobre MEV não é informada pela rede nesse formato, então não entra na conta.",
        "Dois validadores no ar: A cobra 0% e tem desempenho 99,9% (retorno 99,9%); B cobra 5% e tem desempenho 100% (retorno 95%). A aparece primeiro. Se A sair do ar amanhã, ele some da lista, mesmo pagando mais.")
    val CONCENTRATION = t("Concentração",
        "Quando poucos validadores grandes somam mais de um terço de todo o stake, eles juntos poderiam travar a rede.",
        "Delegar para validadores menores espalha o poder e deixa a Solana mais segura para todos — inclusive para o seu SOL.",
        "Se um recomendado cresceu e entrou nesse grupo, ele aparece marcado “concentrado”: ainda funciona, mas prefira outro.")
    val ADVANCED_STAKE = t("Modo avançado",
        "Mostra todos os validadores da rede e deixa colar qualquer endereço de voto.",
        "Para quem já sabe para quem quer delegar. A carteira continua checando se o validador existe e se está no ar, mas não avalia quem opera.",
        "Você quer apoiar o validador de um amigo: cole o endereço de voto dele aqui, confira os primeiros e últimos caracteres e revise.")
    val COMMISSION = t("Comissão",
        "A porcentagem das recompensas que o validador fica para ele.",
        "Comparar validadores: comissão menor = mais recompensa para você.",
        "Recompensa de 1 SOL com comissão de 10%: você recebe 0,9 SOL e o validador 0,1 SOL.")
    val DELINQUENT = t("Fora do ar",
        "O validador parou de participar da rede recentemente.",
        "Enquanto estiver fora do ar, ele não gera recompensas para quem delegou.",
        "Seu validador apareceu “fora do ar”: considere desativar o stake e delegar para outro.")
    val EPOCH = t("Época",
        "Um ciclo da rede Solana com cerca de 2 a 3 dias. Ativar e desativar stake só acontece na virada de uma época.",
        "Explica por que o stake não fica ativo nem disponível na hora.",
        "Você ativa o stake na segunda: ele começa a render na próxima virada de época, por volta de quarta.")
    val STAKE_STATES = t("Estados do stake",
        "Ativando → ativo → desativando → inativo. Só stake inativo pode ser sacado de volta para a carteira.",
        "Saber quando você pode usar o SOL de novo.",
        "Quer o SOL de volta? Toque em Desativar, espere a época terminar e então toque em Sacar.")
    val MIN_STAKE = t("Mínimo para stake",
        "A rede exige pelo menos 1 SOL delegado, mais um pequeno depósito para a conta de stake.",
        "Evitar criar uma conta de stake que a rede recusaria.",
        "Com 1,5 SOL você pode fazer stake de 1 SOL e ainda sobra para as taxas.")
    val SPLIT = t("Dividir stake",
        "Separa parte de uma conta de stake em uma nova conta.",
        "Desativar ou sacar só uma parte, mantendo o resto rendendo.",
        "Você tem 10 SOL em stake e precisa de 3: divida 3 SOL, desative só essa parte e saque depois.")
    val MERGE = t("Juntar stakes",
        "Une duas contas de stake compatíveis (mesmo validador e mesmo estado) em uma só.",
        "Simplificar: menos contas para acompanhar e o depósito de uma delas volta para você.",
        "Você fez stake duas vezes no mesmo validador: junte as contas e fique com uma só.")

    // ------------------------------------------------------------ dApps
    val DAPPS = t("dApps",
        "Aplicativos e sites da Solana que pedem para usar sua carteira, conectados pelo próprio celular (Mobile Wallet Adapter).",
        "Usar serviços da Solana sem entregar sua chave: cada pedido aparece aqui, é analisado e só é assinado com seu PIN.",
        "Um site de NFTs pede para conectar: você vê o nome, escolhe a carteira Connect e aprova. Cada compra pede confirmação.")
    val SESSION = t("Sessão de dApp",
        "Uma conexão aprovada entre um app e sua carteira Connect. Ela expira em 24 horas.",
        "O app só vê seu endereço público. Revogar a sessão corta o acesso na hora.",
        "Não usa mais aquele jogo? Toque em Revogar e ele precisa pedir permissão de novo.")
    val UNVERIFIED_DAPP = t("Identidade do dApp",
        "Nome e site são informados pelo próprio app — a Lock Wallet não consegue comprovar quem ele é.",
        "Golpistas imitam apps famosos. Conferir o endereço ajuda a perceber uma imitação.",
        "Um app diz ser “Magic Eden” mas o endereço é magic-edem.io: recuse.")
    val RAW_PROGRAM = t("Instrução não decodificada",
        "Parte da transação chama um programa que a Lock Wallet não sabe traduzir para português.",
        "Você vê o programa, as contas envolvidas e o efeito simulado, mas não a intenção exata. Só aprove com apps que você confia.",
        "Uma compra num marketplace usa o programa do próprio marketplace: aparece como “não decodificada” com o resultado simulado.")
    val SIWS = t("Entrar com Solana",
        "Uma mensagem assinada que prova a um site que você controla este endereço — como fazer login.",
        "Entrar em sites sem senha. Não move dinheiro.",
        "Um site pede “Entrar com Solana”: confira se o domínio da mensagem é o mesmo do site antes de aprovar.")
    val MESSAGE = t("Assinar mensagem",
        "Assinar um texto prova que você é dono do endereço. Não envia dinheiro.",
        "Logins e confirmações em apps. A Lock Wallet só assina textos legíveis — nunca dados escondidos.",
        "“Quero entrar no app X às 14h” — leia e assine se fizer sentido.")
    val ROLE_CONNECT = t("Carteira Lock Connect",
        "Uma carteira separada, com frase própria, feita para conectar com dApps.",
        "Isolar riscos: se um app enganar você, só o dinheiro da Connect fica exposto; o Vault nunca conecta.",
        "Deixe a maior parte no Vault e passe para a Connect só o que vai usar no app hoje.")
    val ROLE_VAULT = t("Carteira Lock Vault",
        "A carteira de guarda: nunca conecta a dApps, só envia, recebe e faz stake.",
        "Guardar a maior parte do seu dinheiro longe de qualquer app de terceiros.",
        "Sua reserva de longo prazo fica no Vault; para usar um app, transfira uma parte para a Connect.")

    // ------------------------------------------------------------ custody / recovery
    val CUSTODY_LOCAL = t("Neste aparelho",
        "A chave fica criptografada no chip de segurança do celular, protegida pelo PIN e pela biometria.",
        "O jeito mais simples e seguro para a maioria das pessoas — nada sai do aparelho.",
        "Você cria a carteira, anota a frase num papel e guarda em casa. No dia a dia usa só PIN e digital.")
    val CUSTODY_SEED_VAULT = t("Seed Vault",
        "Um cofre de chaves embutido nos celulares Solana (Saga e Seeker). A Lock Wallet nunca vê a frase.",
        "Segurança extra: cada assinatura é autorizada pelo próprio cofre do aparelho.",
        "No Seeker, escolha Seed Vault: a frase é criada e guardada pelo sistema, e a Lock Wallet só pede assinaturas.")
    val PHRASE = t("Frase de recuperação",
        "12 ou 24 palavras que são a chave mestra da carteira. Quem tem a frase tem o dinheiro.",
        "Recuperar tudo se perder o celular. Anote no papel e guarde longe de fotos, prints e nuvem.",
        "Celular roubado? Instale a Lock Wallet em outro aparelho, digite a frase e seu saldo volta.")
    val PHRASE_LENGTH = t("12 ou 24 palavras",
        "Duas opções de tamanho para a frase. As duas são seguras; 24 palavras têm ainda mais margem.",
        "12 palavras são mais fáceis de anotar sem erro; 24 são o padrão de cofres de longo prazo.",
        "Para o dia a dia, 12 palavras bastam. Para guardar uma grande reserva, escolha 24.")
    val QUIZ = t("Teste da frase",
        "Pedimos algumas palavras da frase em posições sorteadas.",
        "Confirmar que você anotou certo — antes de colocar dinheiro numa carteira que talvez não consiga restaurar.",
        "“Qual é a palavra nº 7?” Confira no seu papel e escolha a certa.")
    val PIN = t("PIN",
        "Uma senha de 6 dígitos que abre o cofre neste aparelho. Ela não sai do celular.",
        "Impedir que alguém com seu celular nas mãos abra a carteira. Depois de erros, há espera crescente; a carteira nunca é apagada.",
        "Evite datas e sequências como 123456. Algo que só você saiba, como 4 9 1 7 3 8.")
    val BIOMETRIC = t("Biometria",
        "Digital ou rosto, verificados pelo hardware do aparelho. Com “Desbloquear com a digital” ligado, ela substitui o PIN no dia a dia.",
        "Proteger a chave com o chip de segurança do celular: sem a sua biometria (ou o PIN), ninguém abre a carteira.",
        "Ao enviar SOL, você encosta o dedo e pronto. Se o celular reiniciar, o PIN é pedido uma vez.")
    val LWRP = t("Recovery Package",
        "Um arquivo de backup criptografado (.lwrp) com sua carteira, protegido por uma senha própria, diferente do PIN.",
        "Uma segunda forma de recuperação, útil principalmente para carteiras importadas por chave privada, que não têm frase.",
        "Exporte para um pendrive e guarde a senha de 8 palavras num papel em outro lugar.")
    val PRIVATE_KEY = t("Chave privada",
        "Uma sequência longa que controla um único endereço. Vem de outras carteiras ou ferramentas.",
        "Trazer uma conta que você só tem em forma de chave. Ela não tem frase, então faça um Recovery Package depois.",
        "Você exportou a chave da sua carteira antiga: cole aqui e a mesma conta aparece na Lock Wallet.")
    val KEYPAIR_JSON = t("Arquivo keypair",
        "Um arquivo .json com 64 números, usado por ferramentas de desenvolvedor da Solana.",
        "Importar contas criadas pela linha de comando.",
        "Você tem “id.json” do solana-keygen: escolha o arquivo e a conta é importada.")
    val DISCOVERY = t("Escolher conta",
        "Uma mesma frase gera várias contas. Mostramos as primeiras para você escolher qual restaurar.",
        "Carteiras diferentes numeram as contas de jeitos diferentes; aqui você vê os dois padrões e o saldo de cada uma.",
        "Sua conta antiga tinha saldo: escolha a linha que mostra esse saldo.")
    val BACKUP_REQUIRED = t("Backup obrigatório",
        "Esta carteira ainda não tem uma cópia de segurança fora do aparelho.",
        "Se o celular for perdido sem backup, o dinheiro é perdido para sempre — ninguém consegue recuperar.",
        "Importou por chave privada? Exporte um Recovery Package e guarde num pendrive.")
    val QUARANTINE = t("Carteira em quarentena",
        "O sistema do celular invalidou a chave do cofre (por exemplo, ao trocar a biometria ou o bloqueio de tela).",
        "Proteger você: a carteira não é apagada nem recriada às escondidas. Restaure pela frase ou pelo Recovery Package.",
        "Você cadastrou uma digital nova e a carteira entrou em quarentena: restaure com sua frase e siga normalmente.")
    val DELETE_WALLET = t("Excluir carteira",
        "Apaga deste aparelho a chave cifrada e os dados desta carteira. As outras carteiras não mudam. Os fundos continuam na blockchain.",
        "Liberar o app de uma carteira que você não usa mais. Depois de excluída, ela só volta pela frase de recuperação ou pelo Recovery Package.",
        "Você exclui a carteira “Antiga”: o saldo dela continua na rede; para usá-la de novo, restaure com a frase.")
    val LOCKOUT = t("Espera após erros",
        "Depois de vários PINs errados, o app espera um tempo crescente antes de aceitar outra tentativa.",
        "Tornar inviável adivinhar o PIN. A carteira nunca é apagada por erros — a frase sempre funciona.",
        "5 erros seguidos: espere 1 hora. Cada novo erro dobra a espera, até 24 horas.")

    // ------------------------------------------------------------ settings / network
    val RPC = t("Servidor da rede",
        "O servidor (RPC) pelo qual a carteira conversa com a rede Solana. Por padrão usamos dois públicos, um de reserva do outro.",
        "Ler saldos e enviar transações. Você pode usar um servidor próprio; ele é conferido para garantir que é mesmo a rede Solana principal.",
        "Tem uma conta num provedor de RPC? Cole a URL aqui e a carteira passa a usá-la, com o padrão como reserva.")
    val GENESIS = t("Conferência da rede",
        "Cada rede Solana tem uma “impressão digital” única. A carteira confere essa marca em todo servidor.",
        "Impedir que um servidor de rede de teste ou falso se passe pela rede real.",
        "Você cola por engano um servidor de testes: a carteira recusa e nada é salvo.")
    val PRICE = t("Cotação ao vivo",
        "O preço do SOL é buscado em Coinbase e Kraken a cada poucos segundos, comparando as duas fontes.",
        "Mostrar o saldo em reais ou dólares. Essas consultas revelam seu IP a essas empresas, mas nunca seu endereço.",
        "Desligue para não fazer nenhuma consulta de preço; o saldo passa a aparecer só em SOL.")
    val CURRENCY = t("Moeda",
        "A moeda usada para mostrar valores: dólar, euro, iene, libra, yuan, real, dólar canadense, dólar australiano, franco suíço ou rupia. A cotação vem de fontes públicas (Coinbase, com Kraken de reserva) e nunca é inventada.",
        "Ver o saldo na moeda que você usa no dia a dia. Se a fonte de reserva não tiver a sua moeda, o valor aparece em dólar, marcado como tal.",
        "Escolha Euro para ver “€ 1.650,00”; volte para Real para ver “R$ 10.800,00”.")
    val BIO_UNLOCK = t("Desbloquear com a digital",
        "Abre a carteira e confirma envios só com a digital (ou rosto), sem digitar o PIN. A chave fica no chip de segurança do celular e só funciona depois da sua biometria.",
        "Rapidez no dia a dia sem abrir mão do hardware: o PIN continua valendo e é pedido depois de reiniciar o celular, a cada 24 horas (você pode escolher 1, 4, 24 ou 72 h), depois de um PIN errado e se você cadastrar uma digital nova. Ver a frase, exportar e criar carteiras sempre pedem o PIN. Uma digital pode ser usada à força (alguém segurando seu dedo); se isso preocupa você, deixe desligado.",
        "Você abre o app, encosta o dedo e já vê o saldo. Na segunda-feira, depois de reiniciar o celular, o app pede o PIN uma vez.")
    val ABOUT = t("Sobre a Lock Wallet",
        "Carteira Solana de código aberto. Sem servidor próprio, sem rastreamento, sem anúncios.",
        "Saber exatamente com quem o app conversa: só a rede Solana e a cotação pública do SOL.",
        "Nenhuma conta, e-mail ou cadastro: instalou, criou a carteira e pronto.")
    val ONBOARD_CUSTODY = t("Onde a chave fica",
        "A chave da carteira pode morar neste aparelho (cofre da Lock Wallet) ou num Seed Vault, quando o celular tem um. Você pode ter carteiras dos dois tipos.",
        "Escolher o nível de proteção: o Seed Vault guarda a semente num chip separado; o cofre local protege com PIN, biometria e o chip de segurança do Android.",
        "Num celular comum, escolha “Neste aparelho”. Num Solana Seeker, você também pode usar o Seed Vault.")
    val RESTORE_PRIVACY = t("Privacidade ao restaurar",
        "A frase ou chave digitada fica só neste aparelho, é verificada localmente e apagada da memória logo depois.",
        "Nenhum servidor recebe sua frase — nem o nosso, porque não temos.",
        "Se você colar a frase, a área de transferência é limpa em seguida.")
    val RECEIVE = t("Receber",
        "Seu endereço e o QR code dele. Quem for te pagar escaneia ou copia.",
        "Receber SOL e tokens da rede Solana principal. Só a rede Solana: não envie de outras redes para este endereço.",
        "Mostre o QR para um amigo ou cole o endereço numa corretora para sacar SOL para você.")
    val HIDE_BALANCE = t("Ocultar saldo",
        "Troca os valores por “••••” na tela inicial.",
        "Privacidade em público: abrir a carteira sem mostrar quanto você tem para quem está do lado.",
        "No ônibus, ligue “Ocultar saldo”. Em casa, toque no saldo para mostrar de novo.")
    val HAPTICS = t("Vibração",
        "Pequenas vibrações ao tocar em botões, errar o PIN ou concluir uma ação.",
        "Dar retorno físico sem precisar olhar para a tela.",
        "Ao confirmar um envio, você sente um toque curto de confirmação.")
    val THEME = t("Tema",
        "Dois visuais: claro sobre preto ou preto sobre claro.",
        "Escolher o que for mais confortável para seus olhos.",
        "À noite, o tema escuro cansa menos a vista.")
    val SECURITY_CENTER = t("Central de Segurança",
        "Uma checagem do aparelho: bloqueio de tela, biometria, chip de segurança, atualizações e sinais de risco.",
        "Mostrar o que pode deixar sua carteira menos segura e como resolver. É só informativo: nada é apagado.",
        "Aparece “Depuração USB ligada”? Desligue nas configurações do Android se você não usa.")
    val AUTO_LOCK = t("Bloqueio automático",
        "A carteira se tranca sozinha sempre que você sai do app ou apaga a tela.",
        "Se alguém pegar seu celular desbloqueado, a carteira já estará trancada.",
        "Você troca para o WhatsApp e volta: a Lock Wallet pede a digital (ou o PIN) de novo.")
    val NO_NOTIFICATIONS = t("Sem notificações",
        "A Lock Wallet não tem servidor próprio, então não envia notificações push.",
        "Privacidade: ninguém além da rede Solana fica sabendo do seu endereço ou dos seus horários.",
        "Para conferir se recebeu, abra o app — o saldo atualiza sozinho em segundos.")

    /** The help index (Ajustes › Ajuda). */
    val INDEX: List<Pair<String, List<HelpTopic>>> = listOf(
        "Começando" to listOf(PHRASE, PIN, BIOMETRIC, BIO_UNLOCK, ACCOUNT, ADDRESS, RECEIVE, ROLE_VAULT, ROLE_CONNECT, ONBOARD_CUSTODY),
        "Enviar e receber" to listOf(NETWORK_FEE, PRIORITY_FEE, FEE_CAP, RENT, FIRST_SEND, POISONING, SIMULATION, ANALYZER, TX_STATUS, EXPIRY),
        "Tokens" to listOf(TOKENS, UNVERIFIED_NAME, TOKEN_POLICY, FREEZE, ATA, CLOSE_TOKEN),
        "Stake" to listOf(STAKE, TOTAL_BALANCE, CURATED, CURATED_ORDER, CONCENTRATION, ADVANCED_STAKE, VALIDATOR, COMMISSION, EPOCH, STAKE_STATES, MIN_STAKE, SPLIT, MERGE),
        "dApps" to listOf(DAPPS, SESSION, RAW_PROGRAM, SIWS, MESSAGE),
        "Segurança e backup" to listOf(LWRP, BIO_WINDOW, LOCKDOWN, BACKUP_REQUIRED, QUARANTINE, LOCKOUT, AUTO_LOCK, SECURITY_CENTER, RPC, GENESIS, PRICE, CURRENCY, RESTORE_PRIVACY, NO_NOTIFICATIONS, ABOUT),
    )
}
