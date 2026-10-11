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

import com.android.calculator2.KeyMaps;

import java.util.ArrayList;

/**
 * Passive observer of raw key presses that detects the credential (spec section 5): a whole-number
 * display of at least {@link #MIN_DIGITS} digits at the first operator, then a run of operators whose
 * length equals the stored sequence length.
 *
 * Observe-only. Every press returns to the normal calculator path, so a wrong attempt is
 * indistinguishable from ordinary use (no error, no vibration, no delay). A correct match is confirmed
 * asynchronously by the credential and opens the gate from the callback, so the key handler never
 * blocks.
 *
 * Step-2 change: a hashed, device-bound verifier cannot be compared press-by-press. The matcher
 * assembles a complete candidate and fires {@code test()} ONCE, when the operator run reaches the
 * stored length — not on every press (which under PBKDF2 would mean many derivations per attempt).
 * A run of >= MIN_DIGITS operators consecutively after a >= MIN_DIGITS-digit whole number is a
 * near-never event in ordinary use, so op-length alone is a sufficient single-fire trigger and no
 * digit length is stored.
 */
final class PinMatcher {

    /** Minimum display digits before an operator run can be a PIN attempt (addendum §3). */
    private static final int MIN_DIGITS = 3;

    private final GateHost mHost;
    private final GateCredential mCredential;

    private boolean mInSequence;                 // first operator seen, digits captured
    private String mDigits;                      // candidate digits, captured once
    private final ArrayList<Integer> mOps = new ArrayList<>();
    private int mGeneration;                     // bumped on reset; guards stale async results

    PinMatcher(GateHost host, GateCredential credential) {
        mHost = host;
        mCredential = credential;
    }

    /**
     * Observe one on-screen press (called at the top of onButtonClick). Never consumes the press.
     */
    void observe(int id) {
        if (!mCredential.isConfigured()) {
            return;  // nothing to match against yet
        }

        if (KeyMaps.isBinary(id)) {
            if (!mInSequence) {
                final String digits = mHost.evaluator().getMainWholeDigitsOrNull();
                if (digits == null || digits.length() < MIN_DIGITS) {
                    reset();      // not a whole number, or too short -> not a PIN attempt
                    return;
                }
                mInSequence = true;
                mDigits = digits;
                mOps.clear();
            }
            mOps.add(id);
            // Single fire: only when the run reaches the stored length (==, not >=, so pressing past
            // the length does not re-fire).
            if (mOps.size() == mCredential.configuredOpLen()) {
                fireTest();
            }
            return;
        }

        if (KeyMaps.digVal(id) != KeyMaps.NOT_DIGIT) {
            if (mInSequence) {
                reset();   // a digit after operators: not the PIN shape (digits, then operators)
            }
            // Otherwise digits are still accumulating on the display; nothing to do.
            return;
        }

        // Anything else (decimal point, function, paren, '=', delete, clear, toggles): abandon.
        reset();
    }

    private void fireTest() {
        final int generation = mGeneration;
        final int[] ops = toIntArray(mOps);
        mCredential.test(mDigits, ops, new GateCredential.ResultCallback() {
            @Override
            public void onResult(boolean match) {
                // Ignore a result for an attempt that was abandoned (Clear, etc.) after the test
                // was dispatched.
                if (match && generation == mGeneration) {
                    reset();
                    mHost.openGate();
                }
            }
        });
    }

    /** Return to the clean state; invalidates any in-flight async result. */
    void reset() {
        mInSequence = false;
        mDigits = null;
        mOps.clear();
        mGeneration++;
    }

    private static int[] toIntArray(ArrayList<Integer> list) {
        final int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = list.get(i);
        }
        return a;
    }
}
