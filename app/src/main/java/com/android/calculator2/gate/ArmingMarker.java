/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.calculator2.gate;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

/**
 * Arming marker (spec addendum 2026-10-09-01 §5).
 *
 * Rather than fabricate a new settings key (which would show a tell-tale like {@code
 * device_provisioned_v2} in a listing and persist forever), the marker piggybacks an existing,
 * static, innocuous setting: {@code secure icon_blacklist} (SystemUI's status-bar icon hide-list, a
 * CSV SystemUI only reads). Arming appends a sentinel token; an unknown slot is a harmless no-op.
 *
 *   armed  <=>  the icon_blacklist CSV contains the sentinel token.
 *
 * Arming is done by the provisioner from the shell (append the sentinel). The app only ever READS to
 * check (no permission needed) and strips the sentinel on PIN confirm (needs WRITE_SECURE_SETTINGS,
 * held while armed). Clearing removes ONLY the sentinel token — not a snapshot restore — so any
 * concurrent change to the list is preserved.
 *
 * Shell arm (idempotent):
 *   settings put secure icon_blacklist '&lt;current&gt;,managed_profile'
 */
public final class ArmingMarker {

    /** Settings.Secure key that hosts the marker. */
    private static final String HOST_KEY = "icon_blacklist";

    /** Sentinel token: a real SystemUI slot for a feature a degoogled kiosk lacks → guaranteed no-op. */
    private static final String SENTINEL = "managed_profile";

    private ArmingMarker() {}

    /** True iff the host CSV currently contains the sentinel token. Reading secure settings needs no permission. */
    public static boolean isSet(Context ctx) {
        final String v = read(ctx);
        if (v == null || v.isEmpty()) return false;
        for (String token : v.split(",")) {
            if (SENTINEL.equals(token.trim())) return true;
        }
        return false;
    }

    /**
     * Remove only the sentinel token from the host CSV, preserving every other entry. Requires
     * WRITE_SECURE_SETTINGS (held when armed). No-op if the host is empty or the sentinel is absent.
     */
    public static void clear(Context ctx) {
        final String v = read(ctx);
        if (v == null || v.isEmpty()) return;
        final StringBuilder sb = new StringBuilder();
        for (String token : v.split(",")) {
            final String t = token.trim();
            if (t.isEmpty() || SENTINEL.equals(t)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(token);   // keep the original token (not trimmed) to avoid mutating other entries
        }
        write(ctx, sb.toString());
    }

    private static String read(Context ctx) {
        final ContentResolver cr = ctx.getContentResolver();
        return Settings.Secure.getString(cr, HOST_KEY);
    }

    private static void write(Context ctx, String value) {
        final ContentResolver cr = ctx.getContentResolver();
        Settings.Secure.putString(cr, HOST_KEY, value);
    }
}
