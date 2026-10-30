package org.fossify.gallery.compression.queue

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale

/** Pausa: status térmico ≥ este OU bateria ≥ [PAUSE_BATTERY_TENTHS]. */
private const val PAUSE_STATUS = PowerManager.THERMAL_STATUS_SEVERE

/** Décimos de °C, como em BatteryManager.EXTRA_TEMPERATURE (430 = 43,0 °C). */
private const val PAUSE_BATTERY_TENTHS = 430

/** Emergência (cancela o item atual com pauseNow): status ≥ este OU bateria ≥ [EMERGENCY_BATTERY_TENTHS]. */
private const val EMERGENCY_STATUS = PowerManager.THERMAL_STATUS_CRITICAL
private const val EMERGENCY_BATTERY_TENTHS = 460

/** Retomada: status ≤ este E bateria ≤ [RESUME_BATTERY_TENTHS] E pausa de pelo menos [MIN_PAUSE_MS]. */
private const val RESUME_STATUS = PowerManager.THERMAL_STATUS_LIGHT
private const val RESUME_BATTERY_TENTHS = 400
private const val MIN_PAUSE_MS = 5 * 60 * 1000L

/** Sem esfriar até aqui, a fila para de vez (o tempo pausado conta no limite diário do serviço em primeiro plano). */
const val THERMAL_MAX_PAUSE_MS = 60 * 60 * 1000L

/** Releitura periódica, além dos avisos do sistema. */
private const val POLL_MS = 30_000L

/**
 * Pausa automática da fila por temperatura. Sinais: status térmico do PowerManager (API 29+, aviso imediato) e temperatura
 * da bateria (ACTION_BATTERY_CHANGED, esfria devagar). Pausa se QUALQUER um passar do limite; retoma só com os DOIS abaixo
 * dos limites de retomada e a pausa mínima cumprida. Vive no QueueService, na thread principal. Durante a pausa segura um
 * wake lock próprio, para a releitura continuar com a tela apagada.
 */
class ThermalGuard(context: Context, private val listener: Listener) {
    interface Listener {
        /** Quente: pausar a fila. [emergency] = cancelar o item atual. Pode vir de novo com [emergency] enquanto a pausa não acontece. */
        fun onHot(reading: Reading, emergency: Boolean)

        /** Em pausa: esfriou. */
        fun onCooled(reading: Reading)

        /** Em pausa: passou [THERMAL_MAX_PAUSE_MS] sem esfriar. */
        fun onGaveUp(reading: Reading)
    }

    /** [batteryTenths] = null se a bateria não informou. */
    data class Reading(val status: Int, val batteryTenths: Int?) {
        override fun toString() = "status=$status, bateria=${batteryTenths?.let { String.format(Locale.US, "%.1f °C", it / 10.0) } ?: "?"}"
    }

    private enum class State { IDLE, WATCHING, PAUSING, PAUSING_NOW, COOLING }

    private val context = context.applicationContext
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val handler = Handler(Looper.getMainLooper())
    private var state = State.IDLE
    private var registered = false
    private var batteryTenths: Int? = null
    private var pausedAt = 0L

    /** false depois de "Retomar agora" ainda quente: só a emergência pausa, até a primeira leitura fria. */
    private var armed = true
    private var cooldownLock: PowerManager.WakeLock? = null

    private val statusListener: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        PowerManager.OnThermalStatusChangedListener { evaluate() }
    } else {
        null
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            readBattery(intent)
            evaluate()
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            evaluate()
            handler.postDelayed(this, POLL_MS)
        }
    }

    val cooling get() = state == State.COOLING

    /** Passa a vigiar a fila rodando. [disarm]: retomada manual ainda quente (ver [armed]). */
    fun watch(disarm: Boolean = false) {
        register()
        releaseCooldownLock()
        state = State.WATCHING
        armed = !disarm || isCool(read())
        evaluate()
    }

    /** A fila parou por temperatura: começa a contar a pausa. */
    fun onPaused() {
        register()
        state = State.COOLING
        pausedAt = SystemClock.elapsedRealtime()
        acquireCooldownLock()
    }

    fun stop() {
        state = State.IDLE
        releaseCooldownLock()
        if (!registered) return
        registered = false
        handler.removeCallbacks(poll)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            power.removeThermalStatusListener(statusListener as PowerManager.OnThermalStatusChangedListener)
        }
        runCatching { context.unregisterReceiver(batteryReceiver) }
    }

    fun read() = Reading(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power.currentThermalStatus else PowerManager.THERMAL_STATUS_NONE,
        batteryTenths,
    )

    private fun register() {
        if (registered) return
        registered = true
        ContextCompat.registerReceiver(context, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            ?.let(::readBattery)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            power.addThermalStatusListener(context.mainExecutor, statusListener as PowerManager.OnThermalStatusChangedListener)
        }
        handler.postDelayed(poll, POLL_MS)
    }

    private fun readBattery(intent: Intent) {
        batteryTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
    }

    private fun isHot(r: Reading) = r.status >= PAUSE_STATUS || (r.batteryTenths ?: 0) >= PAUSE_BATTERY_TENTHS

    private fun isEmergency(r: Reading) = r.status >= EMERGENCY_STATUS || (r.batteryTenths ?: 0) >= EMERGENCY_BATTERY_TENTHS

    private fun isCool(r: Reading) = r.status <= RESUME_STATUS && (r.batteryTenths ?: 0) <= RESUME_BATTERY_TENTHS

    private fun evaluate() {
        val r = read()
        when (state) {
            State.IDLE, State.PAUSING_NOW -> Unit
            State.WATCHING -> {
                if (!armed && isCool(r)) armed = true
                if (isEmergency(r)) {
                    state = State.PAUSING_NOW
                    listener.onHot(r, emergency = true)
                } else if (armed && isHot(r)) {
                    state = State.PAUSING
                    listener.onHot(r, emergency = false)
                }
            }

            State.PAUSING -> if (isEmergency(r)) {
                state = State.PAUSING_NOW
                listener.onHot(r, emergency = true)
            }

            State.COOLING -> {
                val elapsed = SystemClock.elapsedRealtime() - pausedAt
                if (elapsed >= MIN_PAUSE_MS && isCool(r)) {
                    listener.onCooled(r)
                } else if (elapsed >= THERMAL_MAX_PAUSE_MS) {
                    listener.onGaveUp(r)
                }
            }
        }
    }

    private fun acquireCooldownLock() {
        releaseCooldownLock()
        cooldownLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "galeria:compression-cooldown").apply {
            setReferenceCounted(false)
            acquire(THERMAL_MAX_PAUSE_MS + COOLDOWN_LOCK_MARGIN_MS)
        }
    }

    private fun releaseCooldownLock() {
        cooldownLock?.takeIf { it.isHeld }?.release()
        cooldownLock = null
    }

    companion object {
        const val TAG = "ThermalGuard"
        private const val COOLDOWN_LOCK_MARGIN_MS = 5 * 60 * 1000L

        /** Registro para análise depois de alguns dias de uso: `adb logcat -s ThermalGuard`. */
        fun log(event: String, reading: Reading, reason: String) = Log.i(TAG, "$event | $reading | $reason")
    }
}
