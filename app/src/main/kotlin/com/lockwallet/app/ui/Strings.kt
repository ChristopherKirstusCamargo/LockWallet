package com.lockwallet.app.ui

import com.lockwallet.custody.local.CheckId
import com.lockwallet.custody.local.CheckStatus
import com.lockwallet.custody.local.CustodyException.Reason
import com.lockwallet.custody.local.api.RecoveryException
import com.lockwallet.custody.local.material.MaterialError

/** pt-BR copy. Single place so wording can be reviewed (spec §9: clear language, no jargon without explanation). */
object S {
    const val APP = "LOCK WALLET"
    const val CREATE = "Criar nova carteira"
    const val RESTORE = "Restaurar carteira"
    const val SECURITY_CENTER = "Central de Segurança"
    const val LOCK = "Bloquear"
    const val BACK = "Voltar"
    const val CONTINUE = "Continuar"
    const val CANCEL = "Cancelar"
    const val DONE = "Concluir"
    const val WORKING = "Processando…"
    const val HOME_EMPTY = "Nenhuma carteira neste aparelho ainda."
    const val VAULT_GROUP = "Lock Vault — guarda de longo prazo"
    const val CONNECT_GROUP = "Lock Connect — uso com dApps"
    const val QUARANTINED = "Em quarentena: a chave de hardware foi invalidada. Restaure com a frase ou o Recovery Package."

    const val CUSTODY_TITLE = "Onde a chave vai ficar?"
    const val CUSTODY_LOCAL = "Neste aparelho"
    const val CUSTODY_LOCAL_DESC = "Cifrada com chave do Android Keystore. O nível de hardware (StrongBox, TEE ou software) é conferido na Central de Segurança. O PIN é obrigatório; biometria forte é opcional."
    const val CUSTODY_SEED_VAULT = "Seed Vault"

    const val ROLE_TITLE = "Tipo de carteira"
    const val ROLE_VAULT = "Lock Vault"
    const val ROLE_VAULT_DESC = "Para guardar. Nunca conecta a dApps. Recomendado para a maior parte dos fundos."
    const val ROLE_CONNECT = "Lock Connect"
    const val ROLE_CONNECT_DESC = "Para usar com dApps. Sempre usa uma frase separada de qualquer Lock Vault."

    const val LENGTH_TITLE = "Tamanho da frase de recuperação"
    const val LENGTH_12 = "12 palavras"
    const val LENGTH_24 = "24 palavras"
    const val LENGTH_DESC = "As duas opções são seguras. 24 palavras dão margem extra; 12 são mais fáceis de anotar sem erro."

    const val WARN_TITLE = "Antes de ver a frase"
    val WARN_POINTS = listOf(
        "Quem tiver estas palavras controla a carteira. A Lock Wallet nunca pede a frase de volta.",
        "Anote em papel, na ordem. Não fotografe, não copie, não salve na nuvem nem em mensagens.",
        "Esteja num lugar reservado. Capturas de tela estão bloqueadas nesta tela.",
        "Se perder a frase e o aparelho, os fundos não podem ser recuperados por ninguém.",
    )
    const val WARN_ACK = "Entendi, mostrar a frase"
    const val PHRASE_TITLE = "Sua frase de recuperação"
    const val PHRASE_WROTE = "Já anotei em papel"
    const val QUIZ_TITLE = "Confirme sua anotação"
    const val QUIZ_WRONG = "Palavra incorreta. Confira sua anotação — a ordem importa."
    fun quizQuestion(pos: Int) = "Qual é a palavra nº $pos?"

    const val NAME_TITLE = "Nome da carteira"
    const val NAME_HINT = "Ex.: Principal"

    const val PIN_CREATE = "Crie um PIN de 6 dígitos"
    const val PIN_CONFIRM = "Confirme o PIN"
    const val PIN_MISMATCH = "Os PINs não conferem. Tente de novo."
    const val PIN_TRIVIAL = "PIN fácil demais (sequência ou repetido). Escolha outro."
    const val PIN_NOTE = "O PIN abre o cofre neste aparelho. Ele não substitui a frase de recuperação."
    const val PIN_ENTER = "Digite seu PIN"
    const val BIO_TITLE = "Biometria forte"
    const val BIO_DESC = "Recomendado: a chave do cofre só é liberada pelo hardware após sua biometria, a cada uso (além do PIN)."
    const val BIO_USE = "Usar PIN + biometria (recomendado)"
    const val BIO_SKIP = "Usar só PIN"
    const val BIO_SKIP_WARN = "Só PIN: se alguém conseguir usar a chave do aparelho, um PIN de 6 dígitos pode ser testado por força bruta."
    const val BIO_NONE = "Biometria forte indisponível ou não cadastrada neste aparelho. O cofre usará só PIN."
    const val PROMPT_UNLOCK = "Desbloquear cofre"
    const val PROMPT_SIGN = "Confirmar assinatura"
    const val USE_PIN = "Usar PIN"
    const val BIO_INVALIDATED = "A biometria do aparelho mudou. Use o PIN e ative a digital de novo nos Ajustes."
    const val PROMPT_SEAL = "Proteger a nova carteira"
    const val PROMPT_UPGRADE = "Atualizar proteção do cofre"
    const val PROMPT_SUB = "Confirme com sua biometria"
    const val BIO_CANCELLED = "Biometria cancelada. Nada foi alterado."

    const val LOCK_TITLE = "Cofre bloqueado"
    fun wrongPin(left: Int) = if (left > 0) "PIN incorreto. Restam $left tentativa(s) antes do bloqueio temporário." else "PIN incorreto."
    fun lockedOut(ms: Long): String {
        val s = (ms + 999) / 1000
        return "Muitas tentativas. Tente de novo em %02d:%02d:%02d. A carteira não é apagada; a frase sempre funciona.".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    const val RESTORE_TITLE = "Como deseja restaurar?"
    const val R_PHRASE = "Frase de recuperação (12 a 24 palavras)"
    const val R_LWRP = "Recovery Package (.lwrp)"
    const val R_PRIVKEY = "Chave privada (Base58)"
    const val R_JSON = "Arquivo keypair JSON (solana-keygen)"
    const val PHRASE_INPUT = "Digite as palavras separadas por espaço"
    const val BIP39_PASSPHRASE_UNSUPPORTED = "A senha extra da frase (passphrase BIP-39) não é suportada. A restauração usa passphrase vazia e pode abrir outro endereço."
    const val PRIVKEY_INPUT = "Cole a chave privada (Base58)"
    const val PICK_FILE = "Escolher arquivo"
    const val LWRP_PASSWORD = "Senha do Recovery Package"

    const val DETAIL_ADDRESS = "Endereço público"
    const val COPY_ADDRESS = "Copiar endereço"
    const val COPIED = "Endereço copiado"
    const val RENAME = "Renomear"
    const val VIEW_PHRASE = "Ver frase de recuperação"
    const val EXPORT_LWRP = "Exportar Recovery Package"
    const val ADD_ACCOUNT = "Adicionar conta (mesma frase)"
    const val REMOVE_QUARANTINED = "Remover carteira em quarentena"
    const val HIDE = "Ocultar"

    const val EXPORT_TITLE = "Recovery Package (.lwrp)"
    const val EXPORT_DESC = "Um arquivo de backup criptografado com uma senha só dele, diferente do PIN. Guarde a senha longe do arquivo."
    const val EXPORT_GENERATE = "Gerar senha de 8 palavras (recomendado)"
    const val EXPORT_OWN = "Usar minha senha (mín. 16 bytes)"
    const val EXPORT_CONFIRM = "Confirme a senha"
    const val EXPORT_MISMATCH = "As senhas não conferem."
    const val EXPORT_SAVE = "Escolher onde salvar"
    const val EXPORT_VERIFIED = "Exportado e verificado: o arquivo foi lido de volta e aberto com a senha."
    const val EXPORT_UNVERIFIED = "O arquivo foi gravado, mas a verificação falhou. Não confie nele; exporte de novo."
    const val EXPORT_FAILED = "Não foi possível gravar o arquivo."
    const val EXPORT_PW_NOTE = "Anote esta senha agora. Sem ela o arquivo não abre."

    const val SC_TITLE = "Central de Segurança"
    const val SC_NOTE = "Checagens feitas agora neste aparelho. É só informativo: nada é apagado nem bloqueado."

    fun reason(r: Reason): String = when (r) {
        Reason.QUARANTINED, Reason.KEY_INVALIDATED -> "A chave de hardware foi invalidada (ex.: biometria ou bloqueio de tela alterados). A carteira ficou em quarentena; restaure pela frase ou pelo Recovery Package."
        Reason.KEY_MISSING -> "A chave do cofre não existe mais no Keystore. Nada foi recriado; restaure pela frase ou pelo Recovery Package."
        Reason.AUTH_REQUIRED -> "Autenticação necessária. Tente de novo."
        Reason.UNSUPPORTED_VERSION -> "Este cofre foi criado por uma versão mais nova do app. Atualize o app; o arquivo não foi alterado."
        Reason.CORRUPTED -> "Dados do cofre inconsistentes. Nada foi alterado; restaure pela frase se necessário."
        Reason.DUPLICATE_WALLET -> "Esta conta já existe neste aparelho."
        Reason.ROLE_CONFLICT -> "Esta frase já é usada por outro tipo de carteira. Lock Vault e Lock Connect devem usar frases diferentes."
        Reason.BAD_NAME -> "Nome inválido (1 a 32 caracteres)."
        Reason.BAD_PIN_FORMAT -> "O PIN deve ter 6 dígitos (0–9)."
        Reason.WEAK_PIN -> PIN_TRIVIAL
        Reason.NO_VAULT -> "Nenhum cofre ativo."
        Reason.VAULT_EXISTS -> "Já existe um cofre ativo."
        Reason.UNKNOWN_WALLET -> "Carteira não encontrada."
        Reason.BAD_ACCOUNT -> "Conta inválida."
        Reason.STORAGE -> "Falha ao gravar com segurança. A operação não foi confirmada; confira o estado antes de continuar."
        Reason.KEYSTORE -> "O Keystore do aparelho falhou. Nada foi alterado; tente de novo."
        Reason.SEED_VAULT_UNAVAILABLE -> "Seed Vault indisponível neste aparelho."
        Reason.SEED_VAULT_DECLINED -> "O Seed Vault não assinou (cancelado ou recusado). Nada foi enviado."
        Reason.BIO_PIN_REQUIRED -> "Digite o PIN desta vez."
        Reason.BIO_NOT_ENROLLED -> "Digite o PIN desta vez."
        Reason.BIO_INVALIDATED -> BIO_INVALIDATED
        Reason.BAD_SIGNATURE -> "A assinatura devolvida não confere com a chave pública da carteira. Nada foi enviado."
        Reason.NOT_APPROVED -> "A custódia recusou assinar: os bytes não correspondem ao que foi revisado ou não passam no analisador."
    }

    fun material(e: MaterialError, pos: Int?): String = when (e) {
        MaterialError.WORD_COUNT -> "A frase deve ter 12, 15, 18, 21 ou 24 palavras."
        MaterialError.UNKNOWN_WORD -> "Palavra ${pos?.plus(1) ?: ""} não está na lista padrão de palavras (em inglês). Confira a grafia."
        MaterialError.CHECKSUM -> "Frase inválida (soma de verificação). Confira a ordem e a grafia."
        MaterialError.NOT_CANONICAL -> "Use apenas letras a–z separadas por espaço."
        MaterialError.KEY_FORMAT -> "Chave em formato inválido (esperado Base58)."
        MaterialError.KEY_LENGTH -> "Tamanho de chave inválido (esperado 64 bytes)."
        MaterialError.KEYPAIR_INCONSISTENT -> "A chave pública não corresponde à chave privada."
        MaterialError.JSON_FORMAT -> "Arquivo JSON inválido (esperado [n0, …, n63])."
        MaterialError.TOO_LARGE -> "Arquivo grande demais para ser um keypair."
    }

    fun recovery(r: RecoveryException.Reason): String = when (r) {
        RecoveryException.Reason.PASSWORD_TOO_SHORT -> "Senha curta demais (mínimo 16 bytes)."
        RecoveryException.Reason.PASSWORD_TOO_LONG -> "Senha longa demais (máximo 256 bytes)."
        RecoveryException.Reason.PASSWORD_INVALID -> "A senha contém caracteres inválidos."
        RecoveryException.Reason.OPEN_FAILED -> "Não foi possível abrir o pacote: senha incorreta ou arquivo alterado."
        RecoveryException.Reason.NOT_A_PACKAGE -> "Este arquivo não é um Recovery Package v1 válido."
    }

    fun checkTitle(id: CheckId) = when (id) {
        CheckId.LOCK_SCREEN -> "Bloqueio de tela do aparelho"
        CheckId.BIOMETRIC_STRONG -> "Biometria forte (Classe 3)"
        CheckId.STRONGBOX_FEATURE -> "StrongBox (chip seguro dedicado)"
        CheckId.VAULT_KEY_LEVEL -> "Onde está a chave do cofre"
        CheckId.VAULT_AUTH_MODE -> "Proteção do cofre"
        CheckId.PIN_ATTEMPTS -> "Tentativas de PIN"
        CheckId.SECURITY_PATCH -> "Atualização de segurança do Android"
        CheckId.ADB -> "Depuração USB (ADB)"
        CheckId.DEVELOPER_OPTIONS -> "Opções do desenvolvedor"
        CheckId.ROOT_HEURISTIC -> "Indícios de root"
        CheckId.DEBUGGER -> "Depurador"
        CheckId.HOOKING -> "Indícios de hooking (Frida/Xposed)"
        CheckId.SCREEN_PROTECTION -> "Proteção de tela"
        CheckId.BACKUP -> "Backup em nuvem do app"
        CheckId.NETWORK -> "Rede"
        CheckId.SEED_VAULT -> "Seed Vault"
    }

    fun checkDetail(id: CheckId, st: CheckStatus, v: String?): String = when (id) {
        CheckId.LOCK_SCREEN -> if (st == CheckStatus.OK) "Ativo." else "Sem bloqueio de tela seguro: qualquer pessoa com o aparelho chega ao app. Ative PIN/senha do Android."
        CheckId.BIOMETRIC_STRONG -> when (v) {
            "AVAILABLE" -> "Disponível e cadastrada."
            "NONE_ENROLLED" -> "Hardware presente, mas nenhuma biometria cadastrada."
            "UPDATE_REQUIRED" -> "O aparelho exige atualização de segurança para usar a biometria."
            else -> "Indisponível neste aparelho."
        }
        CheckId.STRONGBOX_FEATURE -> if (st == CheckStatus.OK) "O aparelho declara StrongBox." else "Não declarado; o TEE é usado."
        CheckId.VAULT_KEY_LEVEL -> when (v) {
            "STRONGBOX" -> "StrongBox (confirmado pelo KeyInfo)."
            "TRUSTED_ENVIRONMENT" -> "TEE — ambiente de execução confiável (confirmado pelo KeyInfo)."
            "UNKNOWN_SECURE" -> "Hardware seguro de tipo não informado."
            "SOFTWARE" -> "Somente software: proteção fraca contra extração. Prefira outro aparelho para valores reais."
            null -> "Nenhum cofre criado ainda."
            else -> "Nível não informado pelo aparelho."
        }
        CheckId.VAULT_AUTH_MODE -> when (v) {
            "PIN_AND_BIOMETRIC" -> "PIN + biometria forte por uso (modo legado)."
            "PIN_AND_DEVICE_AUTH" -> "PIN da carteira + biometria forte ou credencial do Android em cada uso da chave."
            "PIN_ONLY" -> "Só PIN; KEK sem autenticação por uso. Modo legado de menor proteção."
            else -> "Nenhum cofre criado ainda."
        }
        CheckId.PIN_ATTEMPTS -> when (st) {
            CheckStatus.RISK -> "Bloqueio temporário ativo após tentativas erradas."
            CheckStatus.WARN -> "$v tentativa(s) errada(s) desde o último acesso correto."
            else -> "Nenhuma tentativa errada."
        }
        CheckId.SECURITY_PATCH -> when {
            v?.contains("CVE-2025-48651") == true ->
                "A chave do cofre está no StrongBox afetado pela CVE-2025-48651. Atualize o Android para patch 05/04/2026 ou mais recente."
            st == CheckStatus.OK -> "Recente ($v)."
            st == CheckStatus.WARN -> "Antiga ou desconhecida (${v?.ifEmpty { "?" }})."
            else -> "Muito antiga ($v): o aparelho tem falhas conhecidas sem correção."
        }
        CheckId.ADB -> if (st == CheckStatus.OK) "Desligada." else "Ligada: um computador conectado pode controlar o aparelho. Desligue se não usa."
        CheckId.DEVELOPER_OPTIONS -> if (st == CheckStatus.OK) "Desligadas." else "Ligadas."
        CheckId.ROOT_HEURISTIC -> if (st == CheckStatus.OK) "Nenhum indício encontrado (heurística, não é garantia)." else "Indícios: $v. Um aparelho com root anula boa parte das proteções."
        CheckId.SCREEN_PROTECTION -> if (v == "overlay") "Capturas bloqueadas e toques sob sobreposição ignorados, mas o sistema recusou ocultar sobreposições de outros apps." else "Capturas e gravação de tela bloqueadas; toques sob sobreposição ignorados; sobreposições de outros apps ocultadas."
        CheckId.BACKUP -> "Desativado: os dados do cofre não vão para a nuvem nem para outro aparelho."
        CheckId.NETWORK -> "Rede principal da Solana, conferida a cada conexão. Sem servidor próprio, sem rastreamento."
        CheckId.SEED_VAULT -> if (st == CheckStatus.OK) "Disponível neste aparelho: você pode criar carteiras cuja frase fica no Seed Vault." else "Este aparelho não tem Seed Vault (só aparelhos Solana Mobile, como Saga/Seeker)."
        CheckId.DEBUGGER -> when (v) { "attached" -> "Um depurador/rastreador está conectado ao app agora. Não use com valores reais."; "debuggable" -> "Build de depuração (não é a versão de release)."; else -> "Nenhum depurador conectado." }
        CheckId.HOOKING -> if (st == CheckStatus.OK) "Nenhum indício encontrado (heurística, não é garantia)." else "Indícios: $v. Ferramentas de hooking podem ler a tela e alterar o app. Apenas informativo — nada foi apagado."
    }

    // ---------------------------------------------------------------- Phase H: QR
    const val QR_HINT = "Aponte para o QR do endereço. Só o endereço é lido; valor e memo do QR são ignorados."
    const val QR_TX_REQUEST = "Este QR é um pedido de transação do Solana Pay (busca a transação num servidor). A Lock Wallet não assina isso."
    const val QR_INVALID = "O QR não contém um endereço Solana válido."
    const val QR_CAMERA_FAILED = "Não foi possível abrir a câmera."
    const val CAMERA_DENIED = "Sem permissão de câmera. Você pode colar ou digitar o endereço."

    // ---------------------------------------------------------------- Phase H: Lock Connect
    const val DAPP_UNVERIFIED = "Identidade informada pelo próprio dApp — não verificada. Confira o endereço antes de aprovar."
    const val DAPP_NO_CONNECT_WALLET = "Você ainda não tem carteira Connect. Crie uma (com frase própria) e conecte de novo. O Vault nunca conecta a apps."
    const val DAPP_AUTH_EXPLAIN = "Conectar só compartilha seu endereço público. Cada pedido aparece aqui e pede seu PIN. A conexão expira em 24 h e pode ser revogada na aba dApps."
    const val SIWS_DOMAIN_MISMATCH = "O domínio da mensagem de entrada não confere com o endereço informado pelo dApp. Pode ser phishing."
    const val DAPP_MSG_NOT_TEXT = "Não é texto apresentável (ou parece uma transação). A Lock Wallet não assina o que não consegue mostrar."
    const val DAPP_MSG_EXPLAIN = "Assinar uma mensagem prova que você controla este endereço. Não move fundos, mas só assine textos que você entende."
    const val DAPP_RAW_WARNING = "Esta transação chama programa(s) que a Lock Wallet não sabe decodificar. Você vê só o ID do programa, as contas, as permissões, os dados brutos e a variação simulada de SOL/tokens. Um programa malicioso pode fazer algo diferente do que o dApp diz. Use apenas com apps que você confia — aqui é dinheiro real."
    const val DAPP_RAW_ACK = "Entendo os riscos e quero assinar mesmo sem decodificação completa"
    const val DAPP_TAB_EXPLAIN = "Use apps da Solana no próprio celular. Só carteiras Connect conectam; cada pedido é analisado, testado e só é assinado com seu PIN. Ações perigosas são bloqueadas."

    // ---------------------------------------------------------------- Phase G: Seed Vault
    const val SV_DESC = "A frase fica no Seed Vault do aparelho; a Lock Wallet nunca a vê. Cada assinatura é autenticada pelo próprio Seed Vault."
    const val SV_EXPLAIN = "O Seed Vault guarda a frase e pede sua autenticação a cada assinatura. A Lock Wallet só recebe o endereço público e analisa cada transação antes de pedir a assinatura."
    const val SV_CONNECT_NOTE = "Use uma semente diferente da do Lock Vault."
    const val SV_BACKUP = "Backup e recuperação: feitos no próprio Seed Vault. A Lock Wallet não vê a frase, não faz quiz nem exporta Recovery Package desta carteira."
    const val SV_REVOKED = "O Seed Vault não autoriza mais esta semente para a Lock Wallet (removida ou acesso revogado). Remova e autorize de novo."

    // ---------------------------------------------------------------- Phase D: chain, send, network
    const val NO_WALLET = "Crie ou restaure uma carteira primeiro."
    const val BAD_ADDRESS = "Endereço Solana inválido (Base58 de 32 bytes)."
    const val SELF_SEND = "O destino é a própria carteira."
    const val DEST_EXECUTABLE = "O destino é um programa executável; SOL enviado a ele pode ficar inacessível. Envio recusado."
    const val FEE_UNKNOWN = "Não foi possível calcular a taxa desta transação. Envio bloqueado por segurança."
    const val BAD_AMOUNT = "Valor inválido (use até 9 casas decimais, ex.: 0,5)."
    const val DEST_OFF_CURVE = "O destino não é uma chave de carteira (endereço derivado de programa). Confira se é isso mesmo."
    const val DEST_NEW = "O destino ainda não existe na rede (conta nova)."
    const val ACK_NEW_RECIPIENT = "Primeiro envio para este endereço: conferi o endereço inteiro, caractere por caractere."
    const val BLOCKHASH_EXPIRED = "A validade desta transação acabou antes da assinatura. Nada foi assinado nem enviado — revise de novo."
    const val URL_BAD = "Endereço inválido. Use https:// (sem usuário/senha na URL)."
    const val URL_UNREACHABLE = "Não foi possível falar com este servidor."
    const val URL_SAVED = "Servidor conferido (é a rede principal da Solana) e salvo."
    const val URL_RESET = "Voltamos aos servidores padrão."
    fun urlOther(known: com.lockwallet.data.rpc.Cluster?) =
        if (known != null) "Este servidor é de uma rede de testes (${known.label}), não da Solana principal. Nada foi salvo."
        else "Este servidor não é da rede principal da Solana. Nada foi salvo."
    fun insufficient(bal: Long, fee: Long) = "Saldo insuficiente: você tem ${Fmt.sol(bal)} SOL e a taxa é ${Fmt.sol(fee)} SOL."
    fun residual(rent0: Long, max: Long) = "O saldo restante ficaria abaixo do mínimo da rede (${Fmt.sol(rent0)} SOL) e a transação falharia. Envie o máximo (${Fmt.sol(max)} SOL) ou deixe ao menos o mínimo."
    fun newAccountMin(rent0: Long) = "O destino é uma conta nova: precisa receber ao menos ${Fmt.sol(rent0)} SOL (mínimo da rede)."
    fun destOwnedBy(owner: String) = "O destino não é uma carteira comum: é uma conta do programa ${owner.take(8)}…. SOL enviado assim pode ficar preso."
    fun similarTo(known: String) = "ATENÇÃO: este endereço se parece com $known, mas é DIFERENTE. Pode ser envenenamento de endereço."
    fun rpc(e: com.lockwallet.data.rpc.RpcException): String = when (e.kind) {
        com.lockwallet.data.rpc.RpcException.Kind.TRANSPORT, com.lockwallet.data.rpc.RpcException.Kind.HTTP -> "Sem conexão com a rede agora. Tente de novo em instantes."
        com.lockwallet.data.rpc.RpcException.Kind.RATE_LIMITED -> "O servidor da rede está ocupado. Tente de novo em instantes."
        com.lockwallet.data.rpc.RpcException.Kind.WRONG_NETWORK -> "O servidor respondeu como outra rede (não é a Solana principal). Bloqueado por segurança."
        com.lockwallet.data.rpc.RpcException.Kind.MALFORMED -> "Resposta inválida do servidor da rede. Nada foi assinado."
        com.lockwallet.data.rpc.RpcException.Kind.RPC_ERROR -> "A rede recusou: ${e.message?.take(120)}"
    }

    // ---------------------------------------------------------------- Phase E: tokens
    const val TOKEN_GONE = "A conta de token não foi encontrada na rede agora. Atualize e tente de novo."
    const val TOKEN_BLOCKED = "Token bloqueado pela política de segurança:"
    const val TOKEN_FROZEN = "Sua conta deste token está congelada pelo emissor."
    const val TOKEN_FROZEN_DEST = "A conta de destino deste token está congelada."
    const val BAD_TOKEN_DEST = "O destino é uma conta de token de outro token ou ilegível. Use o endereço da carteira do destinatário."
    const val NOT_CLOSABLE = "Só é possível fechar contas vazias, não congeladas e sem outra autoridade de fechamento."
    const val CLOSE_NOTE = "A conta deixa de existir; se receber este token de novo, uma nova conta será criada (com novo depósito)."
    const val UNVERIFIED = "nome do emissor — não verificado"
    fun tokenInsufficient(have: String, name: String) = "Saldo insuficiente: você tem $have $name."
    fun ataCreate(sol: String) = "O destinatário ainda não tem conta para este token: ela será criada nesta transação e você paga o depósito (≈ $sol SOL), que fica com o destinatário."
    fun destIsTokenAccount(owner: String) = "O endereço colado é uma conta de token (dona: ${owner.take(4)}…${owner.takeLast(4)}), não uma carteira. Confira se é isso mesmo."

    // ---------------------------------------------------------------- Phase F: staking
    const val STAKE_GONE = "Conta de stake não encontrada na rede agora. Atualize e tente de novo."
    const val STAKE_NOT_CONTROLLED = "Esta conta de stake é controlada por outra chave; a Lock Wallet não mexe nela."
    const val STAKE_LOCKUP = "Conta de stake com bloqueio por prazo: não suportada."
    const val STAKE_NOT_ACTIVE = "Só é possível desativar um stake ativo ou ativando."
    const val STAKE_NOT_INACTIVE = "Só é possível sacar depois que o stake estiver inativo (desative e aguarde o fim da época)."
    const val PICK_VALIDATOR = "Escolha um validador da lista."
    const val UNKNOWN_VALIDATOR = "Este validador não aparece na lista da rede."
    const val VALIDATOR_DELINQUENT = "Validador fora do ar: o stake não rende enquanto isso durar."
    const val STAKE_WARMUP = "O stake começa a render após a ativação, no início da próxima época (estimativa; a rede pode limitar ativações)."
    const val STAKE_COOLDOWN = "O stake para de render e fica sacável após o fim da época atual (estimativa). O SOL não sai da conta de stake agora."
    const val WITHDRAW_CLOSES = "Sacar tudo fecha a conta de stake; o SOL volta para sua carteira."
    const val MERGE_INCOMPATIBLE = "Contas incompatíveis: só é possível unir contas no mesmo estado e com o mesmo validador."
    const val NATIVE_ONLY = "Stake nativo da Solana: só você controla. Sem intermediários nem tokens de terceiros."
    fun minDelegation(sol: String) = "O mínimo de delegação nesta rede é $sol SOL."
    fun highCommission(p: Int) = "Comissão alta: o validador fica com $p% das recompensas."
    const val AMBIGUOUS_AMOUNT = "Valor ambíguo: use vírgula para decimais (ex.: 1,5). Para mil, digite 1000."
    const val NOT_CURATED = "Validador fora da lista recomendada: escolha avançada, confira o endereço com cuidado."
    const val ADVANCED_WARNING = "Modo avançado: qualquer validador da rede. A Lock Wallet não avalia quem opera estes."
    fun curatedBlocked(name: String, st: com.lockwallet.app.CuratedStatus) = when (st) {
        com.lockwallet.app.CuratedStatus.COMMISSION_UP -> "$name aumentou a comissão desde a revisão; ficou indisponível na lista recomendada."
        com.lockwallet.app.CuratedStatus.DELINQUENT -> "$name está fora do ar agora; ficou indisponível na lista recomendada."
        else -> "$name não aparece como revisado na rede agora; ficou indisponível na lista recomendada."
    }
    fun curatedHidden(n: Int) = if (n == 1) "1 validador recomendado oculto agora" else "$n validadores recomendados ocultos agora"
    fun splitTooMuch(max: String) = "Valor alto demais: a conta original precisa manter a reserva e o mínimo. Máximo: $max SOL."
    fun lifecycle(l: com.lockwallet.core.solana.state.StakeLifecycle) = when (l) {
        com.lockwallet.core.solana.state.StakeLifecycle.INITIALIZED -> "não delegado"
        com.lockwallet.core.solana.state.StakeLifecycle.ACTIVATING -> "ativando"
        com.lockwallet.core.solana.state.StakeLifecycle.ACTIVE -> "ativo"
        com.lockwallet.core.solana.state.StakeLifecycle.DEACTIVATING -> "desativando"
        com.lockwallet.core.solana.state.StakeLifecycle.INACTIVE -> "inativo · sacável"
    }

    // ---------------------------------------------------------------- 2.0.0 redesign
    const val UNEXPECTED = "Algo inesperado aconteceu. Nada foi assinado nem enviado. Tente de novo."
    const val SV_NOT_HERE = "Disponível só em celulares Solana (Saga, Seeker)."
    const val NO_COPY = "Não há botão de copiar: outros apps podem ler a área de transferência."
    const val BAD_FILE = "Arquivo inválido ou grande demais."
    const val RESTORE_PRIVACY = "O que você digitar fica só neste aparelho. Ao colar, a área de transferência é apagada em seguida."
    const val UNLOCKING = "Abrindo o cofre…"
    const val AUTO_LOCK_NOTE = "Bloqueia sozinha ao sair do app"
    const val TAP_TO_REVEAL = "Toque para revelar"
    const val TAP_TO_REVEAL_SUB = "Confira se ninguém está olhando"
    const val RECEIVE_NOTE = "Envie para este endereço apenas SOL e tokens da rede Solana. Moedas de outras redes se perdem."
    const val MAX_NEEDS_DEST = "Digite o endereço de destino antes de usar o máximo."
    const val TX_UNKNOWN = "A rede ainda não confirmou. Não envie de novo: a carteira continua verificando e só reenvia a mesma transação, nunca uma nova."
    const val TX_EXPIRED = "Comprovado: a validade acabou e a transação não foi processada. Nenhum valor saiu. Você pode revisar uma nova."
    const val TX_FAILED = "A rede recusou a transação. O valor não saiu; só a taxa de rede pode ter sido cobrada."
    const val ATA_NOTE = "Se o destinatário nunca teve este token, a conta dele é criada no envio — você vê o custo na revisão."
    const val RPC_PRIVACY = "Só https. O servidor vê seus endereços públicos e seu IP, nunca suas chaves. Prefira um servidor de confiança."
    const val REVEAL_NOTE = "Prints e gravação de tela estão bloqueados. A frase some ao sair desta tela ou do app."
    const val S_CLUSTER_MISMATCH = "Este app pediu uma rede de testes. A Lock Wallet funciona só na rede principal da Solana."
}
