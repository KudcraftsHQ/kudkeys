// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.view.inputmethod.EditorInfo

/**
 * Which field quick voice is talking into. Two EditorInfos for the same field give equal values; a web page
 * moving focus between its inputs (restartInput on the same view) changes at least one of these.
 */
data class VoiceField(
    val fieldId: Int,
    val fieldName: String?,
    val packageName: String?,
    val inputType: Int,
    val imeOptions: Int,
    val private: Boolean,
) {
    companion object {
        @JvmStatic
        fun of(editorInfo: EditorInfo?): VoiceField? = editorInfo?.let {
            VoiceField(it.fieldId, it.fieldName, it.packageName, it.inputType, it.imeOptions, KudPrivacy.isPrivate(it))
        }
    }
}
