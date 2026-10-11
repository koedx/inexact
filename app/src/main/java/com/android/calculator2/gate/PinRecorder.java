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
 * (arming marker present and unconfigured; the hidden view will also allow re-recording).
 *
 * One pass is: [type digits] -> long-press '=' -> [press operators] -> long-press '='. The pass is
 * performed twice and the two must match, then the credential is saved. Minimums (addendum §3): at
 * least {@link #MIN_DIGITS} digits and {@link #MIN_OPS} operators — enough to avoid accidental trips
 * and trivial manual guessing (not cryptographic hardening; PBKDF2 + device binding carry that).
 *
 * Saving is ASYNC (PBKDF2 + Keystore off the UI thread). The machine enters {@link Phase#SAVING} and
 * ignores input until the credential's callback lands: success -> "Saved" + listener (controller
 * restores the normal look) + the controller clears the arming marker; failure -> non-terminal error
 * (guidance dialog, stays in PIN mode), so an armed app is never left configured-broken or PIN-less.
 *
 * PIN-mode appearance (red advanced pad, "PIN" label) is owned by GateController and is already on
 * when recording begins. Errors are NOT terminal (specific guidance, "Try again", reset, stay in PIN
 * mode). Only a successful save ends setup.
 */
final class PinRecorder {

    /** Minimums enforced at recording (addendum §3). */
    private static final int MIN_DIGITS = 3;
    private static final int MIN_OPS = 3;

    /** Notified only when a credential is successfully saved. */
    interface Listener {
        void onRecordingSaved();
    }

    private enum Phase { IDLE, P1_OPS, P2_WAIT_DIGITS, P2_OPS, SAVING }

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
        if (mPhase == Phase.SAVING) {
            return true;   // persisting: swallow everything until the callback resolves
        }

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
                // A non-operator key in the sequence.
                error(R.string.gate_prompt_ops_title, R.string.gate_err_ops_only);
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
                // An operator (or other key) before the digits are entered.
                error(R.string.gate_prompt_title, R.string.gate_err_digits);
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
        if (mPhase == Phase.SAVING) {
            return true;   // ignore during persist
        }
        switch (mPhase) {
            case IDLE: {
                final String digits = mHost.evaluator().getMainWholeDigitsOrNull();
                if (digits == null) {
                    error(R.string.gate_prompt_title, R.string.gate_err_digits);
                    return true;
                }
                if (digits.length() < MIN_DIGITS) {
                    error(R.string.gate_prompt_title, R.string.gate_err_min_digits);
                    return true;
                }
                mDigits1 = digits;
                mOps1.clear();
                mPhase = Phase.P1_OPS;                    // retain the digits: no clear here (spec §6)
                mHost.showRecordingText("");             // clear any stale result text
                promptForOps();
                return true;
            }
            case P1_OPS: {
                if (mOps1.size() < MIN_OPS) {
                    error(R.string.gate_prompt_ops_title, R.string.gate_err_min_ops);
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
                    error(R.string.gate_prompt_title, R.string.gate_err_digits);
                    return true;
                }
                if (digits.length() < MIN_DIGITS) {
                    error(R.string.gate_prompt_title, R.string.gate_err_min_digits);
                    return true;
                }
                mDigits2 = digits;
                mOps2.clear();
                mPhase = Phase.P2_OPS;                    // retain the digits: no clear here
                promptForOps();                           // same feedback as pass 1 (registered; enter ops)
                return true;
            }
            case P2_OPS: {
                if (mOps2.size() < MIN_OPS) {
                    error(R.string.gate_prompt_ops_title, R.string.gate_err_min_ops);
                    return true;
                }
                final boolean ok = mDigits1.equals(mDigits2)
                        && Arrays.equals(toIntArray(mOps1), toIntArray(mOps2));
                if (!ok) {
                    error(R.string.gate_prompt_title, R.string.gate_err_mismatch);
                    return true;
                }
                // Capture before the async hop; then persist off the UI thread.
                final String digits = mDigits1;
                final int[] ops = toIntArray(mOps1);
                mPhase = Phase.SAVING;
                mCredential.save(digits, ops, new GateCredential.ResultCallback() {
                    @Override
                    public void onResult(boolean success) {
                        if (success) {
                            resetState();                 // leave recording (-> IDLE)
                            mHost.revealClear(new Runnable() {
                                @Override public void run() {
                                    mHost.showRecordingText("Saved");
                                    mListener.onRecordingSaved();   // controller restores normal look
                                }
                            });
                        } else {
                            // Non-terminal: stays in PIN mode so the user can retry. On a tablet with
                            // no hardware-backed Keystore this will recur (gate requires TEE) — a
                            // provisioner-handled case.
                            error(R.string.gate_prompt_title, R.string.gate_err_save_failed);
                        }
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

    private void promptForOps() {
        mHost.showGateDialog(R.string.gate_prompt_ops_title, R.string.gate_prompt_ops,
                R.string.gate_got_it);
    }

    /**
     * Non-terminal error: reset the attempt, clear the display, and show specific guidance dismissed
     * with "Try again". Stays in PIN-setup mode — an armed app must not be left without a PIN.
     */
    private void error(int titleRes, int messageRes) {
        resetState();
        mHost.clearDisplayQuietly();
        mHost.showGateDialog(titleRes, messageRes, R.string.gate_try_again);
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
