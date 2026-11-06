/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: in-memory, process-lifetime gate session state (spec addendum 2026-11-06-01 §3.1).
 * Created:  2026-10-14 (origin: HiddenActivity one-shot-token model)
 * Modified: 2026-11-06 09:44:19 UTC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.calculator2.gate;

/**
 * In-memory, process-lifetime gate state.
 *
 * The session now PERSISTS until the task is dismissed or the process dies — the launcher must
 * survive tapping out to a launched app and returning (spec addendum 2026-11-06-01 §3.1). The old
 * one-shot unlock token (2026-10-14-01 §3.4), which re-locked on any recreation, is replaced by a
 * persistent flag. Nothing here is ever persisted or placed in a saved-instance bundle, so process
 * death re-locks — the accepted fail-safe on this low-RAM device. A recents-swipe kills the process
 * on this hardware, so it re-locks the same way.
 *
 * Holds only: the active-session flag, the role of the active session, a one-shot re-record request,
 * and a reference to the shared credential (which holds the application Context, never an Activity).
 */
public final class GateSession {

    private static volatile boolean sSessionActive;
    private static volatile boolean sAdmin;
    private static volatile boolean sRerecordRequested;
    private static volatile Pbkdf2KeystoreCredential sCredential;

    private GateSession() {}

    /**
     * Called by the host when the gate matched. Opens the session; {@code admin} decides whether the
     * admin surface is reachable (single credential == admin until blob v2 roles land).
     */
    public static void begin(boolean admin) {
        sAdmin = admin;
        sSessionActive = true;
    }

    /** Explicit lock (lock action, Change PIN) and the process-death fail-safe both land here. */
    public static void end() {
        sSessionActive = false;
        sAdmin = false;
    }

    /** The launcher finishes immediately if this is false (stray/restored launch, or locked). */
    static boolean isActive() {
        return sSessionActive;
    }

    /** True if the active session may reach the admin surface. */
    static boolean isAdmin() {
        return sAdmin;
    }

    /** Hidden view -> Calculator: enter PIN-setup mode once (already authenticated). */
    static void requestRerecord() {
        sRerecordRequested = true;
    }

    /** Calculator side: read-and-clear the re-record request. */
    static boolean consumeRerecord() {
        final boolean r = sRerecordRequested;
        sRerecordRequested = false;
        return r;
    }

    static void setCredential(Pbkdf2KeystoreCredential c) {
        sCredential = c;
    }

    static Pbkdf2KeystoreCredential credential() {
        return sCredential;
    }
}
