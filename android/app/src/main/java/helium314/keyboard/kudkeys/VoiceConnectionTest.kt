// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import com.kudcrafts.kudkeys.voice.AudioSource
import com.kudcrafts.kudkeys.voice.VoiceClient
import com.kudcrafts.kudkeys.voice.VoiceEndpoint
import com.kudcrafts.kudkeys.voice.VoiceErrorKind
import com.kudcrafts.kudkeys.voice.VoiceStart
import com.kudcrafts.kudkeys.voice.VoiceState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Settings → Voice → Test connection: open a session with silence, send `stop` once the server is ready. */
object VoiceConnectionTest {
    const val OK = "Connected. The token works."

    suspend fun run(endpoint: VoiceEndpoint): String {
        val client = VoiceClient(endpoint = { endpoint }, audioSource = { SilentAudioSource() })
        try {
            if (!client.start(VoiceStart())) return message(client.state.value)
            val ready = withTimeoutOrNull(15_000) {
                client.state.first { it is VoiceState.Listening || it is VoiceState.Error || it is VoiceState.Done }
            } ?: return VoiceErrorKind.NoConnection.userMessage
            if (ready !is VoiceState.Listening) return message(ready)
            return message(client.stopAndWait())
        } finally {
            client.close()
        }
    }

    private fun message(state: VoiceState) = when (state) {
        is VoiceState.Error -> state.kind.userMessage
        is VoiceState.Done, is VoiceState.Listening -> OK
        else -> VoiceErrorKind.NoConnection.userMessage
    }
}

/** 100 ms frames of silence, so the test needs no microphone. */
private class SilentAudioSource : AudioSource {
    @Volatile private var closed = false
    override fun open() = Unit
    override fun read(buffer: ByteArray): Int {
        if (closed) return -1
        try { Thread.sleep(100) } catch (_: InterruptedException) { return -1 }
        buffer.fill(0)
        return if (closed) -1 else buffer.size
    }
    override fun close() { closed = true }
}
