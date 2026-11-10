/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: applies a role's custom values, takes them off again, and repairs single settings.
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The two states of the tablet's tracked settings, and the moves between them.
 *
 * STOCK: every tracked setting is expected to hold its stock value.
 * CUSTOM: the signed-in role's overlay is applied, so the entries it has custom values for are
 * expected to hold those instead. Everything else is still expected to be stock.
 *
 * Moves:
 * <ul>
 *   <li>{@link #apply}: write the role's custom values. Stock to Custom.</li>
 *   <li>{@link #revert}: write stock back for exactly the entries the applied overlay changed.
 *       Custom to Stock. It does not touch anything else, so it is not a "fix everything".</li>
 *   <li>{@link #fix}: put ONE setting back to what is expected of it. For a list floor this adds the
 *       missing tokens and keeps every token already there, which is what keeps the arming
 *       marker's sentinel in the status-bar icon list.</li>
 * </ul>
 *
 * Nothing here runs unless someone signed in asks for it, with two exceptions that only ever move
 * TOWARDS stock: the revert at sign-out, and {@link #reconcileAsync}, which performs that same
 * revert at the next launch if the process died before it could.
 *
 * All moves are serialized on this class, and none of them throws.
 */
final class OverlayEngine {

    /** What a batch of writes did. */
    static final class Outcome {
        int ok;
        int refused;
        int failed;

        boolean clean() {
            return refused == 0 && failed == 0;
        }

        void count(SettingsIo.Result result) {
            switch (result) {
                case OK:      ok++;      break;
                case REFUSED: refused++; break;
                default:      failed++;  break;
            }
        }
    }

    private static final int MAX_TEXT = 512;

    /** Addresses whose last write did not succeed, with the result. Process lifetime only. */
    private static final ConcurrentHashMap<String, SettingsIo.Result> sLastFailure =
            new ConcurrentHashMap<>();

    private static final CopyOnWriteArrayList<Runnable> sListeners = new CopyOnWriteArrayList<>();

    private OverlayEngine() {}

    // ---- state ---------------------------------------------------------------------------------

    /** True if this role's overlay is the one written to the tablet. */
    static boolean isCustomActive(OverlayStore store, int role) {
        return role != OverlayStore.NONE && store.appliedRole() == role;
    }

    /** What the entry should hold right now: the role's custom value if applied, else stock. */
    static String expected(SettingEntry entry, OverlayStore store, int role) {
        if (isCustomActive(store, role)) {
            final String custom = store.custom(role, entry);
            if (custom != null) return custom;
        }
        return entry.stock;
    }

    /** The result of the last write to this entry if it did not succeed, else null. */
    static SettingsIo.Result lastFailure(SettingEntry entry) {
        return sLastFailure.get(entry.address());
    }

    /** Whether a value may be stored as custom for this entry. */
    static boolean isValidCustom(SettingEntry entry, String value) {
        if (!entry.overlayable || value == null) return false;
        if (entry.choiceValues != null) {
            for (String choice : entry.choiceValues) {
                if (choice.equals(value)) return true;
            }
            return false;
        }
        return !value.isEmpty() && value.length() <= MAX_TEXT && value.equals(value.trim())
                && value.indexOf('\n') < 0;
    }

    // ---- moves ---------------------------------------------------------------------------------

    /** Stock to Custom for this role. If another role's overlay is applied, that comes off first. */
    static synchronized Outcome apply(SettingsIo io, OverlayStore store, int role) {
        final Outcome out = new Outcome();
        if (role == OverlayStore.NONE) return out;
        final int applied = store.appliedRole();
        if (applied != OverlayStore.NONE && applied != role) {
            revertLocked(io, store, new Outcome());
        }
        // Marker first: if the process dies part way through, the next launch still reverts.
        store.setAppliedRole(role);
        for (SettingEntry entry : overlayable()) {
            final String custom = store.custom(role, entry);
            if (custom != null) out.count(writeIfDifferent(io, entry, custom));
        }
        notifyListeners();
        return out;
    }

    /** Custom to Stock: stock is written back for the entries the applied overlay changed. */
    static synchronized Outcome revert(SettingsIo io, OverlayStore store) {
        final Outcome out = new Outcome();
        if (store.appliedRole() == OverlayStore.NONE) return out;
        revertLocked(io, store, out);
        notifyListeners();
        return out;
    }

    private static void revertLocked(SettingsIo io, OverlayStore store, Outcome out) {
        final int applied = store.appliedRole();
        for (SettingEntry entry : overlayable()) {
            if (store.custom(applied, entry) != null) {
                out.count(writeIfDifferent(io, entry, entry.stock));
            }
        }
        store.setAppliedRole(OverlayStore.NONE);
    }

    /**
     * Set or clear (null, or the stock value) one custom value for a role. If that role's overlay is
     * applied, the change is written straight away. Returns false if the value is not acceptable.
     */
    static synchronized boolean setCustom(SettingsIo io, OverlayStore store, int role,
            SettingEntry entry, String value) {
        if (role == OverlayStore.NONE || !entry.overlayable) return false;
        final boolean clearing = value == null || value.equals(entry.stock);
        if (!clearing && !isValidCustom(entry, value)) return false;

        store.setCustom(role, entry, clearing ? null : value);
        if (isCustomActive(store, role)) {
            writeIfDifferent(io, entry, clearing ? entry.stock : value);
            if (!store.hasAny(role)) {
                store.setAppliedRole(OverlayStore.NONE);   // nothing custom left: that is Stock
            }
        }
        notifyListeners();
        return true;
    }

    /** Put one setting back to what is expected of it. */
    static synchronized SettingsIo.Result fix(SettingsIo io, OverlayStore store, int role,
            SettingEntry entry) {
        SettingsIo.Result result;
        if (!entry.writable()) {
            result = SettingsIo.Result.REFUSED;
        } else {
            String actual;
            try {
                actual = io.read(entry);
            } catch (RuntimeException e) {
                actual = null;
            }
            final String target = fixValue(entry, actual, expected(entry, store, role));
            result = (target == null) ? SettingsIo.Result.FAILED : write(io, entry, target);
        }
        notifyListeners();
        return result;
    }

    /**
     * The value a fix would write, or null if a fix is not possible for this kind of entry.
     * EXACT: the expected value. SUPERSET: the tokens already present, then the missing ones.
     * PATTERN: null, because a format cannot say which value is the right one.
     */
    static String fixValue(SettingEntry entry, String actual, String expected) {
        switch (entry.compare) {
            case EXACT:
                return expected;
            case SUPERSET:
                final List<String> tokens = new ArrayList<>(
                        SettingCompare.tokens(actual, entry.separator));
                tokens.addAll(SettingCompare.missingTokens(entry, actual, expected));
                final StringBuilder sb = new StringBuilder();
                for (String token : tokens) {
                    if (sb.length() > 0) sb.append(entry.separator);
                    sb.append(token);
                }
                return sb.toString();
            case PATTERN:
            default:
                return null;
        }
    }

    // ---- session hooks ---------------------------------------------------------------------------

    /**
     * A role has just signed in. Another role's overlay never carries into this session, so it
     * comes off; then this role's goes on if the admin asked for that.
     */
    static synchronized void onSignIn(SettingsIo io, OverlayStore store, int role,
            boolean applyCustom) {
        final int applied = store.appliedRole();
        if (applied != OverlayStore.NONE && applied != role) {
            revertLocked(io, store, new Outcome());
            notifyListeners();
        }
        if (applyCustom && store.appliedRole() != role && store.hasAny(role)) {
            apply(io, store, role);
        }
    }

    /**
     * Launch-time catch-up, off the calling thread. If an overlay is still marked applied, nobody
     * is signed in, and settings are meant to return to stock at sign-out, the revert that should
     * have happened (the process was killed first) happens now. On an install with no stored
     * overlay this reads one absent preference and does nothing.
     */
    static void reconcileAsync(final Context context) {
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    synchronized (OverlayEngine.class) {
                        if (GateSession.isActive()) return;
                        final OverlayStore store = new OverlayStore(app);
                        if (store.appliedRole() == OverlayStore.NONE) return;
                        if (!SessionPrefs.stockAtSignOut(app)) return;
                        revertLocked(new SystemSettingsIo(app.getContentResolver()), store,
                                new Outcome());
                    }
                    notifyListeners();
                } catch (RuntimeException e) {
                    // Best effort: the marker is still set, so the next launch tries again.
                }
            }
        }, "overlay-reconcile").start();
    }

    // ---- listeners -------------------------------------------------------------------------------

    /** Called after any move, on the thread that made it. */
    static void addListener(Runnable listener) {
        sListeners.addIfAbsent(listener);
    }

    static void removeListener(Runnable listener) {
        sListeners.remove(listener);
    }

    private static void notifyListeners() {
        for (Runnable r : sListeners) {
            r.run();
        }
    }

    // ---- internals -------------------------------------------------------------------------------

    private static List<SettingEntry> overlayable() {
        final List<SettingEntry> out = new ArrayList<>();
        for (SettingsCatalog.Section section : SettingsCatalog.SECTIONS) {
            for (SettingEntry entry : section.entries) {
                if (entry.overlayable) out.add(entry);
            }
        }
        return out;
    }

    /** Skip the write when the value is already there; a refused key then stays quiet. */
    private static SettingsIo.Result writeIfDifferent(SettingsIo io, SettingEntry entry,
            String value) {
        try {
            final String actual = io.read(entry);
            if (actual != null && actual.trim().equals(value)) {
                sLastFailure.remove(entry.address());
                return SettingsIo.Result.OK;
            }
        } catch (RuntimeException e) {
            // Unreadable: fall through and let the write report what it can.
        }
        return write(io, entry, value);
    }

    private static SettingsIo.Result write(SettingsIo io, SettingEntry entry, String value) {
        final SettingsIo.Result result = entry.writable()
                ? io.write(entry, value) : SettingsIo.Result.REFUSED;
        if (result == SettingsIo.Result.OK) {
            sLastFailure.remove(entry.address());
        } else {
            sLastFailure.put(entry.address(), result);
        }
        return result;
    }

    /** Test hook: forget remembered failures. */
    static void clearFailures() {
        sLastFailure.clear();
    }
}
