// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.text.InputType.*
import android.view.inputmethod.EditorInfo
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class KudPrivacyTest {
    private fun field(type: Int, options: Int = 0, pkg: String = "com.whatsapp") =
        EditorInfo().apply { inputType = type; imeOptions = options; packageName = pkg }

    private fun private(ei: EditorInfo, home: String? = null) =
        KudPrivacy.isPrivate(ei, ei.packageName, KudPrivacy.KNOWN_LAUNCHERS + listOfNotNull(home))

    @Test fun normalFieldsAreNotPrivate() {
        assertFalse(private(field(TYPE_CLASS_TEXT)))
        assertFalse(private(field(TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_SHORT_MESSAGE or TYPE_TEXT_FLAG_MULTI_LINE)))
        assertFalse(private(field(TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_EMAIL_ADDRESS)))
        assertFalse(private(field(TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_PERSON_NAME, EditorInfo.IME_ACTION_SEND)))
        assertFalse(private(field(TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_WEB_EDIT_TEXT, EditorInfo.IME_ACTION_GO)))
        assertFalse(private(field(TYPE_CLASS_NUMBER)))
        assertFalse(private(field(TYPE_CLASS_PHONE)))
        assertFalse(KudPrivacy.isPrivate(null, null, emptySet()))
    }

    @Test fun incognitoFlag() {
        assertTrue(private(field(TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)))
        assertTrue(private(field(TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_URI,
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_GO, "com.android.chrome")))
    }

    @Test fun passwords() {
        assertTrue(private(field(TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_PASSWORD)))
        assertTrue(private(field(TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)))
        assertTrue(private(field(TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_WEB_PASSWORD)))
        assertTrue(private(field(TYPE_CLASS_NUMBER or TYPE_NUMBER_VARIATION_PASSWORD)))
    }

    @Test fun url() {
        assertTrue(private(field(TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_GO)))
    }

    @Test fun search() {
        assertTrue(private(field(TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEARCH)))
        assertTrue(private(field(TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI)))
        assertTrue(private(field(TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_FILTER)))
    }

    @Test fun launchers() {
        assertTrue(private(field(TYPE_CLASS_TEXT, pkg = "com.teslacoilsw.launcher"))) // Nova
        assertFalse(private(field(TYPE_CLASS_TEXT, pkg = "com.google.android.apps.nexuslauncher")))
        assertTrue(private(field(TYPE_CLASS_TEXT, pkg = "com.google.android.apps.nexuslauncher"), home = "com.google.android.apps.nexuslauncher"))
    }
}
