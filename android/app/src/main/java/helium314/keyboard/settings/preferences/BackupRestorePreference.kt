// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import helium314.keyboard.dictionarypack.DictionaryPackConstants
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.emoji.SupportedEmojis
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.FileUtils
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.InfoDialog
import helium314.keyboard.settings.filePicker
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import androidx.core.content.edit
import helium314.keyboard.latin.checkVersionUpgrade
import helium314.keyboard.latin.utils.IntentUtils

@Composable
fun BackupRestorePreference(setting: Setting) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current
    var error: String? by rememberSaveable { mutableStateOf(null) }
    val backupLauncher = backupLauncher { error = it }
    val restoreLauncher = restoreLauncher { error = it }
    Preference(name = setting.title, onClick = { showDialog = true })
    if (showDialog) {
        ConfirmationDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.backup_restore_title)) },
            content = { Text(stringResource(R.string.backup_restore_message)) },
            confirmButtonText = stringResource(R.string.button_backup),
            neutralButtonText = stringResource(R.string.button_restore),
            onNeutral = {
                showDialog = false
                val intent = IntentUtils.getResolvableTypeIntent(ctx, "application/zip")
                    .setAction(Intent.ACTION_OPEN_DOCUMENT)
                restoreLauncher.launch(intent)
            },
            onConfirmed = {
                val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Calendar.getInstance().time)
                val intent = IntentUtils.getResolvableTypeIntent(ctx, "application/zip")
                    .setAction(Intent.ACTION_CREATE_DOCUMENT)
                    .putExtra(
                        Intent.EXTRA_TITLE,
                        ctx.getString(R.string.english_ime_name)
                            .replace(" ", "_") + "_backup_$currentDate.zip"
                    )
                backupLauncher.launch(intent)
            }
        )
    }
    if (error != null) {
        InfoDialog(
            if (error!!.startsWith("b"))
                stringResource(R.string.backup_error, error!!.drop(1))
            else stringResource(R.string.restore_error, error!!.drop(1))
        ) { error = null }
    }
}

@Composable
private fun backupLauncher(onError: (String) -> Unit): ManagedActivityResultLauncher<Intent, ActivityResult> {
    val ctx = LocalContext.current
    return filePicker { uri ->
        val wait = CountDownLatch(1)
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
            try {
                ctx.getActivity()?.contentResolver?.openOutputStream(uri)?.use { os -> writeBackupZip(ctx, os) }
            } catch (t: Throwable) {
                onError("b" + t.message)
                Log.w("AdvancedScreen", "error during backup", t)
            } finally {
                wait.countDown()
            }
        }
        wait.await()
    }
}

@Composable
private fun restoreLauncher(onError: (String) -> Unit): ManagedActivityResultLauncher<Intent, ActivityResult> {
    val ctx = LocalContext.current
    return filePicker { uri ->
        val wait = CountDownLatch(1)
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
            try {
                val input = ctx.getActivity()?.contentResolver?.openInputStream(uri)
                    ?: throw Exception("nothing to restore in the given file")
                input.use { restoreBackupZip(ctx, it) }
                if (Looper.myLooper() == null)
                    Looper.prepare()
                Toast.makeText(ctx, ctx.getString(R.string.backup_restored), Toast.LENGTH_LONG).show()
            } catch (t: Throwable) {
                onError("r" + t.message)
                Log.w("AdvancedScreen", "error during restore", t)
            } finally {
                wait.countDown()
            }
        }
        wait.await()
        reloadAfterRestore(ctx)
    }
}

/*
 * Kud Keys: the backup zip and the restore, shared by this screen and Account → Backups (J/kudkeys/sync). Same zip
 * and same steps as HeliBoard's; [keepPref] drops pref keys (secrets) from the zip.
 */

/** Zips the typed-words dictionaries, user-added dictionaries, custom files and both prefs files into [os]. */
internal fun writeBackupZip(ctx: Context, os: OutputStream, keepPref: (String) -> Boolean = { true }) {
    // zip all files matching the backup patterns
    // essentially this is the typed words information, and user-added dictionaries
    val filesDir = ctx.filesDir ?: return
    val filesPath = filesDir.path + File.separator
    val files = mutableListOf<File>()
    filesDir.walk().forEach { file ->
        val path = file.path.replace(filesPath, "")
        if (file.isFile && backupFilePatterns.any { path.matches(it) })
            files.add(file)
    }
    val protectedFilesDir = DeviceProtectedUtils.getFilesDir(ctx)
    val protectedFilesPath = protectedFilesDir.path + File.separator
    val protectedFiles = mutableListOf<File>()
    protectedFilesDir.walk().forEach { file ->
        val path = file.path.replace(protectedFilesPath, "")
        if (file.isFile && backupFilePatterns.any { path.matches(it) })
            protectedFiles.add(file)
    }
    val zipStream = ZipOutputStream(os)
    files.forEach {
        val fileStream = FileInputStream(it).buffered()
        zipStream.putNextEntry(ZipEntry(it.path.replace(filesPath, "")))
        fileStream.copyTo(zipStream, 1024)
        fileStream.close()
        zipStream.closeEntry()
    }
    protectedFiles.forEach {
        val fileStream = FileInputStream(it).buffered()
        zipStream.putNextEntry(ZipEntry(it.path.replace(protectedFilesDir.path, "unprotected")))
        fileStream.copyTo(zipStream, 1024)
        fileStream.close()
        zipStream.closeEntry()
    }
    zipStream.putNextEntry(ZipEntry(PREFS_FILE_NAME))
    settingsToJsonStream(ctx.prefs().all.filterKeys { it != null && keepPref(it) }, zipStream)
    zipStream.closeEntry()
    zipStream.putNextEntry(ZipEntry(PROTECTED_PREFS_FILE_NAME))
    settingsToJsonStream(ctx.protectedPrefs().all.filterKeys { it != null && keepPref(it) }, zipStream)
    zipStream.closeEntry()
    zipStream.close()
}

/**
 * Restores a backup zip (background thread; call [reloadAfterRestore] on the main thread afterwards). Throws on
 * failure, after putting the files and prefs back as they were. Kud Keys: files the zip never holds (the voice
 * draft, extracted dictionaries) are kept, and the device-protected files really are moved aside first.
 */
internal fun restoreBackupZip(ctx: Context, inputStream: InputStream) {
    val oldPrefs = ctx.prefs().all.toMap()
    val oldProtectedPrefs = ctx.protectedPrefs().all.toMap()
    val filesDir = ctx.filesDir!!
    val deviceProtectedFilesDir = DeviceProtectedUtils.getFilesDir(ctx)
    val filesDir2 = File(filesDir.absolutePath + "2")
    val deviceProtectedFilesDir2 = File(deviceProtectedFilesDir.absolutePath + "2")
    filesDir2.deleteRecursively()
    deviceProtectedFilesDir2.deleteRecursively()
    filesDir.renameTo(filesDir2)
    if (deviceProtectedFilesDir != filesDir) deviceProtectedFilesDir.renameTo(deviceProtectedFilesDir2)
    filesDir.mkdirs()
    deviceProtectedFilesDir.mkdirs()
    try {
        var anyMatch = false
        ZipInputStream(inputStream).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            LayoutUtilsCustom.onLayoutFileChanged()
            Settings.getInstance().stopListener()
            while (entry != null) {
                if (entry.name.startsWith("unprotected${File.separator}")) {
                    val adjustedName = entry.name.substringAfter("unprotected${File.separator}")
                    if (backupFilePatterns.any { adjustedName.matches(it) }) {
                        if (!restoreEntryToDir(zip, deviceProtectedFilesDir, adjustedName)) {
                            Log.w("AdvancedScreen", "skipping unsafe backup entry $adjustedName")
                        }
                    }
                    anyMatch = true
                } else if (backupFilePatterns.any { entry.name.matches(it) }) {
                    if (!restoreEntryToDir(zip, filesDir, entry.name)) {
                        Log.w("AdvancedScreen", "skipping unsafe backup entry ${entry.name}")
                    }
                    anyMatch = true
                } else if (entry.name == PREFS_FILE_NAME) {
                    val prefLines = String(zip.readBytes()).split("\n")
                    val prefs = ctx.prefs()
                    prefs.edit { clear() }
                    anyMatch = true
                    readJsonLinesToSettings(prefLines, prefs)
                } else if (entry.name == PROTECTED_PREFS_FILE_NAME) {
                    val prefLines = String(zip.readBytes()).split("\n")
                    val protectedPrefs = ctx.protectedPrefs()
                    protectedPrefs.edit { clear() }
                    readJsonLinesToSettings(prefLines, protectedPrefs)
                    anyMatch = true
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (!anyMatch)
            throw Exception("nothing to restore in the given file")

        keepFilesOutsideBackup(filesDir2, filesDir)
        keepFilesOutsideBackup(deviceProtectedFilesDir2, deviceProtectedFilesDir)
        filesDir2.deleteRecursively()
        deviceProtectedFilesDir2.deleteRecursively()
    } catch (t: Throwable) {
        filesDir.deleteRecursively()
        filesDir2.renameTo(filesDir)
        if (deviceProtectedFilesDir2.exists()) {
            deviceProtectedFilesDir.deleteRecursively()
            deviceProtectedFilesDir2.renameTo(deviceProtectedFilesDir)
        }
        ctx.prefs().edit {
            clear()
            oldPrefs.forEach { (key, value) -> putAny(key, value) }
        }
        ctx.protectedPrefs().edit {
            clear()
            oldProtectedPrefs.forEach { (key, value) -> putAny(key, value) }
        }
        throw t
    }
}

private fun SharedPreferences.Editor.putAny(key: String, value: Any?) {
    when (value) {
        is String -> putString(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is Float -> putFloat(key, value)
        is Boolean -> putBoolean(key, value)
        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
    }
}

/** Copies back every file of [old] that a backup never holds (and the restore did not write). */
private fun keepFilesOutsideBackup(old: File, new: File) {
    if (!old.isDirectory) return
    val oldPath = old.path + File.separator
    old.walk().filter { it.isFile }.forEach { file ->
        val rel = file.path.replace(oldPath, "")
        if (backupFilePatterns.any { rel.matches(it) }) return@forEach
        val target = File(new, rel)
        if (target.exists()) return@forEach
        target.parentFile?.mkdirs()
        file.copyTo(target)
    }
}

/** HeliBoard's steps after a restore: reload settings, subtypes, dictionaries, layouts and the keyboard theme. */
internal fun reloadAfterRestore(ctx: Context) {
    checkVersionUpgrade(ctx)
    reloadAfterPrefsChanged(ctx)
    LayoutUtilsCustom.onLayoutFileChanged()
    LayoutUtilsCustom.removeMissingLayouts(ctx)
    SupportedEmojis.load(ctx)
}

/** The part of [reloadAfterRestore] that applies changed prefs (used after a settings sync too). */
internal fun reloadAfterPrefsChanged(ctx: Context) {
    Settings.getInstance().startListener()
    SubtypeSettings.reloadEnabledSubtypes(ctx)
    val newDictBroadcast = Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION)
    ctx.sendBroadcast(newDictBroadcast)
    (ctx.getActivity() as? SettingsActivity)?.prefChanged()
    KeyboardSwitcher.getInstance().setThemeNeedsReload()
}

/** Writes [settings] the way a backup's prefs file does, and reads them back into [prefs] (no clear). */
internal fun applySettingsLikeRestore(settings: Map<String, Any>, prefs: SharedPreferences): Boolean {
    val out = java.io.ByteArrayOutputStream()
    settingsToJsonStream(HashMap<String?, Any?>(settings), out)
    Settings.getInstance().stopListener()
    return readJsonLinesToSettings(String(out.toByteArray()).split("\n"), prefs)
}

@Suppress("UNCHECKED_CAST") // it is checked... but whatever (except string set, because can't check for that))
internal fun settingsToJsonStream(settings: Map<String?, Any?>, out: OutputStream) {
    val booleans = settings.filter { it.key is String && it.value is Boolean } as Map<String, Boolean>
    val ints = settings.filter { it.key is String && it.value is Int } as Map<String, Int>
    val longs = settings.filter { it.key is String && it.value is Long } as Map<String, Long>
    val floats = settings.filter { it.key is String && it.value is Float } as Map<String, Float>
    val strings = settings.filter { it.key is String && it.value is String } as Map<String, String>
    val stringSets = settings.filter { it.key is String && it.value is Set<*> } as Map<String, Set<String>>
    // now write
    out.write("boolean settings\n".toByteArray())
    out.write(Json.encodeToString(booleans).toByteArray())
    out.write("\nint settings\n".toByteArray())
    out.write(Json.encodeToString(ints).toByteArray())
    out.write("\nlong settings\n".toByteArray())
    out.write(Json.encodeToString(longs).toByteArray())
    out.write("\nfloat settings\n".toByteArray())
    out.write(Json.encodeToString(floats).toByteArray())
    out.write("\nstring settings\n".toByteArray())
    out.write(Json.encodeToString(strings).toByteArray())
    out.write("\nstring set settings\n".toByteArray())
    out.write(Json.encodeToString(stringSets).toByteArray())
}

private fun readJsonLinesToSettings(list: List<String>, prefs: SharedPreferences): Boolean {
    val i = list.iterator()
    val e = prefs.edit()
    try {
        while (i.hasNext()) {
            when (i.next()) {
                "boolean settings" -> Json.decodeFromString<Map<String, Boolean>>(i.next()).forEach { e.putBoolean(it.key, it.value) }
                "int settings" -> Json.decodeFromString<Map<String, Int>>(i.next()).forEach { e.putInt(it.key, it.value) }
                "long settings" -> Json.decodeFromString<Map<String, Long>>(i.next()).forEach { e.putLong(it.key, it.value) }
                "float settings" -> Json.decodeFromString<Map<String, Float>>(i.next()).forEach { e.putFloat(it.key, it.value) }
                "string settings" -> Json.decodeFromString<Map<String, String>>(i.next()).forEach { e.putString(it.key, it.value) }
                "string set settings" -> Json.decodeFromString<Map<String, Set<String>>>(i.next()).forEach { e.putStringSet(it.key, it.value) }
            }
        }
        e.apply()
        return true
    } catch (e: Exception) {
        return false
    }
}

private fun restoreEntryToDir(zip: ZipInputStream, baseDir: File, entryName: String): Boolean {
    val file = File(baseDir, entryName)
    val canonicalBase = baseDir.canonicalFile
    val canonicalTarget = file.canonicalFile
    if (canonicalTarget.path != canonicalBase.path
        && !canonicalTarget.path.startsWith(canonicalBase.path + File.separator)
    ) return false
    FileUtils.copyStreamToNewFile(zip, file)
    return true
}

private const val PREFS_FILE_NAME = "preferences.json"
private const val PROTECTED_PREFS_FILE_NAME = "protected_preferences.json"

internal val backupFilePatterns by lazy { listOf(
    "blacklists${File.separator}.*\\.txt".toRegex(),
    "layouts${File.separator}.*${LayoutUtilsCustom.CUSTOM_LAYOUT_PREFIX}+\\..{0,4}".toRegex(), // can't expect a period at the end, as this would break restoring older backups
    "dicts${File.separator}.*${File.separator}.*user\\.dict".toRegex(),
    "UserHistoryDictionary.*${File.separator}UserHistoryDictionary.*\\.(body|header)".toRegex(),
    "custom_background_image.*".toRegex(),
    "custom_font".toRegex(),
    "custom_emoji_font".toRegex(),
) }
