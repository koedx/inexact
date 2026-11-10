/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: passive detection of a credential entry on the calculator pad (spec v0.4 §5).
 * Modified: 2026-11-10 06:00:00 UTC
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
 * Passive observer of raw key presses that detects a credential: a whole-number display of at least
 * {@link GateCredential#MIN_DIGITS} digits at the first operator, then a run of operators whose length equals the
 * length stored for the role that the display's first digit identifies.
 *
 * Observe-only. Every press returns to the normal calculator path, so a wrong attempt is
 * indistinguishable from ordinary use (no error, no vibration, no delay). A correct match is
 * confirmed asynchronously by the credential and opens the gate from the callback, so the key
 * handler never blocks.
 *
 * SINGLE FIRE, VIA ROLE ROUTING. A hashed, device-bound verifier cannot be compared press-by-press,
 * so the matcher assembles a complete candidate and asks the credential, once per operator press,
 * whether anything should be tested: {@link GateCredential#roleFor} answers with a role only when
 * the first digit identifies a role that has a record AND the run has reached exactly that record's
 * stored length. Because each role's PIN begins with its own digit, that is at most one record per
 * attempt — one derivation, whatever the number of roles — and pressing past the length does not
 * re-fire. Ordinary arithmetic whose first digit matches no role costs no derivation at all.
 */
final class PinMatcher {

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
                if (digits == null || digits.length() < GateCredential.MIN_DIGITS) {
                    reset();      // not a whole number, or too short -> not a PIN attempt
                    return;
                }
                mInSequence = true;
                mDigits = digits;
                mOps.clear();
            }
            mOps.add(id);
            final int role = mCredential.roleFor(mDigits, mOps.size());
            if (role >= 0) {
                fireTest(role);
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

    private void fireTest(final int role) {
        final int generation = mGeneration;
        final int[] ops = toIntArray(mOps);
        mCredential.test(role, mDigits, ops, new GateCredential.ResultCallback() {
            @Override
            public void onResult(boolean match) {
                // Ignore a result for an attempt that was abandoned (Clear, etc.) after the test
                // was dispatched.
                if (match && generation == mGeneration) {
                    reset();
                    mHost.openGate(role);
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
