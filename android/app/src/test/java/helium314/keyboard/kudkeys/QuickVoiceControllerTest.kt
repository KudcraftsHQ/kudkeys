// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import com.kudcrafts.kudkeys.voice.VoiceErrorKind
import com.kudcrafts.kudkeys.voice.VoiceStart
import com.kudcrafts.kudkeys.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Hold / release / slide-cancel / lock, driven by fake touches and a fake voice client. */
class QuickVoiceControllerTest {
    private class FakeEngine : VoiceEngine {
        override val state = MutableStateFlow<VoiceState>(VoiceState.Idle)
        val calls = mutableListOf<String>()
        var lastStart: VoiceStart? = null
        override fun start(options: VoiceStart): Boolean {
            calls += "start"; lastStart = options
            state.value = VoiceState.Connecting(0, 0f)
            return true
        }
        override fun stop() { calls += "stop"; state.value = VoiceState.Finishing(state.value.liveText) }
        override fun cancel() { calls += "cancel"; state.value = VoiceState.Idle }
        override fun reset() { calls += "reset"; state.value = VoiceState.Idle }
        override fun close() { calls += "close" }
    }

    private class FakeHost : QuickVoiceController.Host {
        var token = true
        var mic = true
        var private = false
        val inserted = mutableListOf<String>()
        val renders = mutableListOf<VoiceBarState>()
        val haptics = mutableListOf<Unit>()
        var permissionRequests = 0
        val pending = mutableListOf<Pair<Long, () -> Unit>>()
        override fun hasToken() = token
        override fun hasMicPermission() = mic
        override fun requestMicPermission() { permissionRequests++ }
        override fun isPrivateField() = private
        override fun haptic() { haptics += Unit }
        override fun insert(text: String) { inserted += text }
        override fun render(state: VoiceBarState) { renders += state }
        override fun barWidth() = 1000
        override fun lockDistance() = 120
        override fun schedule(delayMs: Long, block: () -> Unit): () -> Unit {
            val entry = delayMs to block
            pending += entry
            return { pending.remove(entry) }
        }
        /** Run everything scheduled with exactly this delay (the hold timer or the message timer). */
        fun fire(delayMs: Long) = pending.filter { it.first == delayMs }.forEach { pending.remove(it); it.second() }
        val last get() = renders.last()
    }

    private val engine = FakeEngine()
    private val host = FakeHost()
    private val controller = QuickVoiceController(engine, host, CoroutineScope(Dispatchers.Unconfined))

    private fun holdToRecord(x: Float = 950f, y: Float = 1500f) {
        controller.onMicDown(x, y)
        host.fire(QuickVoiceController.HOLD_MS)
    }

    @Test fun holdRecordsAndReleaseInserts() {
        host.private = true
        holdToRecord()
        assertEquals(listOf("start"), engine.calls)
        assertEquals(true, engine.lastStart?.private)
        assertEquals(1, host.haptics.size)
        assertEquals(VoiceBarState.Recording(0, "", false), host.last)

        engine.state.value = VoiceState.Listening(1200, "halo", 0.4f)
        assertEquals(VoiceBarState.Recording(1200, "halo", false), host.last)
        controller.onMicMove(940f, 1490f) // small wobble: still recording
        engine.state.value = VoiceState.Listening(2300, "halo apa kabar", 0.4f)
        assertEquals(VoiceBarState.Recording(2300, "halo apa kabar", false), host.last)

        controller.onMicUp()
        assertEquals(listOf("start", "stop"), engine.calls)
        assertEquals(VoiceBarState.Finishing("halo apa kabar"), host.last)
        engine.state.value = VoiceState.Done("halo apa kabar")
        assertEquals(listOf("halo apa kabar"), host.inserted)
        assertEquals(listOf("start", "stop", "reset"), engine.calls)
        assertEquals(VoiceBarState.Idle, host.last)
        assertEquals(QuickVoiceController.Phase.Idle, controller.phase)
    }

    @Test fun shortTapShowsHint() {
        controller.onMicDown(950f, 1500f)
        controller.onMicUp()
        host.fire(QuickVoiceController.HOLD_MS) // the hold timer was cancelled
        assertEquals(emptyList(), engine.calls)
        assertEquals(VoiceBarState.Message("Hold to talk"), host.last)
        host.fire(QuickVoiceController.MESSAGE_MS)
        assertEquals(VoiceBarState.Idle, host.last)
    }

    @Test fun slideLeftCancels() {
        holdToRecord()
        engine.state.value = VoiceState.Listening(900, "jangan", 0.3f)
        controller.onMicMove(800f, 1500f) // 150 px of 1000: not yet
        assertEquals(listOf("start"), engine.calls)
        controller.onMicMove(640f, 1510f) // 310 px > 30 %
        assertEquals(listOf("start", "cancel"), engine.calls)
        assertEquals(VoiceBarState.Idle, host.last)
        controller.onMicUp()
        assertEquals(listOf("start", "cancel"), engine.calls) // release after cancel does nothing
        assertTrue(host.inserted.isEmpty())
    }

    @Test fun slideUpLocksAndStopButtonInserts() {
        holdToRecord()
        engine.state.value = VoiceState.Listening(1000, "catatan", 0.3f)
        controller.onMicMove(945f, 1360f) // 140 px up
        assertEquals(QuickVoiceController.Phase.Locked, controller.phase)
        assertEquals(VoiceBarState.Recording(1000, "catatan", true), host.last)
        assertEquals(2, host.haptics.size)
        controller.onMicMove(500f, 1360f) // sliding left once locked does not cancel
        controller.onMicUp() // the locking finger lifts: keeps recording
        assertEquals(listOf("start"), engine.calls)
        engine.state.value = VoiceState.Listening(4000, "catatan panjang", 0.3f)
        assertEquals(VoiceBarState.Recording(4000, "catatan panjang", true), host.last)

        controller.onMicDown(950f, 1500f) // tap the stop button
        controller.onMicUp()
        assertEquals(listOf("start", "stop"), engine.calls)
        engine.state.value = VoiceState.Done("catatan panjang")
        assertEquals(listOf("catatan panjang"), host.inserted)
        assertEquals(VoiceBarState.Idle, host.last)
    }

    @Test fun noTokenShowsSettingsHint() {
        host.token = false
        holdToRecord()
        assertEquals(emptyList(), engine.calls)
        assertEquals(VoiceBarState.Message("Set your voice token in settings"), host.last)
        controller.onMicUp()
        assertEquals(VoiceBarState.Message("Set your voice token in settings"), host.last) // no "Hold to talk" on top
    }

    @Test fun missingPermissionOpensActivity() {
        host.mic = false
        holdToRecord()
        assertEquals(1, host.permissionRequests)
        assertEquals(emptyList(), engine.calls)
    }

    @Test fun errorIsShownBriefly() {
        holdToRecord()
        engine.state.value = VoiceState.Error(VoiceErrorKind.TokenRejected, "401")
        assertEquals(VoiceBarState.Message("Voice: token rejected"), host.last)
        assertEquals(listOf("start", "reset"), engine.calls)
        controller.onMicUp() // already over: nothing to stop
        assertEquals(listOf("start", "reset"), engine.calls)
        host.fire(QuickVoiceController.MESSAGE_MS)
        assertEquals(VoiceBarState.Idle, host.last)
        assertTrue(host.inserted.isEmpty())
    }

    @Test fun errorAfterReleaseKeepsWhatArrived() {
        holdToRecord()
        engine.state.value = VoiceState.Listening(2000, "sebagian", 0.3f)
        controller.onMicUp()
        engine.state.value = VoiceState.Error(VoiceErrorKind.NoConnection, "dropped", "sebagian")
        assertEquals(listOf("sebagian"), host.inserted)
        assertEquals(VoiceBarState.Message("Voice: no connection"), host.last)
    }

    @Test fun leavingTheFieldCancels() {
        holdToRecord()
        engine.state.value = VoiceState.Listening(1500, "rahasia", 0.3f)
        controller.cancelSession()
        assertEquals(listOf("start", "cancel"), engine.calls)
        assertEquals(VoiceBarState.Idle, host.last)
        controller.onMicUp()
        assertTrue(host.inserted.isEmpty())
    }

    @Test fun touchCancelDropsHeldRecording() {
        holdToRecord()
        controller.onMicTouchCancelled()
        assertEquals(listOf("start", "cancel"), engine.calls)
    }

    @Test fun leadingSpace() {
        assertEquals(" halo", VoiceText.withLeadingSpace("oke", "halo"))
        assertEquals("halo", VoiceText.withLeadingSpace("oke ", "halo"))
        assertEquals("halo", VoiceText.withLeadingSpace("", "halo"))
        assertEquals("halo", VoiceText.withLeadingSpace(null, "halo"))
        assertEquals("halo", VoiceText.withLeadingSpace("baris\n", "halo"))
        assertEquals("0:07", VoiceText.elapsed(7_400))
        assertEquals("1:05", VoiceText.elapsed(65_000))
    }
}
