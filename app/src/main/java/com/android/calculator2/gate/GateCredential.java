/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: the credential seam. Role-aware as of blob v2 (spec v0.4 §16 + the role-prefix rule).
 * Modified: 2026-11-08 07:31:00 UTC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.calculator2.gate;

/**
 * The credential seam.
 *
 * The shipping implementation ({@link Pbkdf2KeystoreCredential}) stores one salted PBKDF2 verifier
 * per role, each bound to a non-exportable, hardware-backed Keystore key (device-bound: a blob
 * copied to another tablet will not verify, and an offline brute force of a copied blob is
 * impossible without the on-device key). The matcher and recorder depend only on this interface.
 *
 * ROLES AND THE PREFIX RULE. Each role's PIN must begin with that role's digit — the admin with 0,
 * guest N with N. That is what makes shadowing impossible: one PIN can only mask another if both
 * capture the same digit string, and distinct mandatory first digits forbid it. It also means the
 * role is known from the plaintext display digits BEFORE any derivation runs, so
 * {@link #roleFor} picks the single record to test and the result is a plain match/no-match —
 * one derivation per attempt, whatever the number of roles.
 *
 * Both {@link #save} and {@link #test} are ASYNC: the callback fires on the UI thread. This lets the
 * implementation run PBKDF2 (and the Keystore HMAC) off the UI thread so neither matching nor saving
 * blocks the key handler — the invariant that keeps a wrong attempt indistinguishable from ordinary
 * use (no error, no vibration, no delay).
 */
public interface GateCredential {

    /** The admin role. Its PIN begins with '0'. */
    int ROLE_ADMIN = 0;

    /** Guest roles are 1..MAX_GUESTS, each PIN beginning with its own digit. */
    int MAX_GUESTS = 4;

    /** True once an admin credential has been stored (driven by the on-disk blob). */
    boolean isConfigured();

    /**
     * The role whose record should be tested for this candidate, or -1 for none — the matcher's
     * single-fire trigger.
     *
     * Returns a role only when BOTH the first digit identifies a role that has a stored record AND
     * the observed operator run has reached exactly that record's stored length. Pressing past the
     * length therefore does not re-fire, and ordinary arithmetic whose first digit matches no role
     * costs no derivation at all.
     *
     * One exception: an admin record saved before the prefix rule existed has no recorded first
     * digit, so it is offered at any first digit (guest roles are refused while that is the case, so
     * it is the only candidate).
     *
     * @param digits   the raw decimal digit string captured from the display at the first operator
     * @param opRunLen how many binary operators have been pressed so far in this run
     */
    int roleFor(String digits, int opRunLen);

    /**
     * Persist (or replace) one role's credential, then report success on the UI thread.
     *
     * @param roleTag   {@link #ROLE_ADMIN}, or 1..{@link #MAX_GUESTS} for a guest slot
     * @param profileId the profile this role maps to (currently equal to the role tag)
     * @param digits    raw decimal digit string shown on the display (no grouping separators)
     * @param opIds     the raw binary-operator key-id sequence, captured before repeated-operator
     *                  collapsing (e.g. op_mul, op_mul, op_div)
     * @param callback  {@code onResult(true)} after the credential is durably written; {@code
     *                  onResult(false)} if saving failed (e.g. no hardware-backed Keystore — the gate
     *                  requires one, and refuses rather than storing a software-backed credential).
     *
     * Never stores plaintext at rest. The caller treats a failure as non-terminal (stay in PIN-setup
     * mode) so an armed app is never left configured-broken or silently PIN-less.
     */
    void save(int roleTag, int profileId, String digits, int[] opIds, ResultCallback callback);

    /**
     * Test a candidate against one role's record asynchronously; {@code onResult} fires on the UI
     * thread. A role with no stored record is a non-match. Any error fails closed ({@code
     * onResult(false)} — the gate never opens on error).
     */
    void test(int roleTag, String digits, int[] opIds, ResultCallback callback);

    interface ResultCallback {
        /** Invoked on the UI thread. For {@code test()} this is match/no-match; for {@code save()}
         *  it is save-success/failure. */
        void onResult(boolean ok);
    }
}
