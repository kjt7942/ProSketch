package com.prosketch.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * The current window in dp. The UI was designed for a 1280×800 dp tablet; on phones (narrow or short
 * windows, either orientation, folded or not) panels, bars and the sidebar shrink to fit.
 */
@Immutable
data class Screen(val w: Int, val h: Int) {
    /** Narrower than a small tablet: e.g. a phone held upright. */
    val compactW get() = w < 600
    /** Shorter than a small tablet: e.g. a phone held sideways. */
    val compactH get() = h < 500
    val compact get() = compactW || compactH

    /** A floating panel's width: its design width, or the window minus margins. */
    fun panel(design: Dp): Dp = min(design.value, (w - 24).toFloat()).dp

    /** Height a panel may take below the top bar. */
    val panelMax: Dp get() = (h - 56 - 12).coerceAtLeast(160).dp

    /** A scrolling area inside a panel: its design height, or what is left after [reserve] for the panel's header. */
    fun body(design: Dp, reserve: Dp = 110.dp): Dp = min(design.value, (h - 68 - reserve.value)).coerceAtLeast(120f).dp
}

@Composable
fun screen(): Screen {
    val c = LocalConfiguration.current
    return remember(c.screenWidthDp, c.screenHeightDp) { Screen(c.screenWidthDp, c.screenHeightDp) }
}
