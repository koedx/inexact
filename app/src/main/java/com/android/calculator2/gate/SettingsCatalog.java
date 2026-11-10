/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: the fixed set of system settings the dashboard tracks, with their stock values for this
 *          ROM and what may be done to each.
 * Created:  2026-11-10 06:00:00 UTC
 * Modified: 2026-11-10 09:00:00 UTC
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
 * Which entries may carry a custom value: the cosmetic and convenience ones (theme, taps, battery
 * percentage, tiles, power button). The lock-screen notification entries and the two list floors
 * can only be put back to stock; see {@link SettingEntry} for why.
 *
 * NOT in the catalog: the lock screen message. On this Android version the text shown under the
 * lock screen clock is held by the lock-settings service, not in a Settings table, so it cannot be
 * read or written like the entries here. {@link #OWNER_INFO_PATTERN} is kept for when a way to
 * reach it exists.
 *
 * Adding an entry is safe for stored custom values: they are kept per key, so a store that
 * predates the new entry simply leaves it at stock.
 */
final class SettingsCatalog {

    /**
     * Lock screen message: an 8 digit ID number, a colon and a space, then LAST, FIRST in capitals.
     * Either name may be hyphenated. Matched against the whole value.
     */
    static final String OWNER_INFO_PATTERN = "\\d{8}: [A-Z]+(-[A-Z]+)?, [A-Z]+(-[A-Z]+)?";

    /** The status-bar icons that must be hidden. A floor: the list may hold more than these. */
    static final String ICON_BLACKLIST_FLOOR = "airplane,bluetooth,cast,rotate,vpn,wifi";

    private static final String[] OFF_ON = { "0", "1" };
    private static final int[] OFF_ON_LABELS = {
        R.string.settings_choice_off, R.string.settings_choice_on };

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
            // system namespace: the write is attempted, and the system is expected to refuse it
            // (non-public system keys are closed to ordinary apps). The attempt settles it.
            SettingEntry.exact(SYSTEM, "status_bar_show_battery_percent",
                    R.string.settings_battery_percent_title,
                    R.string.settings_off_on_summary, "0", 1)
                    .choices(OFF_ON, OFF_ON_LABELS).overlayable()),

        new Section(R.string.settings_section_quick_settings,
            // EXACT on purpose: only the rotation tile is wanted, and tile order is meaningful.
            SettingEntry.exact(SECURE, "sysui_qs_tiles",
                    R.string.settings_qs_tiles_title,
                    R.string.settings_qs_tiles_summary, "rotation", 1)
                    .overlayable()),

        new Section(R.string.settings_section_power_button,
            SettingEntry.exact(GLOBAL, "power_button_double_press",
                    R.string.settings_power_double_title,
                    R.string.settings_power_summary, "0", 1)
                    .choices(new String[] { "0", "1", "2" }, new int[] {
                        R.string.settings_choice_nothing,
                        R.string.settings_choice_theater,
                        R.string.settings_choice_brightness_boost })
                    .overlayable(),
            SettingEntry.exact(GLOBAL, "power_button_long_press",
                    R.string.settings_power_long_title,
                    R.string.settings_power_summary, "0", 1)
                    .choices(new String[] { "0", "1", "2", "3", "4" }, new int[] {
                        R.string.settings_choice_nothing,
                        R.string.settings_choice_power_menu,
                        R.string.settings_choice_power_off,
                        R.string.settings_choice_power_off_confirm,
                        R.string.settings_choice_voice_assist })
                    .overlayable(),
            SettingEntry.exact(GLOBAL, "power_button_very_long_press",
                    R.string.settings_power_very_long_title,
                    R.string.settings_power_summary, "0", 1)
                    .choices(new String[] { "0", "1" }, new int[] {
                        R.string.settings_choice_nothing,
                        R.string.settings_choice_power_menu })
                    .overlayable()),

        new Section(R.string.settings_section_display,
            SettingEntry.exact(SECURE, "ui_night_mode",
                    R.string.settings_night_mode_title,
                    R.string.settings_night_mode_summary, "1", 1)
                    .choices(new String[] { "1", "2", "0" }, new int[] {
                        R.string.settings_choice_light,
                        R.string.settings_choice_dark,
                        R.string.settings_choice_automatic })
                    .overlayable(),
            SettingEntry.exact(SYSTEM, "show_touches",
                    R.string.settings_show_touches_title,
                    R.string.settings_off_on_summary, "0", 1)
                    .choices(OFF_ON, OFF_ON_LABELS).overlayable()),

        new Section(R.string.settings_section_lock_screen,
            // Not overlayable: these decide what a locked tablet reveals.
            SettingEntry.exact(SECURE, "lock_screen_show_notifications",
                    R.string.settings_show_notifications_title,
                    R.string.settings_show_notifications_summary, "0", 1)
                    .choices(OFF_ON, OFF_ON_LABELS),
            SettingEntry.exact(SECURE, "lock_screen_allow_private_notifications",
                    R.string.settings_private_notifications_title,
                    R.string.settings_private_notifications_summary, "0", 1)
                    .choices(OFF_ON, OFF_ON_LABELS)),

        new Section(R.string.settings_section_printing,
            // A colon-separated list of component names, like other component lists in Secure.
            SettingEntry.superset(SECURE, "disabled_print_services",
                    R.string.settings_print_services_title,
                    R.string.settings_print_services_summary,
                    "com.android.bips/.BuiltInPrintService", ':', 1)),

        new Section(R.string.settings_section_network,
            // Tier 2: writing the key alone does not switch the radios; the broadcast that does is
            // reserved to the system and shell.
            SettingEntry.exact(GLOBAL, "airplane_mode_on",
                    R.string.settings_airplane_title,
                    R.string.settings_airplane_summary, "1", 2)
                    .choices(OFF_ON, OFF_ON_LABELS)),
    };

    private SettingsCatalog() {}

    static int size() {
        int n = 0;
        for (Section s : SECTIONS) n += s.entries.length;
        return n;
    }

    /** The entry at this address ("secure/ui_night_mode"), or null. */
    static SettingEntry find(String address) {
        for (Section s : SECTIONS) {
            for (SettingEntry e : s.entries) {
                if (e.address().equals(address)) return e;
            }
        }
        return null;
    }
}
