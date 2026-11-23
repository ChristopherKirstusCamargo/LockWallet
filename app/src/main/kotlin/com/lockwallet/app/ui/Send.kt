package com.lockwallet.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockwallet.app.AppController
import com.lockwallet.core.solana.Amounts
import com.lockwallet.core.solana.tx.TransactionState
import com.lockwallet.data.rpc.TxKind

// ======================================================================= send SOL

@Composable
fun SendSol(c: AppController, p: Platform) {
    var to by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (c.scanOnOpen) { c.scanOnOpen = false; c.requestCamera { scanning = true } } }
    val w = c.chain.wallet()
    val v = valuation(c)
    Screen("Enviar", onBack = { c.home() }, bottom = {
        ErrorText(c.error)
        if (c.busy) Busy("Preparando e testando…") else Primary("Revisar", enabled = to.isNotBlank() && amount.isNotBlank(), tag = "review") { c.chain.reviewSol(to, amount, max = false) }
    }) {
        w?.let {
            FromCard(it.name, it.id, "Solana", if (c.hideBalance) "•••• disponíveis" else v.lamports?.let { b -> Fmt.sol(b) + " SOL disponíveis" }) {
                SolLogo(40.dp, Modifier.shared("logo-sol"))
            }
        }
        AddressField(to, { to = it }, "send-to", c, p) { scanning = true }
        if (scanning) QrScanner(onAddress = { to = it; scanning = false }, onMessage = { c.error = it; scanning = false }, onClose = { scanning = false })
        AmountEntry(amount, { amount = it }, "SOL", "send-amount",
            fiatHint = com.lockwallet.app.UserAmount.solOrNull(amount)?.let { lam -> v.price?.let { pr -> formatFiat(Amounts.toDecimal(lam, 9).multiply(pr), v.fiat!!) } }) {
            if (to.isNotBlank()) c.chain.reviewSol(to, "", max = true) else c.error = S.MAX_NEEDS_DEST
        }
        PrivacyDisclosureCard()
        Row(verticalAlignment = Alignment.CenterVertically) { Body("Taxa de rede"); HelpIcon(Help.NETWORK_FEE, 16.dp) }
    }
}

/**
 * Explicitly labels the current public route. This is informational only:
 * it must never imply that a private transfer is available before the
 * shielded-key, proof, analyzer and custody paths are fully integrated.
 */
@Composable
fun PrivacyDisclosureCard(review: Boolean = false) {
    Card(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
                Icon(Ic.GLOBE, size = 18.dp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Visibilidade na rede", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Esta operação é pública", color = C.dim, fontSize = 13.sp)
            }
            Chip("PÚBLICO")
            HelpIcon(Help.PRIVACY_RING, 16.dp)
        }
        Body(
            if (review) "Nesta transação, os endereços, o ativo e a quantidade ficam visíveis na rede Solana."
            else "No modo atual, os endereços, o ativo e a quantidade serão visíveis na rede Solana."
        )
        Body("Para ocultar ativo e valor, use o Saldo confidencial (Default Ring), acessível na tela inicial.")
    }
}

@Composable
fun FromCard(name: String, id: String, asset: String, balance: String?, logo: @Composable () -> Unit) =
    Row(Modifier.fillMaxWidth().glass().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        logo(); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(asset, color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(balance ?: "Carregando saldo…", color = C.dim, fontSize = 13.sp)
        }
        Avatar(name, 28.dp, Modifier.shared("avatar-$id"))
        Spacer(Modifier.width(6.dp)); Text(name.take(14), color = C.dim, fontSize = 13.sp)
    }

@Composable
fun AddressField(value: String, onChange: (String) -> Unit, tag: String, c: AppController, p: Platform, label: String = "Endereço de destino", onScan: () -> Unit) {
    Field(value, { onChange(it.filterNot(Char::isWhitespace).take(64)) }, label, tag = tag, mono = value.isNotEmpty(), keyboard = KeyboardType.Ascii,
        trailing = {
            Row(Modifier.padding(end = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Colar", icon = Ic.PASTE) { p.paste()?.let { onChange(it.trim().take(64)) } }
                Box(Modifier.size(34.dp).glass(CircleShape).pressable { c.requestCamera(onScan) }.testTag("scan-qr"), contentAlignment = Alignment.Center) { Icon(Ic.SCAN, size = 18.dp) }
            }
        })
    if (value.length >= 32) Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Body("Confira o endereço completo:")
            Mono(groupedAddress(value), C.text, 12)
        }
        HelpIcon(Help.POISONING, 16.dp)
    }
}

/** Big centred amount entry with unit, fiat equivalent and a "Máx" chip. */
@Composable
fun AmountEntry(value: String, onChange: (String) -> Unit, unit: String, tag: String, fiatHint: String? = null, onMax: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().glass().padding(vertical = 22.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            BasicTextField(value, { onChange(it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(24)) },
                Modifier.weight(1f, fill = false).testTag(tag), singleLine = true, cursorBrush = SolidColor(C.text),
                textStyle = TextStyle(color = C.text, fontSize = 44.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.End),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                decorationBox = { inner -> Box(contentAlignment = Alignment.CenterEnd) { if (value.isEmpty()) Text("0", color = C.faint, fontSize = 44.sp, fontWeight = FontWeight.Black); inner() } })
            Spacer(Modifier.width(8.dp))
            Text(unit, color = C.dim, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        }
        Text(fiatHint ?: " ", color = C.dim, fontSize = 14.sp)
        if (onMax != null) Row(verticalAlignment = Alignment.CenterVertically) { Chip("Máx", icon = Ic.BOLT, onClick = onMax); HelpIcon(Help.SEND_MAX, 16.dp) }
    }
}

// ======================================================================= review (Transaction Security Analyzer)

@Composable
fun Review(c: AppController) {
    val pt = c.chain.prepared
    val info = c.chain.review
    if (pt == null || info == null) { Screen("Revisão", onBack = { c.home() }) { Body("Nada para revisar.") }; return }
    var ack by remember(pt) { mutableStateOf(info.ackText == null) }
    var tech by remember { mutableStateOf(false) }
    val me = pt.intent.wallet
    val v = valuation(c)
    val blocks = pt.blockers.map { TxText.blocker(it) } + pt.analysis.blockers.map { TxText.finding(it) }
    val warns = pt.analysis.warnings.map { TxText.finding(it) } + info.warnings
    Screen(info.title, onBack = { c.chain.cancelReview() }, help = Help.ANALYZER, bottom = {
        c.chain.notice?.let { ErrorText(it); Secondary("Revisar de novo") { c.chain.reviewAgain() } }
        ErrorText(c.error)
        if (c.busy) Busy() else {
            if (pt.signable) Primary("Assinar", enabled = ack, icon = Ic.LOCK, tag = "sign") { c.chain.authorize() }
            Secondary("Cancelar") { c.chain.cancelReview() }
        }
    }) {
        // Hero: what moves.
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val icon = when (pt.intent.kind) { TxKind.SEND_SOL, TxKind.SEND_TOKEN -> Ic.SEND; TxKind.STAKE_CREATE -> Ic.STAKE; else -> Ic.BOLT }
            if (pt.intent.kind == TxKind.SEND_SOL) SolLogo(56.dp, Modifier.shared("logo-sol")) else
                Box(Modifier.size(56.dp).clip(CircleShape).background(C.text), contentAlignment = Alignment.Center) { Icon(icon, C.bg, 26.dp) }
            val amt = pt.intent.amountUnits?.let { u -> pt.intent.decimals?.let { d -> Fmt.units(u, d) } }
            val mint = pt.intent.mint
            val nativeSol = mint == null
            val symbol = mint?.let { c.chain.tokenSymbols[it] }
            val safeSymbol = symbol?.takeIf {
                it.length in 1..12 && it.all { ch -> ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "._-" } &&
                    it.uppercase() !in setOf("SOL", "USDC", "USDT")
            }
            val unit = if (nativeSol) "SOL" else safeSymbol ?: "Token"
            if (amt != null) Text("$amt $unit".trim(), color = C.text, fontSize = 36.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            if (nativeSol && amt != null && v.price != null) Text(formatFiat(Amounts.toDecimal(pt.intent.amountUnits!!, 9).multiply(v.price), v.fiat!!), color = C.dim, fontSize = 15.sp)
            if (mint != null) Mono("Mint: ${groupedAddress(mint)}", C.dim, 11)
            Text(pt.intent.summary, color = C.dim, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        pt.intent.counterparty?.let { cp ->
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(if (pt.intent.kind == TxKind.STAKE_CREATE) "Validador" else "Para", modifier = Modifier.weight(1f))
                    HelpIcon(if (pt.intent.kind == TxKind.STAKE_CREATE) Help.VALIDATOR else Help.FIRST_SEND, 16.dp)
                }
                Mono(groupedAddress(cp), C.text, 16)
            }
        }
        Group {
            info.lines.forEach { (k, value) -> DetailLine(k, value) }
            DetailLine("Taxa de rede", (pt.feeLamports?.let { Fmt.sol(it) + " SOL" } ?: "desconhecida") +
                (pt.feeLamports?.let { f -> v.price?.let { pr -> " · " + formatFiat(Amounts.toDecimal(f, 9).multiply(pr), v.fiat!!) } } ?: ""), Help.NETWORK_FEE)
            DetailLine("Rede", "Solana", Help.GENESIS)
            DetailLine("Carteira", groupedAddress(me.toBase58()))
        }
        if (pt.intent.kind == TxKind.SEND_SOL || pt.intent.kind == TxKind.SEND_TOKEN) {
            PrivacyDisclosureCard(review = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Label("O que será assinado"); HelpIcon(Help.ANALYZER, 14.dp) }
        Card {
            pt.analysis.ops.forEach { o ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Ic.CHECK, C.dim, 16.dp, Modifier.padding(top = 2.dp)); Spacer(Modifier.width(10.dp))
                    Text(TxText.op(o, me, c.chain.tokenLabel), color = C.text, fontSize = 15.sp, lineHeight = 21.sp)
                }
            }
        }
        val sim = pt.simulation
        Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (sim != null && sim.err == null) Ic.CHECK else Ic.X_CIRCLE, size = 20.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(when { sim == null -> "Teste não executado"; sim.err != null -> "O teste falhou"; else -> "Teste na rede: ok" }, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                pt.simulatedPayerLamports?.let { Text("Saldo depois: ${Fmt.sol(it)} SOL", color = C.dim, fontSize = 13.sp) }
                sim?.err?.let { Text(it.take(140), color = C.dim, fontSize = 12.sp) }
            }
            HelpIcon(Help.SIMULATION, 16.dp)
        }
        blocks.forEach { Notice(it, Ic.X_CIRCLE, strong = true) }
        warns.forEach { Notice(it, Ic.WARN) }
        Row(Modifier.clip(CircleShape).pressable(haptic = Haptic.TICK) { tech = !tech }.padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (tech) "Ocultar detalhes técnicos" else "Detalhes técnicos", color = C.dim, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            val r by animateFloatAsState(if (tech) 180f else 0f, Motion.bouncy(), label = "chev")
            Spacer(Modifier.width(4.dp)); Icon(Ic.CHEVRON_DOWN, C.dim, 16.dp, Modifier.androidxRotate(r))
        }
        AnimatedVisibility(tech, enter = expandVertically(Motion.snappy()) + fadeIn(), exit = shrinkVertically(Motion.snappy())) {
            Card {
                Mono("Hash: " + pt.messageHash.joinToString("") { "%02x".format(it) }.take(32) + "…", C.dim, 12)
                Mono("Validade até o bloco ${pt.lastValidBlockHeight}", C.dim, 12)
                Mono("Programas: " + (pt.analysis.message?.instructions?.map { TxText.short(pt.analysis.message!!.accountKeys[it.programIndex]) }?.distinct()?.joinToString() ?: "—"), C.dim, 12)
                sim?.logs?.takeLast(10)?.forEach { Mono(it.take(160), C.dim, 11) }
            }
        }
        if (info.ackText != null && pt.signable) AckRow(info.ackText, ack, "ack") { ack = it }
    }
}

private fun Modifier.androidxRotate(deg: Float) = this.rotate(deg)

@Composable
fun DetailLine(k: String, v: String, help: HelpTopic? = null) =
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(k, color = C.dim, fontSize = 15.sp); if (help != null) HelpIcon(help, 14.dp)
        Spacer(Modifier.weight(1f))
        Text(v, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End, modifier = Modifier.padding(start = 12.dp))
    }

/** Custom check row (spring tick). */
@Composable
fun AckRow(text: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    val s by animateFloatAsState(if (checked) 1f else 0f, Motion.bouncy(), label = "tick")
    val bg by animateColorAsState(if (checked) C.text else androidx.compose.ui.graphics.Color.Transparent, label = "tickbg")
    Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp)).pressable(haptic = Haptic.TICK) { onChange(!checked) }.padding(14.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(bg).border(1.5.dp, C.text, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Icon(Ic.CHECK, C.bg, 18.dp, Modifier.scale(s))
        }
        Spacer(Modifier.width(12.dp)); Text(text, color = C.text, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

// ======================================================================= status

@Composable
fun TxStatus(c: AppController, p: Platform, sig: String) {
    val r = c.chain.record(sig)
    val h = LocalHaptics.current
    Screen("", onBack = { c.home() }, actions = { HelpIcon(Help.TX_STATUS, 20.dp) }, bottom = {
        if (r != null) {
            r.cluster.explorerTx(r.signature)?.let { url -> Secondary("Ver no explorador", icon = Ic.GLOBE) { p.openUrl(url) } }
            if (r.state == TransactionState.EXPIRED || r.state == TransactionState.FAILED) Primary("Revisar de novo") { c.chain.reviewAgain() }
            Primary("Concluir", tag = "done") { c.home() }
        }
    }) {
        if (r == null) { Body("Registro não encontrado."); return@Screen }
        val ok = r.state == TransactionState.CONFIRMED || r.state == TransactionState.FINALIZED
        val bad = r.state == TransactionState.FAILED || r.state == TransactionState.EXPIRED || r.state == TransactionState.CANCELLED
        LaunchedEffect(ok, bad) { if (ok) h(Haptic.CONFIRM) else if (bad) h(Haptic.REJECT) }
        Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusOrb(ok, bad)
            Text(when { ok -> "Enviado!"; bad -> if (r.state == TransactionState.EXPIRED) "Não processada" else "Não concluída"; else -> "Enviando…" },
                color = C.text, fontSize = 30.sp, fontWeight = FontWeight.Black)
            Text(r.summary, color = C.dim, fontSize = 15.sp, textAlign = TextAlign.Center)
            Text(TxText.state(r.state), color = C.text, fontSize = 15.sp, modifier = Modifier.testTag("tx-state"))
        }
        val steps = listOf("Assinada", "Enviada à rede", "Confirmada", "Finalizada")
        val reached = when (r.state) {
            TransactionState.SIGNED -> 0; TransactionState.SUBMITTED, TransactionState.UNKNOWN, TransactionState.PROCESSED -> 1
            TransactionState.CONFIRMED -> 2; TransactionState.FINALIZED -> 3; else -> -1
        }
        Card {
            steps.forEachIndexed { i, label ->
                val done = i <= reached
                val s by animateFloatAsState(if (done) 1f else 0.7f, Motion.bouncy(), label = "step")
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp)) {
                    Box(Modifier.size(24.dp).scale(s).clip(CircleShape).background(if (done) C.text else C.glass).border(1.dp, C.glassBorder, CircleShape), contentAlignment = Alignment.Center) {
                        if (done) Icon(Ic.CHECK, C.bg, 14.dp)
                    }
                    Text(label, color = if (done) C.text else C.dim, fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
        when (r.state) {
            TransactionState.UNKNOWN -> Notice(S.TX_UNKNOWN, Ic.CLOCK, Help.EXPIRY)
            TransactionState.EXPIRED -> Notice(S.TX_EXPIRED, Ic.INFO, Help.EXPIRY)
            TransactionState.FAILED -> Notice(S.TX_FAILED + (r.detail?.let { " ($it)" } ?: ""), Ic.X_CIRCLE)
            else -> {}
        }
        Label("Assinatura")
        Mono(r.signature, C.dim, 12)
    }
}

/** Pending = orbiting arc; success = check that springs in; failure = X. */
@Composable
private fun StatusOrb(ok: Boolean, bad: Boolean) {
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "spin")
    val pop = remember { Animatable(0f) }
    LaunchedEffect(ok, bad) { pop.snapTo(0f); if (ok || bad) pop.animateTo(1f, Motion.bouncy()) }
    val ring = C.text
    val faint = C.faint
    Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(120.dp)) {
            drawCircle(faint, style = Stroke(3.dp.toPx()))
            if (!ok && !bad) drawArc(ring, spin, 90f, false, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
        if (ok || bad) Box(Modifier.size(96.dp).scale(pop.value).clip(CircleShape).background(C.text), contentAlignment = Alignment.Center) {
            Icon(if (ok) Ic.CHECK else Ic.CLOSE, C.bg, 44.dp)
        }
    }
}


// ======================================================================= Solana Privacy Rings — Default Ring (SOL)

@Composable
private fun PrivacyReviewAddress(label: String, address: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Label(label)
        Mono(groupedAddress(address), C.text, 12, Modifier.fillMaxWidth())
    }
}

@Composable
fun PrivacyScreen(c: AppController, p: Platform) {
    var action by remember { mutableIntStateOf(0) }
    var recipient by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    val address = c.chain.privacyAddress
    val balance = c.chain.privacyBalanceLamports
    val review = c.chain.privacyReview
    val prepared = c.chain.privacyPrepared

    Screen("Saldo confidencial", onBack = { c.home() }, help = Help.PRIVACY_RING, bottom = {
        ErrorText(c.error)
        if (prepared != null && review != null) {
            if (c.busy) Busy("Verificando a operação…") else {
                Primary("Autorizar com PIN", icon = Ic.LOCK, tag = "privacy-authorize") {
                    c.chain.authorizePrivacy()
                }
                Secondary("Cancelar revisão", tag = "privacy-cancel-review") {
                    c.chain.cancelPrivacyReview()
                }
            }
        } else {
            if (c.busy) Busy("Consultando o Default Ring…") else {
                Primary(
                    "Preparar e revisar",
                    enabled = amount.isNotBlank() && (action == 0 || recipient.isNotBlank()),
                    icon = Ic.LOCK,
                    tag = "privacy-prepare",
                ) {
                    c.error = null
                    when (action) {
                        0 -> c.chain.preparePrivacyDeposit(amount)
                        1 -> c.chain.preparePrivacyTransfer(recipient, amount)
                        else -> c.chain.preparePrivacyWithdraw(recipient, amount)
                    }
                }
                Secondary("Atualizar saldo", icon = Ic.BOLT, tag = "privacy-refresh") {
                    c.chain.refreshPrivacySol()
                }
            }
        }
    }) {
        Card(padding = 18.dp) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text("SALDO DISPONÍVEL", color = C.dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                FitText(
                    when {
                        c.hideBalance -> "•••••• SOL"
                        balance != null -> Fmt.sol(balance) + " SOL"
                        else -> "— SOL"
                    },
                    C.text,
                    34.sp,
                    FontWeight.Black,
                    Modifier.fillMaxWidth(),
                )
                Text(
                    if (c.hideBalance) "Saldo oculto"
                    else if (c.chain.privacyUtxoCount != null) "Saldo sincronizado"
                    else "Aguardando sincronização",
                    color = C.dim,
                    fontSize = 12.sp,
                )
            }
        }
        if (address != null) {
            Card(padding = 14.dp) {
                Label("Endereço para receber")
                Mono(groupedAddress(address), C.text, 12, Modifier.fillMaxWidth())
            }
            Secondary("Copiar endereço confidencial", icon = Ic.COPY, tag = "privacy-copy-address") {
                p.copyAddress(address)
            }
        }

        if (prepared != null && review != null) {
            Group("Revisão da operação") {
                val title = when (review.operation) {
                    "deposit_sol" -> "Depósito público"
                    "transfer_sol" -> "Transferência confidencial"
                    else -> "Retirada pública"
                }
                Text(title, color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                DetailLine("Valor", Fmt.sol(review.amountLamports) + " SOL")
                DetailLine("Visibilidade", review.visibility)
                if (review.counterparty.isNotBlank()) {
                    PrivacyReviewAddress(
                        if (review.operation == "deposit_sol") "Carteira de origem" else "Endereço público",
                        review.counterparty,
                    )
                }
                // The destination exactly as typed (shielded address for private transfers): the only form the
                // user can compare against what they pasted; the derived public address is not comparable by eye.
                review.typedCounterparty?.let { typed ->
                    PrivacyReviewAddress(
                        if (review.operation == "transfer_sol") "Destino digitado (confira)" else "Destino digitado",
                        typed,
                    )
                }
                DetailLine("Taxa estimada", Fmt.sol(prepared.feeLamports) + " SOL")
                DetailLine("Válida até o bloco", review.lastValidBlockHeight.toString())
                Notice(
                    when (review.operation) {
                        "deposit_sol" -> "O depósito revela publicamente a carteira de origem, o ativo e o valor."
                        "transfer_sol" -> "O anel padrão oculta ativo e valor da transferência, mas não os endereços do remetente e destinatário."
                        else -> "A retirada é pública e revela o ativo, o valor e o endereço de destino."
                    },
                    Ic.INFO,
                    Help.PRIVACY_RING,
                )
            }
        } else {
            Group("Operação") {
                Segmented(listOf("Depositar", "Transferir", "Retirar"), action) {
                    action = it
                    recipient = ""
                }
                when (action) {
                    0 -> Notice(
                        "O depósito é público e revela o valor enviado.",
                        Ic.ARROW_DOWN,
                        Help.PRIVACY_RING,
                    )
                    1 -> {
                        Notice(
                            "O valor fica oculto; os endereços continuam públicos.",
                            Ic.LOCK,
                            Help.PRIVACY_RING,
                        )
                        Field(
                            recipient,
                            { recipient = it.filterNot(Char::isWhitespace).take(180) },
                            "Endereço Shielded do destinatário",
                            tag = "privacy-recipient",
                            mono = true,
                            keyboard = KeyboardType.Ascii,
                        )
                    }
                    else -> {
                        Notice(
                            "A retirada é pública e revela o valor e o destino.",
                            Ic.ARROW_UP,
                            Help.PRIVACY_RING,
                        )
                        Field(
                            recipient,
                            { recipient = it.filterNot(Char::isWhitespace).take(64) },
                            "Endereço Solana de destino",
                            tag = "privacy-withdraw-recipient",
                            mono = true,
                            keyboard = KeyboardType.Ascii,
                        )
                    }
                }
                AmountEntry(amount, { amount = it }, "SOL", "privacy-amount", onMax = null)
            }
        }

    }
}
