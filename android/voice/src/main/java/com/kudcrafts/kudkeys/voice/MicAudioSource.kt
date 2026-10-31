package com.kudcrafts.kudkeys.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * The phone's mic: AudioRecord, VOICE_RECOGNITION source, 16 kHz mono PCM16.
 * The caller must hold RECORD_AUDIO (see [hasMicPermission]).
 */
class MicAudioSource : AudioSource {
    private val lock = Any()
    private var record: AudioRecord? = null
    private var reading = false
    private var closed = false
    @Volatile private var readFailed = false

    override val failed: Boolean get() = readFailed

    @SuppressLint("MissingPermission")
    override fun open() {
        val rate = Protocol.SAMPLE_RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, Protocol.FRAME_BYTES * 4),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("AudioRecord did not initialise")
        }
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            throw IllegalStateException("AudioRecord did not start")
        }
        synchronized(lock) { record = rec }
    }

    override fun read(buffer: ByteArray): Int {
        val rec = synchronized(lock) {
            if (closed) return -1
            reading = true
            record ?: return -1
        }
        var filled = 0
        try {
            while (filled < buffer.size) {
                val n = rec.read(buffer, filled, buffer.size - filled)
                if (n <= 0) {
                    // An error code (or nothing) while still open: the mic went away under us.
                    if (!synchronized(lock) { closed }) readFailed = true
                    break
                }
                filled += n
                if (synchronized(lock) { closed }) break
            }
        } finally {
            synchronized(lock) {
                reading = false
                if (closed) releaseLocked()
            }
        }
        return if (filled > 0) filled else -1
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            // stop() frees the mic now and makes a blocked read() return.
            runCatching { record?.stop() }
            if (!reading) releaseLocked()
        }
    }

    private fun releaseLocked() {
        runCatching { record?.release() }
        record = null
    }
}
