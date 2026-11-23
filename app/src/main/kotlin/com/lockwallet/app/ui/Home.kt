package com.lockwallet.app.ui

import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.lockwallet.app.Activity
import com.lockwallet.app.AppController
import com.lockwallet.app.AuthPurpose
import com.lockwallet.app.FlowMode
import com.lockwallet.app.Loadable
import com.lockwallet.app.Screen
import com.lockwallet.app.Tab
import com.lockwallet.app.valueOrNull
import com.lockwallet.core.solana.Amounts
import com.lockwallet.custody.local.api.WalletInfo
import com.lockwallet.custody.local.storage.WalletState
import com.lockwallet.data.price.Fiat
import com.lockwallet.data.price.PriceService
import com.lockwallet.data.price.PriceState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

private val PT = Locale.forLanguageTag("pt-BR")

fun formatFiat(v: BigDecimal, f: Fiat): String {
    val nf = NumberFormat.getCurrencyInstance(PT)
    nf.currency = Currency.getInstance(f.name)
    return nf.format(v.setScale(2, RoundingMode.HALF_EVEN))
}

fun formatPct(r: BigDecimal): String {
    val pct = r.multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_EVEN)
    return (if (pct.signum() > 0) "+" else if (pct.signum() < 0) "−" else "") + pct.abs().toPlainString().replace('.', ',') + "%"
}

private val TAB_LABELS = mapOf(Tab.HOME to "Início", Tab.STAKE to "Stake", Tab.CONNECT to "dApps", Tab.SETTINGS to "Ajustes")
private val TAB_ICONS = mapOf(Tab.HOME to Ic.HOME, Tab.STAKE to Ic.STAKE, Tab.CONNECT to Ic.APPS, Tab.SETTINGS to Ic.SETTINGS)

/**
 * Snapshot of what the balance header shows (pure; derived from real chain + price data only).
 * [lamports] = liquid SOL (what can be sent; "max" uses only this). [total] = liquid + stake accounts; [value] and
 * the 24 h change are computed on [total] when it is known, otherwise on the liquid balance ([partial] = true).
 */
data class Valuation(val lamports: Long?, val fiat: Fiat?, val price: BigDecimal?, val value: BigDecimal?, val change: BigDecimal?, val changeValue: BigDecimal?, val stale: Boolean,
                     val staked: Long? = null, val total: Long? = null, val lastEpochReward: Long? = null, val liquidValue: BigDecimal? = null, val partial: Boolean = true)

fun valuation(c: AppController): Valuation {
    val st = c.priceState
    val quote = when (st) { is PriceState.Fresh -> st.quote; is PriceState.Stale -> st.quote; else -> null }
    val shown = if (quote?.prices?.get(c.fiat) != null) c.fiat else if (quote?.prices?.get(Fiat.USD) != null) Fiat.USD else null
    val price = shown?.let { quote?.prices?.get(it) }?.takeIf { c.priceEnabled }
    val stakes = c.chain.stakes.valueOrNull()
    val h = com.lockwallet.app.Holdings.of(c.chain.balance.valueOrNull(), stakes?.first, stakes?.second?.epoch, c.chain.wallet()?.address)
    return valuationOf(h, shown, price, quote?.change24h, st is PriceState.Stale)
}

fun valuationOf(h: com.lockwallet.app.SolHoldings, fiat: Fiat?, price: BigDecimal?, change24h: BigDecimal?, stale: Boolean): Valuation {
    val held = h.total ?: h.liquid
    val value = if (held != null && price != null) Amounts.toDecimal(held, 9).multiply(price) else null
    val liquidValue = if (h.liquid != null && price != null) Amounts.toDecimal(h.liquid, 9).multiply(price) else null
    val r = change24h?.takeIf { price != null }
    // Only what is held moves (staked SOL included): zero holdings ⇒ no variation; SPL tokens are unpriced, so excluded.
    val pc = if (value != null) com.lockwallet.app.Portfolio.change(listOf(com.lockwallet.app.Holding(value, r))) else null
    return Valuation(h.liquid, fiat, price, value, pc?.ratio, pc?.changeValue, stale, h.staked, h.total, h.lastEpochReward, liquidValue, h.total == null)
}

/** Main screen: tab content, bottom pill navigation, '+' action button above it, side drawer. */
@Composable
fun MainScreen(c: AppController, p: Platform) {
    // Real price polling: only while composed, in the foreground, and enabled by the user.
    LaunchedEffect(c.foreground, c.priceEnabled) {
        if (!c.foreground || !c.priceEnabled) return@LaunchedEffect
        while (isActive) { c.pollPrice(); delay(PriceService.POLL_INTERVAL_MS) }
    }
    LaunchedEffect(Unit) { while (isActive) { delay(1000); c.tickPrice() } }
    var drawer by remember { mutableStateOf(false) }
    val blur by animateDpAsState(if (c.actionsOpen || drawer) Fx.backdrop else 0.dp, Motion.soft(), label = "blur")
    val gutter = Space.gutter()
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().softBlur(blur)) {
            AnimatedContent(c.tab, transitionSpec = {
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (slideInHorizontally(Motion.slide) { dir * it / 6 } + fadeIn(Motion.fade(280))) togetherWith
                    (slideOutHorizontally(Motion.slide) { -dir * it / 6 } + fadeOut(Motion.fade(160)))
            }, label = "tab") { tab ->
                Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = gutter)
                    .padding(top = 8.dp, bottom = 176.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (tab) {
                        Tab.HOME -> HomeTab(c, p) { drawer = true }
                        Tab.STAKE -> StakeTab(c, p)
                        Tab.CONNECT -> ConnectTab(c)
                        Tab.SETTINGS -> SettingsTab(c, p)
                    }
                }
            }
        }
        // Scrim behind the FAB menu (tap outside closes it).
        val scrim by animateFloatAsState(if (c.actionsOpen) 1f else 0f, Motion.fade(300), label = "fabScrim")
        val scrimA = if (Fx.blurSupported) 0.45f else 0.7f
        if (scrim > 0.01f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = scrim }.background(C.scrim.copy(alpha = scrimA))
            .clickable(remember { MutableInteractionSource() }, null) { c.actionsOpen = false })
        if (c.actionsOpen) BackHandler { c.actionsOpen = false }
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(bottom = 16.dp)) {
            FabMenu(c, Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 160.dp))
            Fab(c, Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 84.dp))
            PillBar(c, Modifier.align(Alignment.BottomCenter))
        }
        Drawer(c, p, drawer) { drawer = false }
    }
}

@Composable
private fun PillBar(c: AppController, modifier: Modifier) {
    // Adaptive: 80 dp per tab, shrinking to fit 320 dp phones (never wider than the screen minus margins).
    val screenW = LocalConfiguration.current.screenWidthDp.dp
    val itemW = minOf(80.dp, (screenW - 32.dp - 12.dp) / Tab.entries.size)
    val idx = c.tab.ordinal
    // Gooey indicator: the leading edge leaps ahead, the trailing edge follows, so the pill stretches then settles.
    var last by remember { mutableIntStateOf(idx) }
    val right = idx >= last
    SideEffect { last = idx }
    val start by animateDpAsState(itemW * idx, spring(dampingRatio = 0.8f, stiffness = if (right) 260f else 700f), label = "pillStart")
    val end by animateDpAsState(itemW * (idx + 1), spring(dampingRatio = 0.8f, stiffness = if (right) 700f else 260f), label = "pillEnd")
    Box(modifier.width(itemW * Tab.entries.size + 12.dp).height(64.dp).shadowSoft(RoundedCornerShape(32.dp)).glass(RoundedCornerShape(32.dp), strong = true).padding(6.dp)) {
        Box(Modifier.offset(x = start).width((end - start).coerceAtLeast(itemW * 0.6f)).fillMaxHeight().clip(RoundedCornerShape(26.dp)).background(C.text))
        Row(Modifier.fillMaxSize()) {
            Tab.entries.forEach { t ->
                val selected = t == c.tab
                val color by animateColorAsState(if (selected) C.bg else C.dim, Motion.fade(220), label = "tabc")
                val iconScale by animateFloatAsState(if (selected) 1.08f else 1f, Motion.bouncy(), label = "tabs")
                Column(Modifier.width(itemW).fillMaxHeight().clip(RoundedCornerShape(26.dp)).pressable(haptic = Haptic.TICK) { c.actionsOpen = false; c.tab = t }
                    .testTag("tab-${t.name}"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(TAB_ICONS[t]!!, color, 20.dp, Modifier.scale(iconScale))
                    FitText(TAB_LABELS[t]!!, color, 11.sp, if (selected) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun Fab(c: AppController, modifier: Modifier) {
    val rot by animateFloatAsState(if (c.actionsOpen) 135f else 0f, spring(dampingRatio = 0.6f, stiffness = 420f), label = "fab")
    val halo by animateFloatAsState(if (c.actionsOpen) 1f else 0f, Motion.bouncy(), label = "halo")
    val ring = C.text
    Box(modifier.size(60.dp).drawBehind { if (halo > 0.01f) drawCircle(ring.copy(alpha = 0.12f * halo), radius = size.minDimension / 2 * (1f + 0.35f * halo)) }
        .shadowSoft(CircleShape).clip(CircleShape).background(C.text).pressable(haptic = Haptic.PRESS) { c.actionsOpen = !c.actionsOpen }
        .semantics { contentDescription = if (c.actionsOpen) "Fechar ações" else "Ações" }.testTag("fab"), contentAlignment = Alignment.Center) {
        Icon(Ic.PLUS, C.bg, 28.dp, Modifier.rotate(rot))
    }
}

/** Vertical stack of labelled circular actions that springs out of the '+' (staggered). */
@Composable
private fun FabMenu(c: AppController, modifier: Modifier) {
    val hasWallet = c.wallets.any { it.state == WalletState.ACTIVE }
    val items = listOf(
        Triple("Enviar", Ic.SEND) { c.error = null; c.go(Screen.Send()) },
        Triple("Receber", Ic.RECEIVE) { c.go(Screen.Receive) },
        Triple("Stake", Ic.STAKE) { c.error = null; c.go(Screen.StakeNew) },
        Triple("dApps", Ic.APPS) { c.tab = Tab.CONNECT },
        Triple("Nova carteira", Ic.WALLET) { c.startFlow(FlowMode.CREATE) },
        Triple("Importar carteira", Ic.KEY) { c.startFlow(FlowMode.RESTORE) },
    ).filter { hasWallet || it.first == "Nova carteira" || it.first == "Importar carteira" }
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEachIndexed { i, (label, ic, go) ->
            val order = items.size - 1 - i
            AnimatedVisibility(c.actionsOpen,
                enter = fadeIn(spring(stiffness = 500f)) + slideInVertically(spring(dampingRatio = 0.7f, stiffness = 500f - order * 40f)) { it * (order + 1) / 2 } +
                    scaleIn(spring(dampingRatio = 0.6f, stiffness = 500f), initialScale = 0.4f, transformOrigin = TransformOrigin(1f, 1f)),
                exit = fadeOut(spring(stiffness = 800f)) + scaleOut(targetScale = 0.6f, transformOrigin = TransformOrigin(1f, 1f)) + slideOutVertically { it / 2 }) {
                Row(Modifier.pressable { c.actionsOpen = false; go() }.testTag("fab-$label"), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        modifier = Modifier.glass(CircleShape, strong = true).padding(horizontal = 14.dp, vertical = 8.dp))
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.size(52.dp).shadowSoft(CircleShape).glass(CircleShape, strong = true), contentAlignment = Alignment.Center) { Icon(ic, size = 22.dp) }
                }
            }
        }
    }
}

// ======================================================================= drawer

@Composable
private fun Drawer(c: AppController, p: Platform, open: Boolean, close: () -> Unit) {
    val sheets = LocalSheets.current
    val scrim by animateFloatAsState(if (open) 1f else 0f, Motion.fade(320), label = "drawerScrim")
    if (scrim > 0.01f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = scrim }.background(C.scrim).clickable(remember { MutableInteractionSource() }, null) { close() })
    if (open) BackHandler { close() }
    AnimatedVisibility(open, enter = slideInHorizontally(Motion.sheet) { -it } + fadeIn(Motion.fade(220)), exit = slideOutHorizontally(Motion.sheet) { -it } + fadeOut(Motion.fade(200))) {
        val w = c.chain.wallet()
        Column(Modifier.fillMaxHeight().widthIn(max = 330.dp).fillMaxWidth(0.84f).glass(RoundedCornerShape(topEnd = 32.dp, bottomEnd = 32.dp), strong = true)
            .safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp).testTag("drawer"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (w != null) {
                Avatar(w.name, 64.dp, Modifier.shared("avatar-${w.id}"), c.profilePhotoPath(w.id), c.profilePhotoRevision)
                SpacerH(8.dp)
                Text(w.name, color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("@" + shortAddress(w.address.toBase58()), color = C.dim, fontSize = 14.sp)
                SpacerH(12.dp)
                Label("Contas")
                c.wallets.filter { it.state == WalletState.ACTIVE }.forEach { x ->
                    AccountRow(c, x, x.id == w.id) { c.chain.select(x.id); close() }
                }
            } else {
                Text("Lock Wallet", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).pressable { close(); c.startFlow(FlowMode.CREATE) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).border(1.dp, C.glassBorder, CircleShape), contentAlignment = Alignment.Center) { Icon(Ic.PLUS, size = 18.dp) }
                Spacer(Modifier.width(12.dp)); Text("Adicionar carteira", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(C.glassBorder))
            DrawerItem(Ic.PERSON, "Perfil") { close(); w?.let { c.go(Screen.Detail(it.id)) } }
            DrawerItem(Ic.HISTORY, "Atividade") { close(); c.tab = Tab.HOME; c.homeSegment = 1 }
            DrawerItem(Ic.SETTINGS, "Ajustes") { close(); c.tab = Tab.SETTINGS }
            DrawerItem(Ic.SHIELD, "Central de Segurança") { close(); c.go(Screen.SecurityCenter) }
            DrawerItem(Ic.HELP, "Ajuda") { close(); sheets.show { HelpIndex() } }
            if (c.vaultPresent) DrawerItem(Ic.LOCK, "Bloquear agora") { close(); c.lockdown() }
        }
    }
}

@Composable
private fun DrawerItem(ic: Ic, label: String, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).pressable(haptic = Haptic.TICK) { onClick() }.padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(ic, size = 22.dp); Spacer(Modifier.width(16.dp)); Text(label, color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }

@Composable
private fun AccountRow(c: AppController, w: WalletInfo, selected: Boolean, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (selected) C.glass else Color.Transparent).pressable { onClick() }
        .padding(horizontal = 10.dp, vertical = 10.dp).testTag("account-${w.name}"), verticalAlignment = Alignment.CenterVertically) {
        Avatar(w.name, 36.dp, photoPath = c.profilePhotoPath(w.id), photoRevision = c.profilePhotoRevision); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(w.name, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(shortAddress(w.address.toBase58()) + " · " + if (w.role == com.lockwallet.custody.local.storage.WalletRole.VAULT) "Vault" else "Connect", color = C.dim, fontSize = 12.sp)
        }
        if (selected) Icon(Ic.CHECK, size = 18.dp)
    }

/** Help index (Ajuda): every topic, grouped. */
@Composable
fun ColumnScope.HelpIndex() {
    val sheets = LocalSheets.current
    Text("Ajuda", color = C.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Help.INDEX.forEach { (group, topics) ->
        Label(group)
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp))) {
            topics.forEach { t -> ListRow(t.title, onClick = { sheets.show { HelpBody(t) } }) }
        }
    }
}

// ======================================================================= home tab

@Composable
private fun HomeTab(c: AppController, p: Platform, openDrawer: () -> Unit) {
    LaunchedEffect(c.foreground, c.chain.wallet()?.id, c.chain.config) {
        if (!c.foreground) return@LaunchedEffect
        while (isActive) { c.chain.safely { c.chain.refreshHome() }; delay(20_000) }
    }
    val w = c.chain.wallet()
    TopRow(c, p, w, openDrawer)
    HomeSecurityAttention(c)
    if (w == null) {
        Card {
            Body(S.HOME_EMPTY, C.text)
            PillButton("Criar uma carteira", Modifier.fillMaxWidth()) { c.startFlow(FlowMode.CREATE) }
            TextAction("Adicionar uma carteira existente", Modifier.fillMaxWidth()) { c.startFlow(FlowMode.RESTORE) }
        }
        QuarantinedList(c)
        return
    }
    BalanceHeader(c)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton("Enviar", Modifier.weight(1f), icon = Ic.SEND, tag = "home-send") { c.error = null; c.go(Screen.Send()) }
        PillButton("Depositar", Modifier.weight(1f), icon = Ic.RECEIVE, filled = false, tag = "home-receive") { c.go(Screen.Receive) }
    }
    PrivacyEntryCard(c)
    if (c.needsBackup(w)) BackupCard(c, w)
    (c.chain.balance as? Loadable.Failed)?.let { f ->
        Notice("Sem conexão", Ic.GLOBE, Help.RPC)
    }
    Segmented(listOf("Tokens", "Atividade"), c.homeSegment) { c.homeSegment = it }
    AnimatedContent(c.homeSegment, transitionSpec = {
        val d = if (targetState > initialState) 1 else -1
        (slideInHorizontally(Motion.slide) { d * it / 4 } + fadeIn()) togetherWith (slideOutHorizontally(Motion.slide) { -d * it / 4 } + fadeOut()) using SizeTransform(clip = false)
    }, label = "seg") { seg ->
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (seg == 0) Assets(c) else ActivityList(c, p)
        }
    }
    QuarantinedList(c)
    ErrorText(c.error)
}

@Composable
private fun HomeSecurityAttention(c: AppController) {
    if (!c.vaultPresent) return
    val warnings = buildList {
        if (c.legacyKekPinOnly) {
            add("Este cofre ainda usa uma chave principal legada, sem biometria forte exigida a cada uso. A atualização dessa chave é separada da migração do Argon2id e ainda não foi implementada.")
        }
        if (c.legacyKdfWalletCount > 0) {
            add("Há ${c.legacyKdfWalletCount} carteira(s) com o perfil antigo de Argon2id. A atualização é tentada após o desbloqueio por PIN.")
        } else if (c.legacyKdfWalletCount < 0) {
            add("Não foi possível verificar o perfil de proteção das carteiras. Consulte a Central de Segurança; não considere a migração concluída.")
        }
        c.migrationWarning?.let { add(it) }
    }
    if (warnings.isEmpty()) return

    Card(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Ic.WARN, size = 20.dp)
            Text("Atenção de segurança", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Body(warnings.joinToString("\n\n"), C.text)
        TextAction("Ver detalhes na Central de Segurança", Modifier.fillMaxWidth()) {
            c.go(Screen.SecurityCenter)
        }
    }
}

@Composable
private fun TopRow(c: AppController, p: Platform, w: WalletInfo?, openDrawer: () -> Unit) {
    val sheets = LocalSheets.current
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clip(CircleShape).pressable { openDrawer() }.testTag("avatar")) { Avatar(w?.name ?: "L", 40.dp, if (w != null) Modifier.shared("avatar-${w.id}") else Modifier, w?.let { c.profilePhotoPath(it.id) }, c.profilePhotoRevision) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.clip(CircleShape).pressable { sheets.show { AccountSheet(c) } }.padding(vertical = 2.dp).testTag("account-selector"), verticalAlignment = Alignment.CenterVertically) {
                Text(w?.name ?: "Lock Wallet", color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(4.dp)); Icon(Ic.CHEVRON_DOWN, C.dim, 16.dp)
            }
            if (w != null) Row(Modifier.clip(CircleShape).pressable(haptic = Haptic.CONFIRM) { p.copyAddress(w.address.toBase58()) }.testTag("copy-address"), verticalAlignment = Alignment.CenterVertically) {
                Text(shortAddress(w.address.toBase58()), color = C.dim, fontSize = 13.sp)
                Spacer(Modifier.width(4.dp)); Icon(Ic.COPY, C.dim, 14.dp)
            }
        }
        if (w != null) CircleButton(Ic.SCAN, "Ler QR", size = 40.dp) { c.error = null; c.scanOnOpen = true; c.go(Screen.Send()) }
        Spacer(Modifier.width(8.dp))
        CircleButton(Ic.SETTINGS, "Ajustes", size = 40.dp) { c.tab = Tab.SETTINGS }
    }
}

@Composable
private fun ColumnScope.AccountSheet(c: AppController) {
    val sheets = LocalSheets.current
    Row(verticalAlignment = Alignment.CenterVertically) { Text("Contas", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold); HelpIcon(Help.ACCOUNT, 18.dp) }
    val sel = c.chain.wallet()?.id
    c.wallets.filter { it.state == WalletState.ACTIVE }.forEach { w -> AccountRow(c, w, w.id == sel) { c.chain.select(w.id); sheets.dismiss() } }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PillButton("Nova", Modifier.weight(1f), icon = Ic.PLUS) { sheets.dismiss(); c.startFlow(FlowMode.CREATE) }
        PillButton("Importar", Modifier.weight(1f), icon = Ic.KEY, filled = false) { sheets.dismiss(); c.startFlow(FlowMode.RESTORE) }
    }
}

/** Huge centred balance with green/red 24 h change; tap toggles BRL/USD, long-press-free "eye" hides it. */
/** Large balance: tap alternates between the selected fiat unit and a SOL-equivalent valuation. */
@Composable
private fun BalanceHeader(c: AppController) {
    val v = valuation(c)
    val hidden = c.hideBalance
    val loading = v.lamports == null && c.chain.balance !is Loadable.Failed
    // SOL quantity comes from the actual lamport balance; it must remain visible even with a stale/offline quote.
    val solAmount = (v.total ?: v.lamports)?.let { Amounts.toDecimal(it, 9) }
    val canToggle = !hidden && v.value != null && v.fiat != null

    Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.clip(RoundedCornerShape(16.dp))
                .pressable(enabled = canToggle) { c.toggleBalanceUnit() }
                .semantics {
                    contentDescription = "Saldo. Toque para alternar entre ${v.fiat?.name ?: "moeda selecionada"} e SOL."
                }
                .testTag("price"),
            contentAlignment = Alignment.Center,
        ) {
            when {
                loading -> Skeleton(Modifier.fillMaxWidth(0.62f).height(56.dp), RoundedCornerShape(16.dp))
                hidden -> Text("••••••", color = C.text, fontSize = 52.sp, fontWeight = FontWeight.Black)
                c.balanceInSol -> when {
                    solAmount != null -> AnimatedNumber(formatSolEquivalent(solAmount), 52)
                    else -> Text("—", color = C.text, fontSize = 52.sp, fontWeight = FontWeight.Black)
                }
                v.value != null && v.fiat != null -> AnimatedNumber(formatFiat(v.value, v.fiat), 52)
                (v.total ?: v.lamports) != null -> AnimatedNumber(Fmt.sol(v.total ?: v.lamports!!) + " SOL", 44)
                else -> Text("—", color = C.text, fontSize = 52.sp, fontWeight = FontWeight.Black)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!hidden && !c.balanceInSol && v.change != null && v.changeValue != null && v.fiat != null) {
                val pctZero = v.change.multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_EVEN).signum() == 0
                val col by animateColorAsState(if (pctZero) C.dim else if (v.change.signum() > 0) C.up else C.down, Motion.soft(), label = "chg")
                AnimatedNumber((if (pctZero) "" else if (v.changeValue.signum() > 0) "+" else "−") + formatFiat(v.changeValue.abs(), v.fiat), 16, col, FontWeight.SemiBold)
                Chip(formatPct(v.change), color = col)
                HelpIcon(Help.CHANGE_24H, 16.dp)
            }
        }
        val secondaryLine = when {
            hidden -> "Saldo oculto"
            c.balanceInSol && v.stale -> "Cotação desatualizada"
            c.balanceInSol && v.value != null && v.fiat != null -> formatFiat(v.value, v.fiat)
            c.balanceInSol && !c.priceEnabled -> "Cotação desligada"
            c.balanceInSol && c.chain.balance is Loadable.Failed -> "Saldo indisponível"
            c.balanceInSol -> "Cotação indisponível"
            v.lamports != null -> Fmt.sol(v.total ?: v.lamports) + " SOL"
            !c.priceEnabled -> "Cotação desligada"
            c.chain.balance is Loadable.Failed -> "Saldo indisponível"
            else -> "Carregando…"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(secondaryLine, color = C.dim, fontSize = 14.sp, modifier = Modifier.testTag("sol-line"))
            if (v.stale && !hidden && v.value != null) { Spacer(Modifier.width(8.dp)); Chip("cotação atrasada", icon = Ic.CLOCK) }
            Box(Modifier.size(36.dp).clip(CircleShape).pressable(haptic = Haptic.TICK) { c.setHideBalancePref(!hidden) }.testTag("hide-balance")
                .semantics { contentDescription = if (hidden) "Mostrar saldo" else "Ocultar saldo" }, contentAlignment = Alignment.Center) {
                Icon(if (hidden) Ic.EYE_OFF else Ic.EYE, C.dim, 18.dp)
            }
        }
        if (!hidden && canToggle) {
            Text(
                if (c.balanceInSol) "Toque no saldo para ver em ${v.fiat?.name ?: "moeda"}"
                else "Toque no saldo para ver em SOL",
                color = C.dim,
                fontSize = 11.sp,
            )
        }
        if (!hidden && v.lamports != null && ((v.staked ?: 0L) > 0L || (v.staked == null && c.chain.stakes is Loadable.Failed))) BalanceBreakdown(v)
    }
}

/** Fiat value divided by a real positive SOL quote. Null means conversion is not safe to display. */
fun solEquivalent(valueFiat: BigDecimal?, solPriceFiat: BigDecimal?): BigDecimal? {
    if (valueFiat == null || solPriceFiat == null || valueFiat.signum() < 0 || solPriceFiat.signum() <= 0) return null
    return runCatching { valueFiat.divide(solPriceFiat, 9, RoundingMode.HALF_EVEN) }.getOrNull()
}

private fun formatSolEquivalent(value: BigDecimal): String {
    val nf = NumberFormat.getNumberInstance(PT).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 9
        roundingMode = RoundingMode.HALF_EVEN
    }
    return nf.format(value) + " SOL"
}

/** Disponível / Em stake (+ last-epoch reward when the chain answered consistently). */
@Composable
private fun BalanceBreakdown(v: Valuation) {
    Row(Modifier.fillMaxWidth().widthIn(max = 420.dp).glass(RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 8.dp).testTag("balance-breakdown"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Disponível", color = C.dim, fontSize = 11.sp)
            Box(Modifier.testTag("bal-liquid")) { FitText(Fmt.sol(v.lamports ?: 0) + " SOL", C.text, 13.sp, FontWeight.SemiBold) }
        }
        Box(Modifier.width(1.dp).height(26.dp).background(C.glassBorder))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Em stake", color = C.dim, fontSize = 11.sp)
            Box(Modifier.testTag("bal-staked")) { FitText(v.staked?.let { Fmt.sol(it) + " SOL" } ?: "indisponível", C.text, 13.sp, FontWeight.SemiBold) }
        }
        v.lastEpochReward?.let { r ->
            Box(Modifier.width(1.dp).height(26.dp).background(C.glassBorder))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Última época", color = C.dim, fontSize = 11.sp)
                Box(Modifier.testTag("bal-reward")) { FitText("+" + Fmt.sol(r) + " SOL", C.up, 13.sp, FontWeight.SemiBold) }
            }
        }
        HelpIcon(Help.TOTAL_BALANCE, 14.dp)
    }
}

/**
 * Home shortcut to the confidential balance (Solana Privacy Rings, Default Ring). It replaces the old
 * action tiles, which duplicated the buttons right above it, the tabs right below and the FAB menu.
 * The Ajustes sheet stays the configuration hub: configured and eligible goes straight into the flow
 * (fail-closed checks in ChainModel.enterPrivacy), otherwise the sheet explains and collects the endpoint.
 */
@Composable
private fun PrivacyEntryCard(c: AppController) {
    val sheets = LocalSheets.current
    val w = c.chain.wallet()
    val eligible = w != null && w.provider == com.lockwallet.custody.local.api.CustodyProviderId.LOCAL_SECURE && w.role != com.lockwallet.custody.local.storage.WalletRole.CONNECT
    val configured = c.prefs.customSpr
    Row(Modifier.fillMaxWidth().glass().pressable(haptic = Haptic.TICK) {
        c.error = null; c.chain.notice = null
        if (eligible && !configured) sheets.show { PrivacyRingSheet(c) } else c.chain.enterPrivacy()
    }.padding(16.dp).testTag("privacy-entry"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).glass(CircleShape), contentAlignment = Alignment.Center) { Icon(Ic.LOCK, size = 20.dp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Saldo confidencial", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    !eligible -> "Indisponível para esta carteira"
                    !configured -> "Configuração necessária"
                    else -> "Valor protegido nas transferências"
                },
                color = C.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(Ic.CHEVRON, C.dim, 18.dp)
    }
}

@Composable
private fun BackupCard(c: AppController, w: WalletInfo) =
    Row(Modifier.fillMaxWidth().glass().pressable { c.exportResult = null; c.go(Screen.Authorize(AuthPurpose.ExportLwrp(w.id))) }.padding(16.dp).testTag("backup-card"),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(C.text), contentAlignment = Alignment.Center) { Icon(Ic.SHIELD, C.bg, 22.dp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Faça o backup", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp)); Chip("Obrigatório", inverted = true)
            }
        }
        HelpIcon(Help.BACKUP_REQUIRED, 16.dp)
    }

@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    var widthPx by remember { mutableIntStateOf(0) }
    val seg = if (options.isEmpty()) 0 else widthPx / options.size
    val x by androidx.compose.animation.core.animateIntAsState(seg * selected, spring(dampingRatio = 0.75f, stiffness = 500f), label = "seg")
    Box(Modifier.fillMaxWidth().height(44.dp).glass(CircleShape).padding(4.dp).onSizeChanged { widthPx = it.width }.then(modifier)) {
        Box(Modifier.offset { IntOffset(x, 0) }.fillMaxHeight().fillMaxWidth(1f / options.size).clip(CircleShape).background(C.text))
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, o ->
                val col by animateColorAsState(if (i == selected) C.bg else C.dim, Motion.fade(220), label = "segc")
                Box(Modifier.weight(1f).fillMaxHeight().clip(CircleShape).pressable(haptic = Haptic.TICK) { onSelect(i) }.testTag("seg-$o"), contentAlignment = Alignment.Center) {
                    FitText(o, col, 14.sp, FontWeight.SemiBold)
                }
            }
        }
    }
}

// ======================================================================= assets (SOL + tokens)

@Composable
private fun Assets(c: AppController) {
    val v = valuation(c)
    val loading = v.lamports == null && c.chain.balance !is Loadable.Failed
    Row(verticalAlignment = Alignment.CenterVertically) { Text("Tokens", color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold); HelpIcon(Help.TOKENS, 16.dp) }
    Column(Modifier.fillMaxWidth().glass().animateContentSize(Motion.soft()).padding(vertical = 6.dp, horizontal = 4.dp)) {
        if (loading) repeat(3) { Box(Modifier.padding(horizontal = 12.dp)) { SkeletonRow() } }
        else {
            AssetRow(logo = { SolLogo(44.dp, Modifier.shared("logo-sol")) }, name = "Solana", sub = if (c.hideBalance) "••••" else (v.lamports?.let { Fmt.sol(it) + " SOL" } ?: "—"),
                right = if (c.hideBalance) "••••" else (v.liquidValue?.let { formatFiat(it, v.fiat!!) } ?: ""), change = v.change.takeIf { !c.hideBalance }, tag = "asset-sol") { c.error = null; c.go(Screen.Send()) }
            TokenRows(c)
        }
    }
}

@Composable
fun AssetRow(logo: @Composable () -> Unit, name: String, sub: String, right: String, change: BigDecimal?, tag: String? = null, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).pressable(haptic = Haptic.TICK) { onClick() }.padding(horizontal = 12.dp, vertical = 10.dp)
        .then(if (tag != null) Modifier.testTag(tag) else Modifier), verticalAlignment = Alignment.CenterVertically) {
        logo(); Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(sub, color = C.dim, fontSize = 13.sp, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            if (right.isNotEmpty()) AnimatedNumber(right, 16, C.text, FontWeight.SemiBold)
            if (change != null) {
                val col by animateColorAsState(if (change.signum() >= 0) C.up else C.down, Motion.soft(), label = "rowchg")
                AnimatedNumber(formatPct(change), 13, col, FontWeight.Medium)
            }
        }
    }

/** Solana mark drawn as three slanted bars (monochrome, no bitmap, no network fetch). */
@Composable
fun SolLogo(size: Dp = 44.dp, modifier: Modifier = Modifier) {
    val fg = C.bg
    Box(modifier.size(size).clip(CircleShape).background(C.text), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * 0.5f)) {
            val w = this.size.width; val h = this.size.height; val bar = h * 0.2f; val skew = w * 0.18f
            for (k in 0..2) {
                val y = k * (h - bar) / 2
                val path = Path().apply {
                    if (k == 1) { moveTo(0f, y); lineTo(w - skew, y); lineTo(w, y + bar); lineTo(skew, y + bar) }
                    else { moveTo(skew, y); lineTo(w, y); lineTo(w - skew, y + bar); lineTo(0f, y + bar) }
                    close()
                }
                drawPath(path, fg)
            }
        }
    }
}

// ======================================================================= activity

@Composable
fun ActivityList(c: AppController, p: Platform) {
    val items = c.chain.activity
    Row(verticalAlignment = Alignment.CenterVertically) { Text("Atividade", color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold); HelpIcon(Help.TX_STATUS, 16.dp) }
    if (items.isEmpty()) {
        Column(Modifier.fillMaxWidth().glass().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Ic.HISTORY, C.dim, 28.dp)
            Body(if (c.chain.balance is Loadable.Failed) "Sem conexão agora." else "Nenhuma transação ainda.", align = TextAlign.Center)
        }
        return
    }
    Column(Modifier.fillMaxWidth().glass().padding(vertical = 6.dp, horizontal = 4.dp)) {
        items.take(15).forEach { a -> ActivityRow(a) { if (a.local != null) c.go(Screen.TxStatus(a.signature)) else a.url?.let(p.openUrl) } }
    }
}

@Composable
private fun ActivityRow(a: Activity, onClick: () -> Unit) {
    val incoming = a.amount?.startsWith("+") == true
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).pressable(haptic = Haptic.TICK) { onClick() }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
            Icon(when { a.failed -> Ic.X_CIRCLE; incoming -> Ic.ARROW_DOWN; a.amount?.startsWith("−") == true || a.amount?.startsWith("-") == true -> Ic.ARROW_UP; else -> Ic.BOLT }, size = 18.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.title, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(a.status + (a.timeMs?.let { " · " + java.text.SimpleDateFormat("dd MMM, HH:mm", PT).format(java.util.Date(it)) } ?: ""), color = C.dim, fontSize = 12.sp)
        }
        a.amount?.let { Text(it, color = if (a.failed) C.dim else C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun QuarantinedList(c: AppController) {
    val q = c.wallets.filter { it.state == WalletState.QUARANTINED }
    if (q.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) { Label("Em quarentena"); HelpIcon(Help.QUARANTINE, 14.dp) }
    Column(Modifier.fillMaxWidth().glass()) { q.forEach { w -> ListRow(w.name, Ic.WARN, shortAddress(w.address.toBase58())) { c.go(Screen.Detail(w.id)) } } }
}

// ======================================================================= receive

/** Receive: address + QR (always dark modules on white for scanner compatibility). */
@Composable
fun Receive(c: AppController, p: Platform) {
    val active = c.wallets.filter { it.state == WalletState.ACTIVE }
    var sel by rememberSaveable { mutableIntStateOf(maxOf(0, active.indexOfFirst { it.id == c.chain.wallet()?.id })) }
    Screen("Receber", onBack = { c.home() }, help = Help.ADDRESS) {
        val w = active.getOrNull(sel) ?: return@Screen
        if (active.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            active.take(5).forEachIndexed { i, x -> Chip(x.name.take(14), inverted = i == sel) { sel = i } }
        }
        val address = w.address.toBase58()
        val matrix = remember(address) {
            QRCodeWriter().encode(address, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H))
        }
        val appear by animateFloatAsState(1f, spring(dampingRatio = 0.6f, stiffness = 300f), label = "qr")
        Column(Modifier.fillMaxWidth().glass().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(250.dp).graphicsLayer { scaleX = appear; scaleY = appear }.clip(RoundedCornerShape(24.dp)).background(Color.White).padding(14.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().testTag("qr")) {
                    val cell = size.width / matrix.width
                    for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix[x, y]) {
                        drawRoundRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f), androidx.compose.ui.geometry.CornerRadius(cell * 0.2f))
                    }
                }
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(Color.Black), contentAlignment = Alignment.Center) { Icon(Ic.LOCK, Color.White, 18.dp) }
                }
            }
            Text(w.name, color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Mono(groupedAddress(address), C.text, 15, Modifier.fillMaxWidth())
        }
        Primary(S.COPY_ADDRESS, icon = Ic.COPY, tag = "copy") { p.copyAddress(address) }
    }
}
