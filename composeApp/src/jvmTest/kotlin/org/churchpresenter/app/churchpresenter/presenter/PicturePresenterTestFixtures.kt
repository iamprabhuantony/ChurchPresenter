package org.churchpresenter.app.churchpresenter.presenter

import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.IntSize

internal val zeroSizeWindowInfo = object : WindowInfo {
    override val isWindowFocused: Boolean = true
    override val containerSize: IntSize = IntSize.Zero
}
