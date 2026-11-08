/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: RAM-only credential stub for exercising the matcher and recorder without Keystore.
 * Modified: 2026-11-08 07:31:00 UTC
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
 * Holds credentials in RAM for the current process only (no persistence, no hashing, no device
 * binding). Kept so the matcher and recorder can be exercised without Keystore. The shipping path is
 * {@link Pbkdf2KeystoreCredential}.
 *
 * Mirrors the real implementation's role semantics, including the role-prefix routing in
 * {@link #roleFor} and the pre-prefix admin record case, so a test against this stub exercises the
 * same decisions. One record per role, indexed by role tag.
 *
 * WARNING: plaintext in RAM — MUST NOT ship. GateController wires the real credential; this class is
 * only instantiated by tests.
 */
public final class InMemoryGateCredential implements GateCredential {

    private static final int SLOTS = 1 + MAX_GUESTS;

    private final Object mLock = new Object();
    private final String[] mDigits = new String[SLOTS];   // null => no record for that role
    private final int[][] mOps = new int[SLOTS][];
    private final boolean[] mPrefix = new boolean[SLOTS];  // did the PIN begin with the role digit

    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
    private final Handler mMain = new Handler(Looper.getMainLooper());

    @Override
    public boolean isConfigured() {
        synchronized (mLock) {
            return mDigits[ROLE_ADMIN] != null;
        }
    }

    @Override
    public int roleFor(String digits, int opRunLen) {
        if (digits == null || digits.isEmpty()) return -1;
        synchronized (mLock) {
            if (mDigits[ROLE_ADMIN] == null) return -1;
            if (!mPrefix[ROLE_ADMIN]) {
                // Pre-prefix admin record: offered at any first digit, and the only candidate.
                return (mOps[ROLE_ADMIN].length == opRunLen) ? ROLE_ADMIN : -1;
            }
            final char c = digits.charAt(0);
            if (c < '0' || c > '9') return -1;
            final int role = c - '0';
            if (role > MAX_GUESTS) return -1;
            return (mDigits[role] != null && mOps[role].length == opRunLen) ? role : -1;
        }
    }

    @Override
    public void save(final int roleTag, final int profileId, final String digits, final int[] opIds,
            final ResultCallback callback) {
        if (roleTag < ROLE_ADMIN || roleTag > MAX_GUESTS) {
            post(callback, false);
            return;
        }
        mWorker.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (mLock) {
                    mOps[roleTag] = (opIds == null) ? new int[0] : opIds.clone();
                    mPrefix[roleTag] = Pbkdf2KeystoreCredential.hasRolePrefix(roleTag, digits);
                    mDigits[roleTag] = digits;   // set last: a record appears only once ops are in
                }
                post(callback, true);
            }
        });
    }

    @Override
    public void test(final int roleTag, final String digits, final int[] opIds,
            final ResultCallback callback) {
        final String refDigits;
        final int[] refOps;
        synchronized (mLock) {
            if (roleTag < ROLE_ADMIN || roleTag > MAX_GUESTS) {
                post(callback, false);
                return;
            }
            refDigits = mDigits[roleTag];
            refOps = mOps[roleTag];
        }
        mWorker.execute(new Runnable() {
            @Override
            public void run() {
                post(callback, refDigits != null
                        && refDigits.equals(digits)
                        && Arrays.equals(refOps, opIds));
            }
        });
    }

    /** Test helper: drop one role's record, as removing a guest does on the shipping path. */
    void remove(int roleTag) {
        if (roleTag <= ROLE_ADMIN || roleTag > MAX_GUESTS) return;   // admin is never removable
        synchronized (mLock) {
            mDigits[roleTag] = null;
            mOps[roleTag] = null;
            mPrefix[roleTag] = false;
        }
    }

    private void post(final ResultCallback cb, final boolean v) {
        mMain.post(new Runnable() {
            @Override public void run() { cb.onResult(v); }
        });
    }
}
