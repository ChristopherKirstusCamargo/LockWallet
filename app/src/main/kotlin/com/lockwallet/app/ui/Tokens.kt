package com.lockwallet.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockwallet.app.AppController
import com.lockwallet.app.Loadable
import com.lockwallet.app.Screen
import com.lockwallet.core.solana.Amounts
import com.lockwallet.data.rpc.TokenHolding

/**
 * Token logos are never fetched (a logo URL comes from the token creator: fetching it would leak the user's IP and
 * holdings to an arbitrary server). Tasteful monochrome initials instead.
 */
@Composable
fun TokenAvatar(label: String, size: Dp = 44.dp, modifier: Modifier = Modifier) =
    Box(modifier.size(size).clip(CircleShape).background(C.glassStrong).glass(CircleShape), contentAlignment = Alignment.Center) {
        Text(label.filter { it.isLetterOrDigit() }.take(2).uppercase().ifEmpty { "?" }, color = C.text, fontSize = (size.value * 0.34f).sp, fontWeight = FontWeight.Bold)
    }

private fun amountOf(h: TokenHolding) = h.mint?.let { Fmt.units(h.state.amount, it.decimals) } ?: h.state.amount.toString()

@Composable
fun TokenRows(c: AppController) {
    val st = c.chain.tokens
    val all = when (st) { is Loadable.Ready -> st.value; is Loadable.Failed -> st.last ?: emptyList(); else -> emptyList() }
    var showHidden by remember { mutableStateOf(false) }
    val visible = all.filter { showHidden || it.state.mint.toBase58() !in c.chain.hidden }
    if (st is Loadable.Loading && all.isEmpty()) { Box(Modifier.padding(horizontal = 12.dp)) { SkeletonRow() } }
    visible.forEach { h ->
        val name = c.chain.tokenName(h)
        val sub = when {
            !h.verdict.allowed -> "bloqueado pela política"
            h.state.frozen -> "congelado"
            h.symbol != null || h.name != null -> "nome não verificado"
            else -> "sem nome"
        }
        AssetRow(logo = { TokenAvatar(name, modifier = Modifier.shared("logo-${h.account.toBase58()}")) }, name = name,
            sub = if (c.hideBalance) "••••" else "${amountOf(h)} · $sub", right = "", change = null, tag = "token-${h.account.toBase58().take(8)}") {
            c.error = null; c.go(Screen.Token(h.account.toBase58()))
        }
    }
    val nHidden = all.count { it.state.mint.toBase58() in c.chain.hidden }
    if (nHidden > 0) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        TextAction(if (showHidden) "Esconder ocultos" else "Mostrar ocultos ($nHidden)", color = C.dim) { showHidden = !showHidden }
        HelpIcon(Help.HIDDEN_TOKENS, 16.dp)
    }
    if (st is Loadable.Failed) Text("Sem conexão", color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
}

@Composable
fun TokenScreen(c: AppController, p: Platform, account: String) {
    val h = c.chain.holding(account)
    Screen("", onBack = { c.home() }, bottom = {
        if (h != null) {
            ErrorText(c.error)
            if (c.busy) Busy() else {
                if (h.sendable) Primary("Enviar", icon = Ic.SEND, tag = "token-send") { c.error = null; c.go(Screen.Send(account)) }
                if (h.closable && (h.state.amount == 0L || h.state.isNative)) Secondary("Fechar conta e recuperar depósito") { c.chain.reviewClose(account) }
            }
        }
    }) {
        if (h == null) { Body(S.TOKEN_GONE); return@Screen }
        val name = c.chain.tokenName(h)
        val mint = h.state.mint.toBase58()
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TokenAvatar(name, 72.dp, Modifier.shared("logo-$account"))
            AnimatedNumber(if (c.hideBalance) "••••" else amountOf(h), 40)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = C.dim, fontSize = 15.sp, textAlign = TextAlign.Center)
                if (h.name != null || h.symbol != null) HelpIcon(Help.UNVERIFIED_NAME, 16.dp)
            }
        }
        Group("Segurança do token", Help.TOKEN_POLICY) {
            if (h.verdict.allowed) ListRow("Aprovado pela política", Ic.CHECK, "Sem recursos arriscados", chevron = false)
            else h.verdict.reasons.forEach { ListRow(it, Ic.X_CIRCLE, chevron = false) }
            if (h.mint?.freezeAuthority != null) { Divider(); ListRow("Emissor pode congelar", Ic.WARN, help = Help.FREEZE, chevron = false) }
            if (h.mint?.mintAuthority != null) { Divider(); ListRow("Emissor pode criar mais", Ic.WARN, help = Help.MINT_AUTH, chevron = false) }
            if (h.state.delegate != null) {
                Divider()
                ListRow(
                    "Há um delegado",
                    Ic.WARN,
                    if (c.hideBalance) "Pode mover até ••••" else "Pode mover até ${h.mint?.let { Fmt.units(h.state.delegatedAmount, it.decimals) }}",
                    help = Help.DELEGATE,
                    chevron = false,
                )
            }
            if (h.state.frozen) { Divider(); ListRow("Sua conta está congelada", Ic.LOCK, help = Help.FREEZE, chevron = false) }
            if (h.state.isNative) { Divider(); ListRow("SOL embrulhado", Ic.COIN, "Fechar a conta devolve o SOL", chevron = false) }
        }
        Group("Detalhes") {
            ListRow("Endereço do token", Ic.LINK, shortAddress(mint), chevron = false)
            Divider()
            ListRow("Padrão", Ic.CHIP, TxText.program(h.program) + " · ${h.mint?.decimals ?: "?"} casas decimais", chevron = false)
            Divider()
            val hid = mint in c.chain.hidden
            ToggleRow("Ocultar da lista", hid, Ic.EYE_OFF, help = Help.HIDDEN_TOKENS) { c.chain.setHidden(mint, it) }
            c.chain.config.cluster.explorerAddress(mint)?.let { url -> Divider(); ListRow("Ver no explorador", Ic.GLOBE, help = Help.EXPLORER) { p.openUrl(url) } }
        }
        if (h.closable && (h.state.amount == 0L || h.state.isNative)) Row(verticalAlignment = Alignment.CenterVertically) { Body("Conta vazia"); HelpIcon(Help.CLOSE_TOKEN, 16.dp) }
    }
}

@Composable
fun SendToken(c: AppController, p: Platform, account: String) {
    val h = c.chain.holding(account)
    var to by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    val name = h?.let { c.chain.tokenName(it) } ?: "token"
    Screen("Enviar $name", onBack = { c.go(Screen.Token(account)) }, bottom = {
        ErrorText(c.error)
        if (c.busy) Busy("Preparando e testando…") else Primary("Revisar", enabled = to.isNotBlank() && amount.isNotBlank(), tag = "review") { c.chain.reviewToken(account, to, amount) }
    }) {
        if (h == null || h.mint == null) { Body(S.TOKEN_GONE); return@Screen }
        c.chain.wallet()?.let { w ->
            FromCard(w.name, w.id, name, if (c.hideBalance) "•••• disponíveis" else "${amountOf(h)} disponíveis") {
                TokenAvatar(name, 40.dp, Modifier.shared("logo-$account"))
            }
        }
        AddressField(to, { to = it }, "token-to", c, p, "Carteira de destino") { scanning = true }
        if (scanning) QrScanner(onAddress = { to = it; scanning = false }, onMessage = { c.error = it; scanning = false }, onClose = { scanning = false })
        AmountEntry(amount, { amount = it }, h.symbol?.take(8) ?: "", "token-amount") { amount = amountOf(h) }
        PrivacyDisclosureCard()
        Row(verticalAlignment = Alignment.CenterVertically) { Body("Conta de token", modifier = Modifier.weight(1f)); HelpIcon(Help.ATA, 16.dp) }
    }
}
