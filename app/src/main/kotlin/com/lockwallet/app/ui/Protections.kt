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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockwallet.app.AppController
import com.lockwallet.app.AuthPurpose
import com.lockwallet.app.PolicyChange
import com.lockwallet.app.Screen
import com.lockwallet.app.policy.SendPolicy
import com.lockwallet.custody.local.api.CustodyProviderId
import com.lockwallet.data.rpc.Cluster
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun whenText(ms: Long): String = SimpleDateFormat("dd/MM 'às' HH:mm", Locale.forLanguageTag("pt-BR")).format(Date(ms))

// ======================================================================= Privado tab (Solana Privacy Rings)

/**
 * Confidential balance hub (Solana Privacy Rings, Default Ring, via the user's own Helius endpoint). Explains what is
 * and is not hidden, holds the dedicated endpoint (validated, fail-closed; ADR 0026) and opens the PIN-gated session.
 */
@Composable
fun PrivacyTab(c: AppController) {
    val w = c.chain.wallet()
    val mainnet = c.chain.config.cluster == Cluster.MAINNET
    val eligible = w != null && w.provider == CustodyProviderId.LOCAL_SECURE && mainnet
    val configured = c.prefs.customSpr
    var spr by remember { mutableStateOf("") }
    var currentHost by remember { mutableStateOf(c.prefs.sprEndpoint?.let { runCatching { java.net.URI(it).host }.getOrNull() } ?: "") }
    LaunchedEffect(c.chain.notice, c.error) {
        currentHost = c.prefs.sprEndpoint?.let { runCatching { java.net.URI(it).host }.getOrNull() } ?: ""
        if (c.prefs.sprEndpoint != null) spr = ""
    }

    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Title("Privado", Modifier.weight(1f))
        HelpIcon(Help.PRIVACY_RING, 20.dp)
    }

    Column(Modifier.fillMaxWidth().glass().padding(22.dp).testTag("privacy-hero"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(C.text), contentAlignment = Alignment.Center) { Icon(Ic.RINGS, C.bg, 30.dp) }
        Text("Saldo confidencial", color = C.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text("Valores e ativos das transferências ficam cifrados na rede. Endereços, depósitos e retiradas continuam públicos.",
            color = C.dim, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
        Chip(
            when {
                w == null -> "Sem carteira"
                !mainnet -> "Somente Mainnet"
                !eligible -> "Indisponível para esta carteira"
                !configured -> "Configuração pendente"
                else -> "Pronto"
            },
            Modifier.testTag("privacy-status"),
            icon = if (eligible && configured) Ic.CHECK else Ic.INFO,
        )
        Spacer(Modifier.size(2.dp))
        if (eligible && configured) {
            PillButton("Abrir saldo confidencial", Modifier.fillMaxWidth(), icon = Ic.LOCK, tag = "privacy-open") {
                c.error = null; c.chain.notice = null; c.chain.enterPrivacy()
            }
        }
    }

    Group("O que fica visível", Help.PRIVACY_RING) {
        VisibilityRow(Ic.ARROW_DOWN, "Depósito", "Público: origem e valor aparecem na rede")
        Divider()
        VisibilityRow(Ic.RINGS, "Transferência confidencial", "Valor e ativo cifrados; endereços visíveis")
        Divider()
        VisibilityRow(Ic.ARROW_UP, "Retirada", "Pública: valor e destino aparecem na rede")
    }

    Group("Conexão Helius") {
        ListRow("Endpoint", Ic.LINK, value = if (currentHost.isNotBlank()) currentHost else "Não configurado", chevron = false, tag = "spr-status")
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(spr, { spr = it.trim().take(512) }, if (configured) "Substituir URL Helius (com API key)" else "URL Helius com API key",
                keyboard = KeyboardType.Uri, mono = true, secret = true, tag = "spr-field")
            Text("HTTPS da Helius para a Mainnet. A URL é conferida antes de salvar, fica só neste aparelho e a API key não é exibida.",
                color = C.dim, fontSize = 13.sp, lineHeight = 18.sp)
            c.chain.notice?.let { Notice(it, Ic.INFO) }
            ErrorText(c.error)
            if (c.busy) Busy("Conferindo conexão…") else {
                Primary("Testar e salvar", enabled = spr.isNotBlank(), tag = "spr-save") { c.chain.saveSprEndpoint(spr) }
                if (currentHost.isNotBlank()) Secondary("Remover endpoint", tag = "spr-remove") { c.chain.resetSprEndpoint() }
            }
        }
    }
}

@Composable
private fun VisibilityRow(ic: Ic, title: String, sub: String) =
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).glass(CircleShape), contentAlignment = Alignment.Center) { Icon(ic, size = 20.dp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = C.dim, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }

// ======================================================================= Ajustes > Segurança > Proteções de envio

/** Optional send protections, one block. Tightening is immediate; loosening asks for the PIN and waits 24 h. */
@Composable
fun SendProtectionsGroup(c: AppController) {
    val sheets = LocalSheets.current
    @Suppress("UNUSED_VARIABLE") val v = c.chain.policyVersion // re-read on change
    val pol = c.chain.policy
    val pending = pol.pending()
    LaunchedEffect(Unit) { c.chain.settlePolicy() }
    Group("Proteções de envio", Help.ALLOWLIST) {
        ListRow("Contatos", Ic.CONTACTS, value = pol.contacts().size.toString(), help = Help.CONTACTS, tag = "contacts") { c.error = null; c.go(Screen.Contacts) }
        Divider()
        ToggleRow("Somente contatos", pol.allowlistOnly, Ic.LIST_CHECK, help = Help.ALLOWLIST, tag = "allowlist-toggle") { on ->
            if (on) c.chain.enableAllowlist() else c.go(Screen.Authorize(AuthPurpose.LoosenPolicy(PolicyChange.AllowlistOff)))
        }
        Divider()
        val lim = pol.dailyLimitLamports
        ListRow("Limite diário", Ic.GAUGE, value = if (lim > 0L) Fmt.sol(lim) + " SOL" else "Desligado", help = Help.DAILY_LIMIT, tag = "daily-limit") {
            c.error = null; sheets.show { DailyLimitSheet(c) }
        }
        Divider()
        ToggleRow("Confirmar endereço novo", pol.confirmNewAddress, Ic.CHECK, help = Help.CONFIRM_NEW, tag = "confirm-new-toggle") { on ->
            c.chain.setConfirmNewAddress(on)
        }
        Divider()
        ListRow("PIN em cada envio", Ic.KEY, value = "Sempre", chevron = false, help = Help.PIN, tag = "pin-every-send")
        if (pending != null) {
            Divider()
            Column(Modifier.fillMaxWidth().padding(16.dp).testTag("policy-pending"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val what = when (pending.kind) {
                    SendPolicy.Kind.ALLOWLIST_OFF -> "Desligar “Somente contatos”"
                    SendPolicy.Kind.LIMIT_CHANGE -> if (pending.value > 0L) "Limite diário para ${Fmt.sol(pending.value)} SOL" else "Desligar o limite diário"
                }
                Notice("$what: agendado para ${whenText(pending.effectiveAtMs)} (horário da rede).", Ic.CLOCK, Help.DAILY_LIMIT)
                Secondary("Cancelar alteração agendada", tag = "policy-cancel") { c.chain.cancelPolicyChange() }
            }
        }
    }
}

@Composable
private fun DailyLimitSheet(c: AppController) {
    val sheets = LocalSheets.current
    val cur = c.chain.policy.dailyLimitLamports
    var text by remember { mutableStateOf("") }
    Text("Limite diário", color = C.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    Body("Máximo de SOL que pode sair em 24 horas corridas. Diminuir vale na hora. Aumentar ou desligar pede o PIN e vale 24 h depois.")
    Card(padding = 16.dp) {
        DetailLine("Atual", if (cur > 0L) Fmt.sol(cur) + " SOL" else "Desligado")
    }
    AmountEntry(text, { text = it }, "SOL", "limit-amount", onMax = null)
    ErrorText(c.error)
    if (c.busy) Busy() else {
        Primary("Salvar limite", enabled = text.isNotBlank(), tag = "limit-save") { c.chain.requestDailyLimit(text); sheets.dismiss() }
        if (cur > 0L) Secondary("Desligar limite", tag = "limit-off") { c.chain.requestDailyLimit(""); sheets.dismiss() }
    }
}

// ======================================================================= contacts

@Composable
fun ContactsScreen(c: AppController, p: Platform) {
    @Suppress("UNUSED_VARIABLE") val v = c.chain.policyVersion
    val sheets = LocalSheets.current
    val pol = c.chain.policy
    val list = pol.contacts().sortedBy { it.name.lowercase() }
    Screen("Contatos", onBack = { c.error = null; c.home() }, help = Help.CONTACTS, bottom = {
        ErrorText(c.error)
        Primary("Adicionar contato", icon = Ic.PLUS, tag = "contact-add") { c.error = null; sheets.show { ContactSheet(c, p, null) } }
    }) {
        if (pol.allowlistOnly) Notice("“Somente contatos” está ativo: um contato novo pode receber 24 h depois de adicionado.", Ic.LIST_CHECK, Help.ALLOWLIST)
        if (list.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(64.dp).glass(CircleShape), contentAlignment = Alignment.Center) { Icon(Ic.CONTACTS, size = 28.dp) }
                Text("Nenhum contato", color = C.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Body("Salve os endereços que você já conferiu para enviar sem copiar do histórico.", align = TextAlign.Center)
            }
        } else Group {
            list.forEachIndexed { i, ct ->
                if (i > 0) Divider()
                ListRow(ct.name, Ic.PERSON, shortAddress(ct.address), tag = "contact-${ct.name}") { c.error = null; sheets.show { ContactSheet(c, p, ct) } }
            }
        }
    }
}

@Composable
private fun ContactSheet(c: AppController, p: Platform, existing: SendPolicy.Contact?) {
    val sheets = LocalSheets.current
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var address by remember { mutableStateOf(existing?.address ?: "") }
    Text(if (existing == null) "Novo contato" else "Contato", color = C.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    Field(name, { name = it.take(SendPolicy.MAX_NAME) }, "Nome", tag = "contact-name")
    if (existing == null) {
        Field(address, { address = it.filterNot(Char::isWhitespace).take(64) }, "Endereço Solana", tag = "contact-address", mono = true, keyboard = KeyboardType.Ascii)
        Body("Confira o endereço inteiro com a fonte original antes de salvar.")
    } else {
        Card(padding = 14.dp) { Label("Endereço"); Mono(groupedAddress(existing.address), C.text, 13, Modifier.fillMaxWidth()) }
        Secondary("Copiar endereço", icon = Ic.COPY) { p.copyAddress(existing.address) }
    }
    ErrorText(c.error)
    if (c.busy) Busy() else if (existing == null) {
        Primary("Salvar contato", enabled = name.isNotBlank() && address.isNotBlank(), tag = "contact-save") {
            c.chain.addContact(address, name); sheets.dismiss()
        }
    } else {
        Primary("Salvar nome", enabled = name.isNotBlank() && name != existing.name, tag = "contact-rename") { c.chain.renameContact(existing.address, name); sheets.dismiss() }
        Secondary("Excluir contato", icon = Ic.TRASH, tag = "contact-delete") { c.chain.removeContact(existing.address); sheets.dismiss() }
    }
}
