package com.project.puk.jam

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.project.puk.core.AppPrefs

/**
 * Локальная задержка аудиовыхода устройства (spec раздел 4). ExoPlayer
 * позиция стартует двигаться в момент play(), а первые сэмплы выходят из
 * динамика на ~40–100 мс позже (глубина AudioTrack-буфера + HAL), у
 * Bluetooth — на ~200 мс. Два устройства с разной задержкой играют
 * «одновременно» по позиции ExoPlayer, но не по звуку.
 *
 * Достоверных публичных API на timestamp'ы AudioTrack внутри Media3 нет,
 * поэтому (spec раздел 4) здесь: грубая оценка по маршруту + свойствам
 * устройства и НЕобязательная пользовательская калибровка на маршрут.
 * Значение применяется только локально — к планированию старта и к цели
 * дрейф-монитора; серверная позиция Jam не меняется.
 */
object AudioLatency {

    enum class Route { SPEAKER, WIRED, BLUETOOTH }

    private const val PREF_PREFIX = "jam_audio_calib_"

    /** Priors: фиксированная часть + несколько HAL-буферов на маршрут. */
    fun estimateMs(framesPerBuffer: Int, sampleRate: Int, route: Route): Long {
        val bufferMs = framesPerBuffer.coerceAtLeast(1) * 1_000L / sampleRate.coerceAtLeast(1)
        return when (route) {
            Route.BLUETOOTH -> 200L // A2DP: кодек +link buffer, сваливается в ~150–250 мс
            Route.WIRED -> 20L + 3 * bufferMs
            Route.SPEAKER -> 45L + 3 * bufferMs
        }
    }

    fun routeOf(am: AudioManager): Route {
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return when {
            devices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
            } -> Route.BLUETOOTH
            devices.any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE
            } -> Route.WIRED
            else -> Route.SPEAKER
        }
    }

    /** Грубая оценка по свойствам устройства (нет свойств — типовые значения). */
    fun estimateMs(am: AudioManager, route: Route): Long {
        val fpb = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256
        val sr = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48_000
        return estimateMs(fpb, sr, route)
    }

    fun calibrationMs(context: android.content.Context, route: Route): Long =
        AppPrefs.prefs(context).getLong(PREF_PREFIX + route.name, 0L)

    fun setCalibrationMs(context: android.content.Context, route: Route, ms: Long) {
        AppPrefs.prefs(context).edit().putLong(PREF_PREFIX + route.name, ms).apply()
    }

    /** Итоговое значение: оценка + калибровка, ≥ 0. */
    fun totalMs(context: android.content.Context): Long {
        val am = context.getSystemService(android.media.AudioManager::class.java) ?: return 0L
        val route = routeOf(am)
        return (estimateMs(am, route) + calibrationMs(context, route)).coerceAtLeast(0L)
    }

    /** Слежение за сменой маршрута (вставили наушники / подключили BT) — кэш инвалидируется. */
    class RouteWatcher(private val am: AudioManager, private val onChanged: () -> Unit) {
        private val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = onChanged()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = onChanged()
        }

        fun start() = runCatching { am.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper())) }
        fun stop() = runCatching { am.unregisterAudioDeviceCallback(callback) }
    }
}
