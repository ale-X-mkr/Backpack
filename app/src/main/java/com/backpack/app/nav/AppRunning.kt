package com.backpack.app.nav

/**
 * 1.3.0: Backpack is "open" from the moment the map is shown until it is
 * expressly exited (Exit in the notification or in Settings, or swiped away
 * from recent apps). While open, the background service keeps the speed
 * alarm, navigation and the sight heads-ups going with the screen off.
 */
object AppRunning {
    @Volatile var open = false
    /** The map screen is on screen (its own GPS lease covers everything then). */
    @Volatile var uiVisible = false
    /** The map screen closes itself when Backpack is exited. */
    var onExit: (() -> Unit)? = null
    /** The background service re-checks what it must keep (screen shown / hidden). */
    var onChange: (() -> Unit)? = null

    fun markUiVisible(v: Boolean) {
        uiVisible = v
        onChange?.invoke()
    }
}
