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

/**
 * The credential seam.
 *
 * STEP-2 replaces the implementation with a salted PBKDF2 hash bound to a non-exportable device
 * Keystore key (device-bound, so a blob copied to another tablet will not verify, and an offline
 * brute-force of the copied blob is impossible without the on-device key). The matcher and recorder
 * depend only on this interface, so swapping the implementation requires no change to them.
 */
public interface GateCredential {

    /** True once a credential has been stored. */
    boolean isConfigured();

    /**
     * Persist a new credential.
     *
     * @param digits raw decimal digit string shown on the display (no grouping separators)
     * @param opIds  the raw binary-operator key-id sequence, captured before repeated-operator
     *               collapsing (e.g. op_mul, op_mul, op_div, op_div)
     *
     * STEP-2: hash (digits + opIds) with salted PBKDF2 and bind to the device Keystore; never
     * store plaintext at rest.
     */
    void save(String digits, int[] opIds);

    /**
     * Test a candidate asynchronously. The callback fires on the UI thread.
     *
     * The async contract is deliberate: it lets step 2 run PBKDF2 (and any Keystore operation) off
     * the UI thread so matching never blocks the key handler. This is what preserves the no-delay
     * invariant — a wrong attempt returns to the normal calculator path immediately, and a correct
     * attempt opens the gate a few milliseconds later from the callback.
     */
    void test(String digits, int[] opIds, ResultCallback callback);

    interface ResultCallback {
        /** Invoked on the UI thread. */
        void onResult(boolean match);
    }
}
