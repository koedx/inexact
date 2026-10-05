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

/**
 * Owns the gate for one Activity instance. Created only when the app is armed
 * (WRITE_SECURE_SETTINGS granted) — otherwise the Activity never instantiates it and the gate code
 * path does not exist (spec section 4).
 *
 * Routes raw key presses to the section-6 recorder or the section-5 matcher, and resets transient
 * state when the Activity stops.
 */
public final class GateController {

    private final GateHost mHost;
    private final GateCredential mCredential;
    private final PinMatcher mMatcher;
    private final PinRecorder mRecorder;

    /**
     * True iff the app is armed. The host checks this before constructing the controller, so the
     * gate exists only on an armed install (spec section 4).
     */
    public static boolean isArmed(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    public GateController(GateHost host) {
        mHost = host;
        // STEP-1 STUB credential (session RAM). Replaced by PBKDF2 + Keystore storage in step 2.
        mCredential = new InMemoryGateCredential();
        mMatcher = new PinMatcher(host, mCredential);
        mRecorder = new PinRecorder(host, mCredential);
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

    /**
     * STEP-2 SEAM. First-run setup gate. For step 1 this is simply "no PIN yet". Step 2 ties it to
     * the arming marker (device_provisioned_v2) that ProvisionActivity manages, and the hidden view
     * (step 6) will also return true here to allow changing the PIN while already authenticated.
     */
    private boolean isSetupAllowed() {
        return !mCredential.isConfigured();
    }
}
