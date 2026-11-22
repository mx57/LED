package com.glowled.app.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.glowled.app.R
import com.glowled.app.core.LedController
import com.glowled.app.core.PowerGuard

/** Quick Settings tile to toggle the LED on/off or resume from snooze. */
class LedTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        LedController.init(this)
        update()
    }

    override fun onClick() {
        super.onClick()
        val p = LedController.prefs
        if (p.isPaused) {
            p.clearSnooze()
            p.enabled = true
        } else {
            p.enabled = !p.enabled
        }
        update()
    }

    private fun update() {
        val tile = qsTile ?: return
        val p = LedController.prefs
        val snoozed = PowerGuard.isSnoozed(p)
        if (snoozed) {
            tile.state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val leftMin = ((p.pausedUntilEpochMs - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1)
                tile.subtitle = getString(R.string.tile_snooze_sub, leftMin)
            }
        } else {
            tile.state = if (p.enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = if (p.enabled) getString(R.string.state_on) else getString(R.string.state_off)
            }
        }
        tile.updateTile()
    }
}

