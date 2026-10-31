/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.utils;

import helium314.keyboard.latin.utils.Log;

// kudkeys: gesture typing is removed, so only the built-in dictionary library is loaded.
// HeliBoard also loaded a user-supplied or system gesture library here.
public final class JniUtils {
    private static final String TAG = JniUtils.class.getSimpleName();
    public static final String JNI_LIB_NAME = "jni_latinime";

    static {
        try {
            System.loadLibrary(JNI_LIB_NAME);
        } catch (UnsatisfiedLinkError ul) {
            Log.w(TAG, "Could not load native library " + JNI_LIB_NAME, ul);
        }
    }

    private JniUtils() {
        // This utility class is not publicly instantiable.
    }

    public static void loadNativeLibrary() {
        // Ensures the static initializer is called
    }
}
