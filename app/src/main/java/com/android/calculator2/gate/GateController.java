/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: owns the gate for one Activity instance; routes presses to the recorder or the matcher
 *          and drives the PIN-setup appearance. Role-aware as of blob v2.
 * Modified: 2026-11-08 07:31:00 UTC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.calculator2.gate;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import com.android.calculator2.R;

/**
 * Owns the gate for one Activity instance. Created only when the app is armed
 * (WRITE_SECURE_SETTINGS granted) — otherwise the Activity never instantiates it and the gate code
 * path does not exist (spec §4).
 *
 * Routes raw key presses to the §6 recorder or the §5 matcher, drives the PIN-setup appearance
 * (solution 3: the whole-app PIN look is on from the moment the Activity is shown while setup is
 * allowed, and turns off when recording ends), resets transient state on stop, and clears the arming
 * marker once a PIN is durably saved.
 *
 * Setup has three sources, all converging on the recorder with a target role:
 *   - first run: armed marker present and unconfigured  -> admin
 *   - Change PIN from the admin surface (authenticated)  -> admin
 *   - Set/Reset a guest PIN from the admin surface       -> that guest slot
 * The guest path additionally requires a live admin session, and unlike Change PIN it does NOT end
 * the session: the admin is handed straight back to the admin surface once the PIN saves.
 */
public final class GateController implements PinRecorder.Listener {

    private final GateHost mHost;
    /** Concrete type: the controller drives warm-up, which is an implementation concern and
     *  deliberately not part of the GateCredential seam. */
    private final Pbkdf2KeystoreCredential mCredential;
    private final PinMatcher mMatcher;
    private final PinRecorder mRecorder;

    // PIN-setup appearance state (solution 3).
    private boolean mSetupActive;     // PIN-mode visuals currently shown
    private boolean mSetupPrompted;   // first instruction popup already shown this session
    private boolean mSetupEnded;      // recording finished (save/error): do not re-arm this session
    private boolean mRerecording;     // authenticated admin PIN change in progress
    private int mGuestRecordRole = GateSession.NO_ROLE;   // admin is setting this guest's PIN

    /**
     * While PIN-setup mode is active, mark the window FLAG_SECURE: blank recents thumbnail, no
     * screenshots. Costs: you cannot screenshot or screen-record the setup flow, and the recents
     * card reads blank (a mild tell) until the Calculator is next resumed. Set false to disable.
     */
    public static final boolean SECURE_WINDOW_DURING_SETUP = true;

    /**
     * True iff the app is armed. The host checks this before constructing the controller, so the
     * gate exists only on an armed install (spec §4). Note this is the PERMISSION gate (does the
     * gate code path exist at all); first-run setup is additionally gated on the arming MARKER
     * (see {@link #isSetupAllowed()}).
     */
    public static boolean isArmed(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    public GateController(GateHost host) {
        mHost = host;
        mCredential = new Pbkdf2KeystoreCredential(host.context().getApplicationContext());
        GateSession.setCredential(mCredential);   // shared with the admin surface (no Activity held)
        mMatcher = new PinMatcher(host, mCredential);
        mRecorder = new PinRecorder(host, mCredential, this);
    }

    /**
     * Called from onResume. Picks up any pending request from the admin surface, then, while setup
     * is allowed, turns on the PIN-mode appearance (once) and shows the first instruction popup
     * (once). No-op after setup has ended or once configured.
     *
     * Also kicks the credential warm-up (step 2b): class loading, JCA provider init and Keystore
     * binder setup are one-time costs worth roughly a second on this SoC, and paying them here on
     * the background thread keeps the first PIN attempt as fast as later ones.
     */
    public void onActivityResumed() {
        mCredential.warmUp();   // idempotent, background, no Keystore key is created by this

        if (GateSession.consumeRerecord()) {   // admin surface asked for a PIN change
            mRerecording = true;
            mGuestRecordRole = GateSession.NO_ROLE;
            mSetupEnded = false;
            mSetupPrompted = false;
        }

        final int guestRole = GateSession.consumeGuestRecord();
        if (guestRole > GateCredential.ROLE_ADMIN && guestRole <= GateCredential.MAX_GUESTS
                && GateSession.isAdmin()) {
            // Only a live admin session may set a guest PIN; a stale request is dropped.
            mGuestRecordRole = guestRole;
            mRerecording = false;
            mSetupEnded = false;
            mSetupPrompted = false;
        }

        if (mSetupEnded || !isSetupAllowed()) {
            return;
        }
        final int target = targetRole();
        mRecorder.setTargetRole(target);
        if (!mSetupActive) {
            mSetupActive = true;
            mHost.setPinModeVisuals(true);
        }
        if (!mSetupPrompted) {
            mSetupPrompted = true;
            mHost.showGateDialog(R.string.gate_prompt_title,
                    mHost.context().getString(R.string.gate_prompt_digits_role,
                            target, PinRecorder.MIN_DIGITS),
                    R.string.gate_got_it);
        }
    }

    /**
     * Called at the very top of onButtonClick, after the id is resolved. Returns true if the gate
     * consumed the press (the normal button handling must then be skipped).
     */
    public boolean onButtonClick(int id) {
        if (mRecorder.isRecording()) {
            return mRecorder.onButtonClick(id);
        }
        mMatcher.observe(id);   // passive; never consumes
        return false;
    }

    /** Called from onLongClick for R.id.eq. Returns true if handled (long-press consumed). */
    public boolean onEqualsLongPress() {
        if (mRecorder.isRecording()) {
            return mRecorder.onEqualsLongPress();
        }
        if (isSetupAllowed()) {
            mRecorder.setTargetRole(targetRole());
            return mRecorder.onEqualsLongPress();   // begin recording
        }
        return false;   // configured and not in setup: long-press '=' is a no-op
    }

    /**
     * Reset transient matcher/recorder state when the Activity is no longer visible.
     *
     * If recording was in progress, ALSO clear the display. Recording deliberately retains the PIN
     * digits on the display between phases, and the stock Calculator saves the main expression in
     * onSaveInstanceState — which runs AFTER onStop on API 28+ (target is 29) — so without this the
     * digits would be written into the saved-instance bundle. Cleared here, the bundle holds an empty
     * expression.
     *
     * Deliberately NOT done for the matcher's mid-sequence state: "digits then an operator" is
     * ordinary calculator use, and clearing it would drop a user's in-progress expression whenever
     * they switch apps — a stock calculator keeps it, so that would be a tell.
     */
    public void onActivityStopped() {
        final boolean wasRecording = mRecorder.isRecording();
        mMatcher.reset();
        mRecorder.cancel();
        if (wasRecording) {
            mHost.clearDisplayQuietly();
        }
        if (mRerecording) {
            endRerecord();   // a PIN change is one-shot: leaving the screen abandons it
        }
        if (mGuestRecordRole > GateCredential.ROLE_ADMIN) {
            // Same for a guest PIN. The admin session is left open: the admin merely left the
            // screen, and process death re-locks it anyway.
            endGuestRecord();
        }
    }

    /** Leave authenticated re-record mode and restore the normal look. */
    private void endRerecord() {
        mRerecording = false;
        restoreNormalLook();
    }

    /** Abandon an in-progress guest PIN and restore the normal look. */
    private void endGuestRecord() {
        mGuestRecordRole = GateSession.NO_ROLE;
        restoreNormalLook();
    }

    private void restoreNormalLook() {
        if (mSetupActive) {
            mSetupActive = false;
            mHost.setPinModeVisuals(false);
        }
    }

    /** PinRecorder.Listener: a PIN was saved. End setup, restore the normal look, clear the marker. */
    @Override
    public void onRecordingSaved() {
        final boolean wasGuest = mGuestRecordRole > GateCredential.ROLE_ADMIN;
        mSetupEnded = true;
        mSetupActive = false;
        mRerecording = false;
        mGuestRecordRole = GateSession.NO_ROLE;
        mHost.setPinModeVisuals(false);
        // The credential is durably written (we are called from its save callback), so disarm now:
        // strip the sentinel so the install reads as unarmed (spec §4 "delete the marker on confirm").
        // Skipped on a PIN change or a guest PIN, where the marker is not set (clear() would rewrite
        // the setting).
        if (ArmingMarker.isSet(mHost.context())) {
            ArmingMarker.clear(mHost.context());
        }
        if (wasGuest) {
            // The admin session is still open, so hand straight back to the admin surface rather
            // than leaving the admin looking at a calculator.
            mHost.openGate(GateCredential.ROLE_ADMIN, GateCredential.ROLE_ADMIN);
        }
    }

    /** The role the next/current recording writes. */
    private int targetRole() {
        return (mGuestRecordRole > GateCredential.ROLE_ADMIN)
                ? mGuestRecordRole : GateCredential.ROLE_ADMIN;
    }

    /**
     * Setup gate: first run (armed marker present AND not yet configured), an authenticated admin
     * PIN change, or an admin setting a guest slot's PIN. The latter two need no marker.
     */
    private boolean isSetupAllowed() {
        return mRerecording
                || mGuestRecordRole > GateCredential.ROLE_ADMIN
                || (ArmingMarker.isSet(mHost.context()) && !mCredential.isConfigured());
    }
}
