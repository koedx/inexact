/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: the stored custom values (the overlay) for each role, and which role's are applied.
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
 * An overlay is a sparse set of custom values laid over stock: only the entries a role has changed
 * are stored, and everything else is stock by definition. One overlay per role.
 *
 * Also holds the APPLIED marker: the role whose overlay is currently written to the tablet, or
 * {@link #NONE}. It is persisted, not kept in memory, so that if the process dies while custom
 * values are applied, the next launch still knows to take them off.
 *
 * Plain app-private preferences, not encrypted: these are display and convenience values (theme,
 * tiles, button behaviour), the same values anyone can read back from the settings tables.
 * Security-relevant entries cannot be stored here at all, because only overlayable entries are
 * accepted.
 */
final class OverlayStore {

    static final int NONE = GateSession.NO_ROLE;

    private static final String FILE = "overlay";
    private static final String KEY_APPLIED = "applied_role";

    private final SharedPreferences mPrefs;

    OverlayStore(Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static String key(int role, SettingEntry entry) {
        return "r" + role + "/" + entry.address();
    }

    /** The role's custom value for this entry, or null (meaning stock). */
    String custom(int role, SettingEntry entry) {
        if (role == NONE || !entry.overlayable) return null;
        return mPrefs.getString(key(role, entry), null);
    }

    /** Store a custom value; null removes it. Ignored for entries that are not overlayable. */
    void setCustom(int role, SettingEntry entry, String value) {
        if (role == NONE || !entry.overlayable) return;
        final SharedPreferences.Editor editor = mPrefs.edit();
        if (value == null) {
            editor.remove(key(role, entry));
        } else {
            editor.putString(key(role, entry), value);
        }
        editor.commit();
    }

    /** True if the role has at least one custom value. */
    boolean hasAny(int role) {
        if (role == NONE) return false;
        for (SettingsCatalog.Section section : SettingsCatalog.SECTIONS) {
            for (SettingEntry entry : section.entries) {
                if (custom(role, entry) != null) return true;
            }
        }
        return false;
    }

    /** The role whose overlay is written to the tablet right now, or {@link #NONE}. */
    int appliedRole() {
        return mPrefs.getInt(KEY_APPLIED, NONE);
    }

    void setAppliedRole(int role) {
        mPrefs.edit().putInt(KEY_APPLIED, role).commit();   // synchronous: must survive a kill
    }
}
