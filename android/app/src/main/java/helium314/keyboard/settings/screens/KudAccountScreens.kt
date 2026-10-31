// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kudcrafts.kudkeys.voice.Account
import com.kudcrafts.kudkeys.voice.AccountResult
import com.kudcrafts.kudkeys.voice.Profile
import com.kudcrafts.kudkeys.voice.Usage
import com.kudcrafts.kudkeys.voice.VoiceSettings
import helium314.keyboard.kudkeys.KudKeywords
import helium314.keyboard.kudkeys.sync.KudSync
import helium314.keyboard.kudkeys.sync.KudSyncWork
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.PreferenceCategory
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Kud Keys: the account. The row at the top of settings, the sign-in screen and the profile (server/README.md
 * "API for the app"). The session token lives in VoiceSettings' app-private prefs, which backup excludes.
 */

const val KUD_WEB_URL = "https://keys.kudcrafts.com"

/** The profile's language choices, as on the web. */
val KUD_PROFILE_LANGUAGES = listOf(
    "id" to "Indonesian", "en" to "English", "jv" to "Javanese", "su" to "Sundanese", "ms" to "Malay", "ar" to "Arabic",
)

/** Top of settings: "Sign in" when signed out, name + email when signed in. Re-read on every composition. */
@Composable
fun KudAccountRow(onClick: () -> Unit) {
    val settings = VoiceSettings(LocalContext.current)
    if (!settings.isSignedIn) {
        Preference(
            name = stringResource(R.string.kud_account_sign_in),
            description = stringResource(
                if (settings.wasSignedOut) R.string.kud_account_signed_out else R.string.kud_account_sign_in_summary
            ),
            icon = R.drawable.kud_ic_account,
            onClick = onClick,
        ) { NextScreenIcon() }
    } else {
        val name = settings.accountName.ifBlank { settings.accountEmail }
        Row(
            Modifier.fillMaxWidth().clickable { onClick() }.heightIn(min = 44.dp).padding(vertical = 12.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(name, 32)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    settings.accountEmail, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
                )
            }
            NextScreenIcon()
        }
    }
    HorizontalDivider()
}

@Composable
private fun Avatar(name: String, sizeDp: Int) {
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().firstOrNull()?.uppercase() ?: "?",
            color = MaterialTheme.colorScheme.onPrimary,
            style = if (sizeDp >= 48) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
        )
    }
}

/** Settings → Account: the sign-in screen while signed out, the profile once signed in. */
@Composable
fun KudAccountScreen(onClickBack: () -> Unit, account: Account? = null, onClickBackups: () -> Unit = {}) {
    val ctx = LocalContext.current
    val acc = account ?: remember { Account(ctx) }
    var signedIn by remember { mutableStateOf(acc.settings.isSignedIn) }
    if (signedIn) KudProfileScreen(acc, onClickBack, onSignedOut = { signedIn = false }, onLeft = onClickBack, onClickBackups = onClickBackups)
    // v0.4: a sign-in starts sync (pull first, then push) and the periodic sync and backup
    else KudSignInScreen(acc, onClickBack, onSignedIn = { KudSyncWork.onSignedIn(ctx); signedIn = true })
}

@Composable
fun KudSignInScreen(account: Account, onClickBack: () -> Unit, onSignedIn: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(account.settings.accountEmail) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (busy || email.isBlank() || password.isEmpty()) return
        busy = true
        error = null
        scope.launch {
            val r = account.signIn(email, password)
            busy = false
            if (r is AccountResult.Ok) onSignedIn() else error = accountErrorText(ctx, r, account.settings.apiBase)
        }
    }

    SearchSettingsScreen(onClickBack = onClickBack, title = stringResource(R.string.kud_account_sign_in), settings = emptyList()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (account.settings.wasSignedOut) Text(
                stringResource(R.string.kud_account_signed_out),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.kud_account_sign_in_intro),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = email, onValueChange = { email = it; error = null },
                label = { Text(stringResource(R.string.kud_account_email)) },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it; error = null },
                label = { Text(stringResource(R.string.kud_account_password)) },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(
                onClick = ::submit, enabled = !busy && email.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.kud_account_signing_in), Modifier.padding(start = 10.dp))
                } else Text(stringResource(R.string.kud_account_sign_in))
            }
            TextButton(onClick = { openUrl(ctx, KUD_WEB_URL) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.kud_account_no_account), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private sealed interface Loaded {
    data object Loading : Loaded
    data class Failed(val message: String) : Loaded
    data class Ready(val profile: Profile, val usage: Usage?) : Loaded
}

@Composable
fun KudProfileScreen(
    account: Account,
    onClickBack: () -> Unit,
    onSignedOut: () -> Unit,
    onLeft: () -> Unit,
    onClickBackups: () -> Unit = {},
    sync: KudSync? = null,
) {
    val ctx = LocalContext.current
    val kudSync = sync ?: remember { KudSync(ctx, account.settings) }
    var loaded by remember { mutableStateOf<Loaded>(Loaded.Loading) }
    var attempt by remember { mutableStateOf(0) }

    // Load on open (and on "Try again"); a 401 has already marked the account signed out.
    LaunchedEffect(attempt) {
        loaded = Loaded.Loading
        when (val me = account.me()) {
            is AccountResult.Ok -> loaded = Loaded.Ready(me.value, (account.usage() as? AccountResult.Ok)?.value)
            AccountResult.SignedOut -> onSignedOut()
            else -> loaded = Loaded.Failed(accountErrorText(ctx, me, account.settings.apiBase))
        }
    }

    SearchSettingsScreen(onClickBack = onClickBack, title = stringResource(R.string.kud_account_title), settings = emptyList()) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            when (val l = loaded) {
                Loaded.Loading -> {
                    ProfileHeader(account.settings.accountName, account.settings.accountEmail)
                    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                }
                is Loaded.Failed -> {
                    ProfileHeader(account.settings.accountName, account.settings.accountEmail)
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(l.message, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { attempt++ }) { Text(stringResource(R.string.kud_account_retry)) }
                    }
                }
                // key: a saved profile resets the form to what the server stored
                is Loaded.Ready -> androidx.compose.runtime.key(l.profile) {
                    ProfileForm(account, l.profile, l.usage, onSaved = { loaded = l.copy(profile = it) }, onSignedOut = onSignedOut)
                }
            }
            HorizontalDivider()
            KudSyncSection(kudSync, onClickBackups, onSignedOut)
            HorizontalDivider()
            Preference(
                name = stringResource(R.string.kud_account_web),
                description = stringResource(R.string.kud_account_web_summary),
                onClick = { openUrl(ctx, KUD_WEB_URL) },
            )
            Preference(
                name = stringResource(R.string.kud_account_sign_out),
                // a legacy token under Voice → Advanced keeps voice working after sign-out: say so
                description = stringResource(
                    if (account.settings.token.isNotEmpty()) R.string.kud_account_sign_out_summary_legacy
                    else R.string.kud_account_sign_out_summary
                ),
                onClick = { account.signOut(); KudSyncWork.onSignedOut(ctx); onLeft() },
            )
        }
    }
}

@Composable
private fun ProfileHeader(name: String, email: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name.ifBlank { email }, 56)
        Column {
            Text(name.ifBlank { email }, style = MaterialTheme.typography.titleLarge)
            Text(email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProfileForm(account: Account, me: Profile, usage: Usage?, onSaved: (Profile) -> Unit, onSignedOut: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(me.name) }
    var keywords by remember { mutableStateOf(me.voiceKeywords) }
    var languages by remember { mutableStateOf(me.languages) }
    var draft by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val dirty = name.trim() != me.name || keywords != me.voiceKeywords || languages != me.languages

    fun addDraft() {
        val (next, h) = KudKeywords.add(keywords, draft)
        keywords = next
        draft = ""
        hint = when (h) {
            KudKeywords.Hint.TooLong -> ctx.getString(R.string.kud_account_keywords_too_long)
            KudKeywords.Hint.TooMany -> ctx.getString(R.string.kud_account_keywords_too_many)
            null -> null
        }
    }

    fun save() {
        if (draft.isNotBlank()) addDraft()
        saving = true
        error = null
        scope.launch {
            val r = account.save(name.trim(), keywords, languages)
            saving = false
            when (r) {
                is AccountResult.Ok -> {
                    Toast.makeText(ctx, R.string.kud_account_saved, Toast.LENGTH_SHORT).show()
                    onSaved(r.value)
                }
                AccountResult.SignedOut -> onSignedOut()
                else -> error = accountErrorText(ctx, r, account.settings.apiBase)
            }
        }
    }

    ProfileHeader(me.name, me.email)
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
        OutlinedTextField(
            value = name, onValueChange = { if (it.length <= 100) name = it },
            label = { Text(stringResource(R.string.kud_account_name)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            isError = name.isBlank(),
        )
    }

    PreferenceCategory(stringResource(R.string.kud_account_keywords) + "  ·  ${keywords.size}/${KudKeywords.MAX}")
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.kud_account_keywords_summary),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (keywords.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            keywords.forEach { k ->
                InputChip(
                    selected = false,
                    onClick = { keywords = keywords - k },
                    label = { Text(k) },
                    trailingIcon = {
                        Icon(
                            painterResource(R.drawable.ic_close),
                            stringResource(R.string.kud_account_keyword_remove, k),
                            Modifier.size(InputChipDefaults.IconSize),
                        )
                    },
                )
            }
        }
        OutlinedTextField(
            value = draft, onValueChange = { draft = it; hint = null },
            label = { Text(stringResource(R.string.kud_account_keyword_add)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            enabled = keywords.size < KudKeywords.MAX,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { addDraft() }),
            trailingIcon = {
                TextButton(onClick = ::addDraft, enabled = draft.isNotBlank()) {
                    Text(stringResource(R.string.kud_account_keyword_add_button))
                }
            },
            supportingText = hint?.let { { Text(it) } },
        )
    }

    PreferenceCategory(stringResource(R.string.kud_account_languages))
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.kud_account_languages_summary),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KUD_PROFILE_LANGUAGES.forEach { (code, label) ->
                val on = code in languages
                FilterChip(
                    selected = on,
                    onClick = {
                        languages = if (on) languages.takeIf { it.size > 1 }?.minus(code) ?: languages else languages + code
                    },
                    label = { Text(label) },
                )
            }
        }
    }

    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Button(
            onClick = ::save, enabled = (dirty || draft.isNotBlank()) && !saving && name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(if (saving) R.string.kud_account_saving else R.string.kud_account_save)) }
    }

    PreferenceCategory(stringResource(R.string.kud_account_usage))
    UsageSummary(usage)
}

@Composable
private fun UsageSummary(usage: Usage?) {
    if (usage == null) return
    UsageLine(stringResource(R.string.kud_account_usage_week),
        stringResource(R.string.kud_account_usage_value, minutes(usage.week.audioMinutes), usage.week.sessions))
    UsageLine(stringResource(R.string.kud_account_usage_month),
        stringResource(R.string.kud_account_usage_value, minutes(usage.month.audioMinutes), usage.month.sessions))
    Text(
        stringResource(R.string.kud_account_usage_recent),
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
    )
    if (usage.sessions.isEmpty()) Text(
        stringResource(R.string.kud_account_usage_none),
        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    usage.sessions.take(5).forEach { s ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(sessionTime(s.startedAt), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                "${seconds(s.audioSeconds)} · ${s.outcome}", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UsageLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun minutes(m: Double) = if (m < 10) String.format(Locale.US, "%.1f", m) else m.toInt().toString()
private fun seconds(s: Double) = if (s < 60) "${s.toInt()} s" else "${(s / 60).toInt()} min ${(s % 60).toInt()} s"

private val SESSION_TIME = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.US)
fun sessionTime(iso: String): String =
    runCatching { SESSION_TIME.format(Instant.parse(iso).atZone(ZoneId.systemDefault())) }.getOrDefault(iso)

/** The line shown for a failed account call. */
fun accountErrorText(ctx: Context, r: AccountResult<*>, apiBase: String): String = when (r) {
    AccountResult.WrongPassword -> ctx.getString(R.string.kud_account_err_password)
    // DNS failed: offline, unless the phone has a network (then the host is wrong or down)
    AccountResult.NoNetwork -> if (isOnline(ctx)) ctx.getString(R.string.kud_account_err_unreachable, apiBase.substringAfter("://"))
        else ctx.getString(R.string.kud_account_err_offline)
    AccountResult.Unreachable -> ctx.getString(R.string.kud_account_err_unreachable, apiBase.substringAfter("://"))
    AccountResult.NotAllowed -> ctx.getString(R.string.kud_account_err_not_allowed)
    AccountResult.SignedOut -> ctx.getString(R.string.kud_account_signed_out)
    is AccountResult.Failed -> r.message
    is AccountResult.Ok -> ""
}

@Suppress("DEPRECATION")
private fun isOnline(ctx: Context): Boolean {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
    return cm.activeNetworkInfo?.isConnected == true
}

private fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
