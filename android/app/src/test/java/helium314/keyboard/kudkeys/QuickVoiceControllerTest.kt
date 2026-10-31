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
        var blocked = false
        var settingsOpened = 0
        val pending = mutableListOf<Pair<Long, () -> Unit>>()
        override fun hasToken() = token
        override fun hasMicPermission() = mic
        override fun requestMicPermission() { permissionRequests++ }
        override fun isMicBlocked() = blocked
        override fun openMicSettings() { settingsOpened++ }
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

    // --- field identity (H2) -------------------------------------------------------------------------

    @Test fun anotherFieldCancelsALockedRecording() {
        controller.onField("chat")
        holdToRecord()
        controller.onMicMove(945f, 1360f) // lock
        engine.state.value = VoiceState.Listening(3000, "untuk chat", 0.3f)
        controller.onField("search") // the page moved focus with restartInput
        assertEquals(listOf("start", "cancel"), engine.calls)
        assertEquals(QuickVoiceController.Phase.Idle, controller.phase)
        controller.onMicDown(950f, 1500f); controller.onMicUp() // a tap on Mic now is just a tap
        engine.state.value = VoiceState.Done("untuk chat")
        assertTrue(host.inserted.isEmpty())
    }

    @Test fun anotherFieldDuringTheFinishingWaitDropsTheText() {
        controller.onField("chat")
        holdToRecord()
        engine.state.value = VoiceState.Listening(2000, "untuk chat", 0.3f)
        controller.onMicUp()
        assertEquals(QuickVoiceController.Phase.Finishing, controller.phase)
        controller.onField("search")
        assertEquals(listOf("start", "stop", "cancel"), engine.calls)
        engine.state.value = VoiceState.Done("untuk chat") // a late done is ignored
        assertTrue(host.inserted.isEmpty())
    }

    @Test fun sameFieldAgainKeepsTheSession() {
        controller.onField("chat")
        holdToRecord()
        controller.onField("chat") // onStartInputView again for the same field
        assertEquals(listOf("start"), engine.calls)
        controller.onMicUp()
        engine.state.value = VoiceState.Done("halo")
        assertEquals(listOf("halo"), host.inserted)
    }

    // --- hiding the keyboard, screen off (L1, L3) --------------------------------------------------

    @Test fun hidingWhileFinishingStillInsertsIntoTheSameField() {
        controller.onField("chat")
        holdToRecord()
        engine.state.value = VoiceState.Listening(2000, "jangan hilang", 0.3f)
        controller.onMicUp()
        controller.onHidden() // swipe down / Back within the wait
        assertEquals(listOf("start", "stop"), engine.calls)
        engine.state.value = VoiceState.Done("jangan hilang")
        assertEquals(listOf("jangan hilang"), host.inserted)
    }

    @Test fun hidingOrScreenOffDuringALockedRecordingCancelsIt() {
        controller.onField("chat")
        holdToRecord()
        controller.onMicMove(945f, 1360f)
        assertEquals(QuickVoiceController.Phase.Locked, controller.phase)
        controller.onHidden()
        assertEquals(listOf("start", "cancel"), engine.calls)
        assertEquals(QuickVoiceController.Phase.Idle, controller.phase)
    }

    @Test fun fieldFinishedWhileFinishingDropsTheText() {
        controller.onField("chat")
        holdToRecord()
        controller.onMicUp()
        controller.onHidden()
        controller.onField(null) // onFinishInput: the field is gone
        assertEquals(listOf("start", "stop", "cancel"), engine.calls)
        assertTrue(host.inserted.isEmpty())
    }

    // --- errors keep the text (M1) -----------------------------------------------------------------

    @Test fun errorWhileLockedInsertsWhatArrived() {
        holdToRecord()
        controller.onMicMove(945f, 1360f)
        engine.state.value = VoiceState.Listening(120_000, "dua menit dikte", 0.3f)
        engine.state.value = VoiceState.Error(VoiceErrorKind.NoConnection, "handover", "dua menit dikte")
        assertEquals(listOf("dua menit dikte"), host.inserted)
        assertEquals(VoiceBarState.Message("Voice: no connection"), host.last)
        assertEquals(QuickVoiceController.Phase.Idle, controller.phase)
    }

    @Test fun errorWhileHoldingInsertsWhatArrived() {
        holdToRecord()
        engine.state.value = VoiceState.Error(VoiceErrorKind.Mic, "mic read failed", "sampai sini")
        assertEquals(listOf("sampai sini"), host.inserted)
        assertEquals(VoiceBarState.Message("Voice: microphone unavailable"), host.last)
        controller.onMicUp() // the release after the error does nothing
        assertEquals(listOf("start", "reset"), engine.calls)
    }

    // --- mic blocked for good (M2) -----------------------------------------------------------------

    @Test fun blockedMicShowsATappableMessageInsteadOfTheActivity() {
        host.mic = false
        host.blocked = true
        holdToRecord()
        assertEquals(0, host.permissionRequests)
        assertEquals(VoiceBarState.Message(QuickVoiceController.MIC_BLOCKED, opensSettings = true), host.last)
        controller.onMicUp()
        assertEquals(VoiceBarState.Message(QuickVoiceController.MIC_BLOCKED, opensSettings = true), host.last)
        controller.onMessageTapped()
        assertEquals(1, host.settingsOpened)
        assertEquals(VoiceBarState.Idle, host.last)
        controller.onMessageTapped() // the message is gone: a second tap does nothing
        assertEquals(1, host.settingsOpened)
        holdToRecord() // still blocked: the message again, never the permission activity
        assertEquals(0, host.permissionRequests)
        assertEquals(emptyList(), engine.calls)
    }

    @Test fun plainMessagesDontOpenSettings() {
        controller.onMicDown(950f, 1500f)
        controller.onMicUp() // "Hold to talk"
        controller.onMessageTapped()
        assertEquals(0, host.settingsOpened)
    }

    // --- comma long-press: hands-free start --------------------------------------------------------

    @Test fun startLockedRecordsHandsFreeAndStopButtonInserts() {
        controller.onField("chat")
        controller.startLocked()
        assertEquals(listOf("start"), engine.calls)
        assertEquals(QuickVoiceController.Phase.Locked, controller.phase)
        assertEquals(VoiceBarState.Recording(0, "", true), host.last)
        engine.state.value = VoiceState.Listening(1500, "tanpa tangan", 0.3f)
        controller.onMicDown(950f, 1500f) // the first touch on the stop button stops
        controller.onMicUp()
        assertEquals(listOf("start", "stop"), engine.calls)
        engine.state.value = VoiceState.Done("tanpa tangan")
        assertEquals(listOf("tanpa tangan"), host.inserted)
    }

    @Test fun startLockedWithoutTokenShowsTheHint() {
        host.token = false
        controller.startLocked()
        assertEquals(emptyList(), engine.calls)
        assertEquals(VoiceBarState.Message("Set your voice token in settings"), host.last)
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
