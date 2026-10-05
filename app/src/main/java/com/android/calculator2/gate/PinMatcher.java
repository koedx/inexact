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
 * Passive observer of raw key presses that detects the credential (spec section 5): the display
 * equal to the PIN digits at the first operator, then the stored operator sequence.
 *
 * Observe-only. Every press returns to the normal calculator path, so operators flow and collapse
 * exactly as they would otherwise — this is precisely what makes a wrong attempt indistinguishable
 * from ordinary use (no error, no vibration, no delay). A correct match is confirmed asynchronously
 * by the credential and opens the gate from the callback, so the key handler never blocks.
 *
 * Digits are never read from the display text (which carries grouping separators); they come from
 * the raw main-expression accessor, captured once at the first operator press — at that instant the
 * expression is still exactly the typed digits, because the operator has not yet been appended.
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
                if (digits == null) {
                    reset();      // display is not exactly a whole-number -> not a PIN attempt
                    return;
                }
                mInSequence = true;
                mDigits = digits;
                mOps.clear();
            }
            mOps.add(id);
            fireTest();
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
