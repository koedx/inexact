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

import android.os.Handler;
import android.os.Looper;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * STEP-1 STUB ONLY.
 *
 * Holds the credential in RAM for the current process only (no persistence, no hashing). Its sole
 * purpose is to make step 1 testable end-to-end: record a PIN through the section-6 flow, then enter
 * it, and watch the gate fire. It is replaced wholesale in step 2 by a PBKDF2 + Keystore-backed,
 * device-bound implementation.
 *
 * WARNING: plaintext in RAM is acceptable only as a transient dev stub. This class MUST NOT ship —
 * it violates the "no plaintext PIN anywhere (at rest)" invariant the moment it is persisted, and
 * even in RAM it is only tolerable because the candidate PIN is already transient there during entry.
 *
 * The comparison runs on a background thread and posts the result to the UI thread, mirroring the
 * step-2 shape so the matcher is already written against the async contract. (The equality check
 * here is not constant-time; that only matters for the step-2 hash comparison, which will use a
 * constant-time compare.)
 */
public final class InMemoryGateCredential implements GateCredential {

    private volatile String mDigits;   // null => unconfigured
    private volatile int[] mOps;

    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
    private final Handler mMain = new Handler(Looper.getMainLooper());

    @Override
    public boolean isConfigured() {
        return mDigits != null;
    }

    @Override
    public void save(String digits, int[] opIds) {
        mDigits = digits;
        mOps = (opIds == null) ? new int[0] : opIds.clone();
    }

    @Override
    public void test(final String digits, final int[] opIds, final ResultCallback callback) {
        final String refDigits = mDigits;
        final int[] refOps = mOps;
        mWorker.execute(new Runnable() {
            @Override
            public void run() {
                final boolean match = refDigits != null
                        && refDigits.equals(digits)
                        && Arrays.equals(refOps, opIds);
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onResult(match);
                    }
                });
            }
        });
    }
}
