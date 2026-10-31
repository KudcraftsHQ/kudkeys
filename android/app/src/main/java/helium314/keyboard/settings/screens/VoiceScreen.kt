// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.kudcrafts.kudkeys.voice.VoiceSettings
import helium314.keyboard.kudkeys.VoiceConnectionTest
import helium314.keyboard.latin.R
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.dialogs.TextInputDialog
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import helium314.keyboard.settings.preferences.Preference
import kotlinx.coroutines.launch

/** kudkeys: Settings → Voice. Server URL, token (never in the APK) and a connection test. */
@Composable
fun VoiceScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { VoiceSettings(ctx) }
    var url by remember { mutableStateOf(settings.serverUrl) }
    var hasToken by remember { mutableStateOf(settings.hasToken) }
    var editUrl by remember { mutableStateOf(false) }
    var editToken by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    SearchSettingsScreen(onClickBack = onClickBack, title = stringResource(R.string.kud_settings_voice), settings = emptyList()) {
        Column {
            Preference(name = stringResource(R.string.kud_voice_server_url), description = url, onClick = { editUrl = true })
            Preference(
                name = stringResource(R.string.kud_voice_token),
                description = stringResource(if (hasToken) R.string.kud_voice_token_set else R.string.kud_voice_token_unset),
                onClick = { editToken = true },
            )
            Preference(
                name = stringResource(R.string.kud_voice_test),
                description = if (testing) stringResource(R.string.kud_voice_test_running) else testResult,
                onClick = {
                    if (testing) return@Preference
                    testing = true
                    testResult = null
                    scope.launch {
                        testResult = VoiceConnectionTest.run(settings.endpoint())
                        testing = false
                    }
                },
            )
        }
    }
    if (editUrl) TextInputDialog(
        onDismissRequest = { editUrl = false },
        onConfirmed = { settings.serverUrl = it; url = settings.serverUrl },
        title = { Text(stringResource(R.string.kud_voice_server_url)) },
        initialText = url,
        neutralButtonText = stringResource(R.string.button_default),
        onNeutral = { settings.serverUrl = ""; url = settings.serverUrl },
        checkTextValid = { it.trim().startsWith("ws://") || it.trim().startsWith("wss://") },
    )
    if (editToken) {
        val state = rememberTextFieldState("")
        ThreeButtonAlertDialog(
            onDismissRequest = { editToken = false },
            onConfirmed = { settings.token = state.text.toString(); hasToken = settings.hasToken; testResult = null },
            checkOk = { state.text.isNotBlank() },
            neutralButtonText = if (hasToken) stringResource(R.string.delete) else null,
            onNeutral = { editToken = false; settings.token = ""; hasToken = false; testResult = null },
            title = { Text(stringResource(R.string.kud_voice_token)) },
            content = { OutlinedSecureTextField(state = state, modifier = Modifier.fillMaxWidth()) },
        )
    }
}
