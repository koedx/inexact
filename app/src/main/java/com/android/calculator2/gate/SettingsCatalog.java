/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: the fixed set of system settings the dashboard tracks, with their stock values for this
 *          ROM (spec addendum 2026-11-09-01 §3).
 * Created:  2026-11-10 06:00:00 UTC
 * Modified: 2026-11-10 06:00:00 UTC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.calculator2.gate;

import static com.android.calculator2.gate.SettingEntry.Namespace.GLOBAL;
import static com.android.calculator2.gate.SettingEntry.Namespace.SECURE;
import static com.android.calculator2.gate.SettingEntry.Namespace.SYSTEM;

import com.android.calculator2.R;

/**
 * The catalog. Stock is hardcoded because the ROM this app targets is fixed (build
 * QP1A.190711.020): only the settings listed here are tracked, and there is no captured baseline.
 *
 * Namespaces were confirmed on the device on 2026-11-09 by reading each key from all three: the
 * three power-button keys live in {@code global}, and the two display keys in {@code system}.
 *
 * Adding an entry is safe for stored overlays: an overlay holds only the keys it changes, so one
 * that predates the new entry simply leaves it at stock.
 */
final class SettingsCatalog {

    /**
     * Lock screen message: an 8 digit ID number, a colon and a space, then LAST, FIRST in capitals.
     * Either name may be hyphenated. Matched against the whole value.
     */
    static final String OWNER_INFO_PATTERN = "\\d{8}: [A-Z]+(-[A-Z]+)?, [A-Z]+(-[A-Z]+)?";

    /** The status-bar icons that must be hidden. A floor: the list may hold more than these. */
    static final String ICON_BLACKLIST_FLOOR = "airplane,bluetooth,cast,rotate,vpn,wifi";

    static final class Section {
        final int titleRes;
        final SettingEntry[] entries;

        Section(int titleRes, SettingEntry... entries) {
            this.titleRes = titleRes;
            this.entries = entries;
        }
    }

    static final Section[] SECTIONS = {
        new Section(R.string.settings_section_status_bar,
            SettingEntry.superset(SECURE, "icon_blacklist",
                    R.string.settings_icon_blacklist_title,
                    R.string.settings_icon_blacklist_summary,
                    ICON_BLACKLIST_FLOOR, ',', 1),
            SettingEntry.exact(SYSTEM, "status_bar_show_battery_percent",
                    R.string.settings_battery_percent_title,
                    R.string.settings_off_on_summary, "0", 2)),

        new Section(R.string.settings_section_quick_settings,
            // EXACT on purpose: only the rotation tile is wanted, and tile order is meaningful.
            SettingEntry.exact(SECURE, "sysui_qs_tiles",
                    R.string.settings_qs_tiles_title,
                    R.string.settings_qs_tiles_summary, "rotation", 1)),

        new Section(R.string.settings_section_power_button,
            SettingEntry.exact(GLOBAL, "power_button_double_press",
                    R.string.settings_power_double_title,
                    R.string.settings_power_double_summary, "0", 1),
            SettingEntry.exact(GLOBAL, "power_button_long_press",
                    R.string.settings_power_long_title,
                    R.string.settings_power_menu_summary, "0", 1),
            SettingEntry.exact(GLOBAL, "power_button_very_long_press",
                    R.string.settings_power_very_long_title,
                    R.string.settings_power_menu_summary, "0", 1)),

        new Section(R.string.settings_section_display,
            SettingEntry.exact(SECURE, "ui_night_mode",
                    R.string.settings_night_mode_title,
                    R.string.settings_night_mode_summary, "1", 1),
            SettingEntry.exact(SYSTEM, "show_touches",
                    R.string.settings_show_touches_title,
                    R.string.settings_off_on_summary, "0", 2)),

        new Section(R.string.settings_section_lock_screen,
            SettingEntry.pattern(SECURE, "lock_screen_owner_info",
                    R.string.settings_owner_info_title,
                    R.string.settings_owner_info_summary, OWNER_INFO_PATTERN, 1),
            SettingEntry.exact(SECURE, "lock_screen_allow_private_notifications",
                    R.string.settings_private_notifications_title,
                    R.string.settings_private_notifications_summary, "0", 1)),

        new Section(R.string.settings_section_printing,
            // A colon-separated list of component names, like other component lists in Secure.
            SettingEntry.superset(SECURE, "disabled_print_services",
                    R.string.settings_print_services_title,
                    R.string.settings_print_services_summary,
                    "com.android.bips/.BuiltInPrintService", ':', 1)),

        new Section(R.string.settings_section_network,
            SettingEntry.exact(GLOBAL, "airplane_mode_on",
                    R.string.settings_airplane_title,
                    R.string.settings_off_on_summary, "1", 2)),
    };

    private SettingsCatalog() {}

    static int size() {
        int n = 0;
        for (Section s : SECTIONS) n += s.entries.length;
        return n;
    }
}
