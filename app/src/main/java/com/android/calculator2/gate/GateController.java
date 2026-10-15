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

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import com.android.calculator2.R;

/**
 * Owns the gate for one Activity instance. Created only when the app is armed
 * (WRITE_SECURE_SETTINGS granted) — otherwise the Activity never instantiates it and the gate code
 * path does not exist (spec section 4).
 *
 * Routes raw key presses to the section-6 recorder or the section-5 matcher, drives the PIN-setup
 * appearance (solution 3: the whole-app PIN look is on from the moment the Activity is shown while
 * setup is allowed, and turns off when recording ends), resets transient state on stop, and clears
 * the arming marker once a PIN is durably saved.
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
    private boolean mRerecording;     // authenticated PIN change in progress (hidden view -> here)

    /**
     * While PIN-setup mode is active, mark the window FLAG_SECURE: blank recents thumbnail, no
     * screenshots. Costs: you cannot screenshot or screen-record the setup flow, and the recents
     * card reads blank (a mild tell) until the Calculator is next resumed. Set false to disable.
     */
    public static final boolean SECURE_WINDOW_DURING_SETUP = true;

    /**
     * True iff the app is armed. The host checks this before constructing the controller, so the
     * gate exists only on an armed install (spec section 4). Note this is the PERMISSION gate (does
     * the gate code path exist at all); first-run setup is additionally gated on the arming MARKER
     * (see {@link #isSetupAllowed()}).
     */
    public static boolean isArmed(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    public GateController(GateHost host) {
        mHost = host;
        // STEP 2: device-bound PBKDF2 + hardware-backed Keystore credential (replaces the RAM stub).
        mCredential = new Pbkdf2KeystoreCredential(host.context().getApplicationContext());
        GateSession.setCredential(mCredential);   // shared with the hidden view (no Activity held)
        mMatcher = new PinMatcher(host, mCredential);
        mRecorder = new PinRecorder(host, mCredential, this);
    }

    /**
     * Called from onResume. While setup is allowed, turns on the PIN-mode appearance (once) and
     * shows the first instruction popup (once). No-op after setup has ended or once configured.
     *
     * Also kicks the credential warm-up (step 2b): class loading, JCA provider init and Keystore
     * binder setup are one-time costs worth roughly a second on this SoC, and paying them here on
     * the background thread keeps the first PIN attempt as fast as later ones.
     */
    public void onActivityResumed() {
        mCredential.warmUp();   // idempotent, background, no Keystore key is created by this

        if (GateSession.consumeRerecord()) {   // hidden view asked for an authenticated PIN change
            mRerecording = true;
            mSetupEnded = false;
            mSetupPrompted = false;
        }

        if (mSetupEnded || !isSetupAllowed()) {
            return;
        }
        if (!mSetupActive) {
            mSetupActive = true;
            mHost.setPinModeVisuals(true);
        }
        if (!mSetupPrompted) {
            mSetupPrompted = true;
            mHost.showGateDialog(R.string.gate_prompt_title, R.string.gate_prompt_digits,
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
    }

    /** Leave authenticated re-record mode and restore the normal look. */
    private void endRerecord() {
        mRerecording = false;
        if (mSetupActive) {
            mSetupActive = false;
            mHost.setPinModeVisuals(false);
        }
    }

    /** PinRecorder.Listener: a PIN was saved. End setup, restore the normal look, clear the marker. */
    @Override
    public void onRecordingSaved() {
        mSetupEnded = true;
        mSetupActive = false;
        mRerecording = false;
        mHost.setPinModeVisuals(false);
        // The credential is durably written (we are called from its save callback), so disarm now:
        // strip the sentinel so the install reads as unarmed (spec §4 "delete the marker on confirm").
        // Skipped on a PIN change, where the marker is not set (clear() would rewrite the setting).
        if (ArmingMarker.isSet(mHost.context())) {
            ArmingMarker.clear(mHost.context());
        }
    }

    /**
     * Setup gate: either first-run (armed marker present AND not yet configured) or an authenticated
     * PIN change requested from the hidden view (no marker required).
     */
    private boolean isSetupAllowed() {
        return mRerecording
                || (ArmingMarker.isSet(mHost.context()) && !mCredential.isConfigured());
    }
}
