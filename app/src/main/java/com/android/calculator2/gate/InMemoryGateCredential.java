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
 * DEV / UNIT STUB ONLY.
 *
 * Holds the credential in RAM for the current process only (no persistence, no hashing, no device
 * binding). Kept after step 2 for fast unit testing and for exercising the matcher/recorder without
 * Keystore. The shipping path is {@link Pbkdf2KeystoreCredential}.
 *
 * WARNING: plaintext in RAM — MUST NOT ship. GateController wires the real credential; this class is
 * only instantiated by tests.
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
    public int configuredOpLen() {
        final int[] ops = mOps;
        return (mDigits == null || ops == null) ? -1 : ops.length;
    }

    @Override
    public void save(final String digits, final int[] opIds, final ResultCallback callback) {
        mWorker.execute(new Runnable() {
            @Override
            public void run() {
                mOps = (opIds == null) ? new int[0] : opIds.clone();
                mDigits = digits;   // set last: isConfigured() flips only once ops are in place
                mMain.post(new Runnable() {
                    @Override public void run() { callback.onResult(true); }
                });
            }
        });
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
                    @Override public void run() { callback.onResult(match); }
                });
            }
        });
    }
}
