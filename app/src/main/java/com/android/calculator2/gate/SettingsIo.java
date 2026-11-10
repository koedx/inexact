/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: reading and writing one catalog setting, behind an interface so the logic above it can be
 *          tested without a device.
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

/**
 * The only door between the settings logic and the system's settings tables.
 *
 * A write has three outcomes, kept apart because they call for different responses: it worked, the
 * system refused it (this app is not allowed to change that key, so retrying is pointless and the
 * change belongs to the shell tier), or it failed for some other reason.
 */
interface SettingsIo {

    enum Result { OK, REFUSED, FAILED }

    /** The current value, or null if the key is not set. May throw if the read itself fails. */
    String read(SettingEntry entry);

    /** Write a value and confirm it by reading it back. Never throws. */
    Result write(SettingEntry entry, String value);
}
