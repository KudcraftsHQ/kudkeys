// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Kud Keys privacy rule (spec item 5). A field is private when it asks for no learning (Chrome incognito),
 * is a password, a URL or a search field, or belongs to a launcher. Private fields learn nothing and show the
 * amber "Private" pill; voice still works and sends `"private": true`.
 */
object KudPrivacy {
    /** Launchers whose search box counts as a search field even when the field doesn't say so. */
    val KNOWN_LAUNCHERS = setOf("com.teslacoilsw.launcher") // Nova

    /** The default HOME app, refreshed by [refreshHomePackage]. */
    @Volatile
    var homePackage: String? = null
        private set

    fun refreshHomePackage(context: Context) {
        homePackage = runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
                ?.takeIf { it != "android" } // "android" is the chooser when no default is set
        }.getOrNull()
    }

    fun launcherPackages(): Set<String> = homePackage?.let { KNOWN_LAUNCHERS + it } ?: KNOWN_LAUNCHERS

    @JvmStatic
    @JvmOverloads
    fun isPrivate(
        editorInfo: EditorInfo?,
        packageName: String? = editorInfo?.packageName,
        launchers: Set<String> = launcherPackages(),
    ): Boolean {
        if (packageName != null && packageName in launchers) return true
        if (editorInfo == null) return false
        val options = editorInfo.imeOptions
        if (options and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return true
        if (options and EditorInfo.IME_MASK_ACTION == EditorInfo.IME_ACTION_SEARCH) return true
        val type = editorInfo.inputType
        val variation = type and InputType.TYPE_MASK_VARIATION
        return when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation in PRIVATE_TEXT_VARIATIONS
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private val PRIVATE_TEXT_VARIATIONS = setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_URI,
        InputType.TYPE_TEXT_VARIATION_FILTER,
    )
}
