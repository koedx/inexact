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
 * PIN-mode appearance (red advanced pad, "PIN" label) is owned by GateController and is already on
 * when recording begins. An armed app must not be left without a PIN, so an error is NOT terminal:
 * it shows specific guidance (dismissed with "Try again"), resets the attempt, and stays in PIN
 * mode. Only a successful save ends setup (via the listener, which restores the normal look).
 */
final class PinRecorder {

    /** Notified only when a credential is successfully saved. */
    interface Listener {
        void onRecordingSaved();
    }

    private enum Phase { IDLE, P1_OPS, P2_WAIT_DIGITS, P2_OPS }

    private final GateHost mHost;
    private final GateCredential mCredential;
    private final Listener mListener;

    private Phase mPhase = Phase.IDLE;
    private String mDigits1;
    private final ArrayList<Integer> mOps1 = new ArrayList<>();
    private String mDigits2;
    private final ArrayList<Integer> mOps2 = new ArrayList<>();

    PinRecorder(GateHost host, GateCredential credential, Listener listener) {
        mHost = host;
        mCredential = credential;
        mListener = listener;
    }

    boolean isRecording() {
        return mPhase != Phase.IDLE;
    }

    /**
     * Handle a short press while recording. Returns true if the press was consumed and must not
     * reach the normal calculator path. Called only when isRecording() is true.
     */
    boolean onButtonClick(int id) {
        // Clear or delete cancels recording at any point (interruption, not an error).
        if (id == R.id.clr || id == R.id.del) {
            cancel();
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
                error(R.string.gate_err_ops_only);   // a non-operator key in the sequence
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
                error(R.string.gate_err_ops_only);   // an operator before re-typing the digits
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
                    error(R.string.gate_err_digits);     // display is not a whole number
                    return true;
                }
                mDigits1 = digits;
                mOps1.clear();
                mPhase = Phase.P1_OPS;                    // retain the digits: no clear here (spec §6)
                mHost.showRecordingText("");             // clear any stale result text
                mHost.showGateDialog(R.string.gate_prompt_title, R.string.gate_prompt_ops,
                        R.string.gate_got_it);
                return true;
            }
            case P1_OPS: {
                if (mOps1.isEmpty()) {
                    error(R.string.gate_err_no_ops);     // no operators entered
                    return true;
                }
                mPhase = Phase.P2_WAIT_DIGITS;
                // Blue reveal-clear, then prompt the repeat by re-typing the digits.
                mHost.revealClear(new Runnable() {
                    @Override public void run() { mHost.showRecordingText("Repeat"); }
                });
                return true;
            }
            case P2_WAIT_DIGITS: {
                final String digits = mHost.evaluator().getMainWholeDigitsOrNull();
                if (digits == null) {
                    error(R.string.gate_err_digits);
                    return true;
                }
                mDigits2 = digits;
                mOps2.clear();
                mPhase = Phase.P2_OPS;                    // retain the digits: no clear here
                return true;
            }
            case P2_OPS: {
                final boolean ok = !mOps2.isEmpty()
                        && mDigits1.equals(mDigits2)
                        && Arrays.equals(toIntArray(mOps1), toIntArray(mOps2));
                if (!ok) {
                    error(R.string.gate_err_mismatch);   // the two entries did not match
                    return true;
                }
                mCredential.save(mDigits1, toIntArray(mOps1));
                resetState();                            // leave recording synchronously
                mHost.revealClear(new Runnable() {
                    @Override public void run() {
                        mHost.showRecordingText("Saved");
                        mListener.onRecordingSaved();    // controller restores the normal look
                    }
                });
                return true;
            }
            default:
                return false;
        }
    }

    /** Interruption (Activity stop, Clear/Delete): reset without leaving PIN-setup mode. */
    void cancel() {
        resetState();
    }

    /**
     * Non-terminal error: reset the attempt, clear the display, and show specific guidance dismissed
     * with "Try again". Stays in PIN-setup mode — an armed app must not be left without a PIN.
     */
    private void error(int messageRes) {
        resetState();
        mHost.clearDisplayQuietly();
        mHost.showGateDialog(R.string.gate_prompt_title, messageRes, R.string.gate_try_again);
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
