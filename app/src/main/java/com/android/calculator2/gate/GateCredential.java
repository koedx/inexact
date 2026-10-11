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
 * STEP-2 implementation ({@link Pbkdf2KeystoreCredential}) stores a salted PBKDF2 hash bound to a
 * non-exportable, hardware-backed Keystore key (device-bound: a blob copied to another tablet will
 * not verify, and an offline brute force of a copied blob is impossible without the on-device key).
 * The matcher and recorder depend only on this interface.
 *
 * Both {@link #save} and {@link #test} are ASYNC: the callback fires on the UI thread. This lets the
 * step-2 implementation run PBKDF2 (and the Keystore HMAC) off the UI thread so neither matching nor
 * saving blocks the key handler — the invariant that keeps a wrong attempt indistinguishable from
 * ordinary use (no error, no vibration, no delay).
 */
public interface GateCredential {

    /** True once a credential has been stored (driven by the on-disk blob). */
    boolean isConfigured();

    /**
     * The number of operator presses in the stored sequence, or -1 if unconfigured. The matcher uses
     * this as its single-fire trigger (fire {@code test()} once, when the observed operator run
     * reaches this length) rather than testing every prefix. No digit length is exposed or stored.
     */
    int configuredOpLen();

    /**
     * Persist a new credential, then report success on the UI thread.
     *
     * @param digits   raw decimal digit string shown on the display (no grouping separators)
     * @param opIds    the raw binary-operator key-id sequence, captured before repeated-operator
     *                 collapsing (e.g. op_mul, op_mul, op_div)
     * @param callback {@code onResult(true)} after the credential is durably written; {@code
     *                 onResult(false)} if saving failed (e.g. no hardware-backed Keystore — the gate
     *                 requires one, and refuses rather than storing a software-backed credential).
     *
     * Never stores plaintext at rest. The caller treats a failure as non-terminal (stay in PIN-setup
     * mode) so an armed app is never left configured-broken or silently PIN-less.
     */
    void save(String digits, int[] opIds, ResultCallback callback);

    /**
     * Test a candidate asynchronously; {@code onResult} fires on the UI thread. Any error fails
     * closed ({@code onResult(false)} — the gate never opens on error).
     */
    void test(String digits, int[] opIds, ResultCallback callback);

    interface ResultCallback {
        /** Invoked on the UI thread. For {@code test()} this is match/no-match; for {@code save()}
         *  it is save-success/failure. */
        void onResult(boolean ok);
    }
}
