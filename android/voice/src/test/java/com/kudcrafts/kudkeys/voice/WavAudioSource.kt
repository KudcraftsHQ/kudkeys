package com.kudcrafts.kudkeys.voice

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Feeds a 16 kHz mono PCM16 WAV as if it were the mic. [frameDelayMs] > 0 paces it like real time. */
class WavAudioSource(private val pcm: ByteArray, private val frameDelayMs: Long = 0) : AudioSource {
    constructor(file: File, frameDelayMs: Long = 0) : this(readWavPcm(file), frameDelayMs)

    @Volatile var opened = false; private set
    @Volatile var closed = false; private set
    @Volatile var exhausted = false; private set
    private var pos = 0

    override fun open() { opened = true }

    override fun read(buffer: ByteArray): Int {
        if (closed) return -1
        if (frameDelayMs > 0) Thread.sleep(frameDelayMs)
        if (pos >= pcm.size) { exhausted = true; return -1 }
        val n = minOf(buffer.size, pcm.size - pos)
        System.arraycopy(pcm, pos, buffer, 0, n)
        pos += n
        if (pos >= pcm.size) exhausted = true
        return n
    }

    override fun close() { closed = true }
}

fun readWavPcm(file: File): ByteArray {
    val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    require(String(b.array(), 0, 4) == "RIFF" && String(b.array(), 8, 4) == "WAVE") { "not a WAV" }
    var p = 12
    var rate = 0; var channels = 0; var bits = 0
    while (p + 8 <= b.limit()) {
        val id = String(b.array(), p, 4)
        val size = b.getInt(p + 4)
        if (id == "fmt ") { channels = b.getShort(p + 10).toInt(); rate = b.getInt(p + 12); bits = b.getShort(p + 22).toInt() }
        if (id == "data") {
            require(rate == 16000 && channels == 1 && bits == 16) { "need 16 kHz mono PCM16, got $rate/$channels/$bits" }
            return b.array().copyOfRange(p + 8, minOf(p + 8 + size, b.limit()))
        }
        p += 8 + size + (size and 1)
    }
    error("no data chunk")
}

fun writeWav(file: File, pcm: ByteArray) {
    val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
    h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
    h.put("data".toByteArray()).putInt(pcm.size)
    file.writeBytes(h.array() + pcm)
}

/** [seconds] of a 440 Hz tone, PCM16 LE. */
fun tonePcm(seconds: Double): ByteArray {
    val n = (16000 * seconds).toInt()
    val out = ByteArray(n * 2)
    for (i in 0 until n) {
        val v = (Math.sin(2 * Math.PI * 440 * i / 16000) * 8000).toInt()
        out[2 * i] = (v and 0xff).toByte(); out[2 * i + 1] = (v shr 8 and 0xff).toByte()
    }
    return out
}
