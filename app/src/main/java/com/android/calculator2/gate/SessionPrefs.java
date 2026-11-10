/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: session options: sign out after the tablet has been locked for a while, and when custom
 *          settings go on and come off.
 * Created:  2026-11-10 09:00:00 UTC
 * Modified: 2026-11-10 09:00:00 UTC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.calculator2.gate;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Admin-set options for how a session behaves. Defaults:
 * <ul>
 *   <li>Lock timeout: OFF. When on, the session ends once the tablet has been locked for the chosen
 *       time (default 10 minutes; 0 means the moment the screen turns off).</li>
 *   <li>Apply custom settings at sign in: OFF.</li>
 *   <li>Return settings to stock at sign out: ON.</li>
 * </ul>
 * A data-cleared install has no file here and so gets the defaults.
 */
final class SessionPrefs {

    /** The offered lock-timeout lengths, in minutes. 0 is immediate. */
    static final int[] TIMEOUT_CHOICES = { 0, 1, 5, 10, 15, 30, 60 };
    static final int DEFAULT_TIMEOUT_MINUTES = 10;

    private static final String FILE = "session";
    private static final String KEY_TIMEOUT_ON = "lock_timeout_enabled";
    private static final String KEY_TIMEOUT_MINUTES = "lock_timeout_minutes";
    private static final String KEY_AUTO_APPLY = "apply_custom_at_sign_in";
    private static final String KEY_REVERT = "stock_at_sign_out";

    private SessionPrefs() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean lockTimeoutEnabled(Context c) {
        return prefs(c).getBoolean(KEY_TIMEOUT_ON, false);
    }

    static void setLockTimeoutEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_TIMEOUT_ON, on).commit();
    }

    static int lockTimeoutMinutes(Context c) {
        return sanitizeMinutes(prefs(c).getInt(KEY_TIMEOUT_MINUTES, DEFAULT_TIMEOUT_MINUTES));
    }

    static void setLockTimeoutMinutes(Context c, int minutes) {
        prefs(c).edit().putInt(KEY_TIMEOUT_MINUTES, sanitizeMinutes(minutes)).commit();
    }

    static boolean applyCustomAtSignIn(Context c) {
        return prefs(c).getBoolean(KEY_AUTO_APPLY, false);
    }

    static void setApplyCustomAtSignIn(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_AUTO_APPLY, on).commit();
    }

    static boolean stockAtSignOut(Context c) {
        return prefs(c).getBoolean(KEY_REVERT, true);
    }

    static void setStockAtSignOut(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_REVERT, on).commit();
    }

    /** Only the offered lengths are accepted; anything else becomes the default. */
    static int sanitizeMinutes(int minutes) {
        for (int choice : TIMEOUT_CHOICES) {
            if (choice == minutes) return minutes;
        }
        return DEFAULT_TIMEOUT_MINUTES;
    }

    /**
     * Whether a timeout that has come due should end the session. It should unless the user is
     * demonstrably back: screen on AND past the lock screen. A screen that is on but still showing
     * the lock screen counts as locked.
     */
    static boolean stillLocked(boolean screenInteractive, boolean keyguardLocked) {
        return !(screenInteractive && !keyguardLocked);
    }
}
