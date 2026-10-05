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
import com.android.calculator2.R;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Section 6 recording / confirmation state machine. Active only while armed and setup is allowed
 * (unconfigured now; step 2 adds the arming-marker gate, and the hidden view will also allow
 * re-recording).
 *
 * One pass is: [type digits] -> long-press '=' -> [press operators] -> long-press '='. The pass is
 * performed twice and the two must match, then the credential is saved.
 *
 * Symmetry with the matcher: digits always come from the raw main-expression accessor (read at the
 * long-press that closes the digit phase); operators are consumed raw presses (so they neither
 * append nor collapse) and echoed as glyphs to the result field.
 */
final class PinRecorder {

    private enum Phase { IDLE, P1_OPS, P2_WAIT_DIGITS, P2_OPS }

    private final GateHost mHost;
    private final GateCredential mCredential;

    private Phase mPhase = Phase.IDLE;
    private String mDigits1;
    private final ArrayList<Integer> mOps1 = new ArrayList<>();
    private String mDigits2;
    private final ArrayList<Integer> mOps2 = new ArrayList<>();

    PinRecorder(GateHost host, GateCredential credential) {
        mHost = host;
        mCredential = credential;
    }

    boolean isRecording() {
        return mPhase != Phase.IDLE;
    }

    /**
     * Handle a short press while recording. Returns true if the press was consumed and must not
     * reach the normal calculator path. Called only when isRecording() is true.
     */
    boolean onButtonClick(int id) {
        // Clear or delete cancels recording at any point.
        if (id == R.id.clr || id == R.id.del) {
            finish();
            return true;
        }

        switch (mPhase) {
            case P1_OPS:
            case P2_OPS: {
                if (KeyMaps.isBinary(id)) {
                    final ArrayList<Integer> ops = (mPhase == Phase.P1_OPS) ? mOps1 : mOps2;
                    ops.add(id);
                    echoOps(ops);
                    return true;   // consume: do not append / collapse
                }
                if (id == R.id.eq) {
                    return true;   // swallow a short '=' during operator capture
                }
                // Any other key during operator capture: abort (keeps the protocol strict).
                finish();
                return true;
            }
            case P2_WAIT_DIGITS: {
                // Digits flow normally so the user can type the pass-2 digits on the display.
                if (KeyMaps.digVal(id) != KeyMaps.NOT_DIGIT) {
                    return false;
                }
                if (id == R.id.eq) {
                    return true;   // swallow a short '='; the long-press advances the phase
                }
                // An operator (or anything else) before the long-press is out of protocol: abort.
                finish();
                return true;
            }
            default:
                return false;
        }
    }

    /**
     * Long-press '=' while recording-eligible. Returns true (handled). The controller only calls
     * this when recording is already in progress, or when setup is allowed.
     */
    boolean onEqualsLongPress() {
        switch (mPhase) {
            case IDLE: {
                final String digits = mHost.evaluator().getMainWholeDigitsOrNull();
                if (digits == null) {
                    badExpression();                 // display is not a whole-number
                    return true;
                }
                mDigits1 = digits;
                mOps1.clear();
                mPhase = Phase.P1_OPS;
                mHost.setRecordingIndicator(true);
                mHost.showRecordingText("");         // clear any stale result text
                return true;
            }
            case P1_OPS: {
                if (mOps1.isEmpty()) {               // require at least one operator
                    badExpression();
                    return true;
                }
                mPhase = Phase.P2_WAIT_DIGITS;
                mHost.clearDisplayQuietly();          // so the user re-types the pass-2 digits
                mHost.setRecordingIndicator(true);    // clearDisplayQuietly may reset the label
                mHost.showRecordingText("Repeat");
                return true;
            }
            case P2_WAIT_DIGITS: {
                final String digits = mHost.evaluator().getMainWholeDigitsOrNull();
                if (digits == null) {
                    badExpression();
                    return true;
                }
                mDigits2 = digits;
                mOps2.clear();
                mPhase = Phase.P2_OPS;
                return true;
            }
            case P2_OPS: {
                final boolean ok = !mOps2.isEmpty()
                        && mDigits1.equals(mDigits2)
                        && Arrays.equals(toIntArray(mOps1), toIntArray(mOps2));
                if (ok) {
                    mCredential.save(mDigits1, toIntArray(mOps1));
                    mHost.showRecordingText("Saved");
                    finishKeepingText();
                } else {
                    badExpression();
                }
                return true;
            }
            default:
                return false;
        }
    }

    /** Cancel any in-progress recording (e.g. when the Activity stops). */
    void cancel() {
        finish();
    }

    private void badExpression() {
        // Spec section 6: a confirmation mismatch shows the stock error_syntax string
        // ("Bad expression"). We reuse it for any recording failure.
        mHost.showRecordingText(mHost.context().getString(R.string.error_syntax));
        finishKeepingText();
    }

    /** Reset state and drop the indicator and any result text. */
    private void finish() {
        resetState();
        mHost.setRecordingIndicator(false);
        mHost.showRecordingText("");
    }

    /** Reset state and drop the indicator but keep whatever text was just shown ("Saved"/error). */
    private void finishKeepingText() {
        resetState();
        mHost.setRecordingIndicator(false);
    }

    private void resetState() {
        mPhase = Phase.IDLE;
        mDigits1 = null;
        mDigits2 = null;
        mOps1.clear();
        mOps2.clear();
    }

    private void echoOps(ArrayList<Integer> ops) {
        final StringBuilder sb = new StringBuilder();
        for (int id : ops) {
            sb.append(KeyMaps.toString(mHost.context(), id));
        }
        mHost.showRecordingText(sb);
    }

    private static int[] toIntArray(ArrayList<Integer> list) {
        final int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = list.get(i);
        }
        return a;
    }
}
