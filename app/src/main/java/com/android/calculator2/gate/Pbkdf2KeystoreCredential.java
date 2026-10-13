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

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;
import android.util.Log;

import com.android.calculator2.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Shipping credential (spec addendum 2026-10-09-01 §2; perf revisions in 2026-10-12-01).
 *
 * At rest: a salted PBKDF2-HMAC-SHA256 hash of the PIN, then HMAC-SHA256'd with a non-exportable,
 * HARDWARE-BACKED AndroidKeyStore key. The Keystore HMAC is what binds the verifier to this device:
 * a blob copied elsewhere cannot be recomputed without the on-device key, and (while that key is
 * TEE-backed) offline brute force is impossible. Hardware-backing is REQUIRED — if the generated key
 * is not inside secure hardware, save() refuses (deletes the key, fails closed) and no credential is
 * stored, so the app stays a plain calculator. There is no software-backed path.
 *
 * All crypto runs on a single background thread; results post to the UI thread. isConfigured() /
 * configuredOpLen() are served from an in-memory cache of the blob (loaded once) so the matcher can
 * poll them per keypress without file I/O.
 */
public final class Pbkdf2KeystoreCredential implements GateCredential {

    /** STEP-2b TEST CRUTCH: timing instrumentation. Remove in the step-3 leak review along with the
     *  openGate() log. Logs elapsed milliseconds only — never digits, operators, salt or verifier.
     *  Log.i (not .d) because this ROM sets log.tag=I, which discards DEBUG before it reaches the
     *  buffer. */
    private static final boolean TIMING_LOG = true;
    private static final String TAG = "InExactGate";

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS        = "inexact_gate_hmac_v1";
    private static final String HMAC_ALG         = "HmacSHA256";
    private static final String PBKDF2_ALG       = "PBKDF2withHmacSHA256";
    private static final String GATE_FILE        = ".gate";

    // Blob layout (57 bytes). See addendum 2026-10-09-01 §2.5.
    private static final byte FORMAT_VERSION = 0x01;
    private static final int  SALT_LEN       = 16;
    private static final int  VERIFIER_LEN   = 32;   // HMAC-SHA256 output
    private static final int  DERIVED_BITS   = 256;  // PBKDF2 output
    private static final int  BLOB_LEN       = 1 + 4 + 1 + 1 + SALT_LEN + 1 + VERIFIER_LEN + 1;
    private static final byte FLAG_TEE       = 0x01; // bit0: key inside secure hardware

    /**
     * PBKDF2 iterations for NEW credentials.
     *
     * Measured on the MT8168 (Cortex-A53, pure-Java PBKDF2): 150,000 cost roughly 1 s of steady-state
     * derivation — about 4x the ~250 ms design target. Lowered to 50,000 (addendum 2026-10-12-01).
     * This is sound on the design's own terms: hardware-backing is REQUIRED and there is no
     * software-backed path, so an offline attack is impossible regardless of the iteration count —
     * iterations are defense-in-depth, not the primary defense.
     *
     * The count is stored per-blob, so existing credentials keep deriving at the count they were
     * saved with. Re-save (re-provision or re-record) to pick up a new value.
     */
    private static final int ITERATIONS = 50_000;

    private final Context mContext;
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private final Object mLock = new Object();
    private volatile boolean mLoaded;
    private volatile Blob mBlob;   // null => unconfigured
    private volatile boolean mWarmed;

    public Pbkdf2KeystoreCredential(Context context) {
        mContext = context.getApplicationContext();
    }

    // ---- warm-up -----------------------------------------------------------------------------

    /**
     * Pay the one-time cost of class loading, JCA provider initialisation, Keystore binder setup and
     * JIT warm-up on the background thread, so the first real attempt is not slower than the rest.
     * Measured cold-vs-warm difference on the MT8168 was roughly 1 s (≈2 s first attempt vs ≈1 s
     * after) — entirely one-time cost, not derivation cost.
     *
     * Called from onResume while the gate exists. Idempotent and cheap (a 1-iteration derivation).
     * Deliberately does NOT generate a Keystore key: warm-up must not create state on an install
     * that may never be configured. If the key is absent (unconfigured), the PBKDF2 half still warms.
     */
    public void warmUp() {
        if (mWarmed) {
            return;
        }
        mWarmed = true;
        mWorker.execute(new Runnable() {
            @Override
            public void run() {
                final long t0 = System.nanoTime();
                char[] pw = null;
                byte[] dk = null;
                try {
                    // Warms SecretKeyFactory + the PBKDF2 code path (1 iteration: negligible cost).
                    pw = new char[] { 'w', 'a', 'r', 'm' };
                    dk = pbkdf2(pw, new byte[SALT_LEN], 1);

                    // Warms the Keystore binder + Mac path, but only if a key already exists.
                    final KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
                    ks.load(null);
                    if (ks.containsAlias(KEY_ALIAS)) {
                        final SecretKey key = (SecretKey) ks.getKey(KEY_ALIAS, null);
                        if (key != null) {
                            final Mac mac = Mac.getInstance(HMAC_ALG);
                            mac.init(key);
                            mac.doFinal(dk);
                        }
                    }
                    if (TIMING_LOG) {
                        Log.i(TAG, "warmUp " + ((System.nanoTime() - t0) / 1_000_000L) + "ms");
                    }
                } catch (Throwable t) {
                    // Warm-up is best-effort: a failure here must never affect correctness.
                } finally {
                    if (pw != null) Arrays.fill(pw, '\0');
                    if (dk != null) Arrays.fill(dk, (byte) 0);
                }
            }
        });
    }

    // ---- GateCredential ----------------------------------------------------------------------

    @Override
    public boolean isConfigured() {
        ensureLoaded();
        return mBlob != null;
    }

    @Override
    public int configuredOpLen() {
        ensureLoaded();
        final Blob b = mBlob;
        return (b == null) ? -1 : b.opLen;
    }

    @Override
    public void save(final String digits, final int[] opIds, final ResultCallback cb) {
        final int[] ops = (opIds == null) ? new int[0] : opIds.clone();
        mWorker.execute(new Runnable() {
            @Override
            public void run() {
                final long t0 = System.nanoTime();
                boolean ok = false;
                char[] pw = null;
                byte[] dk = null;
                try {
                    ensureKey();
                    if (!isKeyHardwareBacked()) {
                        deleteKey();              // TEE required — refuse software-backed (fail closed)
                        postResult(cb, false);
                        return;
                    }
                    final byte[] salt = new byte[SALT_LEN];
                    new SecureRandom().nextBytes(salt);
                    pw = password(digits, ops);
                    dk = pbkdf2(pw, salt, ITERATIONS);
                    final byte[] verifier = hmac(dk);
                    final byte[] blob = buildBlob(ITERATIONS, FLAG_TEE, salt, verifier, ops.length);
                    writeBlobAtomic(blob);
                    synchronized (mLock) {
                        mBlob = new Blob(ITERATIONS, FLAG_TEE, salt, verifier, ops.length);
                        mLoaded = true;
                    }
                    ok = true;
                } catch (Throwable t) {
                    ok = false;                    // fail closed; nothing persisted
                } finally {
                    if (pw != null) Arrays.fill(pw, '\0');
                    if (dk != null) Arrays.fill(dk, (byte) 0);
                }
                if (TIMING_LOG) {
                    Log.i(TAG, "save " + ((System.nanoTime() - t0) / 1_000_000L) + "ms ok=" + ok
                            + " iter=" + ITERATIONS);
                }
                postResult(cb, ok);
            }
        });
    }

    @Override
    public void test(final String digits, final int[] opIds, final ResultCallback cb) {
        ensureLoaded();
        final Blob blob = mBlob;
        final int[] ops = (opIds == null) ? new int[0] : opIds.clone();
        if (blob == null) {
            postResult(cb, false);
            return;
        }
        mWorker.execute(new Runnable() {
            @Override
            public void run() {
                final long t0 = System.nanoTime();
                boolean match = false;
                char[] pw = null;
                byte[] dk = null;
                try {
                    pw = password(digits, ops);
                    dk = pbkdf2(pw, blob.salt, blob.iterations);
                    final byte[] cand = hmac(dk);
                    match = MessageDigest.isEqual(cand, blob.verifier);   // constant-time
                } catch (Throwable t) {
                    match = false;                 // fail closed
                } finally {
                    if (pw != null) Arrays.fill(pw, '\0');
                    if (dk != null) Arrays.fill(dk, (byte) 0);
                }
                if (TIMING_LOG) {
                    // Elapsed + the blob's stored iteration count only. No PIN material, and no
                    // indication of match/no-match beyond what the UI already shows.
                    Log.i(TAG, "test " + ((System.nanoTime() - t0) / 1_000_000L) + "ms iter="
                            + blob.iterations);
                }
                postResult(cb, match);
            }
        });
    }

    // ---- canonical input ---------------------------------------------------------------------

    /**
     * PBKDF2 password, ASCII only (char == byte for every char, provider-independent):
     * {@code digits + U+001F + stableOpCodes joined by ','}. Operators map to a build-stable code
     * (not R.id), so the verifier survives app updates that reassign resource ids.
     */
    private static char[] password(String digits, int[] opIds) {
        final StringBuilder sb = new StringBuilder();
        sb.append(digits == null ? "" : digits);
        sb.append('\u001F');
        for (int i = 0; i < opIds.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(stableOp(opIds[i]));
        }
        final char[] pw = new char[sb.length()];
        sb.getChars(0, sb.length(), pw, 0);
        for (int i = 0; i < sb.length(); i++) sb.setCharAt(i, '\0'); // best-effort scrub
        return pw;
    }

    /** Fixed operator code independent of R.id (addendum 2026-10-09-01 §2.5). */
    private static int stableOp(int rid) {
        if (rid == R.id.op_add) return 1;
        if (rid == R.id.op_sub) return 2;
        if (rid == R.id.op_mul) return 3;
        if (rid == R.id.op_div) return 4;
        if (rid == R.id.op_pow) return 5;
        return 0; // unreachable: matcher/recorder only pass KeyMaps.isBinary ids
    }

    // ---- crypto primitives -------------------------------------------------------------------

    private static byte[] pbkdf2(char[] pw, byte[] salt, int iterations) throws Exception {
        final PBEKeySpec spec = new PBEKeySpec(pw, salt, iterations, DERIVED_BITS);
        try {
            return SecretKeyFactory.getInstance(PBKDF2_ALG).generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private byte[] hmac(byte[] data) throws Exception {
        final KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        final SecretKey key = (SecretKey) ks.getKey(KEY_ALIAS, null);
        if (key == null) throw new IllegalStateException("gate key missing");
        final Mac mac = Mac.getInstance(HMAC_ALG);
        mac.init(key);
        return mac.doFinal(data);
    }

    private void ensureKey() throws Exception {
        final KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) return;
        final KeyGenerator kg = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(256)
                .setDigests(KeyProperties.DIGEST_SHA256)
                // No setUserAuthenticationRequired: the gate is the auth; requiring lockscreen
                // auth would invalidate the key if the lockscreen changes (fatal for a failsafe).
                // No StrongBox on this SoC — do not request it.
                .build());
        kg.generateKey();
    }

    private boolean isKeyHardwareBacked() throws Exception {
        final KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        final SecretKey key = (SecretKey) ks.getKey(KEY_ALIAS, null);
        if (key == null) return false;
        final SecretKeyFactory f = SecretKeyFactory.getInstance(key.getAlgorithm(), ANDROID_KEYSTORE);
        final KeyInfo info = (KeyInfo) f.getKeySpec(key, KeyInfo.class);
        return info.isInsideSecureHardware();   // API 29; getSecurityLevel() is API 31+
    }

    private void deleteKey() {
        try {
            final KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            ks.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) {
            // best effort
        }
    }

    // ---- blob I/O ----------------------------------------------------------------------------

    private static byte[] buildBlob(int iters, byte flags, byte[] salt, byte[] verifier, int opLen) {
        final ByteArrayOutputStream b = new ByteArrayOutputStream(BLOB_LEN);
        b.write(FORMAT_VERSION);
        b.write((iters >>> 24) & 0xFF);
        b.write((iters >>> 16) & 0xFF);
        b.write((iters >>> 8) & 0xFF);
        b.write(iters & 0xFF);
        b.write(flags);
        b.write(salt.length & 0xFF);
        b.write(salt, 0, salt.length);
        b.write(verifier.length & 0xFF);
        b.write(verifier, 0, verifier.length);
        b.write(opLen & 0xFF);
        return b.toByteArray();
    }

    /** Atomic: write .gate.tmp, fsync, rename over .gate. */
    private void writeBlobAtomic(byte[] blob) throws IOException {
        final File dir = mContext.getFilesDir();
        final File tmp = new File(dir, GATE_FILE + ".tmp");
        final File dst = new File(dir, GATE_FILE);
        final FileOutputStream fos = new FileOutputStream(tmp);
        try {
            fos.write(blob);
            fos.flush();
            fos.getFD().sync();
        } finally {
            fos.close();
        }
        if (!tmp.renameTo(dst)) {
            tmp.delete();
            throw new IOException("atomic rename failed");
        }
    }

    private void ensureLoaded() {
        if (mLoaded) return;
        synchronized (mLock) {
            if (!mLoaded) {
                mBlob = readBlob();
                mLoaded = true;
            }
        }
    }

    /** Parse the blob; any corruption / short read / wrong version => null (fail safe to unconfigured). */
    private Blob readBlob() {
        final File f = new File(mContext.getFilesDir(), GATE_FILE);
        if (!f.exists()) return null;
        final byte[] data;
        try {
            data = readAll(f);
        } catch (IOException e) {
            return null;
        }
        try {
            if (data.length != BLOB_LEN) return null;
            int p = 0;
            if (data[p++] != FORMAT_VERSION) return null;
            final int iters = ((data[p] & 0xFF) << 24) | ((data[p + 1] & 0xFF) << 16)
                    | ((data[p + 2] & 0xFF) << 8) | (data[p + 3] & 0xFF);
            p += 4;
            final byte flags = data[p++];
            final int saltLen = data[p++] & 0xFF;
            if (saltLen != SALT_LEN) return null;
            final byte[] salt = Arrays.copyOfRange(data, p, p + saltLen);
            p += saltLen;
            final int verLen = data[p++] & 0xFF;
            if (verLen != VERIFIER_LEN) return null;
            final byte[] verifier = Arrays.copyOfRange(data, p, p + verLen);
            p += verLen;
            final int opLen = data[p] & 0xFF;
            return new Blob(iters, flags, salt, verifier, opLen);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readAll(File f) throws IOException {
        final FileInputStream in = new FileInputStream(f);
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream(BLOB_LEN);
            final byte[] buf = new byte[64];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private void postResult(final ResultCallback cb, final boolean v) {
        mMain.post(new Runnable() {
            @Override public void run() { cb.onResult(v); }
        });
    }

    private static final class Blob {
        final int iterations;
        final byte flags;
        final byte[] salt;
        final byte[] verifier;
        final int opLen;

        Blob(int iterations, byte flags, byte[] salt, byte[] verifier, int opLen) {
            this.iterations = iterations;
            this.flags = flags;
            this.salt = salt;
            this.verifier = verifier;
            this.opLen = opLen;
        }
    }
}
