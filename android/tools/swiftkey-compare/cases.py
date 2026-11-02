# Test script for the SwiftKey comparison. Research only.
# DSL: letters, space, ',' and '.' are typed with taps; '|' = snapshot (strip crop + field text);
# '<c>' '<l>' '<r>' = tap the centre / left / right suggestion slot.
# Never put personal words here: both keyboards are compared stock.

CASES = [
    # prefix completion
    ("c01_tom", "completion", "t|o|m|"),
    ("c02_bes", "completion", "b|e|s|"),
    ("c03_beaut", "completion", "bea|u|t|"),
    ("c04_sebent", "completion", "seb|e|n|"),
    ("c05_intere", "completion", "int|e|r|e|"),
    ("c06_kemar", "completion", "kem|a|r|"),
    ("c07_prob", "completion", "pro|b|"),
    # typo correction (snapshot before and after space)
    ("c08_teh", "typo", "teh| |"),
    ("c09_beanr", "typo", "beanr| |"),
    ("c10_tdak", "typo", "tdak| |"),
    ("c11_recieve", "typo", "recieve| |"),
    ("c12_sya", "typo", "sya| |"),
    ("c13_becuase", "typo", "becuase| |"),
    ("c14_bagaimna", "typo", "bagaimna| |"),
    # next-word prediction
    ("c15_ill_be_there_in", "prediction", "ill| be| there| in |"),
    ("c16_hari_ini_aku_mau", "prediction", "hari |ini |aku |mau |"),
    ("c17_terima", "prediction", "terima |"),
    ("c18_thank", "prediction", "thank |"),
    ("c19_selamat", "prediction", "selamat |"),
    ("c20_how_are", "prediction", "how are |"),
    ("c21_aku_lagi_di", "prediction", "aku lagi di |"),
    ("c22_see_you", "prediction", "see you |"),
    ("c23_empty_field", "prediction", "|"),
    # apostrophes
    ("c24_dont", "apostrophe", "dont| |"),
    ("c25_im", "apostrophe", "im| |"),
    ("c26_cant", "apostrophe", "cant| |"),
    # capitalisation
    ("c27_i", "caps", "i| |am |"),
    ("c28_after_period", "caps", "ok. |see|"),
    # slang
    ("c29_gw", "slang", "gw| |"),
    ("c30_gak", "slang", "gak| |"),
    ("c31_makasih", "slang", "makasi|h| |"),
    ("c32_kalo", "slang", "kalo| |"),
    ("c33_bgt", "slang", "bgt| |"),
    # mixed languages
    ("c34_besok_meeting", "mixed", "besok |meeting |jam |"),
    ("c35_sorry_telat", "mixed", "sorry |telat |ya |"),
    ("c36_aku_udah_submit", "mixed", "aku udah |sub|mit |"),
    # picking a suggestion: spacing and punctuation after the pick
    ("c37_pick_tom_period", "pick", "tom|<c>|.|"),
    ("c38_pick_bes_comma", "pick", "bes|<c>|,|"),
    ("c39_pick_prediction", "pick", "terima |<c>|.|"),
    ("c40_pick_then_word", "pick", "how |<c>|are|"),
    # more next-word contexts (Hammas's main complaint)
    ("c41_aku", "prediction", "aku |"),
    ("c42_nanti", "prediction", "nanti |"),
    ("c43_i_want_to", "prediction", "i want |to |"),
    ("c44_let_me", "prediction", "let me |"),
    ("c45_gw_mau", "prediction", "gw |mau |"),
    ("c46_after_comma", "prediction", "ok, |"),
    ("c47_good", "prediction", "good |"),
    # learning: does a pair typed once (twice) come back, in the same field and in a fresh one?
    ("c48_learn_same_field", "learning", "makan bakso. makan |makan bakso. makan |"),
    ("c49_learn_new_field", "learning", "makan |"),
    # capitalisation mid-sentence (Hammas's second complaint)
    ("c50_caps_ak_mid", "caps", "ya ak|u|"),
    ("c51_caps_mak_mid", "caps", "terus mak|"),
    ("c52_caps_kan_mid", "caps", "nanti ke kan|"),
    ("c53_caps_kem_mid", "caps", "ya kem|"),
    ("c54_caps_jak_mid", "caps", "aku ke jak|"),
    ("c55_caps_start", "caps", "ak|"),
    ("c56_caps_after_period", "caps", "ok. ak|"),
    ("c57_caps_history", "caps", "aku pulang. aku pulang. ya ak|u|"),
    ("c58_caps_pred_mid", "caps", "ya udah |"),
]

# rerun on Kud Keys with a synthetic SwiftKey-style add-on dictionary (common words + capitalised twins at 140)
ADDON_CASES = ["c01_tom", "c02_bes", "c16_hari_ini_aku_mau", "c30_gak", "c32_kalo", "c34_besok_meeting",
               "c41_aku", "c42_nanti", "c50_caps_ak_mid", "c51_caps_mak_mid", "c52_caps_kan_mid", "c53_caps_kem_mid",
               "c54_caps_jak_mid", "c55_caps_start", "c56_caps_after_period", "c57_caps_history", "c58_caps_pred_mid"]

# typed at full speed with one adb shell call, while recording the screen (feel / lag)
SPEED_SENTENCE = "hari ini aku mau ke kantor dulu ya "
