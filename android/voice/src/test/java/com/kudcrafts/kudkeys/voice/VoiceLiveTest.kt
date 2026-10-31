package com.kudcrafts.kudkeys.voice

import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in: `KUDKEYS_LIVE=1 ./gradlew testDebugUnitTest --tests '*VoiceLiveTest*'`.
 * Streams `$TMPDIR/kudkeys-voice/fm.wav` in real time to production with the token from
 * `~/.config/kudkeys/token`, and writes the result to `$TMPDIR/kudkeys-voice/live-result.txt`.
 */
class VoiceLiveTest {
    @Test fun streamsAClipToProduction() {
        assumeTrue("set KUDKEYS_LIVE=1", System.getenv("KUDKEYS_LIVE") == "1")
        val home = System.getProperty("user.home")
        val token = File(home, ".config/kudkeys/token").readText().trim()
        val dir = File(System.getenv("TMPDIR") ?: "$home/.cache/tmp", "kudkeys-voice")
        val source = WavAudioSource(File(dir, "fm.wav"), frameDelayMs = 100)
        val client = VoiceClient(endpoint = { VoiceEndpoint(VoiceSettings.DEFAULT_URL, token) }, audioSource = { source })
        val log = StringBuilder()
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000

        val end = runBlocking {
            val watch = launch {
                var last: VoiceState? = null
                client.state.collect { s ->
                    // Log state changes and text changes, not every level tick.
                    val key = s.javaClass.simpleName + s.liveText
                    if (last == null || last!!.javaClass.simpleName + last!!.liveText != key) {
                        log.appendLine("${ms()} ms  ${s.javaClass.simpleName}  ${s.liveText}")
                    }
                    last = s
                }
            }
            check(client.start(VoiceStart(keywords = listOf("FaceMap"))))
            withTimeout(120_000) {
                while (!source.exhausted && client.state.value.isActive) kotlinx.coroutines.delay(50)
            }
            val stopAt = ms()
            val end = withTimeout(15_000) { client.stopAndWait() }
            log.appendLine("stop at $stopAt ms, ended at ${ms()} ms (${ms() - stopAt} ms after stop)")
            watch.cancel()
            end
        }
        log.appendLine("END: $end")
        File(dir, "live-result.txt").writeText(log.toString())
        println(log)
        check(end is VoiceState.Done && end.text.isNotBlank()) { "live run ended with $end" }
    }
}
