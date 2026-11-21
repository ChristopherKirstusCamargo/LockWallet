package com.lockwallet.app.ui

import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedContent
import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ======================================================================= bottom sheets (in-window, never a Dialog)

/**
 * Bottom sheets are drawn inside the activity window on purpose: a Dialog/Popup is a separate window that would not
 * inherit FLAG_SECURE nor filterTouchesWhenObscured. One sheet at a time.
 */
class Sheets {
    var content by mutableStateOf<(@Composable ColumnScope.() -> Unit)?>(null)
        private set
    fun show(c: @Composable ColumnScope.() -> Unit) { content = c }
    fun dismiss() { content = null }
}

val LocalSheets = staticCompositionLocalOf { Sheets() }

@Composable
fun SheetHost(sheets: Sheets) {
    val open = sheets.content != null
    var last by remember { mutableStateOf<(@Composable ColumnScope.() -> Unit)?>(null) }
    if (sheets.content != null) last = sheets.content
    val h = LocalHaptics.current
    LaunchedEffect(open) { if (open) h(Haptic.SOFT) }
    // Without RenderEffect the backdrop is not blurred, so the scrim gets denser to keep the sheet legible.
    val scrimAlpha = if (Fx.blurSupported) 1f else 1.35f
    val scrim by animateFloatAsState(if (open) 1f else 0f, Motion.fade(320), label = "scrim")
    if (scrim > 0.01f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = (scrim * scrimAlpha).coerceAtMost(1f) }.background(C.scrim)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { sheets.dismiss() })
    if (open) BackHandler { sheets.dismiss() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(open,
            enter = slideInVertically(Motion.sheet) { it / 2 } + fadeIn(Motion.fade(220)) + scaleIn(Motion.soft(), initialScale = 0.96f, transformOrigin = TransformOrigin(0.5f, 1f)),
            exit = slideOutVertically(Motion.sheet) { it } + fadeOut(Motion.fade(180))) {
            val drag = remember { Animatable(0f) }
            val scope = rememberCoroutineScope()
            val density = LocalDensity.current
            val threshold = with(density) { 120.dp.toPx() }
            var armed by remember { mutableStateOf(false) }
            LaunchedEffect(open) { if (open) drag.snapTo(0f) }
            val shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
            // Handle stretches with the pull; the sheet follows the finger downwards and rubber-bands upwards.
            val handleW by animateDpAsState(if (armed) 56.dp else 40.dp, Motion.bouncy(), label = "handle")
            // Drag-to-dismiss through nested scroll: pulling down at the top of the content moves the sheet (works with
            // scrollable content and the platform stretch overscroll); released past the threshold or flung ⇒ dismiss.
            val connection = remember(sheets) {
                object : NestedScrollConnection {
                    fun move(dy: Float) {
                        scope.launch {
                            drag.snapTo((drag.value + dy).coerceAtLeast(0f))
                            val nowArmed = drag.value > threshold
                            if (nowArmed != armed) { armed = nowArmed; h(Haptic.TICK) }
                        }
                    }
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                        if (drag.value > 0f && available.y < 0f) { val use = maxOf(available.y, -drag.value); move(use); Offset(0f, use) } else Offset.Zero
                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                        if (available.y > 0f && source == NestedScrollSource.UserInput) { move(available.y); Offset(0f, available.y) } else Offset.Zero
                    override suspend fun onPreFling(available: Velocity): Velocity {
                        if (drag.value <= 0f) return Velocity.Zero
                        if (drag.value > threshold || available.y > 2400f) sheets.dismiss() else drag.animateTo(0f, Motion.bouncy())
                        armed = false
                        return available
                    }
                }
            }

            Column(Modifier.fillMaxWidth().widthIn(max = 640.dp).offset { IntOffset(0, drag.value.roundToInt()) }
                .graphicsLayer { val k = (drag.value / (threshold * 6)).coerceIn(-0.02f, 0.04f); scaleX = 1f - k; scaleY = 1f - k }
                .glass(shape, strong = true)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .nestedScroll(connection)
                .navigationBarsPadding().imePadding().padding(horizontal = Space.xl).padding(bottom = 20.dp)
                .heightIn(max = 640.dp).verticalScroll(rememberScrollState()).testTag("sheet"),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(handleW, 5.dp).clip(CircleShape).background(if (armed) C.dim else C.faint))
                }
                last?.invoke(this)
            }
        }
    }
}

// ======================================================================= "?" help

/** One plain-language explanation: what it is, what it is for, one example. */
data class HelpTopic(val title: String, val what: String, val why: String, val example: String)

@Composable
fun HelpIcon(topic: HelpTopic, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    val sheets = LocalSheets.current
    val haptic = LocalHaptics.current
    Box(modifier.size(size + 12.dp).clip(CircleShape).clickable(role = Role.Button) { haptic(Haptic.TICK); sheets.show { HelpBody(topic) } }
        .semantics { contentDescription = "Ajuda: ${topic.title}" }.testTag("help"), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).clip(CircleShape).border(1.4.dp, C.dim, CircleShape), contentAlignment = Alignment.Center) {
            Text("?", color = C.dim, fontSize = (size.value * 0.62f).sp, fontWeight = FontWeight.Bold, lineHeight = (size.value * 0.62f).sp)
        }
    }
}

@Composable
fun ColumnScope.HelpBody(t: HelpTopic) {
    val sheets = LocalSheets.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).glass(CircleShape), contentAlignment = Alignment.Center) { Icon(Ic.HELP, size = 22.dp) }
        Spacer(Modifier.width(14.dp))
        Text(t.title, color = C.text, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
    }
    HelpPart("O que é", t.what)
    HelpPart("Para que serve", t.why)
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Ic.SPARK, C.dim, 16.dp); Spacer(Modifier.width(6.dp)); Label("Exemplo") }
        Text(t.example, color = C.text, fontSize = 15.sp, lineHeight = 22.sp)
    }
    PillButton("Entendi", Modifier.fillMaxWidth()) { sheets.dismiss() }
}

@Composable
private fun HelpPart(h: String, b: String) = Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Label(h); Text(b, color = C.text, fontSize = 16.sp, lineHeight = 23.sp)
}

// ======================================================================= typography

@Composable fun Label(text: String, color: Color = C.dim, modifier: Modifier = Modifier) =
    Text(text.uppercase(), color = color, fontSize = Type.label, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, modifier = modifier)

@Composable fun Body(text: String, color: Color = C.dim, modifier: Modifier = Modifier, align: TextAlign? = null) =
    Text(text, color = color, fontSize = Type.bodySmall, lineHeight = 22.sp, modifier = modifier, textAlign = align)

/** Page title: compact on narrow phones and with large fonts so it never needs more than two lines. */
@Composable fun Title(text: String, modifier: Modifier = Modifier, align: TextAlign? = null) {
    val compact = LocalConfiguration.current.screenWidthDp <= 360 || LocalDensity.current.fontScale > 1.3f
    val size = if (compact) Type.titleCompact else Type.title
    Text(text, color = C.text, fontSize = size, fontWeight = FontWeight.Bold, lineHeight = size * 1.2f, letterSpacing = (-0.5).sp, modifier = modifier, textAlign = align)
}

@Composable fun Mono(text: String, color: Color = C.text, size: Int = 14, modifier: Modifier = Modifier) =
    Text(text, color = color, fontSize = size.sp, fontFamily = FontFamily.Monospace, lineHeight = (size * 1.45).sp, modifier = modifier)

// ======================================================================= press feedback + buttons

/** Spring "press" scale for any clickable surface. */
@Composable
fun Modifier.pressable(enabled: Boolean = true, haptic: Haptic? = Haptic.PRESS, role: Role = Role.Button, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed && enabled) 0.96f else 1f, if (pressed) Motion.snappy() else Motion.bouncy(), label = "press")
    val wash by animateFloatAsState(if (pressed && enabled) 1f else 0f, Motion.fade(if (pressed) 90 else 260), label = "wash")
    val glow = C.glow
    val h = LocalHaptics.current
    return this.scale(s).drawWithContent { drawContent(); if (wash > 0f) drawRect(glow, alpha = wash * 0.6f) }
        .clickable(src, indication = null, enabled = enabled, role = role) { haptic?.let(h); onClick() }
}

@Composable
fun PillButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: Ic? = null, filled: Boolean = true, tag: String? = null, onClick: () -> Unit) {
    val bg by animateColorAsState(when { !enabled -> C.glass; filled -> C.text; else -> C.glass }, Motion.soft(), label = "pbg")
    val fg by animateColorAsState(when { !enabled -> C.dim; filled -> C.bg; else -> C.text }, Motion.soft(), label = "pfg")
    // Filled pill: a whisper of light on top (black pill) or of shade at the bottom (white pill) — depth without gloss.
    val dark = C.mix > 0.5f
    Row(modifier.heightIn(min = 56.dp).clip(CircleShape).background(bg)
        .then(if (filled && enabled) Modifier.drawBehind {
            drawRect(if (dark) Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.07f)), startY = size.height * 0.4f)
                     else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Color.Transparent), endY = size.height * 0.6f))
        }
              else Modifier.glass(CircleShape))
        .pressable(enabled) { onClick() }.then(if (tag != null) Modifier.testTag(tag) else Modifier).padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, fg, 20.dp); Spacer(Modifier.width(8.dp)) }
        Text(text, color = fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable fun Primary(text: String, enabled: Boolean = true, icon: Ic? = null, tag: String? = null, onClick: () -> Unit) =
    PillButton(text, Modifier.fillMaxWidth(), enabled, icon, true, tag, onClick)

@Composable fun Secondary(text: String, enabled: Boolean = true, icon: Ic? = null, tag: String? = null, onClick: () -> Unit) =
    PillButton(text, Modifier.fillMaxWidth(), enabled, icon, false, tag, onClick)

@Composable
fun TextAction(text: String, modifier: Modifier = Modifier, color: Color = C.text, onClick: () -> Unit) =
    Text(text, color = color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        modifier = modifier.clip(CircleShape).pressable { onClick() }.padding(horizontal = 16.dp, vertical = 12.dp))

@Composable
fun CircleButton(ic: Ic, desc: String, modifier: Modifier = Modifier, size: Dp = 44.dp, filled: Boolean = false, tag: String? = null, onClick: () -> Unit) =
    Box(modifier.size(size).then(if (filled) Modifier.clip(CircleShape).background(C.text) else Modifier.glass(CircleShape))
        .pressable { onClick() }.semantics { contentDescription = desc }.then(if (tag != null) Modifier.testTag(tag) else Modifier),
        contentAlignment = Alignment.Center) { Icon(ic, if (filled) C.bg else C.text, size * 0.46f) }

@Composable
fun Chip(text: String, modifier: Modifier = Modifier, icon: Ic? = null, inverted: Boolean = false, color: Color? = null, onClick: (() -> Unit)? = null) {
    val fg = color ?: if (inverted) C.bg else C.text
    Row(modifier.clip(CircleShape).background(if (inverted) C.text else (color?.copy(alpha = 0.14f) ?: C.glass))
        .then(if (onClick != null) Modifier.pressable { onClick() } else Modifier).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, fg, 14.dp); Spacer(Modifier.width(5.dp)) }
        Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ======================================================================= scaffolding

@Composable
fun Screen(title: String, onBack: (() -> Unit)? = null, scroll: Boolean = true, help: HelpTopic? = null,
           actions: @Composable RowScope.() -> Unit = {}, bottom: (@Composable ColumnScope.() -> Unit)? = null,
           content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) CircleButton(Ic.BACK, S.BACK, tag = "back", onClick = onBack) else Spacer(Modifier.size(44.dp))
            Spacer(Modifier.weight(1f)); actions()
        }
        val g = Space.gutter()
        val m = Modifier.fillMaxWidth().weight(1f).padding(horizontal = g)
        Column((if (scroll) m.verticalScroll(rememberScrollState()) else m).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Title(title, Modifier.weight(1f, fill = false))
                if (help != null) { Spacer(Modifier.width(4.dp)); HelpIcon(help, 22.dp) }
            }
            content()
        }
        if (bottom != null) Column(Modifier.fillMaxWidth().padding(horizontal = g, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = bottom)
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, padding: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().glass().padding(padding), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)

/** Grouped list (settings style): rows separated by hairlines inside one glass card. */
@Composable
fun Group(title: String? = null, help: HelpTopic? = null, content: @Composable ColumnScope.() -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (title != null) Row(Modifier.padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(title); if (help != null) HelpIcon(help, 16.dp)
    }
    Column(Modifier.fillMaxWidth().glass(), content = content)
}

@Composable
fun ListRow(title: String, icon: Ic? = null, subtitle: String? = null, value: String? = null, help: HelpTopic? = null, chevron: Boolean = true,
            tag: String? = null, enabled: Boolean = true, trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.pressable(enabled, Haptic.TICK) { onClick() } else Modifier)
        .then(if (tag != null) Modifier.testTag(tag) else Modifier).heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Box(Modifier.size(38.dp).glass(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(icon, if (enabled) C.text else C.dim, 20.dp) }; Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = if (enabled) C.text else C.dim, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
                if (help != null) HelpIcon(help, 16.dp)
            }
            if (subtitle != null) Text(subtitle, color = C.dim, fontSize = 13.sp, lineHeight = 18.sp)
        }
        if (value != null) Text(value, color = C.dim, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
        trailing?.invoke()
        if (onClick != null && chevron && trailing == null) { Spacer(Modifier.width(6.dp)); Icon(Ic.CHEVRON, C.dim, 18.dp) }
    }
}

/** Hairline that fades out at the right edge (softer than a hard rule). */
@Composable fun Divider() {
    val c = C.glassBorder
    Box(Modifier.fillMaxWidth().padding(start = 68.dp).height(1.dp).background(Brush.horizontalGradient(listOf(c, c, c.copy(alpha = 0f)))))
}

@Composable
fun Toggle(checked: Boolean, tag: String? = null, onChange: (Boolean) -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    // Knob stretches while held (iOS-like squish) and springs across; track colour cross-fades.
    val knobW by animateDpAsState(if (pressed) 34.dp else 28.dp, Motion.snappy(), label = "knobW")
    val x by animateDpAsState(if (checked) 52.dp - 2.dp - knobW else 2.dp, Motion.bouncy(), label = "knob")
    val track by animateColorAsState(if (checked) C.text else C.faint, Motion.fade(), label = "track")
    val knob by animateColorAsState(if (checked) C.bg else Color.White, Motion.fade(), label = "knobC")
    val h = LocalHaptics.current
    Box(Modifier.size(52.dp, 32.dp).clip(CircleShape).background(track)
        .clickable(src, indication = null, role = Role.Switch) { h(if (!checked) Haptic.CONFIRM else Haptic.TICK); onChange(!checked) }
        .then(if (tag != null) Modifier.testTag(tag) else Modifier)
        .semantics { contentDescription = if (checked) "ligado" else "desligado" }) {
        Box(Modifier.offset(x = x, y = 2.dp).size(knobW, 28.dp).clip(CircleShape).background(knob))
    }
}

@Composable
fun ToggleRow(title: String, checked: Boolean, icon: Ic? = null, subtitle: String? = null, help: HelpTopic? = null, tag: String? = null, onChange: (Boolean) -> Unit) =
    ListRow(title, icon, subtitle, help = help, chevron = false, trailing = { Toggle(checked, tag, onChange) })

/** Large selectable card: icon bubble, title, subtitle; selected = inverted ring. */
@Composable
fun ChoiceCard(title: String, subtitle: String, icon: Ic, selected: Boolean = false, enabled: Boolean = true, help: HelpTopic? = null, tag: String? = null, onClick: () -> Unit) {
    val ring by animateColorAsState(if (selected) C.text else Color.Transparent, Motion.fade(), label = "ring")
    Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).border(2.dp, ring, RoundedCornerShape(24.dp))
        .pressable(enabled) { onClick() }.then(if (tag != null) Modifier.testTag(tag) else Modifier).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        val bubble by animateColorAsState(if (selected) C.text else C.glass, Motion.fade(), label = "bubble")
        Box(Modifier.size(48.dp).clip(CircleShape).background(bubble), contentAlignment = Alignment.Center) { Icon(icon, if (selected) C.bg else C.text, 24.dp) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = if (enabled) C.text else C.dim, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = C.dim, fontSize = 14.sp, lineHeight = 19.sp)
        }
        if (help != null) HelpIcon(help, 18.dp) else Icon(Ic.CHEVRON, C.dim, 18.dp)
    }
}

/** Notice card: icon + text (+ optional help). Monochrome; severity is in the icon and words. */
@Composable
fun Notice(text: String, icon: Ic = Ic.INFO, help: HelpTopic? = null, strong: Boolean = false) =
    Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp)).then(if (strong) Modifier.background(C.text.copy(alpha = 0.08f)).border(1.dp, C.text.copy(alpha = 0.45f), RoundedCornerShape(18.dp)) else Modifier)
        .padding(14.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, C.text, 20.dp, Modifier.padding(top = 1.dp)); Spacer(Modifier.width(12.dp))
        Text(text, color = C.text, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
        if (help != null) HelpIcon(help, 16.dp)
    }

@Composable
fun ErrorText(msg: String?) {
    var last by remember { mutableStateOf(msg) }
    val h = LocalHaptics.current
    LaunchedEffect(msg) { if (msg != null) { last = msg; h(Haptic.REJECT) } }
    AnimatedVisibility(msg != null, enter = expandVertically(Motion.snappy()) + fadeIn(), exit = shrinkVertically(Motion.snappy()) + fadeOut()) {
        Notice(last ?: "", Ic.WARN, strong = true)
    }
}

@Composable
fun Busy(text: String = S.WORKING) = Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
    CircularProgressIndicator(Modifier.size(20.dp), color = C.text, strokeWidth = 2.dp, trackColor = C.faint)
    Spacer(Modifier.width(12.dp)); Text(text, color = C.dim)
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, tag: String? = null, mono: Boolean = false,
          keyboard: KeyboardType = KeyboardType.Text, secret: Boolean = false, minLines: Int = 1, trailing: (@Composable () -> Unit)? = null) =
    OutlinedTextField(value, onChange, modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier),
        label = { Text(label) }, singleLine = minLines == 1, minLines = minLines, shape = RoundedCornerShape(20.dp),
        textStyle = TextStyle(fontSize = 16.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, color = C.text),
        // Password keyboard type also tells IMEs not to learn/suggest (no Compose flag for IME_FLAG_NO_PERSONALIZED_LEARNING).
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
        visualTransformation = if (secret && minLines == 1) PasswordVisualTransformation() else VisualTransformation.None, trailingIcon = trailing,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.text, unfocusedBorderColor = C.glassBorder, focusedLabelColor = C.text,
            unfocusedLabelColor = C.dim, cursorColor = C.text, focusedContainerColor = C.glass, unfocusedContainerColor = C.glass))

// ======================================================================= fitting text + soft shadow

/** One-line label that shrinks (down to 70 %) instead of clipping or wrapping — tabs, tiles, segments at large fonts. */
@Composable
fun FitText(text: String, color: Color, size: androidx.compose.ui.unit.TextUnit, weight: FontWeight = FontWeight.Medium, modifier: Modifier = Modifier) =
    androidx.compose.foundation.text.BasicText(text, modifier, style = TextStyle(color = color, fontSize = size, fontWeight = weight, textAlign = TextAlign.Center),
        maxLines = 1, softWrap = false,
        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = size * 0.7f, maxFontSize = size, stepSize = 0.5.sp))

/** Soft, wide, low-contrast shadow for floating glass (pill bar, FAB). Monochrome; lighter in the light theme. */
fun Modifier.shadowSoft(shape: androidx.compose.ui.graphics.Shape): Modifier {
    val a = if (C.dark) 0.6f else 0.18f
    return this.shadow(18.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = a), spotColor = Color.Black.copy(alpha = a))
}

// ======================================================================= loading

@Composable
fun shimmerBrush(): Brush {
    val t = rememberInfiniteTransition(label = "shimmer")
    // Soft eased sweep with a pause between passes; wide feathered band (no hard edge).
    val x by t.animateFloat(-600f, 1600f, infiniteRepeatable(tween(1600, delayMillis = 250, easing = Motion.Emphasized), RepeatMode.Restart), label = "x")
    val base = C.glass; val hi = C.glow
    return Brush.linearGradient(listOf(base, base, hi.copy(alpha = hi.alpha * 0.6f), hi, hi.copy(alpha = hi.alpha * 0.6f), base, base),
        start = Offset(x, 0f), end = Offset(x + 600f, 260f))
}

@Composable
fun Skeleton(modifier: Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(10.dp)) {
    val b = shimmerBrush()
    Box(modifier.clip(shape).drawBehind { drawRect(b) }.testTag("skeleton"))
}

@Composable
fun SkeletonRow() = Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
    Skeleton(Modifier.size(44.dp), CircleShape); Spacer(Modifier.width(14.dp))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { Skeleton(Modifier.width(120.dp).height(14.dp)); Skeleton(Modifier.width(80.dp).height(12.dp)) }
    Skeleton(Modifier.width(70.dp).height(14.dp))
}

// ======================================================================= secrets

/**
 * Draws secret characters straight from a CharArray (never a String) on a Canvas. v3: the text never clips — if it
 * is wider than the space it gets, it shrinks to fit (down to 60 %), so long words fit 320 dp phones at 1.3× font.
 */
@Composable
fun SecretChars(chars: CharArray, modifier: Modifier = Modifier, sizeSp: Int = 17, color: Color = C.text) {
    val density = LocalDensity.current
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) } }
    val base = with(density) { sizeSp.sp.toPx() }
    paint.color = color.toArgb()
    val h = with(density) { (sizeSp * 1.6).sp.toDp() }
    Canvas(modifier.height(h).semantics { contentDescription = "palavra oculta" }) {
        paint.textSize = base
        val w = paint.measureText(chars, 0, chars.size)
        if (w > size.width && w > 0f) paint.textSize = (base * size.width / w).coerceAtLeast(base * 0.6f)
        drawIntoCanvas { it.nativeCanvas.drawText(chars, 0, chars.size, 0f, size.height * 0.72f, paint) }
    }
}

/** Splits a space-separated secret into per-word copies (for the numbered grid). Caller wipes the result. */
fun secretWords(chars: CharArray): List<CharArray> {
    val out = ArrayList<CharArray>()
    var start = -1
    for (i in 0..chars.size) {
        val sep = i == chars.size || chars[i] == ' '
        if (!sep && start < 0) start = i
        if (sep && start >= 0) { out += chars.copyOfRange(start, i); start = -1 }
    }
    return out
}

/**
 * Numbered word chips with a tap-to-reveal blur. Adapts to the phone: two columns when each cell gets at least
 * 132 dp × font scale, otherwise one column (320 dp phones, large fonts). Wipes nothing itself: the owner wipes.
 */
@Composable
fun WordGrid(words: List<CharArray>, tag: String = "word-grid") {
    var revealed by remember { mutableStateOf(false) }
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth().testTag(tag)) {
        val cols = if ((maxWidth - 32.dp) / 2 >= 132.dp * fontScale) 2 else 1
        Column(Modifier.fillMaxWidth().glass().padding(12.dp).then(if (revealed) Modifier else Modifier.blur(18.dp)),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val rows = (words.size + cols - 1) / cols
            for (r in 0 until rows) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (col in 0 until cols) {
                    val i = col * rows + r
                    Row(Modifier.weight(1f).clip(CircleShape).background(C.glass).padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (i < words.size) {
                            Text("${i + 1}", color = C.dim, fontSize = 13.sp, maxLines = 1, modifier = Modifier.widthIn(min = 26.dp).padding(end = 4.dp))
                            SecretChars(words[i], Modifier.weight(1f).testTag("word-${i + 1}"))
                        }
                    }
                }
            }
        }
        if (!revealed) Column(Modifier.matchParentSize().clip(RoundedCornerShape(24.dp)).pressable { revealed = true }.testTag("reveal-words"),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Ic.EYE, size = 28.dp); Spacer(Modifier.height(8.dp))
            Text(S.TAP_TO_REVEAL, color = C.text, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
    }
}

/** Wipes the given secret arrays when the composable leaves the screen. */
@Composable
fun WipeOnDispose(vararg secrets: CharArray) {
    DisposableEffect(Unit) { onDispose { secrets.forEach { it.fill('\u0000') } } }
}

/**
 * 6-digit PIN pad. Digits live in a CharArray (never a String); [onComplete] receives a copy which the
 * callee owns and must wipe. The internal buffer is wiped after each completion and on dispose.
 * The dots shake (spring) whenever [message] changes to a new error.
 */
@Composable
fun PinPad(title: String, message: String?, enabled: Boolean = true, subtitle: String? = null, onBiometric: (() -> Unit)? = null, digits: Int = 6, onComplete: (CharArray) -> Unit) {
    require(digits in 6..12)
    val buf = remember(digits) { CharArray(digits) }
    var n by remember(digits) { mutableIntStateOf(0) }
    val shake = remember { Animatable(0f) }
    val h = LocalHaptics.current
    DisposableEffect(buf) { onDispose { buf.fill('\u0000') } }
    LaunchedEffect(message) { if (message != null) { h(Haptic.REJECT); for (x in listOf(18f, -16f, 12f, -8f, 4f, 0f)) shake.animateTo(x, tween(45)) } }
    // Compact keypad (v3): keys sized from the available width (never wider than 280 dp, never taller than 56 dp), so it
    // fits a 320 dp phone with large fonts and the thumb travels less. Digits react on press with a spring.
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 26.sp)
        if (subtitle != null) Text(subtitle, color = C.dim, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 2)
        Row(Modifier.offset { IntOffset(shake.value.roundToInt(), 0) }.padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(if (digits >= 10) 6.dp else 14.dp)) {
            repeat(digits) { i ->
                val sz by animateDpAsState(if (i < n) 14.dp else 10.dp, Motion.bouncy(), label = "dot")
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(sz).clip(CircleShape).background(if (i < n) C.text else C.faint)) }
            }
        }
        AnimatedVisibility(message != null) { Text(message ?: "", color = C.text, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 300.dp)) }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", if (onBiometric != null) "bio" else "", "0", "⌫")
        Column(Modifier.widthIn(max = 280.dp).fillMaxWidth().testTag("pinpad"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in keys.chunked(3)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in row) {
                    val src = remember { MutableInteractionSource() }
                    val pressed by src.collectIsPressedAsState()
                    val s by animateFloatAsState(if (pressed) 0.9f else 1f, Motion.bouncy(), label = "key")
                    val bg by animateColorAsState(if (k.isEmpty()) Color.Transparent else if (pressed) C.glow else C.glass, tween(90), label = "keybg")
                    Box(Modifier.weight(1f).height(54.dp).scale(s).clip(RoundedCornerShape(16.dp)).background(bg)
                        .clickable(src, indication = null, enabled = enabled && k.isNotEmpty()) {
                            h(Haptic.TICK)
                            when (k) {
                                "⌫" -> if (n > 0) { n--; buf[n] = '\u0000' }
                                "bio" -> onBiometric?.invoke()
                                else -> if (n < digits) {
                                    buf[n] = k[0]; n++
                                    if (n == digits) { val copy = buf.copyOf(); buf.fill('\u0000'); n = 0; onComplete(copy) }
                                }
                            }
                        }.semantics { contentDescription = when (k) { "⌫" -> "apagar"; "bio" -> "usar biometria"; else -> k } }
                        .then(if (k == "bio") Modifier.testTag("pin-bio") else Modifier), contentAlignment = Alignment.Center) {
                        when (k) {
                            "⌫" -> Icon(Ic.BACK, size = 22.dp)
                            "bio" -> Icon(Ic.FINGER, size = 24.dp)
                            else -> Text(k, color = C.text, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

// ======================================================================= animated numbers

/**
 * Odometer-style number: each digit rolls up or down (spring, slight overshoot) when its value changes; other
 * characters cross-fade; width changes animate. The whole string is exposed once to accessibility / tests.
 */
@Composable
fun AnimatedNumber(text: String, sizeSp: Int = 44, color: Color = C.text, weight: FontWeight = FontWeight.Black, modifier: Modifier = Modifier) =
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
    // Fit: big balances on a 320 dp phone with large fonts shrink to the available width instead of clipping.
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = LocalDensity.current
    val maxPx = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
    val natural = remember(text, sizeSp, weight, density) {
        measurer.measure(text, TextStyle(fontSize = sizeSp.sp, fontWeight = weight, letterSpacing = if (sizeSp >= 30) (-1).sp else 0.sp), maxLines = 1, softWrap = false).size.width
    }
    val fit = if (natural > maxPx && natural > 0) (maxPx.toFloat() / natural * 0.97f).coerceAtLeast(0.45f) else 1f
    val fitted by animateFloatAsState(fit, Motion.soft(), label = "fit")
    val effSp = sizeSp * fitted
    Row(Modifier.animateContentSize(Motion.soft()).clearAndSetSemantics { this.text = androidx.compose.ui.text.AnnotatedString(text) },
        verticalAlignment = Alignment.CenterVertically) {
        text.forEachIndexed { i, ch ->
            androidx.compose.runtime.key(text.length - i) {
                AnimatedContent(targetState = ch, transitionSpec = {
                    if (initialState.isDigit() && targetState.isDigit()) {
                        val up = targetState > initialState
                        (slideInVertically(spring(dampingRatio = 0.72f, stiffness = 260f)) { if (up) it else -it } + fadeIn(tween(160))) togetherWith
                            (slideOutVertically(spring(dampingRatio = 0.72f, stiffness = 260f)) { if (up) -it else it } + fadeOut(tween(120))) using SizeTransform(clip = true)
                    } else (fadeIn(tween(180)) togetherWith fadeOut(tween(120))) using SizeTransform(clip = false)
                }, label = "digit") { ch2 ->
                    Text(ch2.toString(), color = color, fontSize = effSp.sp, fontWeight = weight, letterSpacing = if (sizeSp >= 30) (-1).sp else 0.sp, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

// ======================================================================= misc

/** Status as symbol (monochrome). */
fun statusSymbol(s: com.lockwallet.custody.local.CheckStatus) = when (s) {
    com.lockwallet.custody.local.CheckStatus.OK -> "✓"
    com.lockwallet.custody.local.CheckStatus.WARN -> "!"
    com.lockwallet.custody.local.CheckStatus.RISK -> "✕"
    com.lockwallet.custody.local.CheckStatus.UNAVAILABLE -> "–"
    com.lockwallet.custody.local.CheckStatus.INFO -> "i"
}

fun statusIcon(s: com.lockwallet.custody.local.CheckStatus) = when (s) {
    com.lockwallet.custody.local.CheckStatus.OK -> Ic.CHECK
    com.lockwallet.custody.local.CheckStatus.WARN -> Ic.WARN
    com.lockwallet.custody.local.CheckStatus.RISK -> Ic.X_CIRCLE
    com.lockwallet.custody.local.CheckStatus.UNAVAILABLE -> Ic.INFO
    com.lockwallet.custody.local.CheckStatus.INFO -> Ic.INFO
}

fun shortAddress(a: String) = if (a.length > 10) a.take(4) + "…" + a.takeLast(4) else a

/** Full address in groups of 4 so a human can actually compare it character by character. */
fun groupedAddress(a: String) = a.chunked(4).joinToString(" ")

/** Avatar bubble with the wallet's initial (monochrome). */
@Composable
fun Avatar(name: String, size: Dp = 40.dp, modifier: Modifier = Modifier) =
    Box(modifier.size(size).clip(CircleShape).background(C.text), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase().ifEmpty { "L" }, color = C.bg, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold)
    }

@Composable
fun SpacerH(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun BoxScope.Center(content: @Composable () -> Unit) = Box(Modifier.align(Alignment.Center)) { content() }

val NoPadding = PaddingValues(0.dp)
