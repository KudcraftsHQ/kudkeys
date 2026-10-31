// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M4: the voice draft lives in credential-encrypted storage, and only in memory while the user is locked. */
@RunWith(RobolectricTestRunner::class)
class KudDraftStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences(PrefsDraftStore.PREFS, 0).also { it.edit().clear().commit() }
    private val dir = File(context.cacheDir, "ce-test").also { it.deleteRecursively() }
    private val file = File(dir, PrefsDraftStore.DRAFT_FILE)

    @Test fun credentialDirIsTheDeviceProtectedOnesTwin() {
        val info = ApplicationInfo().apply {
            deviceProtectedDataDir = "/data/user_de/0/com.kudcrafts.kudkeys"
            dataDir = "/data/user_de/0/com.kudcrafts.kudkeys" // defaultToDeviceProtectedStorage
        }
        assertEquals(File("/data/user/0/com.kudcrafts.kudkeys"), PrefsDraftStore.credentialDataDir(info))
        // on an adopted SD card too
        info.deviceProtectedDataDir = "/mnt/expand/1234/user_de/10/com.kudcrafts.kudkeys"
        info.dataDir = info.deviceProtectedDataDir
        assertEquals(File("/mnt/expand/1234/user/10/com.kudcrafts.kudkeys"), PrefsDraftStore.credentialDataDir(info))
        // an app whose data dir is already the credential one
        info.dataDir = "/data/user/0/x"
        assertEquals(File("/data/user/0/x"), PrefsDraftStore.credentialDataDir(info))
    }

    @Test fun lockedUser_memoryOnly_thenWrittenAtUnlock() {
        var unlocked = false
        val store = PrefsDraftStore(prefs) { if (unlocked) file else null }
        val d = VoiceDraft("rahasia", "rahasia")
        store.save(d)
        assertFalse(file.exists())
        assertEquals(d, store.load())
        unlocked = true
        assertEquals(d, store.load())
        assertTrue(file.exists())
        assertFalse(prefs.contains(PrefsDraftStore.KEY_DRAFT))
        store.save(null)
        assertFalse(file.exists())
        assertNull(store.load())
    }

    @Test fun draftLeftInDevicePrefs_movesToTheFile() {
        val d = VoiceDraft("lama", "lama")
        prefs.edit().putString(PrefsDraftStore.KEY_DRAFT, d.toJson()).commit()
        val store = PrefsDraftStore(prefs) { file }
        assertEquals(d, store.load())
        assertTrue(file.exists())
        assertFalse(prefs.contains(PrefsDraftStore.KEY_DRAFT))
        assertEquals(d, PrefsDraftStore(prefs) { file }.load())
    }
}
