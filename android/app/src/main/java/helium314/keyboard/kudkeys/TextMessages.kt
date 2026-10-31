// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import com.kudcrafts.kudkeys.voice.TextResult

/** The short texts Fix, Instruct and tidy show for a failed call (spec items 1–3). */
enum class TextAction(val label: String) {
    Fix("Fix"), Instruct("Instruct"), Tidy("tidy");

    fun errorMessage(r: TextResult<*>): String = when (r) {
        is TextResult.Ok -> ""
        TextResult.NotSignedIn -> NOT_SIGNED_IN
        TextResult.SignedOut -> if (this == Tidy) "Sign in to tidy — kept as said" else "Sign in to use $label"
        is TextResult.RateLimited -> SLOW_DOWN
        TextResult.Unavailable, TextResult.NoNetwork ->
            if (this == Tidy) "Tidy unavailable — kept as said" else "$label unavailable"
        TextResult.Rejected -> when (this) {
            Fix -> "Couldn't fix this text"
            Instruct -> "Couldn't do that"
            Tidy -> "Couldn't tidy — kept as said"
        }
    }

    companion object {
        const val NOT_SIGNED_IN = "Sign in from settings"
        const val SLOW_DOWN = "Slow down a moment"
    }
}
