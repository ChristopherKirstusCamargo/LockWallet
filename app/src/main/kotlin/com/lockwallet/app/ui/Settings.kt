package com.lockwallet.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockwallet.app.AppController
import com.lockwallet.app.AuthPurpose
import com.lockwallet.app.FlowMode
import com.lockwallet.app.Screen
import com.lockwallet.app.SettingsSection
import com.lockwallet.app.Tab
import com.lockwallet.custody.local.CheckStatus
import com.lockwallet.custody.local.api.RecoveryPasswords
import com.lockwallet.custody.local.storage.WalletState
import com.lockwallet.data.price.Fiat

/**
 * Ajustes: a profile card, then short navigation rows (icon, title, current value, chevron). Each row opens its own
 * screen ([SettingsSectionScreen]); every option lives in exactly one place. The balance unit and hiding are
 * gestures on the balance itself, not settings.
 */
@Composable
fun SettingsTab(c: AppController, p: Platform) {
    Row(Modifier.padding(top = 8.dp)) { Title("Ajustes") }

    c.chain.wallet()?.let { w ->
        Row(Modifier.fillMaxWidth().glass().pressable { c.go(Screen.Detail(w.id)) }.padding(16.dp).testTag("profile-card"),
            verticalAlignment = Alignment.CenterVertically) {
            Avatar(w.name, 56.dp, Modifier.shared("avatar-${w.id}"), photoPath = c.profilePhotoPath(w.id), photoRevision = c.profilePhotoRevision)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(w.name, color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Ver perfil", color = C.dim, fontSize = 14.sp)
            }
            Icon(Ic.CHEVRON, C.dim, 18.dp)
        }
    }

    fun open(s: SettingsSection) { c.error = null; c.chain.notice = null; c.go(Screen.Settings(s)) }
    Group {
        SECTIONS.forEachIndexed { i, (sec, ic) ->
            if (i > 0) Divider()
            ListRow(SECTION_TITLES[sec]!!, ic, tag = "settings-${sec.name.lowercase()}") { open(sec) }
        }
    }
}

private val SECTIONS = listOf(
    SettingsSection.SECURITY to Ic.SHIELD, SettingsSection.CURRENCY to Ic.COIN, SettingsSection.PRIVACY to Ic.EYE_OFF,
    SettingsSection.WALLETS to Ic.WALLET, SettingsSection.APPEARANCE to Ic.MOON, SettingsSection.ABOUT to Ic.INFO,
)

private val SECTION_TITLES = mapOf(
    SettingsSection.SECURITY to "Segurança", SettingsSection.CURRENCY to "Moeda", SettingsSection.PRIVACY to "Privacidade",
    SettingsSection.WALLETS to "Carteiras", SettingsSection.APPEARANCE to "Aparência", SettingsSection.ABOUT to "Sobre",
)

/** One Ajustes group on its own screen. Back returns to the Ajustes tab. */
@Composable
fun SettingsSectionScreen(c: AppController, p: Platform, section: SettingsSection) {
    val sheets = LocalSheets.current
    Screen(SECTION_TITLES[section]!!, onBack = { c.error = null; c.tab = Tab.SETTINGS; c.home() }) {
        when (section) {
            SettingsSection.WALLETS -> {
                val all = c.wallets
                if (all.isNotEmpty()) Group("Neste aparelho", Help.ACCOUNT) {
                    all.forEachIndexed { i, w ->
                        if (i > 0) Divider()
                        ListRow(w.name, Ic.WALLET, value = shortAddress(w.address.toBase58()) + if (w.state == WalletState.QUARANTINED) " · quarentena" else "",
                            tag = "wallet-${w.name}") { c.go(Screen.Detail(w.id)) }
                    }
                }
                Group("Adicionar") {
                    ListRow("Criar carteira", Ic.PLUS, tag = "wallet-create") { c.startFlow(FlowMode.CREATE) }
                    Divider()
                    ListRow("Importar carteira", Ic.KEY, tag = "wallet-import") { c.startFlow(FlowMode.RESTORE) }
                }
            }
            SettingsSection.SECURITY -> {
                Group {
                    ListRow("Central de Segurança", Ic.SHIELD, help = Help.SECURITY_CENTER, tag = "security-center") { c.go(Screen.SecurityCenter) }
                }
                if (c.vaultPresent) Group("Acesso") {
                    ListRow("PIN do cofre", Ic.KEY, value = "${c.pinLength} dígitos", help = Help.PIN, chevron = false, tag = "pin-info")
                    if (c.bioCapable) {
                        Divider()
                        ToggleRow("Desbloquear com digital", c.bioEnabled, Ic.FINGER, help = Help.BIO_UNLOCK, tag = "bio-toggle") { c.setBiometricUnlock(it) }
                        if (c.bioEnabled && !c.bioReady) {
                            Text("Digital indisponível agora. Use o PIN.", color = C.dim, fontSize = 13.sp, modifier = Modifier.padding(start = 68.dp, end = 16.dp, bottom = 10.dp))
                        }
                        val win = c.bioWindowHours
                        if (c.bioEnabled && win != null) {
                            Divider()
                            SegmentHeader("Exigir PIN a cada", Help.BIO_WINDOW)
                            val opts = com.lockwallet.custody.local.bio.BioVault.WINDOWS_H
                            Segmented(opts.map { "$it h" }, opts.indexOf(win).coerceAtLeast(0), Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) { c.setBioWindow(opts[it]) }
                        }
                    }
                    Divider()
                    SegmentHeader("Bloquear após inatividade", Help.AUTO_LOCK)
                    val opts = com.lockwallet.app.IDLE_LOCK_OPTIONS
                    Segmented(opts.map { "$it min" }, opts.indexOf(c.idleLockMinutes).coerceAtLeast(0),
                        Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp).testTag("idle-lock")) { c.setIdleLockPref(opts[it]) }
                    Divider()
                    ListRow("Bloquear agora", Ic.LOCK, help = Help.LOCKDOWN, tag = "lock-now") { c.lockdown() }
                }
                if (c.vaultPresent) SendProtectionsGroup(c)
                c.chain.wallet()?.takeIf { it.state == WalletState.ACTIVE }?.let { w ->
                    Group("Backup · ${w.name}", Help.PHRASE) {
                        if (w.isMnemonic) { ListRow(S.VIEW_PHRASE, Ic.EYE, tag = "view-phrase") { c.go(Screen.Authorize(AuthPurpose.RevealPhrase(w.id))) }; Divider() }
                        ListRow(S.EXPORT_LWRP, Ic.FILE, help = Help.LWRP, tag = "export") { c.exportResult = null; c.go(Screen.Authorize(AuthPurpose.ExportLwrp(w.id))) }
                    }
                }
            }
            SettingsSection.PRIVACY -> {
                Group {
                    ListRow("Saldo confidencial", Ic.RINGS, value = if (c.prefs.customSpr) "Configurado" else "Desligado", tag = "privacy-ring") {
                        c.error = null; c.chain.notice = null; c.tab = Tab.PRIVACY; c.home()
                    }
                    Divider()
                    ListRow("Servidor da rede", Ic.GLOBE, value = if (c.prefs.customRpc) "Próprio" else "Padrão", help = Help.RPC, tag = "network") { c.chain.notice = null; c.error = null; c.go(Screen.Network) }
                }
                Body("Sem telemetria, conta ou servidor próprio. O app fala só com a rede Solana e, com a cotação ligada, com Coinbase ou Kraken.")
            }
            SettingsSection.CURRENCY -> {
                Group("Moeda de exibição", Help.CURRENCY) {
                    ListRow("Moeda", Ic.COIN, value = "${fiatSymbol(c.fiat)} · ${c.fiat.name}", tag = "currency") { sheets.show { CurrencySheet(c) } }
                }
                Group("Valores grandes") {
                    Segmented(listOf("Compacto", "Completo"), if (c.compactAmounts) 0 else 1,
                        Modifier.padding(16.dp).testTag("compact-format")) { c.setCompactAmountsPref(it == 0) }
                }
                Group("Preços", Help.PRICE) {
                    ToggleRow("Cotação ao vivo", c.priceEnabled, Ic.BOLT, tag = "price-toggle") { c.setPriceOn(it) }
                }
                Body(if (c.compactAmounts) "A partir de 1 milhão, valores aparecem abreviados (por exemplo, R$ 1,25 bi). Toque e segure o saldo para ver o valor exato."
                    else "Valores sempre completos; números grandes diminuem para caber na tela.")
            }
            SettingsSection.APPEARANCE -> Group {
                ListRow("Tema", Ic.MOON, value = if (C.dark) "Escuro" else "Claro", help = Help.THEME, tag = "theme") { c.setDarkTheme(!C.dark) }
                Divider()
                ToggleRow("Animações", c.animations, Ic.SPARK, help = Help.ANIMATIONS, tag = "animations-toggle") { c.setAnimationsPref(it) }
                Divider()
                ToggleRow("Vibração", c.haptics, Ic.VIBRATE, help = Help.HAPTICS, tag = "haptics-toggle") { c.setHapticsPref(it) }
            }
            SettingsSection.ABOUT -> Group {
                ListRow("Ajuda", Ic.HELP, tag = "help-index") { sheets.show { HelpIndex() } }
                Divider()
                ListRow("Sobre a Lock Wallet", Ic.INFO, tag = "about") { sheets.show { HelpBody(Help.ABOUT) } }
            }
        }
    }
}

@Composable
private fun SegmentHeader(text: String, help: HelpTopic) =
    Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        HelpIcon(help, 16.dp)
    }


/** Typographic currency mark (no flags or third-party artwork): each currency is recognisable at a glance. */
fun fiatSymbol(f: Fiat): String = when (f) {
    Fiat.USD -> "$"; Fiat.EUR -> "€"; Fiat.JPY -> "¥"; Fiat.GBP -> "£"; Fiat.CNY -> "元"
    Fiat.BRL -> "R$"; Fiat.CAD -> "C$"; Fiat.AUD -> "A$"; Fiat.CHF -> "Fr"; Fiat.INR -> "₹"
}

@Composable
private fun CurrencyBadge(f: Fiat, selected: Boolean) {
    val sym = fiatSymbol(f)
    Box(Modifier.size(40.dp).then(if (selected) Modifier.clip(CircleShape).background(C.text) else Modifier.glass(CircleShape)), contentAlignment = Alignment.Center) {
        Text(sym, color = if (selected) C.bg else C.text, fontSize = if (sym.length > 1) 14.sp else 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

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
            val selected = f == c.fiat
            Row(Modifier.fillMaxWidth().pressable(haptic = Haptic.TICK) { c.setFiatPref(f); sheets.dismiss() }.testTag("fiat-${f.name}")
                .heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                CurrencyBadge(f, selected)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(fiatName(f), color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(f.name, color = C.dim, fontSize = 13.sp)
                }
                if (selected) Icon(Ic.CHECK, size = 20.dp)
            }
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
            ListRow("Principal", Ic.LINK, hostLabel(cfg.primaryUrl), chevron = false)
            Divider()
            ListRow("Reserva", Ic.LINK, hostLabel(cfg.secondaryUrl), chevron = false)
        }
        Label("Servidor próprio (opcional)")
        Field(primary, { primary = it.trim().take(512) }, "Principal — vazio = padrão", keyboard = KeyboardType.Uri, mono = true)
        Body("Servidor de reserva fixo e independente; não pode ser substituído por uma URL personalizada.")
    }
}

/** Host shown in the network rows: truncated with an ellipsis so a cut URL never reads as a shorter one. */
private fun hostLabel(url: String?, max: Int = 48): String =
    url?.removePrefix("https://")?.let { if (it.length > max) it.take(max - 1) + "…" else it } ?: "nenhuma"

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
                "Há ${c.legacyKdfWalletCount} carteira(s) com a proteção de PIN de uma versão anterior. A atualização acontece após o desbloqueio por PIN; se for interrompida, a proteção anterior continua valendo.",
                Ic.WARN, strong = true,
            )
        }
        if (c.vaultPresent && c.legacyKdfWalletCount < 0) {
            Notice("Não foi possível verificar a proteção de PIN das carteiras. Tente de novo depois do próximo desbloqueio.", Ic.WARN, strong = true)
        }
        c.migrationWarning?.let { Notice(it, Ic.WARN, strong = true) }
        if (legacyKek) {
            Notice(
                "Este cofre foi criado por uma versão anterior: a chave principal não exige biometria forte a cada uso e continua protegida pelo PIN. Essa chave não é migrada automaticamente. Para a proteção atual, confirme a frase ou um backup restaurável, reinstale o app e restaure as carteiras.",
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
    val photoPath = c.profilePhotoPath(w.id)
    val pendingPhotoBytes = c.pendingProfilePhoto?.takeIf { it.first == w.id }?.second
    val photoPreview = rememberProfilePicture(null, pendingPhotoBytes, 0, 88.dp)
    Screen("", onBack = { c.home() }) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.clip(CircleShape).pressable(haptic = Haptic.TICK) { p.pickProfilePhoto(w.id) { bytes -> if (bytes != null) c.prepareProfilePhoto(w.id, bytes) } }
                .semantics { contentDescription = if (photoPath == null) "Adicionar fotografia" else "Alterar fotografia" }.testTag("profile-photo-pick")) {
                Avatar(w.name, 96.dp, Modifier.shared("avatar-${w.id}"), photoPath, c.profilePhotoRevision)
            }
            FitText(w.name, C.text, 28.sp, FontWeight.Black, Modifier.fillMaxWidth())
            Chip(shortAddress(w.address.toBase58()), icon = Ic.COPY) { p.copyAddress(w.address.toBase58()) }
        }
        if (photoPreview != null) {
            Card(padding = 14.dp) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(88.dp).clip(CircleShape).semantics { contentDescription = "Prévia da fotografia de perfil" }) { ProfilePictureView(photoPreview) }
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Usar esta fotografia?", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Primary("Salvar foto", icon = Ic.CHECK) { c.savePendingProfilePhoto(w.id) }
                        Secondary("Descartar") { c.discardPendingProfilePhoto() }
                    }
                }
            }
        }
        if (w.state == WalletState.QUARANTINED) Notice(S.QUARANTINED, Ic.WARN, Help.QUARANTINE, strong = true)

        Group("Perfil") {
            if (renaming) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(name, { if (it.length <= 32) name = it }, S.NAME_HINT)
                    Primary("Salvar nome", enabled = name.isNotBlank()) { c.rename(w.id, name); renaming = false }
                    Secondary(S.CANCEL) { name = w.name; renaming = false }
                }
            } else ListRow(S.RENAME, Ic.PERSON, value = w.name) { renaming = true }
            Divider()
            ListRow(if (photoPath == null) "Adicionar fotografia" else "Alterar fotografia", Ic.IMAGE, tag = "profile-photo") {
                p.pickProfilePhoto(w.id) { bytes -> if (bytes != null) c.prepareProfilePhoto(w.id, bytes) }
            }
            if (photoPath != null) {
                Divider()
                ListRow("Remover fotografia", Ic.TRASH, tag = "profile-photo-remove") { c.removeProfilePhoto(w.id) }
            }
        }

        Group("Endereço", Help.ADDRESS) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Mono(groupedAddress(w.address.toBase58()), C.text, 15)
                Text(if (w.isMnemonic) "Conta ${w.accountIndex + 1} da frase de recuperação" else "Chave importada (sem frase)", color = C.dim, fontSize = 13.sp)
            }
            Divider()
            ListRow(S.COPY_ADDRESS, Ic.COPY, tag = "copy-address") { p.copyAddress(w.address.toBase58()) }
            c.chain.config.cluster.explorerAddress(w.address.toBase58())?.let { url -> Divider(); ListRow("Ver no explorador", Ic.GLOBE, help = Help.EXPLORER) { p.openUrl(url) } }
        }

        ErrorText(c.error)
        if (w.state == WalletState.ACTIVE && w.isMnemonic) Group("Contas") {
            ListRow(S.ADD_ACCOUNT, Ic.PLUS, help = Help.ACCOUNT, tag = "add-account") { c.go(Screen.Authorize(AuthPurpose.AddAccount(w.id))) }
        }

        Group {
            if (w.state == WalletState.ACTIVE) ListRow("Excluir carteira", Ic.TRASH, help = Help.DELETE_WALLET, tag = "delete-wallet") { confirmDelete = true }
            else ListRow(S.REMOVE_QUARANTINED, Ic.TRASH, help = Help.QUARANTINE) { c.removeQuarantined(w.id) }
            if (confirmDelete) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Notice("Excluir “${w.name}” deste aparelho? Os fundos continuam na blockchain; ela só volta pela frase ou pelo Recovery Package.", Ic.WARN, Help.DELETE_WALLET, strong = true)
                Field(typed, { if (it.length <= 16) typed = it }, "Digite EXCLUIR")
                if (typed.trim().uppercase() == "EXCLUIR") Primary("Excluir carteira", icon = Ic.TRASH) { confirmDelete = false; typed = ""; c.go(Screen.Authorize(AuthPurpose.DeleteWallet(w.id))) }
                Secondary(S.CANCEL) { confirmDelete = false; typed = "" }
            }
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
                ChoiceCard(S.EXPORT_OWN, "Mínimo de 20 bytes em UTF-8", Ic.KEY) { own = true }
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
            val passwordBytes = remember(pw) { utf8NfcByteLength(pw) }
            Field(pw, { pw = it.take(256) }, "Senha", secret = true)
            Field(pw2, { pw2 = it.take(256) }, S.EXPORT_CONFIRM, secret = true)
            when {
                pw.isNotEmpty() && passwordBytes < 0 -> ErrorText(S.EXPORT_PW_INVALID_UNICODE)
                pw.isNotEmpty() && passwordBytes < RecoveryPasswords.MIN_BYTES -> ErrorText(S.EXPORT_PW_TOO_SHORT)
                pw.isNotEmpty() && passwordBytes > RecoveryPasswords.MAX_BYTES -> ErrorText(S.EXPORT_PW_TOO_LONG)
            }
            if (pw2.isNotEmpty() && pw != pw2) ErrorText(S.EXPORT_MISMATCH)
            ErrorText(c.error)
            val passwordLengthValid = passwordBytes in RecoveryPasswords.MIN_BYTES..RecoveryPasswords.MAX_BYTES
            if (c.busy) Busy() else Primary(S.EXPORT_SAVE, enabled = passwordLengthValid && pw == pw2, icon = Ic.FILE) {
                val chars = pw.toCharArray(); pw = ""; pw2 = ""
                c.prepareExport(chars) { name -> p.createDocument(name) }
            }
        }
        Text(shortAddress(s.info.address.toBase58()), color = C.dim, fontSize = 13.sp)
    }
}


/** Mirrors LWRP's NFC + strict UTF-8 length check so invalid passwords are rejected before export. */
private fun utf8NfcByteLength(value: String): Int {
    val normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC)
    val encoder = Charsets.UTF_8.newEncoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    val encoded = try {
        encoder.encode(java.nio.CharBuffer.wrap(normalized))
    } catch (_: java.nio.charset.CharacterCodingException) {
        return -1
    }
    val length = encoded.remaining()
    if (encoded.hasArray()) encoded.array().fill(0)
    return length
}
