/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: decides whether a setting's actual value matches what is expected of it.
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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.PatternSyntaxException;

/**
 * Drift detection. Pure functions over strings, free of Android types.
 *
 * "Expected" is the stock value for now. When overlays arrive it becomes stock with the active
 * overlay applied on top, which is why every method takes the expected value as a parameter instead
 * of reading it off the entry.
 *
 * Drift is only ever REPORTED here. Nothing in this class, or anything that calls it, corrects a
 * value.
 */
final class SettingCompare {

    enum State {
        /** Matches what is expected. */
        STOCK,
        /** Set, and different from what is expected. Not an error: an overlay drifts on purpose. */
        DRIFT,
        /**
         * An EXACT entry whose key is absent. Reported on its own instead of as drift: the platform
         * default then applies, and this class does not claim to know what that default is.
         */
        UNSET,
        /** A PATTERN entry whose value is absent or does not match. This one IS an error. */
        INVALID
    }

    private SettingCompare() {}

    static State evaluate(SettingEntry entry, String actual) {
        return evaluate(entry, actual, entry.stock);
    }

    static State evaluate(SettingEntry entry, String actual, String expected) {
        switch (entry.compare) {
            case SUPERSET:
                // An absent list is an empty list, so it is missing every required token.
                return missingTokens(entry, actual, expected).isEmpty() ? State.STOCK : State.DRIFT;
            case PATTERN:
                return matches(actual, expected) ? State.STOCK : State.INVALID;
            case EXACT:
            default:
                if (actual == null) return State.UNSET;
                return actual.trim().equals(expected == null ? "" : expected.trim())
                        ? State.STOCK : State.DRIFT;
        }
    }

    /** The required tokens that the actual value lacks, in the order they are required. */
    static List<String> missingTokens(SettingEntry entry, String actual, String expected) {
        final List<String> have = tokens(actual, entry.separator);
        final List<String> missing = new ArrayList<>();
        for (String want : tokens(expected, entry.separator)) {
            if (!have.contains(want)) missing.add(want);
        }
        return missing;
    }

    /** Split on the separator, trimming each token and dropping empty ones. Null gives no tokens. */
    static List<String> tokens(String value, char separator) {
        final List<String> out = new ArrayList<>();
        if (value == null) return out;
        int start = 0;
        for (int i = 0; i <= value.length(); i++) {
            if (i == value.length() || value.charAt(i) == separator) {
                final String t = value.substring(start, i).trim();
                if (!t.isEmpty()) out.add(t);
                start = i + 1;
            }
        }
        return out;
    }

    /** Full-string match; a null value or a broken pattern is a non-match, never an exception. */
    static boolean matches(String actual, String regex) {
        if (actual == null || regex == null) return false;
        try {
            return actual.matches(regex);
        } catch (PatternSyntaxException e) {
            return false;
        }
    }
}
