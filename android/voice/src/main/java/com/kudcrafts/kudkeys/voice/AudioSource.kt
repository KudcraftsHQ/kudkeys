package com.kudcrafts.kudkeys.voice

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 16 kHz mono PCM16 little-endian audio. The real one is [MicAudioSource]; tests feed a WAV.
 *
 * [VoiceClient] calls [open] on the caller's thread, [read] in a loop on a background thread, and
 * [close] from whichever thread ends the session, possibly while [read] is blocked.
 */
interface AudioSource {
    /** Start capturing. Throws if the source can't start (no permission, mic busy). */
    fun open()

    /**
     * Block until [buffer] is full (one frame) or the source ends; return the bytes written, or
     * ≤ 0 when the source has ended or was closed.
     */
    fun read(buffer: ByteArray): Int

    /** Stop capturing and free the device now. Idempotent, any thread, may interrupt [read]. */
    fun close()

    /**
     * True when [read] ended because the device failed (route change, mic taken by another app), not because
     * the source was closed or simply ran out. [VoiceClient] then ends the session with [VoiceErrorKind.Mic].
     */
    val failed: Boolean get() = false
}

/** Mic level 0..1 from a PCM16 LE frame: RMS on a -60..0 dBFS scale. */
internal fun pcmLevel(buf: ByteArray, len: Int): Float {
    val samples = len / 2
    if (samples == 0) return 0f
    var sum = 0.0
    for (i in 0 until samples) {
        val s = ((buf[2 * i + 1].toInt() shl 8) or (buf[2 * i].toInt() and 0xff)).toShort().toDouble()
        sum += s * s
    }
    val rms = sqrt(sum / samples) / 32768.0
    if (rms <= 0.0) return 0f
    val db = 20 * log10(rms)
    return ((db + 60) / 60).coerceIn(0.0, 1.0).toFloat()
}
