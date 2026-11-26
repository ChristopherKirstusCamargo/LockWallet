@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.lockwallet.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.key
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.graphics.graphicsLayer
import com.lockwallet.app.Curated
import com.lockwallet.app.CuratedStatus
import com.lockwallet.app.CuratedView
import com.lockwallet.app.VALIDATORS_TTL_MS
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockwallet.app.AppController
import com.lockwallet.app.Loadable
import com.lockwallet.app.Screen
import com.lockwallet.app.Tab
import com.lockwallet.app.valueOrNull
import com.lockwallet.core.solana.Amounts
import com.lockwallet.core.solana.state.StakeLifecycle
import com.lockwallet.data.rpc.StakeView
import com.lockwallet.data.rpc.ValidatorInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun StakeTab(c: AppController, p: Platform) {
    LaunchedEffect(c.foreground, c.chain.wallet()?.id, c.chain.config) {
        if (!c.foreground) return@LaunchedEffect
        while (isActive) { c.chain.safely { c.chain.refreshStakes() }; delay(30_000) }
    }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) { Title("Stake"); HelpIcon(Help.STAKE, 22.dp) }
    val w = c.chain.wallet()
    if (w == null) { Card { Body(S.NO_WALLET, C.text) }; return }
    val st = c.chain.stakes
    val data = st.valueOrNull()
    val list = data?.first.orEmpty()
    val v = valuation(c)
    Column(Modifier.fillMaxWidth().glass().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label("Total em stake")
        if (data == null && st !is Loadable.Failed) Skeleton(Modifier.size(180.dp, 44.dp))
        else {
            val total = list.sumOf { it.lamports }
            AnimatedNumber(if (c.hideBalance) "••••" else Compact.sol(total, Compact.enabled), 36)
            if (!c.hideBalance) v.price?.let { pr -> AnimatedNumber(formatFiat(Amounts.toDecimal(total, 9).multiply(pr), v.fiat!!), 15, C.dim, FontWeight.Normal) }
        }
        data?.second?.let { ep ->
            val frac = if (ep.slotsInEpoch > 0) (ep.slotIndex.toBigDecimal().divide(ep.slotsInEpoch.toBigDecimal(), 4, java.math.RoundingMode.DOWN)).toFloat() else 0f
            val anim by animateFloatAsState(frac, Motion.soft(), label = "epoch")
            SpacerH(6.dp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Época ${ep.epoch}", color = C.dim, fontSize = 13.sp); HelpIcon(Help.EPOCH, 14.dp)
                Spacer(Modifier.weight(1f)); Text("${(frac * 100).toInt()}%", color = C.dim, fontSize = 13.sp)
            }
            Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(C.glass)) {
                Box(Modifier.fillMaxWidth(anim).fillMaxHeight().clip(CircleShape).background(C.text))
            }
        }
        if (st is Loadable.Failed) Text("Sem conexão", color = C.dim, fontSize = 12.sp)
    }
    Primary("Novo stake", icon = Ic.PLUS, tag = "stake-new") { c.error = null; c.go(Screen.StakeNew) }
    if (data == null && st !is Loadable.Failed) Column(Modifier.fillMaxWidth().glass().padding(12.dp)) { repeat(2) { SkeletonRow() } }
    else if (list.isEmpty()) Column(Modifier.fillMaxWidth().glass().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Ic.STAKE, C.dim, 30.dp)
        Body("Nenhum stake ainda.")
    } else Column(Modifier.fillMaxWidth().glass().animateContentSize(Motion.soft()).padding(vertical = 6.dp, horizontal = 4.dp)) {
        list.forEach { s -> StakeRow(c, s) { c.error = null; c.go(Screen.StakeAccount(s.address.toBase58())) } }
    }
}

@Composable
private fun StakeRow(c: AppController, v: StakeView, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).pressable(haptic = Haptic.TICK) { onClick() }.padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).glass(CircleShape).shared("stake-${v.address.toBase58()}"), contentAlignment = Alignment.Center) { Icon(Ic.STAKE, size = 20.dp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(v.state.voter?.let { vo -> com.lockwallet.app.Curated.LIST.firstOrNull { it.vote == vo.toBase58() }?.name ?: ("Validador " + TxText.short(vo)) } ?: "Sem delegação",
                color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(
                S.lifecycle(v.lifecycle) + (v.lastReward?.let { " · " + if (c.hideBalance) "••••" else "+${Compact.sol(it.amount, Compact.enabled)} na última época" } ?: ""),
                color = C.dim,
                fontSize = 12.sp,
            )
        }
        Text(if (c.hideBalance) "••••" else Compact.sol(v.lamports, Compact.enabled), color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
fun StakeNew(c: AppController) {
    // Live health: re-read the vote accounts while this screen is open (network work runs on IO inside the model).
    LaunchedEffect(Unit) { while (isActive) { c.chain.safely { c.chain.loadValidators() }; delay(VALIDATORS_TTL_MS) } }
    var amount by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<String?>(null) }
    var advanced by remember { mutableStateOf(false) }
    val curated = c.chain.curated
    // A curated pick that turned unhealthy while the screen was open is dropped, never kept silently.
    LaunchedEffect(curated) { val p0 = picked; if (p0 != null) curated.firstOrNull { it.entry.vote == p0 }?.let { if (!it.selectable) picked = null } }
    Screen("Novo stake", onBack = { c.home(); c.tab = Tab.STAKE }, help = Help.STAKE, bottom = {
        ErrorText(c.error)
        if (c.busy) Busy("Preparando e testando…") else Primary("Revisar", enabled = amount.isNotBlank() && picked != null, tag = "review") { c.chain.reviewStakeCreate(amount, picked!!) }
    }) {
        val bal = c.chain.balance.valueOrNull()
        AmountEntry(
            amount, { amount = it }, "SOL", "stake-amount",
            fiatHint = if (c.hideBalance) "Disponível: •••• SOL" else bal?.let { "Disponível: ${Fmt.sol(it)} SOL" },
            onMax = null,
        )
        Row(verticalAlignment = Alignment.CenterVertically) { Label("Mínimo", modifier = Modifier.weight(1f)); HelpIcon(Help.MIN_STAKE, 16.dp) }
        Row(verticalAlignment = Alignment.CenterVertically) { Label("Recomendados", modifier = Modifier.weight(1f)); HelpIcon(Help.CURATED_ORDER, 16.dp); HelpIcon(Help.CURATED, 16.dp) }
        val vs = c.chain.validators
        when {
            curated.isNotEmpty() -> {
                val visible = curated.filterNot { it.hidden }
                Column(Modifier.fillMaxWidth().glass().padding(vertical = 6.dp, horizontal = 4.dp).animateContentSize(Motion.soft()).testTag("curated-block")) {
                    visible.forEachIndexed { i, v -> key(v.entry.vote) { CuratedRow(v, i, picked == v.entry.vote) { picked = v.entry.vote } } }
                    if (visible.isEmpty()) Body(S.curatedHidden(curated.size), modifier = Modifier.padding(14.dp))
                }
                val hidden = curated.count { it.hidden }
                if (hidden in 1 until curated.size) Notice(S.curatedHidden(hidden), Ic.INFO, Help.CURATED)
            }
            vs is Loadable.Failed -> ErrorText(vs.message)
            else -> Column(Modifier.fillMaxWidth().glass().padding(12.dp)) { repeat(5) { SkeletonRow() } }
        }
        Secondary(if (advanced) "Fechar avançado" else "Avançado", icon = if (advanced) Ic.CLOSE else Ic.SETTINGS, tag = "advanced") {
            advanced = !advanced
            if (!advanced) { filter = ""; if (picked != null && Curated.find(picked!!) == null) picked = null }
        }
        AnimatedVisibility(advanced, enter = expandVertically(Motion.soft()) + fadeIn(Motion.fade()), exit = shrinkVertically(Motion.soft()) + fadeOut(Motion.fade())) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Notice(S.ADVANCED_WARNING, Ic.WARN, Help.ADVANCED_STAKE, strong = true)
                Field(filter, { filter = it.trim().take(44) }, "Buscar ou colar endereço de voto", trailing = { Icon(Ic.SEARCH, C.dim, 18.dp, Modifier.padding(end = 12.dp)) })
                val p0 = picked
                if (p0 != null && Curated.find(p0) == null) Notice(S.NOT_CURATED, Ic.WARN, Help.VALIDATOR)
                when (vs) {
                    is Loadable.Ready -> {
                        val shown = vs.value.filter { filter.isEmpty() || it.vote.toBase58().startsWith(filter) || it.node.toBase58().startsWith(filter) }.take(40)
                        Row(verticalAlignment = Alignment.CenterVertically) { Body("Todos os validadores", modifier = Modifier.weight(1f)); HelpIcon(Help.VALIDATOR, 14.dp) }
                        Column(Modifier.fillMaxWidth().glass().padding(vertical = 6.dp, horizontal = 4.dp)) {
                            shown.forEach { v -> ValidatorRow(v, picked == v.vote.toBase58()) { picked = v.vote.toBase58() } }
                        }
                    }
                    is Loadable.Failed -> ErrorText(vs.message)
                    else -> Column(Modifier.fillMaxWidth().glass().padding(12.dp)) { repeat(4) { SkeletonRow() } }
                }
            }
        }
    }
}

@Composable
private fun CuratedRow(v: CuratedView, index: Int, selected: Boolean, onClick: () -> Unit) {
    // Staggered rise-in (draw-phase only: graphicsLayer reads the animated value, no recomposition per frame).
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(40L * index); enter.animateTo(1f, Motion.gentle()) }
    val ring by animateColorAsState(if (selected) C.text else Color.Transparent, Motion.soft(), label = "ring")
    val fill by animateColorAsState(if (selected) C.glassStrong else Color.Transparent, Motion.soft(), label = "fill")
    val alpha = if (v.selectable) 1f else 0.45f
    val live = v.live
    Row(Modifier.fillMaxWidth().graphicsLayer { val e = enter.value; this.alpha = alpha * e; translationY = (1f - e) * 14.dp.toPx() }.clip(RoundedCornerShape(18.dp)).background(fill).border(1.5.dp, ring, RoundedCornerShape(18.dp))
        .pressable(enabled = v.selectable, haptic = Haptic.TICK) { onClick() }.padding(horizontal = 12.dp, vertical = 10.dp).testTag("curated"), verticalAlignment = Alignment.CenterVertically) {
        TokenAvatar(nameInitials(v.entry.name), 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(v.entry.name, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (live != null) {
                // Metrics wrap as whole items instead of being cut mid-word on narrow screens.
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("comissão ${Fmt.bps(v.effectiveBps ?: live.commission * 100)}", color = C.dim, fontSize = 12.sp, maxLines = 1)
                    v.creditRatio?.let { Text("desempenho ${Fmt.pct1(it)}", color = C.dim, fontSize = 12.sp, maxLines = 1) }
                }
                Text("${Fmt.solWhole(live.activatedStake)} SOL em stake", color = C.dim, fontSize = 12.sp, maxLines = 1)
            }
            when (v.status) {
                CuratedStatus.CONCENTRATED -> Chip("concentrado", Modifier.padding(top = 4.dp), icon = Ic.INFO)
                CuratedStatus.COMMISSION_UP -> Chip("comissão subiu", Modifier.padding(top = 4.dp), icon = Ic.WARN)
                else -> {}
            }
        }
        if (v.selectable) Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp).testTag("curated-return")) {
            Text(v.returnIndex?.let { Fmt.pct1(it) } ?: "—", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("retorno", color = C.dim, fontSize = 11.sp, maxLines = 1)
        }
        AnimatedVisibility(selected, enter = scaleIn(Motion.bouncy()) + fadeIn(), exit = scaleOut(Motion.snappy()) + fadeOut()) { Icon(Ic.CHECK, size = 18.dp, modifier = Modifier.padding(start = 8.dp)) }
    }
}


/** Avatar initials from a display name: first letters of the first two words, or the first two letters of one word. */
internal fun nameInitials(name: String): String {
    val words = name.split(' ', '-', '_').map { w -> w.filter { it.isLetterOrDigit() } }.filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

@Composable
private fun ValidatorRow(v: ValidatorInfo, selected: Boolean, onClick: () -> Unit) {
    val ring = if (selected) C.text else Color.Transparent
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (selected) C.glass else Color.Transparent).border(1.5.dp, ring, RoundedCornerShape(18.dp))
        .pressable(haptic = Haptic.TICK) { onClick() }.padding(horizontal = 12.dp, vertical = 10.dp).testTag("validator"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).glass(CircleShape), contentAlignment = Alignment.Center) { Icon(Ic.STAKE, size = 18.dp) }
        Spacer(Modifier.width(12.dp))
        // Unlisted validators have no verified name: lead with the decision facts, the vote account as a small reference.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("comissão ${v.commission}% · ${Fmt.solWhole(v.activatedStake)} SOL", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text("conta de voto " + TxText.short(v.vote), color = C.dim, fontSize = 12.sp, maxLines = 1, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
        if (v.delinquent) Chip("fora do ar", icon = Ic.WARN)
        if (selected) Icon(Ic.CHECK, size = 18.dp)
    }
}

@Composable
fun StakeAccountScreen(c: AppController, p: Platform, address: String) {
    val v = c.chain.stake(address)
    val me = c.chain.wallet()?.address
    var split by remember { mutableStateOf("") }
    var showSplit by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { c.chain.safely { c.chain.loadValidators() } }
    Screen("", onBack = { c.home(); c.tab = Tab.STAKE }, help = Help.STAKE_STATES) {
        if (v == null || me == null) { Body(S.STAKE_GONE); return@Screen }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(C.text).shared("stake-$address"), contentAlignment = Alignment.Center) { Icon(Ic.STAKE, C.bg, 28.dp) }
            AnimatedNumber(if (c.hideBalance) "••••" else Compact.sol(v.lamports, Compact.enabled), 36)
            Text(S.lifecycle(v.lifecycle), color = C.dim, fontSize = 14.sp)
        }
        StakeTimeline(v.lifecycle)
        Group {
            DetailLine("Delegado", if (c.hideBalance) "••••" else Compact.sol(v.state.delegatedStake, Compact.enabled))
            DetailLine("Depósito da conta", if (c.hideBalance) "••••" else Compact.sol(v.state.rentExemptReserve, Compact.enabled), Help.RENT)
            v.state.voter?.let { vote ->
                val info = c.chain.validator(vote)
                DetailLine("Validador", TxText.short(vote), Help.VALIDATOR)
                info?.let { DetailLine("Comissão", "${it.commission}%" + if (it.delinquent) " · fora do ar" else "", Help.COMMISSION) }
            }
            v.lastReward?.let { DetailLine("Última recompensa", if (c.hideBalance) "••••" else "+${Compact.sol(it.amount, Compact.enabled)}") }
            DetailLine("Controle", if (v.controlledBy(me)) "você" else "outra conta")
        }
        if (!v.controlledBy(me)) Notice(S.STAKE_NOT_CONTROLLED, Ic.LOCK)
        ErrorText(c.error)
        if (c.busy) { Busy(); return@Screen }
        if (!v.controlledBy(me) || v.state.hasLockup) return@Screen
        when (v.lifecycle) {
            StakeLifecycle.ACTIVE, StakeLifecycle.ACTIVATING -> Primary("Desativar", tag = "deactivate") { c.chain.reviewDeactivate(address) }
            StakeLifecycle.INACTIVE, StakeLifecycle.INITIALIZED -> Primary(
                if (c.hideBalance) "Sacar stake" else "Sacar ${Compact.sol(v.lamports, Compact.enabled)}",
                tag = "withdraw",
            ) { c.chain.reviewWithdraw(address) }
            StakeLifecycle.DEACTIVATING -> Notice("Desativando", Ic.CLOCK, Help.EPOCH)
        }
        if (showSplit) {
            AmountEntry(split, { split = it }, "SOL", "split-amount", onMax = null)
            Primary("Revisar divisão", enabled = split.isNotBlank()) { c.chain.reviewSplit(address, split) }
        } else if (v.lifecycle != StakeLifecycle.DEACTIVATING) Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton("Dividir", Modifier.weight(1f), icon = Ic.SPLIT, filled = false) { showSplit = true }; HelpIcon(Help.SPLIT, 18.dp)
        }
        val merge = c.chain.mergeCandidates(v, me)
        if (merge.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) { Label("Juntar outra conta nesta"); HelpIcon(Help.MERGE, 14.dp) }
            merge.forEach { m ->
                Secondary(
                    "Juntar ${TxText.short(m.address)} · ${if (c.hideBalance) "••••" else Compact.sol(m.lamports, Compact.enabled)}",
                    icon = Ic.MERGE,
                ) { c.chain.reviewMerge(address, m.address.toBase58()) }
            }
        }
        c.chain.config.cluster.explorerAddress(address)?.let { url -> Secondary("Ver no explorador", icon = Ic.GLOBE) { p.openUrl(url) } }
    }
}

/** Lifecycle: ativando → ativo → desativando → inativo (animated, monochrome). */
@Composable
private fun StakeTimeline(l: StakeLifecycle) {
    val steps = listOf("ativando", "ativo", "desativando", "inativo")
    val at = when (l) { StakeLifecycle.INITIALIZED -> -1; StakeLifecycle.ACTIVATING -> 0; StakeLifecycle.ACTIVE -> 1; StakeLifecycle.DEACTIVATING -> 2; StakeLifecycle.INACTIVE -> 3 }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { i, label ->
            val a by animateFloatAsState(if (i <= at) 1f else 0.2f, Motion.soft(), label = "tl")
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(C.text.copy(alpha = a)))
                Text(label, color = if (i == at) C.text else C.dim, fontSize = 11.sp, fontWeight = if (i == at) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}
