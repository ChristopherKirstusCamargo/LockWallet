package com.lockwallet.app.ui

import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import com.lockwallet.app.AppController
import com.lockwallet.app.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Platform hooks installed by the activity (documents, clipboard, links). */
class Platform(
    val openDocument: (onBytes: (ByteArray?) -> Unit) -> Unit,
    val createDocument: (fileName: String) -> Unit,
    val copyAddress: (String) -> Unit,
    val openUrl: (String) -> Unit = {},
    val paste: () -> String? = { null },
    /** Paste a secret (recovery phrase/key): returns the text and wipes the clipboard. */
    val pasteSecret: () -> String? = paste,
    val haptics: () -> Boolean = { true },
)

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalAnimScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Shared-element morph between screens (e.g. a token's logo flies from the list into its detail header). */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.shared(key: String): Modifier {
    val s = LocalSharedScope.current ?: return this
    val a = LocalAnimScope.current ?: return this
    return with(s) {
        this@shared.sharedElement(rememberSharedContentState(key), a,
            boundsTransform = { _: Rect, _: Rect -> spring(dampingRatio = 0.82f, stiffness = 340f) })
    }
}

/** Navigation depth: forward = slide in from the right, back = from the left. */
private fun depth(s: Screen): Int = when (s) {
    Screen.Home, Screen.Lock -> 0
    Screen.Review, Screen.Quiz, Screen.Name, Screen.ShowPhrase -> 3
    is Screen.TxStatus, Screen.PinCreate, Screen.BioChoice -> 4
    is Screen.Authorize -> 5
    Screen.Role, Screen.Warning, is Screen.RestoreInput, Screen.SeedVaultSetup, Screen.Length -> 2
    is Screen.Send, is Screen.Token, is Screen.StakeAccount -> 1
    else -> 1
}

/** Which screens may render while the vault is locked. Everything else shows the lock screen instead. */
private fun allowedWhileLocked(s: Screen) = s == Screen.Lock || s == Screen.SecurityCenter

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun App(c: AppController, p: Platform) {
    // Transaction reconciliation: only in the foreground and unlocked; never creates or re-signs anything.
    LaunchedEffect(c.foreground, c.unlocked) {
        if (!c.foreground || !c.unlocked) return@LaunchedEffect
        while (isActive) { c.chain.safely { c.chain.reconcileTick() }; delay(2_500) }
    }
    val sheets = remember { Sheets() }
    val haptic = rememberHapticPerformer { c.haptics }
    LockTheme(haptic) {
        CompositionLocalProvider(LocalSheets provides sheets) {
            Box(Modifier.fillMaxSize()) {
                AmbientBackground()
                // Real backdrop blur (RenderEffect) under sheets; the scrim alone where unsupported.
                val backdrop by animateDpAsState(if (sheets.content != null) Fx.backdrop else 0.dp, Motion.soft(), label = "backdrop")
                SharedTransitionLayout(Modifier.softBlur(backdrop)) {
                    AnimatedContent(
                        targetState = c.screen,
                        transitionSpec = {
                            val fwd = depth(targetState) >= depth(initialState)
                            if (targetState == Screen.Lock || initialState == Screen.Lock) {
                                // Lock: a calm zoom-through (content recedes, the lock rises).
                                (fadeIn(Motion.fade(360)) + scaleIn(spring(dampingRatio = 0.9f, stiffness = 260f), initialScale = 0.94f)) togetherWith
                                    (fadeOut(Motion.fade(200)) + scaleOut(Motion.fade(260), targetScale = 1.05f))
                            } else {
                                // Push/pop with parallax: the new page glides a third, the old one drifts a fifth and dims.
                                (slideInHorizontally(Motion.slide) { if (fwd) it / 3 else -it / 3 } + fadeIn(Motion.fade(300)) +
                                    scaleIn(Motion.soft(), initialScale = 0.985f)) togetherWith
                                    (slideOutHorizontally(Motion.slide) { if (fwd) -it / 5 else it / 5 } + fadeOut(Motion.fade(180)) +
                                        scaleOut(Motion.soft(), targetScale = 0.985f))
                            }.using(androidx.compose.animation.SizeTransform(clip = false))
                        },
                        label = "screen",
                    ) { screen ->
                        CompositionLocalProvider(LocalSharedScope provides this@SharedTransitionLayout, LocalAnimScope provides this) {
                            when {
                                // Hard lock gate: no wallet data renders while locked, whatever the navigation state says.
                                c.locked && !allowedWhileLocked(screen) -> LockScreen(c, null)
                                // Lock Connect: a pending dApp request takes over once unlocked (never shown while locked).
                                !c.locked && c.connect.current != null && screen !is Screen.Authorize -> ConnectRequestScreen(c)
                                else -> ScreenContent(c, p, screen)
                            }
                        }
                    }
                }
                SheetHost(sheets)
            }
        }
    }
    // A sheet never survives locking or a screen change.
    LaunchedEffect(c.screen, c.unlocked) { sheets.dismiss() }
}

@Composable
private fun ScreenContent(c: AppController, p: Platform, screen: Screen) {
    when (val s = screen) {
        Screen.Home -> if (c.wallets.isEmpty() && !c.vaultPresent) Welcome(c) else MainScreen(c, p)
        Screen.Receive -> Receive(c, p)
        Screen.Lock -> LockScreen(c, null)
        Screen.SecurityCenter -> SecurityCenter(c)
        Screen.Custody -> CustodyChoice(c)
        Screen.Role -> RoleChoice(c)
        Screen.Length -> LengthChoice(c)
        Screen.Warning -> Warning(c)
        Screen.ShowPhrase -> ShowPhrase(c)
        Screen.Quiz -> Quiz(c)
        Screen.RestoreMethod -> RestoreMethod(c)
        is Screen.RestoreInput -> RestoreInput(c, p, s.kind)
        Screen.Name -> NameWallet(c)
        Screen.PinCreate -> PinCreate(c)
        Screen.BioChoice -> BioChoice(c)
        is Screen.Authorize -> LockScreen(c, s.purpose)
        is Screen.Detail -> Detail(c, p, s.walletId)
        is Screen.Reveal -> Reveal(c)
        is Screen.Export -> Export(c, p)
        is Screen.Send -> if (s.tokenAccount == null) SendSol(c, p) else SendToken(c, p, s.tokenAccount)
        Screen.Review -> Review(c)
        is Screen.TxStatus -> TxStatus(c, p, s.signature)
        Screen.Network -> NetworkScreen(c)
        is Screen.Token -> TokenScreen(c, p, s.account)
        Screen.StakeNew -> StakeNew(c)
        is Screen.StakeAccount -> StakeAccountScreen(c, p, s.address)
        Screen.SeedVaultSetup -> SeedVaultSetup(c)
    }
}
