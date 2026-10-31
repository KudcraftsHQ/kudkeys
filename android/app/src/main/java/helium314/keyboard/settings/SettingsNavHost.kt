// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.settings.getTransitionAnimationScale
import helium314.keyboard.settings.screens.AboutScreen
import helium314.keyboard.settings.screens.AdvancedSettingsScreen
import helium314.keyboard.settings.screens.AppearanceScreen
import helium314.keyboard.settings.screens.ColorsScreen
import helium314.keyboard.settings.screens.DebugScreen
import helium314.keyboard.settings.screens.DictionaryScreen
import helium314.keyboard.settings.screens.LanguageScreen
import helium314.keyboard.settings.screens.KudAdvancedScreen
import helium314.keyboard.settings.screens.KudDestination
import helium314.keyboard.settings.screens.KudEmojiScreen
import helium314.keyboard.settings.screens.KudLanguagesScreen
import helium314.keyboard.settings.screens.KudLayoutKeysScreen
import helium314.keyboard.settings.screens.KudMainSettingsScreen
import helium314.keyboard.settings.screens.KudPhysicalKeyboardScreen
import helium314.keyboard.settings.screens.KudResizeScreen
import helium314.keyboard.settings.screens.KudRichInputScreen
import helium314.keyboard.settings.screens.KudSoundScreen
import helium314.keyboard.settings.screens.KudTypingScreen
import helium314.keyboard.settings.screens.PersonalDictionariesScreen
import helium314.keyboard.settings.screens.PersonalDictionaryScreen
import helium314.keyboard.settings.screens.PreferencesScreen
import helium314.keyboard.settings.screens.SecondaryLayoutScreen
import helium314.keyboard.settings.screens.SubtypeScreen
import helium314.keyboard.settings.screens.TextCorrectionScreen
import helium314.keyboard.settings.screens.VoiceScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
fun SettingsNavHost(
    onClickBack: () -> Unit,
    startDestination: String? = null,
) {
    val navController = rememberNavController()
    val dir = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    val target = SettingsDestination.navTarget.collectAsState()

    // duration does not change when system setting changes, but that's rare enough to not care
    val duration = (250 * getTransitionAnimationScale(LocalContext.current)).toInt()
    val animation = tween<IntOffset>(durationMillis = duration)

    fun goBack() {
        if (!navController.popBackStack()) onClickBack()
    }

    NavHost(
        navController = navController,
        startDestination = startDestination ?: SettingsDestination.Settings,
        enterTransition = { slideInHorizontally(initialOffsetX = { +it * dir }, animationSpec = animation) },
        exitTransition = { slideOutHorizontally(targetOffsetX = { -it * dir }, animationSpec = animation) },
        popEnterTransition = { slideInHorizontally(initialOffsetX = { -it * dir }, animationSpec = animation) },
        popExitTransition = { slideOutHorizontally(targetOffsetX = { +it * dir }, animationSpec = animation) }
    ) {
        composable(SettingsDestination.Settings) {
            // Kud Keys: SwiftKey's sections; HeliBoard's own screens are under Advanced
            KudMainSettingsScreen(
                onClickLanguages = { navController.navigate(KudDestination.Languages) },
                onClickTyping = { navController.navigate(KudDestination.Typing) },
                onClickEmoji = { navController.navigate(KudDestination.Emoji) },
                onClickRichInput = { navController.navigate(KudDestination.RichInput) },
                onClickLayoutKeys = { navController.navigate(KudDestination.LayoutKeys) },
                onClickSound = { navController.navigate(KudDestination.Sound) },
                onClickAdvanced = { navController.navigate(KudDestination.More) },
                onClickBack = ::goBack,
            )
        }
        composable(KudDestination.More) {
            KudAdvancedScreen(
                onClickPreferences = { navController.navigate(SettingsDestination.Preferences) },
                onClickAppearance = { navController.navigate(SettingsDestination.Appearance) },
                onClickTextCorrection = { navController.navigate(SettingsDestination.TextCorrection) },
                onClickLayouts = { navController.navigate(SettingsDestination.Layouts) },
                onClickDictionaries = { navController.navigate(SettingsDestination.Dictionaries) },
                onClickAdvanced = { navController.navigate(SettingsDestination.Advanced) },
                onClickAbout = { navController.navigate(SettingsDestination.About) },
                onClickBack = ::goBack,
            )
        }
        composable(KudDestination.Languages) {
            KudLanguagesScreen(onClickAddLanguages = { navController.navigate(SettingsDestination.Languages) }, onClickBack = ::goBack)
        }
        composable(KudDestination.Typing) { KudTypingScreen(onClickBack = ::goBack) }
        composable(KudDestination.PhysicalKeyboard) { KudPhysicalKeyboardScreen(onClickBack = ::goBack) }
        composable(KudDestination.Emoji) { KudEmojiScreen(onClickBack = ::goBack) }
        composable(KudDestination.RichInput) { KudRichInputScreen(onClickBack = ::goBack) }
        composable(KudDestination.LayoutKeys) { KudLayoutKeysScreen(onClickBack = ::goBack) }
        composable(KudDestination.Resize) { KudResizeScreen(onClickBack = ::goBack) }
        composable(KudDestination.Sound) { KudSoundScreen(onClickBack = ::goBack) }
        composable(SettingsDestination.About) {
            AboutScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.TextCorrection) {
            TextCorrectionScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Preferences) {
            PreferencesScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Voice) {
            VoiceScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Advanced) {
            AdvancedSettingsScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Debug) {
            DebugScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Appearance) {
            AppearanceScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.PersonalDictionary + "{locale}") {
            val locale = it.arguments?.getString("locale")?.takeIf { loc -> loc.isNotBlank() }?.constructLocale()
            PersonalDictionaryScreen(
                onClickBack = ::goBack,
                locale = locale
            )
        }
        composable(SettingsDestination.PersonalDictionaries) {
            PersonalDictionariesScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Languages) {
            LanguageScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Dictionaries) {
            DictionaryScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Layouts) {
            SecondaryLayoutScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Colors + "{theme}") {
            ColorsScreen(isNight = false, theme = it.arguments?.getString("theme"), onClickBack = ::goBack)
        }
        composable(SettingsDestination.ColorsNight + "{theme}") {
            ColorsScreen(isNight = true, theme = it.arguments?.getString("theme"), onClickBack = ::goBack)
        }
        composable(SettingsDestination.Subtype + "{subtype}") {
            SubtypeScreen(initialSubtype = it.arguments?.getString("subtype")!!.toSettingsSubtype(), onClickBack = ::goBack)
        }
    }
    if (target.value != SettingsDestination.Settings/* && target.value != navController.currentBackStackEntry?.destination?.route*/)
        navController.navigate(route = target.value)
}

object SettingsDestination {
    const val Settings = "settings"
    const val About = "about"
    const val TextCorrection = "text_correction"
    const val Preferences = "preferences"
    const val Voice = "voice"
    const val Advanced = "advanced"
    const val Debug = "debug"
    const val Appearance = "appearance"
    const val Colors = "colors/"
    const val ColorsNight = "colors_night/"
    const val PersonalDictionaries = "personal_dictionaries"
    const val PersonalDictionary = "personal_dictionary/"
    const val Languages = "languages"
    const val Subtype = "subtype/"
    const val Layouts = "layouts"
    const val Dictionaries = "dictionaries"
    val navTarget = MutableStateFlow(Settings)

    private val navScope = CoroutineScope(Dispatchers.Default)
    fun navigateTo(target: String) {
        if (navTarget.value == target) {
            // triggers recompose twice, but that's ok as it's a rare event
            navTarget.value = Settings
            navScope.launch { delay(10); navTarget.value = target }
        } else
            navTarget.value = target
        navScope.launch { delay(50); navTarget.value = Settings }
    }
}
