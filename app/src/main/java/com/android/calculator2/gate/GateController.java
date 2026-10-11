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
    private final GateCredential mCredential;
    private final PinMatcher mMatcher;
    private final PinRecorder mRecorder;

    // PIN-setup appearance state (solution 3).
    private boolean mSetupActive;     // PIN-mode visuals currently shown
    private boolean mSetupPrompted;   // first instruction popup already shown this session
    private boolean mSetupEnded;      // recording finished (save/error): do not re-arm this session

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
        mMatcher = new PinMatcher(host, mCredential);
        mRecorder = new PinRecorder(host, mCredential, this);
    }

    /**
     * Called from onResume. While setup is allowed, turns on the PIN-mode appearance (once) and
     * shows the first instruction popup (once). No-op after setup has ended or once configured.
     *
     * Step 2 ties isSetupAllowed() to the arming marker, so the PIN-setup UI no longer appears on an
     * unconfigured armed install unless the marker (secure icon_blacklist sentinel) is present.
     */
    public void onActivityResumed() {
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

    /** Reset transient matcher/recorder state when the Activity is no longer visible. */
    public void onActivityStopped() {
        mMatcher.reset();
        mRecorder.cancel();
    }

    /** PinRecorder.Listener: a PIN was saved. End setup, restore the normal look, clear the marker. */
    @Override
    public void onRecordingSaved() {
        mSetupEnded = true;
        mSetupActive = false;
        mHost.setPinModeVisuals(false);
        // The credential is durably written (we are called from its save callback), so disarm now:
        // strip the sentinel so the install reads as unarmed (spec §4 "delete the marker on confirm").
        ArmingMarker.clear(mHost.context());
    }

    /**
     * First-run setup gate: armed marker present AND not yet configured. (Step 6's hidden view will
     * also allow re-recording an already-configured PIN while authenticated; that is a separate seam,
     * not wired here.)
     */
    private boolean isSetupAllowed() {
        return ArmingMarker.isSet(mHost.context()) && !mCredential.isConfigured();
    }
}
