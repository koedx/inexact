/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: the real SettingsIo: Settings.Secure, Settings.Global and Settings.System through this
 *          app's own ContentResolver. No shell, no Shizuku.
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

import android.content.ContentResolver;
import android.provider.Settings;

/**
 * Tier 1 writes. What the system allows this app:
 * <ul>
 *   <li>{@code secure} and {@code global}: allowed by the WRITE_SECURE_SETTINGS grant.</li>
 *   <li>{@code system}: that grant gets past the permission check, but the system then accepts
 *       only its PUBLIC system keys from an ordinary app; the rest are reserved to the system,
 *       the shell and privileged apps. The two system keys in the catalog are not public, so the
 *       expectation (UNVERIFIED on the device until this build runs) is that those writes are
 *       refused. The refusal arrives as an exception, reported here as {@link Result#REFUSED}.</li>
 * </ul>
 * Every write is read back: a put that reports success but did not change the value is a failure.
 */
final class SystemSettingsIo implements SettingsIo {

    private final ContentResolver mResolver;

    SystemSettingsIo(ContentResolver resolver) {
        mResolver = resolver;
    }

    @Override
    public String read(SettingEntry entry) {
        switch (entry.namespace) {
            case SECURE: return Settings.Secure.getString(mResolver, entry.key);
            case GLOBAL: return Settings.Global.getString(mResolver, entry.key);
            case SYSTEM:
            default:     return Settings.System.getString(mResolver, entry.key);
        }
    }

    @Override
    public Result write(SettingEntry entry, String value) {
        try {
            final boolean put;
            switch (entry.namespace) {
                case SECURE: put = Settings.Secure.putString(mResolver, entry.key, value); break;
                case GLOBAL: put = Settings.Global.putString(mResolver, entry.key, value); break;
                case SYSTEM:
                default:     put = Settings.System.putString(mResolver, entry.key, value); break;
            }
            if (!put) return Result.FAILED;
            final String now = read(entry);
            return (now != null && now.equals(value)) ? Result.OK : Result.FAILED;
        } catch (SecurityException | IllegalArgumentException e) {
            return Result.REFUSED;   // not permitted for this app: the same answer every time
        } catch (RuntimeException e) {
            return Result.FAILED;
        }
    }
}
