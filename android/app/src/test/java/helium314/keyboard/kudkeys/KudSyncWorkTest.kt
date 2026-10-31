// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.kudcrafts.kudkeys.voice.VoiceSettings
import helium314.keyboard.kudkeys.sync.KudBackupWorker
import helium314.keyboard.kudkeys.sync.KudSyncStore
import helium314.keyboard.kudkeys.sync.KudSyncWork
import helium314.keyboard.kudkeys.sync.KudSyncWorker
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** When sync and backup run: WorkManager's periodic work, sign-in, sign-out, the switch, settings closed. */
@RunWith(RobolectricTestRunner::class)
class KudSyncWorkTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val voice = VoiceSettings(ctx.getSharedPreferences(VoiceSettings.PREFS, Context.MODE_PRIVATE))
    private val store = KudSyncStore(ctx)

    init {
        voice.signOut()
        store.resetForSignOut()
        store.update { it.copy(autoBackup = true) }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ctx, Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    private val wm get() = WorkManager.getInstance(ctx)
    private fun live(name: String): List<WorkInfo> =
        wm.getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }

    @Test fun signedOut_nothingScheduled() {
        KudSyncWork.ensureScheduled(ctx)
        KudSyncWork.syncSoon(ctx)
        KudSyncWork.onSettingsClosed(ctx)
        for (name in listOf(KudSyncWork.PERIODIC_SYNC, KudSyncWork.PERIODIC_BACKUP, KudSyncWork.SYNC_NOW))
            assertTrue(live(name).isEmpty(), "$name scheduled while signed out")
    }

    @Test fun signIn_schedulesPeriodicSyncAndBackup_andSyncsNow() {
        voice.signedIn("sess.tok", "Test", "test@example.com")
        KudSyncWork.onSignedIn(ctx)

        val periodic = live(KudSyncWork.PERIODIC_SYNC).single()
        assertEquals(TimeUnit.HOURS.toMillis(6), periodic.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, periodic.constraints.requiredNetworkType)
        assertTrue(periodic.constraints.requiresBatteryNotLow())

        val backup = live(KudSyncWork.PERIODIC_BACKUP).single()
        assertEquals(TimeUnit.HOURS.toMillis(24), backup.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, backup.constraints.requiredNetworkType)
        assertTrue(backup.constraints.requiresBatteryNotLow())

        // the first sync after sign-in waits only for a network (it ran or is enqueued)
        assertEquals(1, wm.getWorkInfosForUniqueWork(KudSyncWork.SYNC_NOW).get().size)
        assertTrue(!store.state.initialDone) // it will pull first
    }

    @Test fun automaticBackupOff_cancelsTheDailyBackup() {
        voice.signedIn("sess.tok", "Test", "test@example.com")
        KudSyncWork.ensureScheduled(ctx)
        assertEquals(1, live(KudSyncWork.PERIODIC_BACKUP).size)
        store.update { it.copy(autoBackup = false) }
        KudSyncWork.ensureScheduled(ctx)
        assertTrue(live(KudSyncWork.PERIODIC_BACKUP).isEmpty())
        assertEquals(1, live(KudSyncWork.PERIODIC_SYNC).size)
    }

    @Test fun signOut_cancelsEverything_andForgetsSyncState() {
        voice.signedIn("sess.tok", "Test", "test@example.com")
        KudSyncWork.onSignedIn(ctx)
        store.update { it.copy(initialDone = true, lastSyncAt = 5) }
        voice.signOut()
        KudSyncWork.onSignedOut(ctx)
        for (name in listOf(KudSyncWork.PERIODIC_SYNC, KudSyncWork.PERIODIC_BACKUP, KudSyncWork.SYNC_NOW))
            assertTrue(live(name).isEmpty(), "$name still scheduled after sign-out")
        assertEquals(0, store.state.lastSyncAt)
    }

    @Test fun settingsClosedAfterAChange_syncsSoon() {
        voice.signedIn("sess.tok", "Test", "test@example.com")
        store.update { it.copy(settingsHash = "something else") }
        ctx.prefs().edit().putBoolean("show_hints", true).commit()
        KudSyncWork.onSettingsClosed(ctx)
        assertEquals(1, wm.getWorkInfosForUniqueWork(KudSyncWork.SYNC_NOW).get().size)
    }

    @Test fun workersSignedOut_doNothingAndCancel() = runBlocking<Unit> {
        voice.signedIn("sess.tok", "Test", "test@example.com")
        KudSyncWork.ensureScheduled(ctx)
        voice.markSignedOut() // e.g. a 401 on voice
        assertEquals(ListenableWorker.Result.success(), TestListenableWorkerBuilder<KudSyncWorker>(ctx).build().doWork())
        assertEquals(ListenableWorker.Result.success(), TestListenableWorkerBuilder<KudBackupWorker>(ctx).build().doWork())
        assertTrue(live(KudSyncWork.PERIODIC_SYNC).isEmpty())
        assertTrue(live(KudSyncWork.PERIODIC_BACKUP).isEmpty())
    }
}
