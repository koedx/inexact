/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: PIN recording / confirmation state machine (spec v0.4 §6), now recording for one role.
 * Modified: 2026-11-08 07:31:00 UTC
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
 * Recording / confirmation state machine. Active only while setup is allowed: an armed, unconfigured
 * install; an authenticated admin PIN change; or an admin setting a guest slot's PIN.
 *
 * FIRST ENTRY (delimited, because the digit and operator counts are not yet known):
 * [type digits] -> long-press '=' -> [press operators] -> long-press '='.
 *
 * REPEAT (undelimited, the way the PIN is entered in normal use, 2026-11-06): the user types the
 * whole PIN again, digits then operators, with no '=' press of any kind. The digits are captured at
 * the first operator press, and the attempt is verified automatically when the operator count
 * reaches the count of the first entry (the same single-fire trigger the matcher uses). A match
 * saves; a mismatch is a non-terminal error that restarts recording from the first entry.
 *
 * ROLE. Whichever role {@link #setTargetRole} names is the record that gets written, and the PIN is
 * required to begin with that role's digit ({@link #MIN_DIGITS} digits in total). The controller
 * sets the target before recording begins.
 *
 * Saving is ASYNC (PBKDF2 + Keystore off the UI thread). The machine enters {@link Phase#SAVING} and
 * ignores input until the credential's callback lands: success -> "Saved" + listener (controller
 * restores the normal look, clears the arming marker on a first run, and hands an admin back to the
 * admin surface after a guest PIN); failure -> non-terminal error (guidance dialog, stays in PIN
 * mode), so an armed app is never left configured-broken or PIN-less.
 *
 * PIN-mode appearance (red advanced pad, "PIN" label) is owned by GateController and is already on
 * when recording begins. Errors are NOT terminal (specific guidance, "Try again", reset, stay in PIN
 * mode). Only a successful save ends setup.
 */
final class PinRecorder {

    /**
     * Minimum digits for a NEWLY RECORDED PIN.
     *
     * One higher than the matcher's floor, deliberately. The first digit is now the role digit and
     * so is publicly inferable from the app, and a fourth digit keeps three digits free — restoring
     * what the prefix rule costs. The matcher does NOT share this value: raising its floor would
     * make a shorter credential written by an older build unmatchable, i.e. lock the owner out.
     */
    static final int MIN_DIGITS = 4;

    /** Minimum operators (addendum §3). */
    private static final int MIN_OPS = 3;

    /** Notified only when a credential is successfully saved. */
    interface Listener {
        void onRecordingSaved();
    }

    private enum Phase { IDLE, P1_OPS, P2_ENTRY, SAVING }

    private final GateHost mHost;
    private final GateCredential mCredential;
    private final Listener mListener;

    private Phase mPhase = Phase.IDLE;
    /** The role whose record this recording will write. */
    private int mTargetRole = GateCredential.ROLE_ADMIN;
    private String mDigits1;
    private final ArrayList<Integer> mOps1 = new ArrayList<>();
    /** Repeat digits; null until the first operator press of the repeat captures them. */
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

    /** Set the role to record for. Ignored once recording is under way. */
    void setTargetRole(int roleTag) {
        if (mPhase == Phase.IDLE
                && roleTag >= GateCredential.ROLE_ADMIN && roleTag <= GateCredential.MAX_GUESTS) {
            mTargetRole = roleTag;
        }
    }

    int targetRole() {
        return mTargetRole;
    }

    /**
     * Handle a short press while recording. Returns true if the press was consumed and must not
     * reach the normal calculator path. Called only when isRecording() is true.
     */
    boolean onButtonClick(int id) {
        if (mPhase == Phase.SAVING) {
            return true;   // persisting: swallow everything until the callback resolves
        }

        // Clear or delete. During the repeat these only affect the repeat attempt; anywhere else
        // they cancel recording (interruption, not an error).
        if (id == R.id.clr || id == R.id.del) {
            if (mPhase == Phase.P2_ENTRY) {
                if (mDigits2 == null && id == R.id.del) {
                    return false;   // ordinary backspace while the repeat digits are being typed
                }
                restartRepeat();
                return true;
            }
            cancel();
            return true;
        }

        switch (mPhase) {
            case P1_OPS: {
                if (KeyMaps.isBinary(id)) {
                    mOps1.add(id);
                    echoOps(mOps1);
                    return true;   // consume: do not append / collapse
                }
                if (id == R.id.eq) {
                    return true;   // swallow a short '=' during operator capture
                }
                // A non-operator key in the sequence.
                error(R.string.gate_prompt_ops_title, R.string.gate_err_ops_only);
                return true;
            }
            case P2_ENTRY:
                return onRepeatPress(id);
            default:
                return false;
        }
    }

    /**
     * Repeat phase: digits flow to the display normally; the first operator captures them; the
     * attempt fires once the operator count equals the first entry's.
     */
    private boolean onRepeatPress(int id) {
        if (KeyMaps.digVal(id) != KeyMaps.NOT_DIGIT) {
            if (mDigits2 != null) {
                // A digit after the operators began: not the shape of the PIN.
                repeatError(mHost.context().getString(R.string.gate_err_repeat_ops_only));
                return true;
            }
            return false;   // digits flow normally onto the display
        }
        if (KeyMaps.isBinary(id)) {
            if (mDigits2 == null) {
                final String digits = mHost.evaluator().getMainWholeDigitsOrNull();
                if (digits == null) {
                    repeatError(mHost.context().getString(R.string.gate_err_repeat_digits));
                    return true;
                }
                if (digits.length() < MIN_DIGITS) {
                    repeatError(text(R.string.gate_err_repeat_min_digits_role, MIN_DIGITS));
                    return true;
                }
                mDigits2 = digits;
                mOps2.clear();
            }
            mOps2.add(id);
            echoOps(mOps2);
            if (mOps2.size() == mOps1.size()) {
                verifyAndSave();
            }
            return true;   // consume: do not append / collapse
        }
        if (id == R.id.eq) {
            return true;   // swallow a short '='; nothing in the repeat uses it
        }
        repeatError(mDigits2 == null
                ? mHost.context().getString(R.string.gate_err_repeat_digits)
                : mHost.context().getString(R.string.gate_err_repeat_ops_only));
        return true;
    }

    /**
     * Invalid key during the repeat: restart only the repeat (the first entry is kept) and show
     * repeat-specific guidance. Non-terminal, like {@link #error}.
     */
    private void repeatError(CharSequence message) {
        restartRepeat();
        mHost.showGateDialog(R.string.gate_prompt_repeat_title, message, R.string.gate_try_again);
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
                    errorText(R.string.gate_prompt_title,
                            text(R.string.gate_err_min_digits_role, MIN_DIGITS, mTargetRole));
                    return true;
                }
                if (!Pbkdf2KeystoreCredential.hasRolePrefix(mTargetRole, digits)) {
                    errorText(R.string.gate_prompt_title,
                            text(R.string.gate_err_prefix_role, mTargetRole));
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
                mPhase = Phase.P2_ENTRY;
                mDigits2 = null;
                mOps2.clear();
                // Blue reveal-clear, then tell the user to enter the whole PIN again, undelimited.
                mHost.revealClear(new Runnable() {
                    @Override public void run() {
                        mHost.showRecordingText("Repeat");
                        mHost.showGateDialog(R.string.gate_prompt_repeat_title,
                                R.string.gate_prompt_repeat, R.string.gate_got_it);
                    }
                });
                return true;
            }
            case P2_ENTRY:
                return true;   // the repeat never uses a long-press; ignore a stray one
            default:
                return false;
        }
    }

    /** Compare the repeat with the first entry; save on a match, otherwise a non-terminal error. */
    private void verifyAndSave() {
        final boolean ok = mDigits1.equals(mDigits2)
                && Arrays.equals(toIntArray(mOps1), toIntArray(mOps2));
        if (!ok) {
            error(R.string.gate_prompt_title, R.string.gate_err_mismatch);
            return;
        }
        // Capture before the async hop; then persist off the UI thread.
        final String digits = mDigits1;
        final int[] ops = toIntArray(mOps1);
        final int role = mTargetRole;
        mPhase = Phase.SAVING;
        // Profile ids currently equal role tags.
        mCredential.save(role, role, digits, ops, new GateCredential.ResultCallback() {
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
    }

    /** Interruption (Activity stop): reset without leaving PIN-setup mode. */
    void cancel() {
        resetState();
    }

    /** Restart only the repeat attempt (first entry is kept). */
    private void restartRepeat() {
        mDigits2 = null;
        mOps2.clear();
        mHost.clearDisplayQuietly();
        mHost.showRecordingText("Repeat");
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

    /** As {@link #error}, with an already-formatted message. */
    private void errorText(int titleRes, CharSequence message) {
        resetState();
        mHost.clearDisplayQuietly();
        mHost.showGateDialog(titleRes, message, R.string.gate_try_again);
    }

    /**
     * Reset the attempt state. The target role is deliberately NOT reset: a non-terminal error must
     * leave the recorder still aimed at the same role so the retry writes the intended record.
     */
    private void resetState() {
        mPhase = Phase.IDLE;
        mDigits1 = null;
        mDigits2 = null;
        mOps1.clear();
        mOps2.clear();
    }

    private CharSequence text(int res, Object... args) {
        return mHost.context().getString(res, args);
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
