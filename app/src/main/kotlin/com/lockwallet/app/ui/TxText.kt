package com.lockwallet.app.ui

import com.lockwallet.core.solana.Amounts
import com.lockwallet.core.solana.ProgramIds
import com.lockwallet.core.solana.SolanaPublicKey
import com.lockwallet.core.solana.analyzer.Finding
import com.lockwallet.core.solana.analyzer.Op
import com.lockwallet.core.solana.tx.TransactionState
import com.lockwallet.data.rpc.PrepBlocker

/** Human-readable (pt-BR) text derived ONLY from the decoded operations — what the user approves. */
object TxText {
    fun short(k: SolanaPublicKey) = k.toBase58().let { it.take(4) + "…" + it.takeLast(4) }

    /** One line per decoded instruction. Full addresses are shown separately on the review screen. */
    fun op(o: Op, me: SolanaPublicKey, token: (SolanaPublicKey) -> String = { short(it) }): String = when (o) {
        is Op.SolTransfer -> "Transferir ${Fmt.sol(o.lamports)} SOL de ${who(o.from, me)} para ${who(o.to, me)}"
        is Op.CreateAccountWithSeed -> "Criar conta de stake ${short(o.address)} (semente \"${o.seed}\") com ${Fmt.sol(o.lamports)} SOL"
        is Op.StakeInitialize -> "Definir ${who(o.staker, me)} como autoridade de stake e de saque, sem lockup"
        is Op.StakeDelegate -> "Delegar a conta ${short(o.stake)} ao validador ${short(o.vote)}"
        is Op.StakeDeactivate -> "Desativar a conta de stake ${short(o.stake)} (o SOL fica sacável após o resfriamento)"
        is Op.StakeWithdraw -> "Sacar ${Fmt.sol(o.lamports)} SOL da conta de stake ${short(o.stake)} para ${who(o.to, me)}"
        is Op.StakeSplit -> "Dividir: mover ${Fmt.sol(o.lamports)} SOL de ${short(o.stake)} para a nova conta ${short(o.dest)}"
        is Op.StakeMerge -> "Unir a conta ${short(o.source)} na conta ${short(o.dest)} (a de origem é fechada)"
        is Op.TokenTransfer -> "Transferir ${Fmt.units(o.amount, o.decimals)} ${token(o.mint)} (${program(o.program)}) para a conta ${short(o.dest)}"
        is Op.TokenClose -> "Fechar a conta de token vazia ${short(o.account)}; o depósito volta para ${who(o.dest, me)}"
        is Op.CreateAta -> "Criar (se não existir) a conta de token de ${who(o.owner, me)} para ${token(o.mint)} — depósito reembolsável pago por você"
        is Op.ComputeUnitLimit -> "Limite de computação: ${o.units} unidades"
        is Op.ComputeUnitPrice -> "Taxa de prioridade: ${o.microLamports} micro-lamports por unidade"
    }

    private fun who(k: SolanaPublicKey, me: SolanaPublicKey) = if (k == me) "você" else short(k)
    fun program(p: SolanaPublicKey) = if (p == ProgramIds.TOKEN_2022) "Token-2022" else "SPL Token"

    fun finding(f: Finding): String = when (f) {
        Finding.MALFORMED -> "A transação está corrompida."
        Finding.UNSUPPORTED_FORMAT -> "Formato de transação que a Lock Wallet não analisa (só o formato clássico é aceito)."
        Finding.EXTRA_SIGNER -> "Exige outro assinador além desta carteira."
        Finding.FEE_PAYER_MISMATCH -> "Quem paga a taxa não é esta carteira."
        Finding.UNKNOWN_PROGRAM -> "Chama um programa fora da lista que a Lock Wallet sabe analisar."
        Finding.UNKNOWN_INSTRUCTION -> "Contém uma instrução que a Lock Wallet não permite."
        Finding.BAD_ACCOUNTS -> "As contas envolvidas não batem com o esperado."
        Finding.UNEXPECTED_WRITABLE -> "Pede permissão de alteração numa conta sem necessidade."
        Finding.AUTHORITY_NOT_WALLET -> "Uma autoridade não é esta carteira."
        Finding.LOCKUP_OR_CUSTODIAN -> "Lockup ou custodiante na conta de stake (não suportado)."
        Finding.BAD_DERIVED_ADDRESS -> "Endereço derivado incorreto."
        Finding.STAKE_ACCOUNT_NOT_CREATED_HERE -> "Conta de stake não criada nesta transação."
        Finding.DUPLICATE_COMPUTE_BUDGET -> "Configuração de taxa repetida."
        Finding.COMPUTE_LIMIT_TOO_HIGH -> "Pede processamento acima do máximo da rede."
        Finding.CLOSE_TO_THIRD_PARTY -> "O depósito iria para outra pessoa."
        Finding.SELF_TRANSFER -> "Origem e destino iguais."
        Finding.ZERO_AMOUNT -> "Valor zero."
        Finding.TOKEN_UNVERIFIED_CONTEXT -> "Dados do token não conferem com a rede (mint, dono ou programa)."
        Finding.TOKEN_POLICY_BLOCKED -> "Token com recursos arriscados, recusado pela política de tokens."
        Finding.TOKEN_DECIMALS_MISMATCH -> "Casas decimais divergem da mint."
        Finding.UNKNOWN_VALIDATOR -> "Este validador não aparece na lista da rede."
        Finding.EMPTY -> "Transação vazia."
        Finding.PRIORITY_FEE -> "Inclui taxa de prioridade (para entrar mais rápido na fila)."
        Finding.WITHDRAW_TO_OTHER -> "O saque vai para outro endereço, não para você."
        Finding.DELINQUENT_VALIDATOR -> "Validador fora do ar: enquanto estiver assim, não gera recompensas."
        Finding.TOKEN_DELEGATE_SET -> "Sua conta de token tem um delegado que pode mover parte do saldo."
        Finding.MINT_FREEZE_AUTHORITY -> "O emissor pode congelar contas deste token."
        Finding.NEW_RECIPIENT_ACCOUNT -> "Conta de destino nova."
        Finding.FEE_TOO_HIGH -> "A taxa de prioridade passa do limite de segurança (0,005 SOL). Isso pode ser uma tentativa de esvaziar a carteira."
    }

    fun blocker(b: PrepBlocker): String = when (b) {
        PrepBlocker.BUSY -> "Há uma transação desta carteira ainda sem resultado final. Aguarde a confirmação ou a expiração comprovada — nunca enviamos em dobro."
        PrepBlocker.TOO_LARGE -> "Transação grande demais."
        PrepBlocker.ANALYZER -> "O analisador de segurança recusou esta transação."
        PrepBlocker.FEE_UNAVAILABLE -> "Não foi possível calcular a taxa. Bloqueado por segurança."
        PrepBlocker.SIMULATION_FAILED -> "A simulação falhou: a rede recusaria esta transação."
        PrepBlocker.BLOCKHASH_INVALID -> "A validade desta transação já acabou (ou a rede não respondeu). Peça ao app para refazer."
        PrepBlocker.SIMULATION_UNAVAILABLE -> "Não foi possível testar a transação agora (sem conexão). Sem teste, nada é assinado."
        PrepBlocker.SIMULATION_SINGLE_SOURCE -> "Esta transação tem partes que a carteira não decodifica e só um servidor conseguiu testá-la. São necessários dois. Bloqueado."
        PrepBlocker.SIMULATION_DIVERGED -> "Os dois servidores deram resultados diferentes para esta transação. Bloqueado por segurança."
    }

    fun state(s: TransactionState): String = when (s) {
        TransactionState.SIGNED -> "• assinada"
        TransactionState.SUBMITTED -> "• enviada"
        TransactionState.PROCESSED -> "• processada"
        TransactionState.CONFIRMED -> "✓ confirmada"
        TransactionState.FINALIZED -> "✓ finalizada"
        TransactionState.FAILED -> "✕ falhou"
        TransactionState.EXPIRED -> "✕ expirou (não incluída)"
        TransactionState.UNKNOWN -> "? aguardando confirmação"
        TransactionState.CANCELLED -> "– cancelada"
        else -> "• ${s.name.lowercase()}"
    }

    fun commitment(c: String) = when (c) { "finalized" -> "✓ finalizada"; "confirmed" -> "✓ confirmada"; else -> "• processada" }
}
