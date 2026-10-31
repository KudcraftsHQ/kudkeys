// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import com.kudcrafts.kudkeys.voice.VoiceSettings
import helium314.keyboard.latin.R
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M3: Android backup keeps HeliBoard's settings but never the voice prefs (the token) or the voice draft. */
@RunWith(RobolectricTestRunner::class)
class KudBackupRulesTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** (section, domain, path) of every <exclude> in an XML resource. */
    private fun excludes(res: Int): List<Triple<String, String, String>> {
        val parser = context.resources.getXml(res)
        val out = mutableListOf<Triple<String, String, String>>()
        var section = ""
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "cloud-backup", "device-transfer" -> section = parser.name
                "exclude" -> out += Triple(section, parser.getAttributeValue(null, "domain"), parser.getAttributeValue(null, "path"))
            }
        }
        return out
    }

    private val voicePrefs = "${VoiceSettings.PREFS}.xml"
    private val draftPrefs = "${PrefsDraftStore.PREFS}.xml"

    @Test fun backupStaysOnForTheKeyboardSettings() {
        assertTrue(context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
    }

    @Test fun legacyBackupExcludesVoicePrefsAndDraftOnly() {
        assertEquals(
            (listOf(voicePrefs, draftPrefs).flatMap { listOf(Triple("", "sharedpref", it), Triple("", "device_sharedpref", it)) } +
                listOf(Triple("", "file", PrefsDraftStore.DRAFT_FILE), Triple("", "device_file", PrefsDraftStore.DRAFT_FILE))).toSet(),
            excludes(R.xml.kud_backup_rules).toSet(),
        )
    }

    @Test fun cloudBackupAndTransferExcludeVoicePrefsOnly() {
        val expected = listOf("cloud-backup", "device-transfer").flatMap { section ->
            listOf(voicePrefs, draftPrefs).flatMap { listOf(Triple(section, "sharedpref", it), Triple(section, "device_sharedpref", it)) } +
                listOf(Triple(section, "file", PrefsDraftStore.DRAFT_FILE), Triple(section, "device_file", PrefsDraftStore.DRAFT_FILE))
        }.toSet()
        assertEquals(expected, excludes(R.xml.kud_data_extraction_rules).toSet())
    }
}
