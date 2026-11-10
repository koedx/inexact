/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: one managed system setting in the catalog.
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

/**
 * A catalog entry: where a setting lives, what its stock value is, how "matches" is judged for it,
 * and what may be done to it. Immutable, and free of Android types so the logic around it can be
 * tested off-device.
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
 * What may be done to an entry:
 * <ul>
 *   <li>{@code tier}: 1 means this app attempts the write itself; 2 means only a shell-level caller
 *       can make the change take effect, so the entry is read-only until that path exists.</li>
 *   <li>{@code overlayable}: the entry may carry a custom value that the Custom state applies and
 *       the Stock state takes away again. Entries that are NOT overlayable can only be put back to
 *       stock. Security-relevant entries are deliberately not overlayable: a custom value comes
 *       off automatically at sign-out, and a missed revert must never be a security failure.</li>
 *   <li>{@code choiceValues}/{@code choiceLabels}: the values offered when editing, with a label
 *       each. Null means free text.</li>
 * </ul>
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
    final boolean overlayable;
    final String[] choiceValues;
    final int[] choiceLabels;

    private SettingEntry(Namespace namespace, String key, int titleRes, int summaryRes,
            Compare compare, String stock, char separator, int tier, boolean overlayable,
            String[] choiceValues, int[] choiceLabels) {
        this.namespace = namespace;
        this.key = key;
        this.titleRes = titleRes;
        this.summaryRes = summaryRes;
        this.compare = compare;
        this.stock = stock;
        this.separator = separator;
        this.tier = tier;
        this.overlayable = overlayable;
        this.choiceValues = choiceValues;
        this.choiceLabels = choiceLabels;
    }

    static SettingEntry exact(Namespace ns, String key, int titleRes, int summaryRes, String stock,
            int tier) {
        return new SettingEntry(ns, key, titleRes, summaryRes, Compare.EXACT, stock, ',', tier,
                false, null, null);
    }

    static SettingEntry superset(Namespace ns, String key, int titleRes, int summaryRes,
            String requiredTokens, char separator, int tier) {
        return new SettingEntry(ns, key, titleRes, summaryRes, Compare.SUPERSET, requiredTokens,
                separator, tier, false, null, null);
    }

    static SettingEntry pattern(Namespace ns, String key, int titleRes, int summaryRes,
            String regex, int tier) {
        return new SettingEntry(ns, key, titleRes, summaryRes, Compare.PATTERN, regex, ',', tier,
                false, null, null);
    }

    /** A copy that may carry a custom value. Only EXACT entries can: a floor has no single value. */
    SettingEntry overlayable() {
        if (compare != Compare.EXACT) {
            throw new IllegalStateException("only EXACT entries can be overlaid: " + key);
        }
        return new SettingEntry(namespace, key, titleRes, summaryRes, compare, stock, separator,
                tier, true, choiceValues, choiceLabels);
    }

    /** A copy that offers these values, each with a label, when edited or displayed. */
    SettingEntry choices(String[] values, int[] labels) {
        if (values.length != labels.length) {
            throw new IllegalArgumentException("choices and labels differ in length: " + key);
        }
        return new SettingEntry(namespace, key, titleRes, summaryRes, compare, stock, separator,
                tier, overlayable, values, labels);
    }

    /** True if this app attempts the write itself (it may still be refused by the system). */
    boolean writable() {
        return tier == 1;
    }

    /** Label resource for a value, or 0 if the value is not one of the offered choices. */
    int labelFor(String value) {
        if (choiceValues == null || value == null) return 0;
        for (int i = 0; i < choiceValues.length; i++) {
            if (choiceValues[i].equals(value.trim())) return choiceLabels[i];
        }
        return 0;
    }

    /** "secure/icon_blacklist": how the entry is addressed from a shell, and its storage key. */
    String address() {
        return namespace.name().toLowerCase(java.util.Locale.ROOT) + "/" + key;
    }
}
