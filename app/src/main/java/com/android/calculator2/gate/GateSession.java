/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: in-memory, process-lifetime gate session state (spec addendum 2026-11-06-01 §3.1),
 *          now carrying the role the session was opened for.
 * Created:  2026-10-14 (origin: HiddenActivity one-shot-token model)
 * Modified: 2026-11-08 07:31:00 UTC
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
 * The session PERSISTS until the task is dismissed or the process dies — the launcher must survive
 * tapping out to a launched app and returning (spec addendum 2026-11-06-01 §3.1). The old one-shot
 * unlock token (2026-10-14-01 §3.4), which re-locked on any recreation, is replaced by a persistent
 * flag. Nothing here is ever persisted or placed in a saved-instance bundle, so process death
 * re-locks — the accepted fail-safe on this low-RAM device. A recents-swipe kills the process on this
 * hardware, so it re-locks the same way.
 *
 * Holds only: the active-session flag, the role and profile the session was opened for, a one-shot
 * re-record request, and a reference to the shared credential (which holds the application Context,
 * never an Activity).
 */
public final class GateSession {

    /** No role: the value reported while no session is open. */
    public static final int NO_ROLE = -1;

    private static volatile boolean sSessionActive;
    private static volatile int sRoleTag = NO_ROLE;
    private static volatile int sProfileId = NO_ROLE;
    private static volatile boolean sRerecordRequested;
    private static volatile int sGuestRecordRole = NO_ROLE;
    private static volatile Pbkdf2KeystoreCredential sCredential;

    private GateSession() {}

    /**
     * Called by the host when the gate matched. Opens the session for one role; the role is what
     * decides whether the admin surface is reachable, so a guest session can never reach it.
     */
    public static void begin(int roleTag, int profileId) {
        sRoleTag = roleTag;
        sProfileId = profileId;
        sSessionActive = true;
    }

    /** Explicit lock (lock action, Change PIN) and the process-death fail-safe both land here. */
    public static void end() {
        sSessionActive = false;
        sRoleTag = NO_ROLE;
        sProfileId = NO_ROLE;
    }

    /** The launcher finishes immediately if this is false (stray/restored launch, or locked). */
    static boolean isActive() {
        return sSessionActive;
    }

    /**
     * True only for an active admin session. Derived from the role rather than stored separately, so
     * there is no second flag that could fall out of step with it.
     */
    static boolean isAdmin() {
        return sSessionActive && sRoleTag == GateCredential.ROLE_ADMIN;
    }

    /** The role this session was opened for, or {@link #NO_ROLE}. */
    static int roleTag() {
        return sSessionActive ? sRoleTag : NO_ROLE;
    }

    /** The profile this session's role maps to, or {@link #NO_ROLE}. */
    static int profileId() {
        return sSessionActive ? sProfileId : NO_ROLE;
    }

    /** Admin surface -> Calculator: enter PIN-setup mode once (already authenticated). */
    static void requestRerecord() {
        sRerecordRequested = true;
    }

    /** Calculator side: read-and-clear the re-record request. */
    static boolean consumeRerecord() {
        final boolean r = sRerecordRequested;
        sRerecordRequested = false;
        return r;
    }

    /**
     * Admin surface -> Calculator: record a PIN for one guest slot, without ending the admin
     * session (unlike Change PIN, the admin is handed back to the admin surface afterwards). The
     * Calculator side additionally requires {@link #isAdmin()} before honouring this, so a stale
     * request can never let a guest session set a PIN.
     */
    static void requestGuestRecord(int roleTag) {
        sGuestRecordRole = roleTag;
    }

    /** Calculator side: read-and-clear the guest-record request; {@link #NO_ROLE} if none. */
    static int consumeGuestRecord() {
        final int r = sGuestRecordRole;
        sGuestRecordRole = NO_ROLE;
        return r;
    }

    static void setCredential(Pbkdf2KeystoreCredential c) {
        sCredential = c;
    }

    static Pbkdf2KeystoreCredential credential() {
        return sCredential;
    }
}
