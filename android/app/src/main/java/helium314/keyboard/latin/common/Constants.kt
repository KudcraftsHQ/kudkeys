// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.common

object Links {
    const val DICTIONARY_URL = "https://codeberg.org/Helium314/aosp-dictionaries"
    const val DICTIONARY_DOWNLOAD_SUFFIX = "/raw/branch/main/"
    const val DICTIONARY_NORMAL_SUFFIX = "dictionaries/"
    const val DICTIONARY_EMOJI_CLDR_SUFFIX = "emoji_cldr_signal_dictionaries/"
    // kudkeys: GITHUB is our fork; wiki and community links stay on upstream HeliBoard
    const val GITHUB = "https://github.com/KudcraftsHQ/kudkeys"
    const val HELIBOARD = "https://github.com/HeliBorg/HeliBoard"
    const val LICENSE = "$GITHUB/blob/main/android/LICENSE"
    const val WIKI_URL = "$HELIBOARD/wiki"
    const val LAYOUT_WIKI_URL = "$WIKI_URL/2.-Layouts"
    const val CUSTOM_LAYOUTS = "$HELIBOARD/discussions/categories/custom-layout"
    const val CUSTOM_COLORS = "$HELIBOARD/discussions/categories/custom-colors"
    const val SWIPE_O_SCOPE = "https://codeberg.org/eclexic/swipe-o-scope"
    const val COMMUNITY_LINKS = "$GITHUB#to-community"
}

val combiningRange = 0x300..0x35b
