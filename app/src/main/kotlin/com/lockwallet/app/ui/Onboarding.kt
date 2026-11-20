package com.lockwallet.app.ui

import kotlinx.coroutines.launch
import com.lockwallet.app.biometricAllowed
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockwallet.app.AppController
import com.lockwallet.app.FlowMode
import com.lockwallet.app.RestoreKind
import com.lockwallet.app.Screen
import com.lockwallet.custody.local.keystore.AuthMode
import com.lockwallet.custody.local.BiometricAvailability
import com.lockwallet.custody.local.storage.WalletRole
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ======================================================================= welcome

/** First run: animated security emblem, followed by the create and restore actions. */
@Composable
fun Welcome(c: AppController) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            CircleButton(Ic.SHIELD, S.SECURITY_CENTER) { c.go(Screen.SecurityCenter) }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { Orbit() }
        // Staggered entrance: title, tagline, then the actions rise in with a soft spring.
        val enter = remember { List(3) { Animatable(0f) } }
        LaunchedEffect(Unit) { enter.forEachIndexed { i, a -> launch { delay(120L + i * 90L); a.animateTo(1f, Motion.gentle()) } } }
        fun Modifier.rise(i: Int) = graphicsLayer { val v = enter[i].value; alpha = v; translationY = (1f - v) * 28.dp.toPx() }
        Text("Lock Wallet", color = C.text, fontSize = 40.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp, maxLines = 1, modifier = Modifier.rise(0))
        SpacerH(8.dp)
        Body("Sua chave, seu dinheiro.\nSolana com segurança de cofre.", align = TextAlign.Center, modifier = Modifier.rise(1))
        SpacerH(32.dp)
        Column(Modifier.fillMaxWidth().rise(2), horizontalAlignment = Alignment.CenterHorizontally) {
            PillButton("Criar uma carteira", Modifier.fillMaxWidth(), tag = "create") { c.startFlow(FlowMode.CREATE) }
            SpacerH(6.dp)
            TextAction("Adicionar uma carteira existente", Modifier.fillMaxWidth().testTag("restore")) { c.startFlow(FlowMode.RESTORE) }
        }
        SpacerH(8.dp)
    }
}

private val ORBIT_ICONS = listOf(
    listOf(Ic.KEY, Ic.SHIELD, Ic.COIN),
    listOf(Ic.STAKE, Ic.FINGER, Ic.GLOBE, Ic.SEND),
    listOf(Ic.WALLET, Ic.BOLT, Ic.RECEIVE, Ic.APPS, Ic.LOCK),
)

@Composable
private fun Orbit() {
    val t = rememberInfiniteTransition(label = "orbit")
    val turn by t.animateFloat(0f, 360f, infiniteRepeatable(tween(60_000, easing = LinearEasing)), label = "turn")
    val breath by t.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "breath")
    val intro = remember { Animatable(0f) }
    LaunchedEffect(Unit) { intro.animateTo(1f, Motion.bouncy()) }
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, 360.dp)
        val px = with(LocalDensity.current) { side.toPx() }
        val radii = listOf(0.22f, 0.34f, 0.47f).map { it * px }
        val ring = C.glassBorder
        Canvas(Modifier.size(side)) { radii.forEach { r -> drawCircle(ring, r * intro.value, Offset(size.width / 2, size.height / 2), style = Stroke(1.dp.toPx())) } }
        ORBIT_ICONS.forEachIndexed { ri, icons ->
            val dir = if (ri % 2 == 0) 1f else -1f
            icons.forEachIndexed { i, ic ->
                val a = Math.toRadians((turn * dir * (1f + ri * 0.35f) + i * 360f / icons.size + ri * 40f).toDouble())
                val r = radii[ri] * intro.value
                val bubble = (40 + 6 * (2 - ri)).dp
                Box(Modifier.offset { IntOffset((r * cos(a)).roundToInt(), (r * sin(a)).roundToInt()) }
                    .size(bubble).scale(intro.value).glass(CircleShape, strong = true), contentAlignment = Alignment.Center) { Icon(ic, size = bubble * 0.45f) }
            }
        }
        Box(Modifier.size(104.dp).scale(breath * intro.value).clip(CircleShape).background(C.text), contentAlignment = Alignment.Center) {
            Icon(Ic.LOCK, C.bg, 48.dp)
        }
    }
}

// ======================================================================= create / restore steps

/** Step header: small progress dots + big title + subtitle. */
@Composable
private fun Steps(step: Int, total: Int) = Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    repeat(total) { i ->
        val w by androidx.compose.animation.core.animateDpAsState(if (i == step) 22.dp else 6.dp, Motion.bouncy(), label = "step")
        Box(Modifier.size(w, 6.dp).clip(CircleShape).background(if (i <= step) C.text else C.faint))
    }
}

@Composable
fun CustodyChoice(c: AppController) {
    val svOk = remember { c.seedVaultAvailable }
    Screen(S.CUSTODY_TITLE, onBack = { c.home() }, help = Help.ONBOARD_CUSTODY) {
        Steps(0, 5)
        ChoiceCard(S.CUSTODY_LOCAL, S.CUSTODY_LOCAL_DESC, Ic.PHONE, help = Help.CUSTODY_LOCAL, tag = "custody-local") { c.go(Screen.Role) }
        ChoiceCard(S.CUSTODY_SEED_VAULT, if (svOk) S.SV_DESC else S.SV_NOT_HERE, Ic.CHIP, enabled = svOk, help = Help.CUSTODY_SEED_VAULT, tag = "custody-sv") {
            if (svOk) c.go(Screen.SeedVaultSetup)
        }
    }
}

@Composable
fun SeedVaultSetup(c: AppController) {
    var role by remember { mutableStateOf(WalletRole.VAULT) }
    var name by remember { mutableStateOf("Seed Vault") }
    Screen(S.CUSTODY_SEED_VAULT, onBack = { c.go(Screen.Custody) }, help = Help.CUSTODY_SEED_VAULT) {
        ChoiceCard(S.ROLE_VAULT, S.ROLE_VAULT_DESC, Ic.LOCK, role == WalletRole.VAULT, help = Help.ROLE_VAULT) { role = WalletRole.VAULT }
        ChoiceCard(S.ROLE_CONNECT, S.ROLE_CONNECT_DESC + " " + S.SV_CONNECT_NOTE, Ic.APPS, role == WalletRole.CONNECT, help = Help.ROLE_CONNECT) { role = WalletRole.CONNECT }
        Field(name, { if (it.length <= 32) name = it }, S.NAME_HINT)
        ErrorText(c.error)
        if (c.busy) Busy() else {
            Primary("Usar uma semente do Seed Vault") { c.addSeedVaultWallet(role, name, com.lockwallet.custody.seedvault.SeedSource.AUTHORIZE_EXISTING) }
            Secondary("Criar nova semente") { c.addSeedVaultWallet(role, name, com.lockwallet.custody.seedvault.SeedSource.CREATE_NEW) }
            Secondary("Importar frase no Seed Vault") { c.addSeedVaultWallet(role, name, com.lockwallet.custody.seedvault.SeedSource.IMPORT) }
        }
    }
}

@Composable
fun RoleChoice(c: AppController) {
    val d = c.draft ?: return
    Screen(S.ROLE_TITLE, onBack = { c.go(Screen.Custody) }, help = Help.ROLE_VAULT) {
        Steps(1, 5)
        val next = { r: WalletRole -> d.role = r; c.go(if (d.mode == FlowMode.CREATE) Screen.Length else Screen.RestoreMethod) }
        ChoiceCard(S.ROLE_VAULT, S.ROLE_VAULT_DESC, Ic.LOCK, d.role == WalletRole.VAULT, help = Help.ROLE_VAULT, tag = "role-vault") { next(WalletRole.VAULT) }
        ChoiceCard(S.ROLE_CONNECT, S.ROLE_CONNECT_DESC, Ic.APPS, d.role == WalletRole.CONNECT, help = Help.ROLE_CONNECT, tag = "role-connect") { next(WalletRole.CONNECT) }
    }
}

@Composable
fun LengthChoice(c: AppController) {
    val d = c.draft ?: return
    Screen(S.LENGTH_TITLE, onBack = { c.go(Screen.Role) }, help = Help.PHRASE_LENGTH) {
        Steps(2, 5)
        ChoiceCard(S.LENGTH_12, "Mais fácil de anotar sem erro", Ic.FILE, d.words == 12, tag = "len-12") { d.words = 12; c.go(Screen.Warning) }
        ChoiceCard(S.LENGTH_24, "Margem extra para grandes reservas", Ic.SHIELD, d.words == 24, tag = "len-24") { d.words = 24; c.go(Screen.Warning) }
    }
}

@Composable
fun Warning(c: AppController) = Screen(S.WARN_TITLE, onBack = { c.go(Screen.Length) }, help = Help.PHRASE,
    bottom = { ErrorText(c.error); if (c.busy) Busy() else Primary(S.WARN_ACK, tag = "warn-ack") { c.generatePhrase() } }) {
    Steps(3, 5)
    val icons = listOf(Ic.KEY, Ic.EYE_OFF, Ic.PERSON, Ic.FILE)
    S.WARN_POINTS.forEachIndexed { i, t ->
        Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).glass(CircleShape), contentAlignment = Alignment.Center) { Icon(icons[i % icons.size], size = 20.dp) }
            Spacer(Modifier.width(14.dp)); Text(t, color = C.text, fontSize = 15.sp, lineHeight = 21.sp)
        }
    }
}

@Composable
fun ShowPhrase(c: AppController) {
    val m = c.draft?.material ?: return
    val words = remember(m) { m.words() }
    DisposableEffect(words) { onDispose { words.forEach { it.fill('\u0000') } } }
    Screen(S.PHRASE_TITLE, onBack = { c.go(Screen.Warning) }, help = Help.PHRASE,
        bottom = { if (c.busy) Busy() else Primary(S.PHRASE_WROTE, tag = "phrase-wrote") { c.beginQuiz() } }) {
        Steps(4, 5)
        WordGrid(words)
    }
}

@Composable
fun Quiz(c: AppController) {
    val d = c.draft ?: return
    val m = d.material ?: return
    var step by remember { mutableIntStateOf(0) }
    var wrong by remember { mutableStateOf(false) }
    val h = androidx.compose.runtime.rememberUpdatedState(LocalHaptics.current)
    Screen(S.QUIZ_TITLE, onBack = { c.go(Screen.ShowPhrase) }, help = Help.QUIZ) {
        Steps(4, 5)
        Body("Pergunta ${step + 1} de ${d.quiz.size}")
        AnimatedContent(step, transitionSpec = {
            (slideInHorizontally(Motion.slide) { it / 2 } + fadeIn()) togetherWith (slideOutHorizontally(Motion.slide) { -it / 2 } + fadeOut())
        }, label = "quiz") { st ->
            val q = d.quiz.getOrNull(st) ?: return@AnimatedContent
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(S.quizQuestion(q.position), color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                q.options.forEachIndexed { i, opt ->
                    Row(Modifier.fillMaxWidth().glass(CircleShape).pressable {
                        if (m.isWordAt(q.position, opt)) { wrong = false; h.value(Haptic.CONFIRM); if (st + 1 < d.quiz.size) step++ else c.quizPassed() }
                        else { wrong = true; step = 0; c.go(Screen.ShowPhrase) }
                    }.padding(horizontal = 22.dp, vertical = 14.dp).testTag("quiz-opt-$i"), verticalAlignment = Alignment.CenterVertically) {
                        SecretChars(opt, Modifier.weight(1f)); Icon(Ic.CHEVRON, C.dim, 18.dp)
                    }
                }
            }
        }
        if (wrong) ErrorText(S.QUIZ_WRONG)
    }
}

@Composable
fun RestoreMethod(c: AppController) = Screen(S.RESTORE_TITLE, onBack = { c.go(Screen.Role) }, help = Help.RESTORE_PRIVACY) {
    ChoiceCard(S.R_PHRASE, "12 a 24 palavras em inglês", Ic.FILE, help = Help.PHRASE, tag = "r-phrase") { c.go(Screen.RestoreInput(RestoreKind.PHRASE)) }
    ChoiceCard(S.R_LWRP, "Arquivo de backup da Lock Wallet + senha", Ic.SHIELD, help = Help.LWRP) { c.go(Screen.RestoreInput(RestoreKind.LWRP)) }
    ChoiceCard(S.R_PRIVKEY, "Uma única conta, sem frase", Ic.KEY, help = Help.PRIVATE_KEY) { c.go(Screen.RestoreInput(RestoreKind.PRIVATE_KEY)) }
    ChoiceCard(S.R_JSON, "Arquivo id.json de ferramentas Solana", Ic.CHIP, help = Help.KEYPAIR_JSON) { c.go(Screen.RestoreInput(RestoreKind.KEYPAIR_JSON)) }
}

/** Local plausibility only (word count); the full BIP-39 checksum runs in custody on Continuar. */
private fun phraseLooksComplete(t: String): Boolean = t.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size in setOf(12, 15, 18, 21, 24)

/**
 * Text input for restore. Compose TextField state is a String (platform limitation, ADR 0013): it is cleared
 * as soon as the value is handed to custody as a CharArray. Password keyboard ⇒ no suggestions/learning.
 * "Colar" reads the clipboard once and then wipes it (other apps/keyboards cannot read the phrase again).
 */
@Composable
fun RestoreInput(c: AppController, p: Platform, kind: RestoreKind) {
    var text by remember { mutableStateOf("") }
    var file by remember { mutableStateOf<ByteArray?>(null) }
    DisposableEffect(Unit) { onDispose { text = ""; file?.fill(0) } }
    val (title, sub, help) = when (kind) {
        RestoreKind.PHRASE -> Triple("Digite sua frase de recuperação", "As palavras separadas por espaço, na ordem.", Help.PHRASE)
        RestoreKind.LWRP -> Triple("Recovery Package", "Escolha o arquivo .lwrp e digite a senha dele.", Help.LWRP)
        RestoreKind.PRIVATE_KEY -> Triple("Chave privada", "Cole a chave exportada da outra carteira.", Help.PRIVATE_KEY)
        RestoreKind.KEYPAIR_JSON -> Triple("Arquivo keypair", "Escolha o arquivo .json com 64 números.", Help.KEYPAIR_JSON)
    }
    val valid = when (kind) {
        RestoreKind.PHRASE -> phraseLooksComplete(text)
        RestoreKind.PRIVATE_KEY -> text.trim().length in 80..90
        RestoreKind.KEYPAIR_JSON -> file != null
        RestoreKind.LWRP -> file != null && text.isNotEmpty()
    }
    Screen(title, onBack = { c.go(Screen.RestoreMethod) }, help = help, bottom = {
        ErrorText(c.error)
        if (c.busy) Busy() else Primary(S.CONTINUE, enabled = valid, tag = "restore-continue") {
            val chars = text.toCharArray(); text = ""
            when (kind) {
                RestoreKind.PHRASE, RestoreKind.PRIVATE_KEY -> c.submitRestore(kind, chars)
                RestoreKind.KEYPAIR_JSON -> { val f = file!!; c.submitRestore(kind, CharArray(f.size) { (f[it].toInt() and 0xff).toChar() }); chars.fill('\u0000') }
                RestoreKind.LWRP -> c.submitLwrp(file!!, chars)
            }
        }
    }) {
        val pasteChip = @Composable {
            Chip("Colar", Modifier.padding(end = 8.dp).testTag("paste"), icon = Ic.PASTE) { p.pasteSecret()?.let { text = it.trim().lowercase().replace(Regex("\\s+"), " ").take(400) } }
        }
        when (kind) {
            RestoreKind.PHRASE -> {
                Box {
                    Field(text, { text = it.take(400) }, S.PHRASE_INPUT, Modifier.height(190.dp), tag = "phrase-input", secret = true, minLines = 5)
                    Box(Modifier.align(Alignment.BottomEnd).padding(bottom = 12.dp)) { pasteChip() }
                }
                val n = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
                Text(if (n == 0) "0 palavras" else "$n palavra(s)" + if (valid) " ✓" else "", color = C.dim, fontSize = 13.sp)
            }
            RestoreKind.PRIVATE_KEY -> Field(text, { text = it.trim().take(100) }, S.PRIVKEY_INPUT, secret = true, mono = true, trailing = { pasteChip() })
            RestoreKind.KEYPAIR_JSON, RestoreKind.LWRP -> {
                ListRow(if (file == null) S.PICK_FILE else "Arquivo selecionado", Ic.FILE, subtitle = file?.let { "${it.size} bytes" }) {
                    p.openDocument { b -> file?.fill(0); file = b; if (b == null) c.error = S.BAD_FILE }
                }
                if (kind == RestoreKind.LWRP) Field(text, { text = it.take(300) }, S.LWRP_PASSWORD, secret = true)
            }
        }
    }
}

@Composable
fun NameWallet(c: AppController) {
    val d = c.draft ?: return
    var name by remember { mutableStateOf(if (d.role == WalletRole.VAULT) "Conta 1" else "Connect 1") }
    Screen(S.NAME_TITLE, onBack = { c.home() }, bottom = {
        ErrorText(c.error); if (c.busy) Busy() else Primary(S.CONTINUE, enabled = name.isNotBlank(), tag = "name-continue") { c.submitName(name) }
    }) {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) { Avatar(name, 88.dp) }
        Field(name, { if (it.length <= 32) name = it }, S.NAME_HINT, tag = "name")
        d.account?.let { Text(shortAddress(it.address.toBase58()), color = C.dim, fontSize = 14.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
    }
}

@Composable
fun PinCreate(c: AppController) {
    var first by remember { mutableStateOf<CharArray?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { first?.fill('\u0000') } }
    Screen("", onBack = { c.home() }, actions = { HelpIcon(Help.PIN, 22.dp) }) {
        AnimatedContent(first == null, transitionSpec = { (slideInHorizontally(Motion.slide) { it / 3 } + fadeIn()) togetherWith fadeOut() }, label = "pin") { isFirst ->
            PinPad(if (isFirst) S.PIN_CREATE else S.PIN_CONFIRM, msg, subtitle = null) { pin ->
                val f = first
                when {
                    f == null && isTrivialPin(pin) -> { pin.fill('\u0000'); msg = S.PIN_TRIVIAL }
                    f == null -> { first = pin; msg = null }
                    f.contentEquals(pin) -> { f.fill('\u0000'); first = null; c.setNewPin(pin) }
                    else -> { f.fill('\u0000'); pin.fill('\u0000'); first = null; msg = S.PIN_MISMATCH }
                }
            }
        }
    }
}

private fun isTrivialPin(p: CharArray): Boolean = p.all { it == p[0] } ||
    (1 until p.size).all { p[it] - p[it - 1] == 1 } || (1 until p.size).all { p[it - 1] - p[it] == 1 }

/** Biometrics opt-in: illustration, "Ativar biometria"; "Saltar" asks "Tem a certeza?" in a sheet first. */
@Composable
fun BioChoice(c: AppController) {
    val bio = remember { c.capabilities.biometricAvailability() }
    val sheets = LocalSheets.current
    val available = bio == BiometricAvailability.AVAILABLE
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CircleButton(Ic.BACK, S.BACK) { c.go(Screen.PinCreate) }
            Spacer(Modifier.weight(1f))
            if (available && !c.busy) TextAction("Saltar", Modifier.testTag("bio-skip")) {
                sheets.show {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(64.dp).glass(CircleShape), contentAlignment = Alignment.Center) { Icon(Ic.WARN, size = 30.dp) }
                    }
                    Text("Tem a certeza?", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Body(S.BIO_SKIP_WARN, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Voltar", Modifier.weight(1f), filled = false) { sheets.dismiss() }
                        PillButton("Saltar", Modifier.weight(1f), tag = "bio-skip-confirm") { sheets.dismiss(); c.sealNewVault(AuthMode.PIN_ONLY) }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { FingerIllustration() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title(if (available) "Proteja com biometria" else "Biometria indisponível", align = TextAlign.Center)
            HelpIcon(Help.BIOMETRIC, 20.dp)
        }
        SpacerH(8.dp)
        SpacerH(24.dp)
        ErrorText(c.error)
        if (c.busy) Busy() else if (available) PillButton("Ativar biometria", Modifier.fillMaxWidth(), icon = Ic.FINGER, tag = "bio-enable") { c.sealNewVault(AuthMode.PIN_AND_BIOMETRIC) }
        else PillButton(S.CONTINUE, Modifier.fillMaxWidth(), tag = "bio-continue") { c.sealNewVault(AuthMode.PIN_ONLY) }
        SpacerH(12.dp)
    }
}

@Composable
private fun FingerIllustration() {
    val t = rememberInfiniteTransition(label = "pulse")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "p")
    val ring = C.text
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(260.dp)) {
            for (k in 0..2) {
                val f = (p + k / 3f) % 1f
                drawCircle(ring.copy(alpha = (1f - f) * 0.35f), size.minDimension / 2 * (0.4f + 0.6f * f), style = Stroke(1.5.dp.toPx()))
            }
        }
        Box(Modifier.size(112.dp).glass(CircleShape, strong = true), contentAlignment = Alignment.Center) { Icon(Ic.FINGER, size = 56.dp) }
    }
}

/** Lock screen ([purpose] = null) and authorization for sensitive actions. Countdown while locked out. */
@Composable
fun LockScreen(c: AppController, purpose: com.lockwallet.app.AuthPurpose?) {
    LaunchedEffect(purpose) { c.lockMessage = null; c.refreshLockout() }
    LaunchedEffect(c.lockoutMs > 0) { while (c.lockoutMs > 0) { delay(1000); c.refreshLockout() } }
    val title = when (purpose) {
        null -> "Bem-vindo de volta"
        com.lockwallet.app.AuthPurpose.SealDraft -> "Confirme para guardar a nova carteira"
        is com.lockwallet.app.AuthPurpose.RevealPhrase -> "Confirme para ver a frase"
        is com.lockwallet.app.AuthPurpose.ExportLwrp -> "Confirme para exportar"
        is com.lockwallet.app.AuthPurpose.AddAccount -> "Confirme para adicionar a conta"
        is com.lockwallet.app.AuthPurpose.Sign -> "Confirme para assinar"
        is com.lockwallet.app.AuthPurpose.DappSign -> "Confirme o pedido do app"
        com.lockwallet.app.AuthPurpose.EnableBiometric -> "Confirme o PIN para ativar a digital"
        is com.lockwallet.app.AuthPurpose.SetBioWindow -> "Confirme o PIN para aumentar o prazo"
        is com.lockwallet.app.AuthPurpose.DeleteWallet -> "Confirme o PIN para excluir a carteira"
    }
    val sub = when (purpose) {
        null -> S.PIN_ENTER
        is com.lockwallet.app.AuthPurpose.Sign -> c.chain.prepared?.intent?.summary
        is com.lockwallet.app.AuthPurpose.DappSign -> c.connect.current?.dapp?.name?.take(32)
        else -> S.PIN_ENTER
    }
    val back: (() -> Unit)? = when (purpose) {
        null -> null
        is com.lockwallet.app.AuthPurpose.Sign -> ({ c.go(Screen.Review) })
        is com.lockwallet.app.AuthPurpose.DappSign -> ({ c.go(Screen.Home) })
        else -> ({ c.home() })
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (back != null) CircleButton(Ic.BACK, S.BACK, onClick = back) else Spacer(Modifier.size(44.dp))
            Spacer(Modifier.weight(1f))
            if (purpose == null) CircleButton(Ic.SHIELD, S.SECURITY_CENTER, tag = "lock-security") { c.go(Screen.SecurityCenter) }
        }
        Spacer(Modifier.weight(0.5f))
        Box(Modifier.size(72.dp).clip(CircleShape).background(C.text).graphicsLayer { }, contentAlignment = Alignment.Center) { Icon(Ic.LOCK, C.bg, 34.dp) }
        SpacerH(18.dp)
        if (c.lockoutMs > 0) {
            Text(title, color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            SpacerH(12.dp)
            Row(verticalAlignment = Alignment.CenterVertically) { Body(S.lockedOut(c.lockoutMs), C.text, align = TextAlign.Center, modifier = Modifier.weight(1f, fill = false)); HelpIcon(Help.LOCKOUT, 18.dp) }
            Spacer(Modifier.weight(1f))
        } else if (c.busy) { Busy(S.UNLOCKING); Spacer(Modifier.weight(1f)) }
        else {
            val bio = c.bioReady && purpose.biometricAllowed()
            // Biometric first when allowed (once per appearance); the PIN pad stays as the fallback, with a finger key.
            LaunchedEffect(purpose, bio) { if (bio) c.autoBiometric(purpose) }
            PinPad(title, c.lockMessage ?: c.error, subtitle = sub, onBiometric = if (bio) ({ c.submitBiometric(purpose) }) else null) { pin -> c.submitPin(pin, purpose) }
            Spacer(Modifier.weight(1f))
        }
        if (purpose == null) HelpIcon(Help.AUTO_LOCK, 16.dp)
    }
}
