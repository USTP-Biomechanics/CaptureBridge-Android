package com.marksimonlehner.capturebridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

internal enum class BatteryChargeStatus(val protocolValue: String) {
    Charging("charging"),
    Full("full"),
    Discharging("discharging"),
    NotCharging("not_charging"),
    Unknown("unknown"),
}

internal enum class BatteryPluggedSource(val protocolValue: String) {
    Usb("usb"),
    Ac("ac"),
    Wireless("wireless"),
    Dock("dock"),
    None("none"),
    Unknown("unknown"),
}

internal data class BatterySnapshot(
    val levelPct: Int,
    val status: BatteryChargeStatus,
    val plugged: BatteryPluggedSource,
) {
    fun toProtocolLine(): String =
        "BATTERY level_pct=$levelPct status=${status.protocolValue} plugged=${plugged.protocolValue}"
}

internal fun normalizeBatterySnapshot(
    level: Int,
    scale: Int,
    statusCode: Int,
    pluggedCode: Int,
): BatterySnapshot? {
    if (level < 0 || scale <= 0) {
        return null
    }
    val levelPct = (level.toDouble() * 100.0 / scale.toDouble())
        .roundToInt()
        .coerceIn(0, 100)
    val status = when (statusCode) {
        BatteryManager.BATTERY_STATUS_CHARGING -> BatteryChargeStatus.Charging
        BatteryManager.BATTERY_STATUS_FULL -> BatteryChargeStatus.Full
        BatteryManager.BATTERY_STATUS_DISCHARGING -> BatteryChargeStatus.Discharging
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> BatteryChargeStatus.NotCharging
        else -> BatteryChargeStatus.Unknown
    }
    val plugged = when (pluggedCode) {
        BatteryManager.BATTERY_PLUGGED_USB -> BatteryPluggedSource.Usb
        BatteryManager.BATTERY_PLUGGED_AC -> BatteryPluggedSource.Ac
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> BatteryPluggedSource.Wireless
        BATTERY_PLUGGED_DOCK_VALUE -> BatteryPluggedSource.Dock
        0 -> BatteryPluggedSource.None
        else -> BatteryPluggedSource.Unknown
    }
    return BatterySnapshot(levelPct = levelPct, status = status, plugged = plugged)
}

internal class BatteryTelemetry(
    context: Context,
    private val onSnapshotChanged: (BatterySnapshot) -> Unit,
) {
    private val appContext = context.applicationContext

    @Volatile
    private var latestSnapshot: BatterySnapshot? = null
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) {
                return
            }
            updateSnapshot(intent.toBatterySnapshot())
        }
    }

    fun start() {
        if (registered) {
            return
        }
        registered = true
        try {
            val stickyIntent = ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            updateSnapshot(stickyIntent?.toBatterySnapshot())
        } catch (error: RuntimeException) {
            registered = false
            throw error
        }
    }

    fun stop() {
        if (!registered) {
            return
        }
        registered = false
        appContext.unregisterReceiver(receiver)
    }

    fun currentSnapshot(): BatterySnapshot? = latestSnapshot

    private fun updateSnapshot(snapshot: BatterySnapshot?) {
        if (snapshot == null || snapshot == latestSnapshot) {
            return
        }
        latestSnapshot = snapshot
        onSnapshotChanged(snapshot)
    }
}

private fun Intent.toBatterySnapshot(): BatterySnapshot? = normalizeBatterySnapshot(
    level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
    scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1),
    statusCode = getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
    pluggedCode = getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
)

// BatteryManager.BATTERY_PLUGGED_DOCK is API 33; its documented wire value is stable.
private const val BATTERY_PLUGGED_DOCK_VALUE = 8
