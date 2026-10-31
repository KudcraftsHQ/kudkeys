// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import com.kudcrafts.kudkeys.voice.Fixed
import com.kudcrafts.kudkeys.voice.Segment
import com.kudcrafts.kudkeys.voice.TextResult
import com.kudcrafts.kudkeys.voice.TextService
import com.kudcrafts.kudkeys.voice.Tidied
import com.kudcrafts.kudkeys.voice.VoiceStart
import com.kudcrafts.kudkeys.voice.VoiceState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/** A voice client that does what it's told: tests set [state] to what the server would send. */
class FakeVoiceEngine : VoiceEngine {
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

    /** Say [text] and finish. */
    fun say(text: String) {
        state.value = VoiceState.Listening(1000, text, 0.5f)
        state.value = VoiceState.Done(text)
    }
}

/** Text endpoints with canned answers; [hold] makes the next call wait until [release]. */
class FakeTextService : TextService {
    val calls = mutableListOf<String>()
    val privates = mutableListOf<Boolean>()
    val segments = mutableListOf<List<Segment>>()
    var tidyResult: (String) -> TextResult<Tidied> = { TextResult.Ok(Tidied(it, emptyList())) }
    var fixResult: (String) -> TextResult<Fixed> = { TextResult.Ok(Fixed(it, false)) }
    var instructResult: (String, String) -> TextResult<String> = { _, t -> TextResult.Ok(t) }
    var hold: CompletableDeferred<Unit>? = null

    private suspend fun gate() { hold?.await() }
    fun release() { hold?.complete(Unit); hold = null }

    override suspend fun tidy(text: String, private: Boolean, segments: List<Segment>): TextResult<Tidied> {
        calls += "tidy:$text"; privates += private; this.segments += segments; gate(); return tidyResult(text)
    }
    override suspend fun fix(text: String, private: Boolean): TextResult<Fixed> {
        calls += "fix:$text"; privates += private; gate(); return fixResult(text)
    }
    override suspend fun instruct(instruction: String, text: String, private: Boolean): TextResult<String> {
        calls += "instruct:$instruction|$text"; privates += private; gate(); return instructResult(instruction, text)
    }
}

class MemoryDraftStore : DraftStore {
    var json: String? = null
    var saves = 0
    override fun load() = json?.let(VoiceDraft::fromJson)
    override fun save(draft: VoiceDraft?) { saves++; json = draft?.takeIf { !it.isEmpty }?.toJson() }
    override var tidyOn = true
}

/** Collects scheduled blocks; [fire] runs those with a given delay. */
class FakeScheduler {
    val pending = mutableListOf<Pair<Long, () -> Unit>>()
    fun schedule(delayMs: Long, block: () -> Unit): () -> Unit {
        val e = delayMs to block
        pending += e
        return { pending.remove(e) }
    }
    fun fire(delayMs: Long) = pending.filter { it.first == delayMs }.forEach { pending.remove(it); it.second() }
}
