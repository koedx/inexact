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

/**
 * In-memory, process-lifetime gate state (spec addendum 2026-10-14-01 §3.4).
 *
 * Nothing here is ever persisted or placed in a saved-instance bundle, so process death re-locks —
 * the accepted fail-safe on this low-RAM device. Holds only: a one-shot unlock token, a one-shot
 * re-record request, and a reference to the shared credential (which holds the application Context,
 * never an Activity).
 */
public final class GateSession {

    private static volatile boolean sUnlockToken;
    private static volatile boolean sRerecordRequested;
    private static volatile Pbkdf2KeystoreCredential sCredential;

    private GateSession() {}

    /** Called by the host when the gate matched. One-shot: consumed by HiddenActivity.onCreate. */
    public static void unlock() {
        sUnlockToken = true;
    }

    /** True once per unlock(); the hidden view finishes immediately if there is no token. */
    static boolean consumeUnlock() {
        final boolean t = sUnlockToken;
        sUnlockToken = false;
        return t;
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
