// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys.sync

import android.content.Context
import androidx.core.os.UserManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kudcrafts.kudkeys.voice.VoiceSettings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import java.util.concurrent.TimeUnit

/**
 * When sync and backup run (docs/v0.4-spec.md): every 6 h and every 24 h with WorkManager (any network, battery
 * not low), once right after sign-in (pull, then push) and when the settings screen closes after a change.
 * Nothing is scheduled while signed out; sign-out cancels it all.
 */
object KudSyncWork {
    const val PERIODIC_SYNC = "kudkeys-sync-periodic"
    const val PERIODIC_BACKUP = "kudkeys-backup-periodic"
    const val SYNC_NOW = "kudkeys-sync-now"
    const val SYNC_INTERVAL_H = 6L
    const val BACKUP_INTERVAL_H = 24L

    val constraints: Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    /** App start, sign-in, the Automatic backup switch: the periodic work matches the account and the switch. */
    fun ensureScheduled(context: Context) = withWork(context) { wm ->
        if (!VoiceSettings(context.applicationContext).isSignedIn) return@withWork cancel(wm)
        wm.enqueueUniquePeriodicWork(
            PERIODIC_SYNC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<KudSyncWorker>(SYNC_INTERVAL_H, TimeUnit.HOURS).setConstraints(constraints).build(),
        )
        if (KudSyncStore(context).state.autoBackup) wm.enqueueUniquePeriodicWork(
            PERIODIC_BACKUP, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<KudBackupWorker>(BACKUP_INTERVAL_H, TimeUnit.HOURS).setConstraints(constraints)
                .setInitialDelay(1, TimeUnit.HOURS).build(),
        ) else wm.cancelUniqueWork(PERIODIC_BACKUP)
    }

    /** One sync as soon as there is a network (sign-in, settings closed after a change). */
    fun syncSoon(context: Context) = withWork(context) { wm ->
        if (!VoiceSettings(context.applicationContext).isSignedIn) return@withWork
        wm.enqueueUniqueWork(
            SYNC_NOW, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<KudSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
        )
    }

    /** Just signed in: start clean (the first sync pulls first), schedule, and sync now. */
    fun onSignedIn(context: Context) {
        KudSyncStore(context).resetForSignOut()
        ensureScheduled(context)
        syncSoon(context)
    }

    /** Signed out (or the server said so): nothing runs any more, and the next account starts clean. */
    fun onSignedOut(context: Context) {
        cancelAll(context)
        KudSyncStore(context).resetForSignOut()
    }

    fun cancelAll(context: Context) = withWork(context) { cancel(it) }

    /** The settings screen closed: sync if a synced pref changed since the last sync. */
    fun onSettingsClosed(context: Context) {
        if (!VoiceSettings(context.applicationContext).isSignedIn) return
        val hash = KudSettingsSync.hash(KudSettingsSync.export(context.prefs()))
        if (hash != KudSyncStore(context).state.settingsHash) syncSoon(context)
    }

    private fun cancel(wm: WorkManager) {
        wm.cancelUniqueWork(PERIODIC_SYNC)
        wm.cancelUniqueWork(PERIODIC_BACKUP)
        wm.cancelUniqueWork(SYNC_NOW)
    }

    /** WorkManager isn't there before the first unlock (its database is credential-encrypted), nor in some tests. */
    private inline fun withWork(context: Context, f: (WorkManager) -> Unit) {
        if (!UserManagerCompat.isUserUnlocked(context)) return
        val wm = try {
            WorkManager.getInstance(context.applicationContext)
        } catch (e: IllegalStateException) {
            Log.w("KudSyncWork", "WorkManager not initialised")
            return
        }
        f(wm)
    }
}

class KudSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (KudSync(applicationContext).sync()) {
        KudSyncOutcome.Done -> Result.success()
        KudSyncOutcome.Retry -> Result.retry()
        KudSyncOutcome.NotSignedIn, KudSyncOutcome.SignedOut -> { KudSyncWork.cancelAll(applicationContext); Result.success() }
        is KudSyncOutcome.Failed -> Result.failure()
    }
}

class KudBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sync = KudSync(applicationContext)
        if (!sync.store.state.autoBackup) return Result.success()
        return when (sync.backup(KIND_AUTO)) {
            KudSyncOutcome.Done -> Result.success()
            KudSyncOutcome.Retry -> Result.retry()
            KudSyncOutcome.NotSignedIn, KudSyncOutcome.SignedOut -> { KudSyncWork.cancelAll(applicationContext); Result.success() }
            is KudSyncOutcome.Failed -> Result.failure()
        }
    }

    companion object {
        const val KIND_AUTO = "auto"
        const val KIND_MANUAL = "manual"
    }
}
