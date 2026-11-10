/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: one managed system setting in the catalog (spec addendum 2026-11-09-01 §3).
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

/**
 * A catalog entry: where a setting lives, what its stock value is, and how "matches stock" is
 * judged for it. Immutable, and free of Android types so the comparison logic can be tested
 * off-device.
 *
 * The comparison mode is per entry because "equal to stock" means different things:
 * <ul>
 *   <li>{@link Compare#EXACT}: the value must equal the expected value.</li>
 *   <li>{@link Compare#SUPERSET}: the value is a separated list and every required token must be
 *       present; extra tokens are fine. This is a floor, not a fixed value. It is what lets the
 *       status-bar icon list carry the arming marker's sentinel token without that ever reading as
 *       drift.</li>
 *   <li>{@link Compare#PATTERN}: the value must match a regular expression in full.</li>
 * </ul>
 *
 * {@code tier} records what writing the setting will need (1: the provisioned grants, 2: Shizuku).
 * Nothing writes yet; the field is carried so the catalog is the single description of each entry.
 */
final class SettingEntry {

    enum Namespace { SECURE, GLOBAL, SYSTEM }

    enum Compare { EXACT, SUPERSET, PATTERN }

    final Namespace namespace;
    final String key;
    final int titleRes;
    final int summaryRes;
    final Compare compare;
    /** EXACT: the stock value. SUPERSET: the required tokens, separated. PATTERN: the regex. */
    final String stock;
    /** Token separator for SUPERSET entries; unused otherwise. */
    final char separator;
    final int tier;

    private SettingEntry(Namespace namespace, String key, int titleRes, int summaryRes,
            Compare compare, String stock, char separator, int tier) {
        this.namespace = namespace;
        this.key = key;
        this.titleRes = titleRes;
        this.summaryRes = summaryRes;
        this.compare = compare;
        this.stock = stock;
        this.separator = separator;
        this.tier = tier;
    }

    static SettingEntry exact(Namespace ns, String key, int titleRes, int summaryRes, String stock,
            int tier) {
        return new SettingEntry(ns, key, titleRes, summaryRes, Compare.EXACT, stock, ',', tier);
    }

    static SettingEntry superset(Namespace ns, String key, int titleRes, int summaryRes,
            String requiredTokens, char separator, int tier) {
        return new SettingEntry(ns, key, titleRes, summaryRes, Compare.SUPERSET, requiredTokens,
                separator, tier);
    }

    static SettingEntry pattern(Namespace ns, String key, int titleRes, int summaryRes,
            String regex, int tier) {
        return new SettingEntry(ns, key, titleRes, summaryRes, Compare.PATTERN, regex, ',', tier);
    }

    /** "secure/icon_blacklist": how the entry is addressed from a shell, for the row caption. */
    String address() {
        return namespace.name().toLowerCase(java.util.Locale.ROOT) + "/" + key;
    }
}
