package com.lockwallet.app.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockwallet.app.AppController
import com.lockwallet.app.AuthPurpose
import com.lockwallet.app.FlowMode
import com.lockwallet.app.Screen
import com.lockwallet.app.Tab
import com.lockwallet.custody.local.CheckStatus
import com.lockwallet.custody.local.api.RecoveryPasswords
import com.lockwallet.custody.local.storage.WalletRole
import com.lockwallet.custody.local.storage.WalletState
import com.lockwallet.data.price.Fiat

/** Grouped settings: Preferências · Carteiras · Privacidade e segurança · Suporte. */
@Composable
fun SettingsTab(c: AppController, p: Platform) {
    val sheets = LocalSheets.current
    Row(Modifier.padding(top = 8.dp)) { Title("Ajustes") }
    Group("Preferências") {
        ListRow("Tema", Ic.MOON, value = if (C.dark) "Escuro" else "Claro", help = Help.THEME, tag = "theme") { c.setDarkTheme(!C.dark) }
        Divider()
        ListRow("Moeda", Ic.COIN, value = fiatName(c.fiat), help = Help.CURRENCY, tag = "currency") { sheets.show { CurrencySheet(c) } }
        Divider()
        ToggleRow("Cotação ao vivo", c.priceEnabled, Ic.BOLT, help = Help.PRICE, tag = "price-toggle") { c.setPriceOn(it) }
        Divider()
        ToggleRow("Ocultar saldo", c.hideBalance, Ic.EYE_OFF, help = Help.HIDE_BALANCE, tag = "hide-toggle") { c.setHideBalancePref(it) }
        Divider()
        ToggleRow("Vibração", c.haptics, Ic.VIBRATE, help = Help.HAPTICS, tag = "haptics-toggle") { c.setHapticsPref(it) }
    }
    val active = c.wallets
    if (active.isNotEmpty()) Group("Carteiras", Help.ACCOUNT) {
        active.forEachIndexed { i, w ->
            if (i > 0) Divider()
            ListRow(w.name, if (w.role == WalletRole.VAULT) Ic.LOCK else Ic.APPS,
                shortAddress(w.address.toBase58()) + " · " + (if (w.role == WalletRole.VAULT) "Vault" else "Connect") + if (w.state == WalletState.QUARANTINED) " · quarentena" else "",
                tag = "wallet-${w.name}") { c.go(Screen.Detail(w.id)) }
        }
        Divider()
        ListRow("Adicionar carteira", Ic.PLUS) { c.startFlow(FlowMode.CREATE) }
        Divider()
        ListRow("Importar carteira", Ic.KEY) { c.startFlow(FlowMode.RESTORE) }
    }
    Group("Privacidade e segurança") {
        ListRow("Central de Segurança", Ic.SHIELD, help = Help.SECURITY_CENTER, tag = "security-center") { c.go(Screen.SecurityCenter) }
        Divider()
        ListRow("Servidor da rede", Ic.GLOBE, value = if (c.prefs.customRpc) "Personalizado" else "Padrão", help = Help.RPC, tag = "network") { c.chain.notice = null; c.error = null; c.go(Screen.Network) }
        if (c.vaultPresent && c.bioCapable) {
            Divider()
            ToggleRow("Desbloquear com a digital", c.bioEnabled, Ic.FINGER, help = Help.BIO_UNLOCK, tag = "bio-toggle") { c.setBiometricUnlock(it) }
            val w = c.bioWindowHours
            if (c.bioEnabled && w != null) {
                Divider()
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) { Label("Pedir o PIN pelo menos a cada", modifier = Modifier.weight(1f)); HelpIcon(Help.BIO_WINDOW, 14.dp) }
                val opts = com.lockwallet.custody.local.bio.BioVault.WINDOWS_H
                Segmented(opts.map { "$it h" }, opts.indexOf(w).coerceAtLeast(0)) { c.setBioWindow(opts[it]) }
            }
        }
        if (c.vaultPresent) { Divider(); ListRow("Bloquear agora", Ic.LOCK, help = Help.LOCKDOWN, tag = "lock-now") { c.lockdown() } }
    }
    Group("Suporte") {
        ListRow("Ajuda", Ic.HELP, tag = "help-index") { sheets.show { HelpIndex() } }
        Divider()
        ListRow("Notificações", Ic.INFO, value = "Não usadas", help = Help.NO_NOTIFICATIONS, chevron = false)
        Divider()
        ListRow("Sobre", Ic.SPARK, help = Help.ABOUT, chevron = false)
    }
}

const val APP_VERSION = "Final"

/** pt-BR currency names (settings list and value). */
fun fiatName(f: Fiat): String = when (f) {
    Fiat.USD -> "Dólar americano"; Fiat.EUR -> "Euro"; Fiat.JPY -> "Iene"; Fiat.GBP -> "Libra esterlina"; Fiat.CNY -> "Yuan"
    Fiat.BRL -> "Real"; Fiat.CAD -> "Dólar canadense"; Fiat.AUD -> "Dólar australiano"; Fiat.CHF -> "Franco suíço"; Fiat.INR -> "Rupia indiana"
}

/** Currency picker: 10 world currencies, selected one marked; picking closes the sheet with a haptic tick. */
@Composable
fun ColumnScope.CurrencySheet(c: AppController) {
    val sheets = LocalSheets.current
    Row(verticalAlignment = Alignment.CenterVertically) { Title("Moeda"); Spacer(Modifier.weight(1f)); HelpIcon(Help.CURRENCY, 18.dp) }
    Group(null) {
        Fiat.entries.forEachIndexed { i, f ->
            if (i > 0) Divider()
            ListRow(fiatName(f), Ic.COIN, value = f.name, chevron = false, tag = "fiat-${f.name}",
                trailing = if (f == c.fiat) ({ Icon(Ic.CHECK, size = 20.dp) }) else null) { c.setFiatPref(f); sheets.dismiss() }
        }
    }
}

// ======================================================================= network (custom RPC, mainnet only)

@Composable
fun NetworkScreen(c: AppController) {
    val cfg = c.chain.config
    val d = com.lockwallet.data.rpc.NetworkConfig.MAINNET_DEFAULT
    var primary by remember(cfg) { mutableStateOf(if (cfg.primaryUrl == d.primaryUrl) "" else cfg.primaryUrl) }
    Screen("Servidor da rede", onBack = { c.chain.notice = null; c.home(); c.tab = Tab.SETTINGS }, help = Help.RPC, bottom = {
        c.chain.notice?.let { Notice(it, Ic.INFO) }
        ErrorText(c.error)
        if (c.busy) Busy("Conferindo o servidor…") else {
            Primary("Testar e salvar", tag = "rpc-save") { c.chain.saveEndpoints(primary) }
            if (c.prefs.customRpc) Secondary("Voltar ao padrão") { c.chain.resetEndpoints() }
        }
    }) {
        Group {
            ListRow("Rede", Ic.GLOBE, value = "Solana", help = Help.GENESIS, chevron = false)
            Divider()
            ListRow("Principal", Ic.LINK, cfg.primaryUrl.removePrefix("https://").take(48), chevron = false)
            Divider()
            ListRow("Reserva", Ic.LINK, cfg.secondaryUrl?.removePrefix("https://")?.take(48) ?: "nenhuma", chevron = false)
        }
        Label("Servidor próprio (opcional)")
        Field(primary, { primary = it.trim().take(512) }, "Principal — vazio = padrão", keyboard = KeyboardType.Uri, mono = true)
        Body("Servidor de reserva fixo e independente; não pode ser substituído por uma URL personalizada.")
    }
}

// ======================================================================= security center

@Composable
fun SecurityCenter(c: AppController) {
    val checks = remember {
        c.capabilities.checks().map {
            if (it.id == com.lockwallet.custody.local.CheckId.SCREEN_PROTECTION && !c.overlaysHidden) it.copy(status = CheckStatus.WARN, value = "overlay") else it
        }
    }
    val legacyKek = checks.any {
        it.id == com.lockwallet.custody.local.CheckId.VAULT_AUTH_MODE && it.value == "PIN_ONLY"
    }
    val risks = checks.count { it.status == CheckStatus.RISK } +
        if (c.vaultPresent && c.legacyKdfWalletCount != 0 && checks.none { it.status == CheckStatus.RISK }) 1 else 0
    val warns = checks.count { it.status == CheckStatus.WARN }
    Screen(S.SC_TITLE, onBack = { c.go(if (c.locked) Screen.Lock else Screen.Home) }, help = Help.SECURITY_CENTER) {
        Column(Modifier.fillMaxWidth().glass().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (risks > 0) Ic.WARN else Ic.SHIELD, size = 40.dp)
            Text(when { risks > 0 -> "$risks ponto(s) de risco"; warns > 0 -> "$warns ponto(s) de atenção"; else -> "Tudo certo" }, color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        if (c.vaultPresent && c.legacyKdfWalletCount > 0) {
            Notice(
                "Há ${c.legacyKdfWalletCount} carteira(s) com o perfil antigo de Argon2id. A migração é tentada após o desbloqueio por PIN; o estado permanece indicado aqui até todas serem atualizadas. Se houver cancelamento ou falha, o envelope anterior é preservado.",
                Ic.WARN, strong = true,
            )
        }
        if (c.vaultPresent && c.legacyKdfWalletCount < 0) {
            Notice("Não foi possível verificar o perfil KDF do cofre. Não considere a migração concluída até a verificação voltar a funcionar.", Ic.WARN, strong = true)
        }
        c.migrationWarning?.let { Notice(it, Ic.WARN, strong = true) }
        if (legacyKek) {
            Notice(
                "Este cofre ainda usa uma KEK legada sem biometria forte por uso. A atualização do Argon2id não altera a política dessa chave. A correção exige reembrulhar todos os envelopes do cofre em uma migração transacional; não remova o cofre antes de confirmar sua frase ou um backup restaurável.",
                Ic.WARN, strong = true,
            )
        }
        Column(Modifier.fillMaxWidth().glass()) {
            checks.forEachIndexed { i, ch ->
                if (i > 0) Divider()
                ListRow(S.checkTitle(ch.id), statusIcon(ch.status), S.checkDetail(ch.id, ch.status, ch.value), chevron = false)
            }
        }
    }
}

// ======================================================================= wallet detail (Perfil)

@Composable
fun Detail(c: AppController, p: Platform, id: String) {
    val w = c.wallets.firstOrNull { it.id == id } ?: return
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(w.name) }
    val sv = w.provider == com.lockwallet.custody.local.api.CustodyProviderId.SEED_VAULT
    Screen("", onBack = { c.home() }) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Avatar(w.name, 84.dp, Modifier.shared("avatar-${w.id}"))
            Text(w.name, color = C.text, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip(if (w.role == WalletRole.VAULT) "Lock Vault" else "Lock Connect", icon = if (w.role == WalletRole.VAULT) Ic.LOCK else Ic.APPS)
                HelpIcon(if (w.role == WalletRole.VAULT) Help.ROLE_VAULT else Help.ROLE_CONNECT, 14.dp)
                Chip(if (sv) "Seed Vault" else "Neste aparelho", icon = if (sv) Ic.CHIP else Ic.PHONE)
                HelpIcon(if (sv) Help.CUSTODY_SEED_VAULT else Help.CUSTODY_LOCAL, 14.dp)
            }
        }
        if (w.state == WalletState.QUARANTINED) Notice(S.QUARANTINED, Ic.WARN, Help.QUARANTINE, strong = true)
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) { Label(S.DETAIL_ADDRESS, modifier = Modifier.weight(1f)); HelpIcon(Help.ADDRESS, 14.dp) }
            Mono(groupedAddress(w.address.toBase58()), C.text, 15)
            Text(if (sv) "Conta ${w.accountIndex + 1} do Seed Vault" else if (w.isMnemonic) "Conta derivada da frase" else "Chave importada (sem frase)", color = C.dim, fontSize = 13.sp)
        }
        Primary(S.COPY_ADDRESS, icon = Ic.COPY) { p.copyAddress(w.address.toBase58()) }
        if (renaming) {
            Field(name, { if (it.length <= 32) name = it }, S.NAME_HINT)
            Primary("Salvar nome") { c.rename(w.id, name); renaming = false }
        }
        if (confirmDelete) {
            Notice("Excluir “${w.name}” deste aparelho? Os fundos continuam na blockchain; ela só volta pela frase ou pelo Recovery Package.", Ic.WARN, Help.DELETE_WALLET, strong = true)
            Field(typed, { if (it.length <= 16) typed = it }, "Digite EXCLUIR")
            if (typed.trim().uppercase() == "EXCLUIR") Primary("Excluir carteira", icon = Ic.TRASH) { confirmDelete = false; typed = ""; c.go(Screen.Authorize(AuthPurpose.DeleteWallet(w.id))) }
            Primary("Cancelar") { confirmDelete = false; typed = "" }
        }
        ErrorText(c.error)
        Group {
            if (!renaming) { ListRow(S.RENAME, Ic.PERSON) { renaming = true }; Divider() }
            if (sv) {
                ListRow("Backup", Ic.SHIELD, S.SV_BACKUP, chevron = false)
                Divider()
                ListRow("Remover deste app", Ic.TRASH, "A frase continua no Seed Vault") { c.removeSeedVaultWallet(w.id) }
            } else if (w.state == WalletState.ACTIVE) {
                if (w.isMnemonic) { ListRow(S.VIEW_PHRASE, Ic.EYE, help = Help.PHRASE, tag = "view-phrase") { c.go(Screen.Authorize(AuthPurpose.RevealPhrase(w.id))) }; Divider() }
                ListRow(S.EXPORT_LWRP, Ic.FILE, help = Help.LWRP, tag = "export") { c.exportResult = null; c.go(Screen.Authorize(AuthPurpose.ExportLwrp(w.id))) }
                if (w.isMnemonic) { Divider(); ListRow(S.ADD_ACCOUNT, Ic.PLUS, help = Help.ACCOUNT, tag = "add-account") { c.go(Screen.Authorize(AuthPurpose.AddAccount(w.id))) } }
                Divider(); ListRow("Excluir carteira", Ic.TRASH, help = Help.DELETE_WALLET, tag = "delete-wallet") { confirmDelete = true }
            } else ListRow(S.REMOVE_QUARANTINED, Ic.TRASH, help = Help.QUARANTINE) { c.removeQuarantined(w.id) }
            c.chain.config.cluster.explorerAddress(w.address.toBase58())?.let { url -> Divider(); ListRow("Ver no explorador", Ic.GLOBE, help = Help.EXPLORER) { p.openUrl(url) } }
        }
    }
}

@Composable
fun Reveal(c: AppController) {
    val s = c.opened?.session ?: return
    val words = remember(s) { s.words() }
    DisposableEffect(words) { onDispose { words.forEach { it.fill('\u0000') } } }
    Screen(S.PHRASE_TITLE, onBack = { c.home() }, help = Help.PHRASE, bottom = { Primary(S.HIDE, icon = Ic.EYE_OFF) { c.home() } }) {
        Notice(S.REVEAL_NOTE, Ic.EYE_OFF, strong = true)
        WordGrid(words)
    }
}

@Composable
fun Export(c: AppController, p: Platform) {
    val s = c.opened?.session ?: return
    var generated by remember { mutableStateOf<CharArray?>(null) }
    var own by remember { mutableStateOf(false) }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { generated?.fill('\u0000'); pw = ""; pw2 = "" } }
    Screen(S.EXPORT_TITLE, onBack = { c.home() }, help = Help.LWRP) {
        val result = c.exportResult
        if (result != null) {
            Notice(result, if (result == S.EXPORT_VERIFIED) Ic.CHECK else Ic.X_CIRCLE, strong = result != S.EXPORT_VERIFIED)
            Primary(S.DONE) { c.home() }
            return@Screen
        }
        if (!own) {
            val g = generated
            if (g == null) {
                ChoiceCard(S.EXPORT_GENERATE, "8 palavras aleatórias — forte e fácil de anotar", Ic.SPARK) { generated = RecoveryPasswords.generate() }
                ChoiceCard(S.EXPORT_OWN, "No mínimo 16 caracteres", Ic.KEY) { own = true }
            } else {
                // v3 fix: the generated password used to be drawn on ONE canvas line and was clipped on most phones.
                // Now it is shown as a numbered, wrapping word grid; the exact same CharArray is what gets used.
                val gw = remember(g) { secretWords(g) }
                DisposableEffect(gw) { onDispose { gw.forEach { it.fill('\u0000') } } }
                Notice(S.EXPORT_PW_NOTE, Ic.WARN, strong = true)
                WordGrid(gw, tag = "export-password")
                ErrorText(c.error)
                if (c.busy) Busy() else Primary(S.EXPORT_SAVE, icon = Ic.FILE) { val copy = g.copyOf(); c.prepareExport(copy) { name -> p.createDocument(name) } }
            }
        } else {
            Field(pw, { pw = it.take(256) }, "Senha", secret = true)
            Field(pw2, { pw2 = it.take(256) }, S.EXPORT_CONFIRM, secret = true)
            if (pw2.isNotEmpty() && pw != pw2) ErrorText(S.EXPORT_MISMATCH)
            ErrorText(c.error)
            if (c.busy) Busy() else Primary(S.EXPORT_SAVE, enabled = pw.isNotEmpty() && pw == pw2, icon = Ic.FILE) {
                val chars = pw.toCharArray(); pw = ""; pw2 = ""
                c.prepareExport(chars) { name -> p.createDocument(name) }
            }
        }
        Text(shortAddress(s.info.address.toBase58()), color = C.dim, fontSize = 13.sp)
    }
}
