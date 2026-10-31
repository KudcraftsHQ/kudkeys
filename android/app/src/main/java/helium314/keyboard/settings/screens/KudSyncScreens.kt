// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kudcrafts.kudkeys.voice.BackupInfo
import com.kudcrafts.kudkeys.voice.SyncResult
import helium314.keyboard.kudkeys.sync.KudBackupWorker
import helium314.keyboard.kudkeys.sync.KudSync
import helium314.keyboard.kudkeys.sync.KudSyncOutcome
import helium314.keyboard.kudkeys.sync.KudSyncWork
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.PreferenceCategory
import kotlinx.coroutines.launch

/*
 * Kud Keys v0.4: Account → Sync (last synced / last backup, Sync now, Back up now, Backups, Automatic backup) and
 * the Backups list (restore, delete). The work itself is KudSync; these screens only start it and show the outcome.
 */

/** "5 minutes ago", "Just now", "Never". */
fun kudRelativeTime(ctx: Context, ms: Long, now: Long = System.currentTimeMillis()): String = when {
    ms <= 0 -> ctx.getString(R.string.kud_sync_never)
    now - ms < DateUtils.MINUTE_IN_MILLIS -> ctx.getString(R.string.kud_sync_just_now)
    else -> DateUtils.getRelativeTimeSpanString(ms, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

/** The plain line for an outcome, or null when it went fine. */
private fun outcomeText(ctx: Context, o: KudSyncOutcome, retryText: String): String? = when (o) {
    KudSyncOutcome.Done -> null
    KudSyncOutcome.NotSignedIn, KudSyncOutcome.SignedOut -> ctx.getString(R.string.kud_sync_signed_out)
    KudSyncOutcome.Retry -> retryText
    is KudSyncOutcome.Failed -> o.message
}

@Composable
fun KudSyncSection(sync: KudSync, onClickBackups: () -> Unit, onSignedOut: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(sync.store.state) }
    var busy by remember { mutableStateOf<Int?>(null) } // the string of what's running
    var error by remember { mutableStateOf(state.lastError) }

    fun start(running: Int, doneText: Int, retryText: String, work: suspend () -> KudSyncOutcome) {
        if (busy != null) return
        busy = running
        error = null
        scope.launch {
            val o = work()
            busy = null
            state = sync.store.state
            error = outcomeText(ctx, o, retryText)
            if (o == KudSyncOutcome.Done) Toast.makeText(ctx, doneText, Toast.LENGTH_SHORT).show()
            if (o == KudSyncOutcome.SignedOut) onSignedOut()
        }
    }

    PreferenceCategory(stringResource(R.string.kud_sync_title))
    StatusLine(stringResource(R.string.kud_sync_last_synced), kudRelativeTime(ctx, state.lastSyncAt))
    StatusLine(stringResource(R.string.kud_sync_last_backup), kudRelativeTime(ctx, state.lastBackupAt))
    error?.let {
        Text(
            it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
    Preference(
        name = stringResource(if (busy == R.string.kud_sync_syncing) R.string.kud_sync_syncing else R.string.kud_sync_now),
        description = stringResource(R.string.kud_sync_now_summary),
        onClick = { start(R.string.kud_sync_syncing, R.string.kud_sync_done, KudSync.SYNC_ERROR) { sync.sync() } },
    ) { if (busy == R.string.kud_sync_syncing) Spinner() }
    Preference(
        name = stringResource(if (busy == R.string.kud_sync_backing_up) R.string.kud_sync_backing_up else R.string.kud_sync_backup_now),
        description = stringResource(R.string.kud_sync_backup_now_summary),
        onClick = {
            start(R.string.kud_sync_backing_up, R.string.kud_sync_backed_up, KudSync.BACKUP_ERROR) {
                sync.backup(KudBackupWorker.KIND_MANUAL)
            }
        },
    ) { if (busy == R.string.kud_sync_backing_up) Spinner() }
    Preference(
        name = stringResource(R.string.kud_sync_backups),
        description = stringResource(R.string.kud_sync_backups_summary),
        onClick = onClickBackups,
    ) { NextScreenIcon() }
    fun setAuto(on: Boolean) {
        sync.store.update { it.copy(autoBackup = on) }
        state = sync.store.state
        KudSyncWork.ensureScheduled(ctx)
    }
    Preference(
        name = stringResource(R.string.kud_sync_auto_backup),
        description = stringResource(R.string.kud_sync_auto_backup_summary),
        onClick = { setAuto(!state.autoBackup) },
    ) { Switch(checked = state.autoBackup, onCheckedChange = ::setAuto) }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Spinner() = CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)

private sealed interface BackupsLoad {
    data object Loading : BackupsLoad
    data class Failed(val message: String) : BackupsLoad
    data class Ready(val backups: List<BackupInfo>) : BackupsLoad
}

/** Account → Backups: newest first; tap one to restore it (after a confirm), the bin deletes it (after a confirm). */
@Composable
fun KudBackupsScreen(onClickBack: () -> Unit, sync: KudSync? = null) {
    val ctx = LocalContext.current
    val s = sync ?: remember { KudSync(ctx) }
    val scope = rememberCoroutineScope()
    var load by remember { mutableStateOf<BackupsLoad>(BackupsLoad.Loading) }
    var attempt by remember { mutableStateOf(0) }
    var restoring by remember { mutableStateOf<BackupInfo?>(null) }
    var deleting by remember { mutableStateOf<BackupInfo?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(attempt) {
        load = BackupsLoad.Loading
        load = when (val r = s.listBackups()) {
            is SyncResult.Ok -> BackupsLoad.Ready(r.value.sortedByDescending { it.createdAt })
            SyncResult.SignedOut -> BackupsLoad.Failed(ctx.getString(R.string.kud_sync_signed_out))
            SyncResult.NoNetwork, SyncResult.Unavailable -> BackupsLoad.Failed(ctx.getString(R.string.kud_account_err_offline))
            is SyncResult.Failed -> BackupsLoad.Failed(r.message)
            SyncResult.NotFound -> BackupsLoad.Ready(emptyList())
        }
    }

    SearchSettingsScreen(onClickBack = onClickBack, title = stringResource(R.string.kud_sync_backups), settings = emptyList()) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            when (val l = load) {
                BackupsLoad.Loading -> Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
                is BackupsLoad.Failed -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(l.message, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { attempt++ }) { Text(stringResource(R.string.kud_account_retry)) }
                }
                is BackupsLoad.Ready -> {
                    if (l.backups.isEmpty()) Text(
                        stringResource(R.string.kud_backups_empty), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
                    )
                    l.backups.forEach { b ->
                        Preference(
                            name = sessionTime(b.createdAt),
                            description = backupDetails(ctx, b),
                            onClick = { if (busyId == null) restoring = b },
                        ) {
                            if (busyId == b.id) Spinner()
                            else IconButton(onClick = { if (busyId == null) deleting = b }) {
                                Icon(
                                    painterResource(R.drawable.kud_ic_trash),
                                    stringResource(R.string.kud_backups_delete_cd, sessionTime(b.createdAt)),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    restoring?.let { b ->
        ConfirmationDialog(
            onDismissRequest = { restoring = null },
            onConfirmed = {
                restoring = null
                busyId = b.id
                message = ctx.getString(R.string.kud_backups_restoring)
                // In the app's scope, not this screen's: leaving the screen mid-restore neither cancels it nor loses
                // its outcome, which then still shows as a toast.
                val app = ctx.applicationContext
                KudSync.appScope.launch {
                    val o = s.restore(b.id)
                    val text = outcomeText(app, o, KudSync.RESTORE_ERROR) ?: app.getString(R.string.kud_backups_restored)
                    busyId = null
                    message = text
                    Toast.makeText(app, text, Toast.LENGTH_LONG).show()
                }
            },
            title = { Text(stringResource(R.string.kud_backups_restore_title)) },
            content = { Text(stringResource(R.string.kud_backups_restore_message, sessionTime(b.createdAt))) },
            confirmButtonText = stringResource(R.string.kud_backups_restore),
        )
    }
    deleting?.let { b ->
        ConfirmationDialog(
            onDismissRequest = { deleting = null },
            onConfirmed = {
                deleting = null
                busyId = b.id
                scope.launch {
                    val o = s.deleteBackup(b.id)
                    busyId = null
                    message = outcomeText(ctx, o, KudSync.DELETE_ERROR)
                    if (o == KudSyncOutcome.Done) (load as? BackupsLoad.Ready)?.let { r -> load = r.copy(backups = r.backups - b) }
                }
            },
            title = { Text(stringResource(R.string.kud_backups_delete_title)) },
            content = { Text(stringResource(R.string.kud_backups_delete_message, sessionTime(b.createdAt))) },
            confirmButtonText = stringResource(R.string.kud_backups_delete),
        )
    }
}

/** "Pixel 8 · 1.2 MB · automatic". */
fun backupDetails(ctx: Context, b: BackupInfo): String = listOfNotNull(
    b.deviceName.ifBlank { null },
    Formatter.formatShortFileSize(ctx, b.sizeBytes),
    when (b.kind) {
        KudBackupWorker.KIND_AUTO -> ctx.getString(R.string.kud_backups_kind_auto)
        KudBackupWorker.KIND_MANUAL -> ctx.getString(R.string.kud_backups_kind_manual)
        else -> b.kind.ifBlank { null }
    },
).joinToString(" · ")
