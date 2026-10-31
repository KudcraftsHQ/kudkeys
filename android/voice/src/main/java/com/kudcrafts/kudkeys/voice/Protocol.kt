package com.kudcrafts.kudkeys.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

/** Wire format of server/README.md "Stream protocol". */
internal object Protocol {
    const val SAMPLE_RATE = 16_000
    /** 100 ms of PCM16 mono at 16 kHz. */
    const val FRAME_BYTES = SAMPLE_RATE / 10 * 2

    fun start(options: VoiceStart): String = buildJsonObject {
        put("type", "start")
        put("sampleRate", SAMPLE_RATE)
        putJsonArray("languages") { options.languages.forEach { add(it) } }
        if (options.keywords.isNotEmpty()) putJsonArray("keywords") { options.keywords.forEach { add(it) } }
        put("private", options.private)
    }.toString()

    val STOP = """{"type":"stop"}"""
    val CANCEL = """{"type":"cancel"}"""

    sealed interface Msg {
        data class Ready(val session: String) : Msg
        data class Partial(val segment: Int, val text: String) : Msg
        data class Final(val segment: Int, val text: String) : Msg
        data object Done : Msg
        data class Error(val code: String, val message: String) : Msg
        data object Unknown : Msg
    }

    fun parse(raw: String): Msg {
        val o: JsonObject = try {
            Json.parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            return Msg.Unknown
        }
        fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        fun int(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
        return when (o["type"]?.jsonPrimitive?.contentOrNull) {
            "ready" -> Msg.Ready(str("session") ?: "")
            "partial" -> Msg.Partial(int("segment") ?: 0, str("text") ?: "")
            "final" -> Msg.Final(int("segment") ?: 0, str("text") ?: "")
            "done" -> Msg.Done
            "error" -> Msg.Error(str("code") ?: "internal", str("message") ?: "")
            else -> Msg.Unknown
        }
    }

    fun errorKind(code: String): VoiceErrorKind = when (code) {
        "bad_request" -> VoiceErrorKind.BadRequest
        "not_started" -> VoiceErrorKind.NotStarted
        "provider" -> VoiceErrorKind.Provider
        "limit" -> VoiceErrorKind.Limit
        else -> VoiceErrorKind.Server
    }
}
