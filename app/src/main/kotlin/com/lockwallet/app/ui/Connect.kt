package com.lockwallet.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockwallet.app.AppController
import com.lockwallet.app.FlowMode
import com.lockwallet.app.connect.AuthorizeRequestModel
import com.lockwallet.app.connect.ConnectModel
import com.lockwallet.app.connect.DappIdentity
import com.lockwallet.app.connect.DappTxReview
import com.lockwallet.app.connect.SignKind
import com.lockwallet.app.connect.SignRequestModel
import com.lockwallet.core.solana.Amounts
import com.lockwallet.core.solana.dapp.DappBlock
import com.lockwallet.core.solana.dapp.DappWarning
import com.lockwallet.core.solana.dapp.IxKind
import com.lockwallet.core.solana.dapp.MessagePolicy
import com.lockwallet.custody.local.storage.WalletRole

@Composable
fun ConnectRequestScreen(c: AppController) {
    when (val r = c.connect.current) {
        is AuthorizeRequestModel -> AuthorizeDapp(c, r)
        is SignRequestModel -> SignDapp(c, r)
        null -> {}
    }
}

@Composable
private fun Identity(d: DappIdentity) = Column(Modifier.fillMaxWidth().glass().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    TokenAvatar(d.name.ifBlank { "?" }, 60.dp)
    Text(d.name.take(64).ifBlank { "App sem nome" }, color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    Mono(d.uri?.take(120) ?: "sem endereço informado", C.text, 14)
    d.callingPackage?.let { Text("App Android: $it", color = C.dim, fontSize = 12.sp) }
    Row(verticalAlignment = Alignment.CenterVertically) { Text("Identidade não verificada", color = C.dim, fontSize = 13.sp); HelpIcon(Help.UNVERIFIED_DAPP, 14.dp) }
}

@Composable
private fun AuthorizeDapp(c: AppController, r: AuthorizeRequestModel) {
    val m = c.connect
    val wallets = m.connectWallets()
    val siwsDomainOk = r.siws == null || (r.siws.domain?.trim()?.trimEnd('.')?.lowercase() != null &&
        r.siws.domain.trim().trimEnd('.').lowercase() == r.dapp.domain?.trim()?.trimEnd('.')?.lowercase())
    val ok = wallets.isNotEmpty() && r.cluster == c.chain.config.cluster && m.selectedWallet != null && siwsDomainOk
    Screen("Conectar app", onBack = { m.decline() }, help = Help.DAPPS, bottom = {
        ErrorText(m.reviewError ?: c.error)
        if (c.busy) Busy() else {
            Primary(if (r.siws != null) "Conectar e entrar" else "Conectar", enabled = ok, tag = "dapp-approve") { m.approveAuthorize() }
            Secondary("Recusar", tag = "dapp-decline") { m.decline() }
        }
    }) {
        Identity(r.dapp)
        if (r.cluster != c.chain.config.cluster) Notice(S.S_CLUSTER_MISMATCH, Ic.X_CIRCLE, Help.GENESIS, strong = true)
        if (wallets.isEmpty()) {
            Notice(S.DAPP_NO_CONNECT_WALLET, Ic.WALLET, Help.ROLE_CONNECT, strong = true)
            Secondary("Criar carteira Connect", icon = Ic.PLUS) { m.decline(); c.startFlow(FlowMode.CREATE) }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) { Label("Carteira a compartilhar"); HelpIcon(Help.ROLE_CONNECT, 14.dp) }
            wallets.forEach { w -> ChoiceCard(w.name, shortAddress(w.address.toBase58()), Ic.WALLET, m.selectedWallet == w.id) { m.selectedWallet = w.id } }
        }
        r.siws?.let { s ->
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Entrar com Solana", color = C.text, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f)); HelpIcon(Help.SIWS, 16.dp) }
                DetailLine("Domínio", s.domain ?: "—")
                s.statement?.let { Body("“${it.take(300)}”", C.text) }
                s.expirationTime?.let { Text("Expira: $it", color = C.dim, fontSize = 13.sp) }
                val idDomain = r.dapp.domain
                if (s.domain == null || idDomain == null || s.domain.trim().trimEnd('.').lowercase() != idDomain) Notice(ConnectModel.S_SIWS_DOMAIN_MISMATCH, Ic.X_CIRCLE, strong = true)
                if (s.chainId != null && com.lockwallet.app.connect.ConnectModel.clusterOf(s.chainId) != c.chain.config.cluster) Notice("A mensagem cita outra rede (${s.chainId.take(40)}).", Ic.WARN)
            }
        }
    }
}

@Composable
private fun SignDapp(c: AppController, r: SignRequestModel) {
    val m = c.connect
    val title = when (r.kind) { SignKind.MESSAGES -> "Assinar mensagem"; SignKind.TRANSACTIONS -> "Assinar transação"; SignKind.SIGN_AND_SEND -> "Assinar e enviar" }
    val rv = m.reviews
    val can = when (r.kind) {
        SignKind.MESSAGES -> r.payloads.all { MessagePolicy.check(it) == null }
        else -> rv != null && rv.isNotEmpty() && rv.all { it.signable } && (!rv.any { it.verdict.needsRawAck } || m.rawAck)
    }
    Screen(title, onBack = { m.decline() }, help = if (r.kind == SignKind.MESSAGES) Help.MESSAGE else Help.ANALYZER, bottom = {
        ErrorText(m.reviewError ?: c.error)
        if (c.busy) Busy() else {
            Primary(if (r.kind == SignKind.SIGN_AND_SEND) "Assinar e enviar" else "Assinar", enabled = can, icon = Ic.LOCK, tag = "dapp-sign") { m.approveSign() }
            Secondary("Recusar", tag = "dapp-decline") { m.decline() }
        }
    }) {
        Identity(r.dapp)
        Text("Carteira ${shortAddress(r.session.address)} · Solana", color = C.dim, fontSize = 13.sp)
        if (r.kind == SignKind.MESSAGES) {
            r.payloads.forEachIndexed { i, p ->
                val text = MessagePolicy.printableText(p, MessagePolicy.MAX_BYTES)
                Card {
                    Label("Mensagem ${i + 1} de ${r.payloads.size}")
                    if (text != null && MessagePolicy.check(p) == null) Mono(text, C.text, 14)
                    else Notice(S.DAPP_MSG_NOT_TEXT, Ic.X_CIRCLE, strong = true)
                }
            }
        } else {
            if (rv == null) { if (m.reviewError == null) Busy("Analisando e testando…") } else {
                rv.forEachIndexed { i, t -> TxReviewCard(c, t, i, rv.size) }
                Text("A simulação é uma estimativa de servidores externos, não uma garantia.", color = C.dim, fontSize = 12.sp)
                if (rv.any { it.verdict.needsRawAck } && rv.all { it.signable }) {
                    Notice(S.DAPP_RAW_WARNING, Ic.WARN, Help.RAW_PROGRAM, strong = true)
                    AckRow(S.DAPP_RAW_ACK, m.rawAck, "raw-ack") { m.rawAck = it }
                }
            }
        }
    }
}

@Composable
private fun TxReviewCard(c: AppController, t: DappTxReview, i: Int, n: Int) {
    val v = t.verdict
    val me = v.message?.feePayer
    val val0 = valuation(c)
    Card {
        Label("Transação ${i + 1} de $n")
        t.solDelta?.let { d ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (d > 0) "+" else if (d < 0) "−" else "") + Fmt.sol(kotlin.math.abs(d)) + " SOL", color = C.text, fontSize = 26.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(6.dp)); Text("no seu saldo", color = C.dim, fontSize = 13.sp); HelpIcon(Help.SIMULATION, 14.dp)
            }
        }
        t.tokenDeltas.forEach { d ->
            val dec = d.decimals
            val delta = d.post?.let { it - d.pre }
            Text("Token ${groupedAddress(d.mint)}: " + when {
                delta == null -> "conta alterada"
                dec == null -> (if (delta > 0) "+" else "") + "$delta unidades"
                else -> (if (delta > 0) "+" else "") + Fmt.units(delta, dec)
            }, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        val fee = maxOf(t.prepared?.feeLamports ?: 0L, v.maxFeeLamports ?: 0L).takeIf { it > 0 }
        DetailLine("Taxa máxima", (fee?.let { Fmt.sol(it) + " SOL" } ?: "—") + (fee?.let { f -> val0.price?.let { pr -> " · " + formatFiat(Amounts.toDecimal(f, 9).multiply(pr), val0.fiat!!) } } ?: ""), Help.FEE_CAP)
        v.instructions.forEach { ix ->
            when (ix.kind) {
                IxKind.DECODED -> ix.ops.forEach { o ->
                    Row(verticalAlignment = Alignment.Top) { Icon(Ic.CHECK, C.dim, 16.dp, Modifier.padding(top = 2.dp)); Spacer(Modifier.width(8.dp))
                        Text(TxText.op(o, me ?: ix.program, c.chain.tokenLabel), color = C.text, fontSize = 14.sp, lineHeight = 20.sp) }
                }
                IxKind.MEMO -> Text("Nota: “${ix.memo?.take(200)}”", color = C.text, fontSize = 14.sp)
                else -> Column(Modifier.fillMaxWidth().glass().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (ix.kind == IxKind.FORBIDDEN) Ic.X_CIRCLE else Ic.HELP, size = 16.dp); Spacer(Modifier.width(8.dp))
                        Text(ix.programName ?: "Programa não decodificado", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        HelpIcon(Help.RAW_PROGRAM, 14.dp)
                    }
                    Mono(ix.program.toBase58(), C.dim, 11)
                    ix.accounts.forEach { a ->
                        val flags = listOfNotNull(if (a.signer) "assina" else null, if (a.writable) "pode alterar" else null).joinToString(", ").ifEmpty { "só leitura" }
                        Text((if (a.key == me) "SUA CARTEIRA" else groupedAddress(a.key.toBase58())) + " · " + flags, color = if (a.key == me) C.text else C.dim, fontSize = 12.sp)
                    }
                    Mono("dados: ${ix.dataLength} bytes · ${ix.dataPreviewHex}${if (ix.dataLength > 24) "…" else ""}", C.dim, 11)
                    ix.reasons.forEach { f -> Text(TxText.finding(f), color = C.text, fontSize = 12.sp) }
                }
            }
        }
        v.blockers.forEach { Notice(dappBlock(it), Ic.X_CIRCLE, if (it == DappBlock.FEE_TOO_HIGH) Help.FEE_CAP else null, strong = true) }
        t.prepProblems.forEach { Notice(TxText.blocker(it), Ic.X_CIRCLE, strong = true) }
        t.postProblems.forEach { Notice(it, Ic.X_CIRCLE, strong = true) }
        t.prepared?.simulation?.err?.let { Notice("O teste falhou: ${it.take(140)}", Ic.X_CIRCLE, Help.SIMULATION, strong = true) }
        v.warnings.forEach { Notice(dappWarning(it), Ic.WARN, if (it == DappWarning.PRIORITY_FEE) Help.PRIORITY_FEE else null) }
    }
}

private fun dappBlock(b: DappBlock) = when (b) {
    DappBlock.MALFORMED -> "Transação corrompida."
    DappBlock.UNSUPPORTED_FORMAT -> "Formato de transação que a Lock Wallet não analisa (só o formato clássico)."
    DappBlock.FEE_PAYER_MISMATCH -> "Quem paga a taxa não é a sua carteira."
    DappBlock.EXTRA_SIGNER -> "Pede outras assinaturas além da sua."
    DappBlock.EMPTY -> "Transação vazia."
    DappBlock.FORBIDDEN_INSTRUCTION -> "Contém uma ação proibida para apps (ex.: dar permissão sobre seus tokens, trocar o dono de uma conta, alterar programas)."
    DappBlock.UNKNOWN_PROGRAM -> "Programa de terceiros não reconhecido. A assinatura é bloqueada por segurança."
    DappBlock.TOO_MANY_INSTRUCTIONS -> "Instruções demais para revisar com segurança."
    DappBlock.FEE_TOO_HIGH -> "A taxa pedida passa do limite de segurança (0,005 SOL). Pode ser uma tentativa de esvaziar a carteira."
    DappBlock.FEE_UNPRICEABLE -> "A configuração de taxa é ambígua: não dá para saber quanto você pagaria."
}

private fun dappWarning(w: DappWarning) = when (w) {
    DappWarning.RAW_PROGRAM -> "Há programa(s) que a Lock Wallet não decodifica. Confira o efeito no saldo acima."
    DappWarning.WALLET_SIGNS_FOR_RAW_PROGRAM -> "Sua assinatura vai para um programa não decodificado: ele pode mover SOL ou tokens da carteira."
    DappWarning.WALLET_WRITABLE_IN_RAW_PROGRAM -> "Um programa não decodificado pode alterar sua carteira."
    DappWarning.PRIORITY_FEE -> "Inclui taxa de prioridade."
    DappWarning.TOKEN_DELEGATE_SET -> "A conta de token já tem um delegado."
    DappWarning.WITHDRAW_TO_OTHER -> "Saque de stake para outro endereço."
    DappWarning.MANY_INSTRUCTIONS -> "Esta transação contém muitas instruções. Revise cada ação com atenção."
}

/** dApps tab: what Lock Connect is, connected apps (sessions) and revocation. */
@Composable
fun ConnectTab(c: AppController) {
    val m = c.connect
    LaunchedEffect(Unit) { m.refreshSessions() }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) { Title("dApps"); HelpIcon(Help.DAPPS, 22.dp) }
    Column(Modifier.fillMaxWidth().glass().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(C.text), contentAlignment = Alignment.Center) { Icon(Ic.APPS, C.bg, 24.dp) }
        Text("Lock Connect", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        val n = c.wallets.count { it.role == WalletRole.CONNECT }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip(if (n == 0) "Nenhuma carteira Connect" else "$n carteira(s) Connect", icon = Ic.WALLET); HelpIcon(Help.ROLE_CONNECT, 14.dp)
        }
        if (n == 0) Secondary("Criar carteira Connect", icon = Ic.PLUS) { c.startFlow(FlowMode.CREATE) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) { Label("Apps conectados"); HelpIcon(Help.SESSION, 14.dp) }
    if (m.sessions.isEmpty()) Column(Modifier.fillMaxWidth().glass().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Ic.LINK, C.dim, 28.dp); Body("Nenhum app conectado.", align = TextAlign.Center)
    }
    else Column(Modifier.fillMaxWidth().glass()) {
        m.sessions.forEachIndexed { i, s ->
            if (i > 0) Divider()
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                TokenAvatar(s.name.ifBlank { "?" }, 40.dp); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name.ifBlank { "App sem nome" }, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text((s.uri ?: "sem endereço").take(40), color = C.dim, fontSize = 12.sp)
                    Text("${shortAddress(s.address)} · expira em ${((s.expiresAtMs - System.currentTimeMillis()) / 3_600_000).coerceAtLeast(0)} h", color = C.dim, fontSize = 12.sp)
                }
                Chip("Revogar", icon = Ic.CLOSE) { m.revoke(s.id) }
            }
        }
    }
    if (m.sessions.size > 1) Secondary("Revogar todos") { m.revokeAll() }
}
