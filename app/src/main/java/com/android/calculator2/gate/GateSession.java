/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: in-memory, process-lifetime gate session state, carrying the role the session was
 *          opened for and notifying whoever needs to react when it ends.
 * Created:  2026-10-14 (origin: HiddenActivity one-shot-token model)
 * Modified: 2026-11-10 06:00:00 UTC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.calculator2.gate;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory, process-lifetime gate state.
 *
 * A session is a LOGIN: the user stays signed in while using the tablet, because the unlocked
 * surface is the primary route to the kiosk's functions. It ends only when the user locks it, from
 * the in-app lock action or from the session notification ({@link SessionService}). There is no
 * automatic timeout. While a session is open, {@link SessionService} runs as a foreground service,
 * which keeps this process (and so this state) alive when the task is swiped away.
 *
 * Nothing here is ever persisted or placed in a saved-instance bundle, so if the process does die
 * (the system can still kill a foreground service under pressure) the session is simply gone and
 * the gate is locked: the fail-safe direction.
 *
 * Holds only: the active-session flag, the role the session was opened for, one-shot requests from
 * the admin surface to the calculator pad, and a reference to the shared credential (which holds
 * the application Context, never an Activity).
 *
 * Threading: begin/end and the listener calls happen on the main thread.
 */
public final class GateSession {

    /** No role: the value reported while no session is open. */
    public static final int NO_ROLE = -1;

    private static volatile boolean sSessionActive;
    private static volatile int sRoleTag = NO_ROLE;
    private static volatile boolean sRerecordRequested;
    private static volatile int sGuestRecordRole = NO_ROLE;
    private static volatile Pbkdf2KeystoreCredential sCredential;

    /** Run when the session ends, so the surface closes and the notification goes away together. */
    private static final CopyOnWriteArrayList<Runnable> sEndListeners = new CopyOnWriteArrayList<>();

    private GateSession() {}

    /**
     * Called by the host when the gate matched. Opens the session for one role; the role is what
     * decides whether the admin surface is reachable, so a guest session can never reach it.
     */
    public static void begin(int roleTag) {
        sRoleTag = roleTag;
        sSessionActive = true;
    }

    /**
     * End the session. Every lock path lands here (the in-app lock action, the notification's Lock
     * action, Change PIN), and the registered listeners then close the surface and stop the session
     * service, so no path can end the session and leave one of them behind.
     */
    public static void end() {
        final boolean wasActive = sSessionActive;
        sSessionActive = false;
        sRoleTag = NO_ROLE;
        sGuestRecordRole = NO_ROLE;
        if (wasActive) {
            for (Runnable r : sEndListeners) {
                r.run();
            }
        }
    }

    static void addEndListener(Runnable listener) {
        sEndListeners.addIfAbsent(listener);
    }

    static void removeEndListener(Runnable listener) {
        sEndListeners.remove(listener);
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
