/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: shipping credential store. Salted PBKDF2 verifiers bound to a hardware-backed Keystore
 *          HMAC key, one record per role (admin + up to four guests).
 * Modified: 2026-11-10 06:00:00 UTC
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

import com.android.calculator2.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Shipping credential.
 *
 * At rest: per record, a salted PBKDF2-HMAC-SHA256 hash of the PIN, then HMAC-SHA256'd with a
 * non-exportable, HARDWARE-BACKED AndroidKeyStore key. The Keystore HMAC binds every verifier to
 * this device: a blob copied elsewhere cannot be recomputed without the on-device key, and offline
 * brute force is impossible while that key is TEE-backed. Hardware-backing is REQUIRED. If the key
 * is not inside secure hardware, save() refuses (deletes the key, fails closed) and nothing is
 * stored, so the app stays a plain calculator. There is no software-backed path.
 *
 * ROLES. One record per role: {@link #ROLE_ADMIN} plus up to {@link #MAX_GUESTS} guests. Each role's
 * PIN must begin with its own digit (admin 0, guest N with N), which makes it impossible for one
 * role's PIN to shadow another's and lets {@link #roleFor} pick the single record to test from the
 * plaintext first digit. {@link #save} refuses a PIN that breaks the rule, so every stored record
 * obeys it.
 *
 * ON-DISK FORMAT. One format, 3 + 58N bytes for N = 1..5 records:
 *
 *     header: [0] 0x02 | [1] record count N | [2] reserved 0x00
 *     record: [0] role tag | [1] reserved 0x00 | [2..5] iterations BE | [6] flags
 *             | [7] salt len 0x10 | [8..23] salt | [24] verifier len 0x20
 *             | [25..56] verifier | [57] opSeqLen
 *
 * The reserved bytes are written as zero and not validated on read, so a later writer can claim
 * them. There is deliberately no reader for the older single-record 0x01 layout: the app has not
 * been deployed, so no such blob needs to survive, and carrying a second format would be code with
 * nothing to do. A file in any other shape (unknown version, wrong length, duplicate or unknown
 * role, no admin record) reads as unconfigured: fail safe to a plain calculator, never a crash.
 * Writes are atomic (tmp + fsync + rename). Recovery from an unreadable blob is the owner reset.
 *
 * All crypto runs on a single background thread; results post to the UI thread. isConfigured() and
 * the routing lookup are served from an in-memory copy of the store (loaded once) so the matcher
 * can poll them per keypress without file I/O.
 */
public final class Pbkdf2KeystoreCredential implements GateCredential {

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS        = "inexact_gate_hmac_v1";
    private static final String HMAC_ALG         = "HmacSHA256";
    private static final String PBKDF2_ALG       = "PBKDF2withHmacSHA256";
    private static final String GATE_FILE        = ".gate";

    private static final int MAX_RECORDS = 1 + MAX_GUESTS;

    private static final byte FORMAT_VERSION = 0x02;

    private static final int SALT_LEN     = 16;
    private static final int VERIFIER_LEN = 32;   // HMAC-SHA256 output
    private static final int DERIVED_BITS = 256;  // PBKDF2 output

    private static final int HEADER_LEN = 3;
    private static final int RECORD_LEN = 1 + 1 + 4 + 1 + 1 + SALT_LEN + 1 + VERIFIER_LEN + 1;  // 58

    /** bit0: the Keystore key was inside secure hardware when this record was written. */
    private static final byte FLAG_TEE = 0x01;

    /**
     * PBKDF2 iterations for NEW records.
     *
     * Measured on the MT8168 (Cortex-A53, pure-Java PBKDF2): 150,000 cost roughly 1 s of
     * steady-state derivation, about 4x the ~250 ms design target. Lowered to 50,000 (addendum
     * 2026-10-12-01); re-measured at ~540-580 ms and accepted as UX-only. Sound on the design's own
     * terms: hardware-backing is REQUIRED and there is no software-backed path, so an offline attack
     * is impossible regardless of the count. Iterations are defense-in-depth, not the primary
     * defense.
     *
     * Stored per record, so existing credentials keep deriving at the count they were saved with.
     * Re-save (re-provision or re-record) to pick up a new value.
     */
    private static final int ITERATIONS = 50_000;

    private final Context mContext;
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private final Object mLock = new Object();
    private volatile boolean mLoaded;
    private volatile Store mStore;   // null => unconfigured
    private volatile boolean mWarmed;

    public Pbkdf2KeystoreCredential(Context context) {
        mContext = context.getApplicationContext();
    }

    // ---- warm-up -----------------------------------------------------------------------------

    /**
     * Pay the one-time cost of class loading, JCA provider initialisation, Keystore binder setup and
     * JIT warm-up on the background thread, so the first real attempt is not slower than the rest.
     * Measured cold-vs-warm difference on the MT8168 was roughly 1 s, entirely one-time cost, not
     * derivation cost.
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
                } catch (Throwable t) {
                    // Warm-up is best-effort: a failure here must never affect correctness.
                } finally {
                    if (pw != null) Arrays.fill(pw, '\0');
                    if (dk != null) Arrays.fill(dk, (byte) 0);
                }
            }
        });
    }

    // ---- diagnostics -------------------------------------------------------------------------

    /** Snapshot for the admin surface. Contains no secret material. */
    public static final class Diagnostics {
        public final boolean configured;
        public final int formatVersion;
        public final int iterations;
        public final boolean blobHardwareFlag;
        public final boolean keyPresent;
        public final boolean keyHardwareBacked;
        /** Guest records currently stored, and the ceiling, for the roles row. */
        public final int guestCount;
        public final int maxGuests;

        Diagnostics(boolean configured, int formatVersion, int iterations, boolean blobHardwareFlag,
                boolean keyPresent, boolean keyHardwareBacked, int guestCount, int maxGuests) {
            this.configured = configured;
            this.formatVersion = formatVersion;
            this.iterations = iterations;
            this.blobHardwareFlag = blobHardwareFlag;
            this.keyPresent = keyPresent;
            this.keyHardwareBacked = keyHardwareBacked;
            this.guestCount = guestCount;
            this.maxGuests = maxGuests;
        }
    }

    /** Live Keystore check + stored-store summary. BLOCKING (Keystore binder): call off the UI thread. */
    public Diagnostics diagnose() {
        ensureLoaded();
        final Store s = mStore;
        boolean present = false;
        boolean hardware = false;
        try {
            final KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            present = ks.containsAlias(KEY_ALIAS);
            hardware = present && isKeyHardwareBacked();
        } catch (Throwable t) {
            // report what we know: key unverifiable
        }
        final Record admin = (s == null) ? null : s.find(ROLE_ADMIN);
        int guests = 0;
        if (s != null) {
            for (Record r : s.records) {
                if (r.roleTag != ROLE_ADMIN) guests++;
            }
        }
        return new Diagnostics(
                s != null,
                s == null ? 0 : (FORMAT_VERSION & 0xFF),
                admin == null ? 0 : admin.iterations,
                admin != null && (admin.flags & FLAG_TEE) != 0,
                present, hardware,
                guests, MAX_GUESTS);
    }

    /** True if a credential record exists for this role. */
    boolean hasRole(int roleTag) {
        ensureLoaded();
        final Store s = mStore;
        return s != null && s.find(roleTag) != null;
    }

    // ---- GateCredential ----------------------------------------------------------------------

    @Override
    public boolean isConfigured() {
        ensureLoaded();
        return mStore != null;
    }

    /**
     * Route by the first digit: a role's PIN begins with that role's digit, so the candidate's role
     * is known from the plaintext display before any derivation. Fires only when the operator run
     * has reached exactly that role's stored length, which preserves the single-fire property and
     * costs one derivation per attempt however many roles exist.
     */
    @Override
    public int roleFor(String digits, int opRunLen) {
        ensureLoaded();
        final Store s = mStore;
        if (s == null || digits == null || digits.isEmpty()) return -1;
        final char c = digits.charAt(0);
        if (c < '0' || c > '9') return -1;
        final int role = c - '0';
        if (role > MAX_GUESTS) return -1;
        final Record r = s.find(role);
        return (r != null && r.opLen == opRunLen) ? role : -1;
    }

    @Override
    public void test(final int roleTag, final String digits, final int[] opIds,
            final ResultCallback cb) {
        ensureLoaded();
        final Store s = mStore;
        final Record r = (s == null) ? null : s.find(roleTag);
        if (r == null) {
            postResult(cb, false);
            return;
        }
        final int[] ops = (opIds == null) ? new int[0] : opIds.clone();
        mWorker.execute(new Runnable() {
            @Override
            public void run() {
                postResult(cb, matches(r, digits, ops));
            }
        });
    }

    /**
     * Write (or replace) one role's record, then report success on the UI thread. Fails closed: a
     * PIN that breaks the rules is refused, and on any error nothing is persisted and the previous
     * store stands.
     */
    @Override
    public void save(final int roleTag, final String digits, final int[] opIds,
            final ResultCallback cb) {
        final int[] ops = (opIds == null) ? new int[0] : opIds.clone();
        if (!isAcceptable(roleTag, digits, ops.length)) {
            postResult(cb, false);
            return;
        }
        mWorker.execute(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                char[] pw = null;
                byte[] dk = null;
                try {
                    ensureLoaded();
                    // A guest record without an admin record would be an unreadable store.
                    if (roleTag != ROLE_ADMIN && (mStore == null || mStore.find(ROLE_ADMIN) == null)) {
                        postResult(cb, false);
                        return;
                    }
                    ensureKey();
                    if (!isKeyHardwareBacked()) {
                        deleteKey();              // TEE required: refuse software-backed (fail closed)
                        postResult(cb, false);
                        return;
                    }
                    final byte[] salt = new byte[SALT_LEN];
                    new SecureRandom().nextBytes(salt);
                    pw = password(digits, ops);
                    dk = pbkdf2(pw, salt, ITERATIONS);
                    final byte[] verifier = hmac(dk);
                    final Record rec = new Record(roleTag, ITERATIONS, FLAG_TEE, salt, verifier,
                            ops.length);

                    final Store next = Store.with(mStore, rec);
                    writeAtomic(serialize(next.records));
                    synchronized (mLock) {
                        mStore = next;
                        mLoaded = true;
                    }
                    ok = true;
                } catch (Throwable t) {
                    ok = false;                    // fail closed; nothing persisted
                } finally {
                    if (pw != null) Arrays.fill(pw, '\0');
                    if (dk != null) Arrays.fill(dk, (byte) 0);
                }
                postResult(cb, ok);
            }
        });
    }

    /**
     * Remove one guest role's record. The admin record cannot be removed: losing it would leave an
     * install with no way back in short of the owner reset. Reports success on the UI thread; a role
     * that has no record counts as already removed.
     */
    void removeRole(final int roleTag, final ResultCallback cb) {
        if (roleTag <= ROLE_ADMIN || roleTag > MAX_GUESTS) {
            postResult(cb, false);
            return;
        }
        mWorker.execute(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                try {
                    ensureLoaded();
                    final Store cur = mStore;
                    if (cur == null || cur.find(roleTag) == null) {
                        postResult(cb, true);      // nothing to do
                        return;
                    }
                    final Store next = Store.without(cur, roleTag);
                    writeAtomic(serialize(next.records));
                    synchronized (mLock) {
                        mStore = next;
                        mLoaded = true;
                    }
                    ok = true;
                } catch (Throwable t) {
                    ok = false;
                }
                postResult(cb, ok);
            }
        });
    }

    /** The role-prefix rule: a role's PIN must begin with that role's digit. */
    static boolean hasRolePrefix(int roleTag, String digits) {
        return digits != null && !digits.isEmpty()
                && digits.charAt(0) == (char) ('0' + roleTag);
    }

    /**
     * Everything a PIN must satisfy to be stored: a valid role, all-decimal digits of at least the
     * minimum length beginning with the role's digit, and at least the minimum operators. The
     * recorder and the scripted entry point check the same things first so they can explain a
     * refusal; this is the backstop that guarantees every stored record obeys the rules.
     */
    static boolean isAcceptable(int roleTag, String digits, int opCount) {
        if (roleTag < ROLE_ADMIN || roleTag > MAX_GUESTS) return false;
        if (digits == null || digits.length() < MIN_DIGITS) return false;
        for (int i = 0; i < digits.length(); i++) {
            final char c = digits.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return hasRolePrefix(roleTag, digits) && opCount >= MIN_OPS;
    }

    // ---- verification ------------------------------------------------------------------------

    /** Derive and compare one record. Runs on the worker thread; any error is a non-match. */
    private boolean matches(Record r, String digits, int[] ops) {
        char[] pw = null;
        byte[] dk = null;
        try {
            pw = password(digits, ops);
            dk = pbkdf2(pw, r.salt, r.iterations);
            final byte[] cand = hmac(dk);
            return MessageDigest.isEqual(cand, r.verifier);   // constant-time
        } catch (Throwable t) {
            return false;                  // fail closed
        } finally {
            if (pw != null) Arrays.fill(pw, '\0');
            if (dk != null) Arrays.fill(dk, (byte) 0);
        }
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
                // No StrongBox on this SoC: do not request it.
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

    // ---- serialization -----------------------------------------------------------------------

    static byte[] serialize(Record[] records) {
        final ByteArrayOutputStream b =
                new ByteArrayOutputStream(HEADER_LEN + records.length * RECORD_LEN);
        b.write(FORMAT_VERSION);
        b.write(records.length & 0xFF);
        b.write(0x00);                       // reserved
        for (Record r : records) {
            b.write(r.roleTag & 0xFF);
            b.write(0x00);                   // reserved
            b.write((r.iterations >>> 24) & 0xFF);
            b.write((r.iterations >>> 16) & 0xFF);
            b.write((r.iterations >>> 8) & 0xFF);
            b.write(r.iterations & 0xFF);
            b.write(r.flags);
            b.write(r.salt.length & 0xFF);
            b.write(r.salt, 0, r.salt.length);
            b.write(r.verifier.length & 0xFF);
            b.write(r.verifier, 0, r.verifier.length);
            b.write(r.opLen & 0xFF);
        }
        return b.toByteArray();
    }

    /** Parse the blob; anything unexpected returns null (fail safe to unconfigured). */
    static Store parse(byte[] data) {
        if (data == null || data.length < HEADER_LEN) return null;
        try {
            if (data[0] != FORMAT_VERSION) return null;
            final int n = data[1] & 0xFF;
            if (n < 1 || n > MAX_RECORDS) return null;
            if (data.length != HEADER_LEN + n * RECORD_LEN) return null;
            // data[2] and each record's byte [1] are reserved and deliberately not validated.
            final Record[] records = new Record[n];
            final boolean[] seen = new boolean[MAX_RECORDS];
            int p = HEADER_LEN;
            for (int i = 0; i < n; i++) {
                final int roleTag = data[p++] & 0xFF;
                p++;                                               // reserved
                if (roleTag > MAX_GUESTS || seen[roleTag]) return null;   // unknown or duplicate
                seen[roleTag] = true;
                final int iters = ((data[p] & 0xFF) << 24) | ((data[p + 1] & 0xFF) << 16)
                        | ((data[p + 2] & 0xFF) << 8) | (data[p + 3] & 0xFF);
                p += 4;
                final byte flags = data[p++];
                if ((data[p++] & 0xFF) != SALT_LEN) return null;
                final byte[] salt = Arrays.copyOfRange(data, p, p + SALT_LEN);
                p += SALT_LEN;
                if ((data[p++] & 0xFF) != VERIFIER_LEN) return null;
                final byte[] verifier = Arrays.copyOfRange(data, p, p + VERIFIER_LEN);
                p += VERIFIER_LEN;
                final int opLen = data[p++] & 0xFF;
                records[i] = new Record(roleTag, iters, flags, salt, verifier, opLen);
            }
            // A store with no admin record is unusable: fail safe rather than offer guest-only entry.
            if (!seen[ROLE_ADMIN]) return null;
            return new Store(records);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- file I/O ----------------------------------------------------------------------------

    /** Atomic: write .gate.tmp, fsync, rename over .gate. */
    private void writeAtomic(byte[] blob) throws IOException {
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
                mStore = readStore();
                mLoaded = true;
            }
        }
    }

    private Store readStore() {
        final File f = new File(mContext.getFilesDir(), GATE_FILE);
        if (!f.exists()) return null;
        try {
            return parse(readAll(f));
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] readAll(File f) throws IOException {
        final FileInputStream in = new FileInputStream(f);
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream(HEADER_LEN + RECORD_LEN);
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

    // ---- model -------------------------------------------------------------------------------

    /** One role's credential. Immutable; the arrays are never handed outside this class. */
    static final class Record {
        final int roleTag;
        final int iterations;
        final byte flags;
        final byte[] salt;
        final byte[] verifier;
        final int opLen;

        Record(int roleTag, int iterations, byte flags, byte[] salt, byte[] verifier, int opLen) {
            this.roleTag = roleTag;
            this.iterations = iterations;
            this.flags = flags;
            this.salt = salt;
            this.verifier = verifier;
            this.opLen = opLen;
        }
    }

    /** The parsed store: its records, admin first then guests in ascending role order. */
    static final class Store {
        final Record[] records;

        Store(Record[] records) {
            this.records = records;
        }

        Record find(int roleTag) {
            for (Record r : records) {
                if (r.roleTag == roleTag) return r;
            }
            return null;
        }

        /** Copy of {@code base} with {@code rec} added, or replacing the record of the same role. */
        static Store with(Store base, Record rec) {
            final ArrayList<Record> out = new ArrayList<>(MAX_RECORDS);
            if (base != null) {
                for (Record r : base.records) {
                    if (r.roleTag != rec.roleTag) out.add(r);
                }
            }
            out.add(rec);
            return ordered(out);
        }

        /** Copy of {@code base} without the given role. */
        static Store without(Store base, int roleTag) {
            final ArrayList<Record> out = new ArrayList<>(MAX_RECORDS);
            for (Record r : base.records) {
                if (r.roleTag != roleTag) out.add(r);
            }
            return ordered(out);
        }

        /** Deterministic on-disk order: admin, then guests ascending. */
        private static Store ordered(ArrayList<Record> list) {
            final Record[] arr = new Record[list.size()];
            int k = 0;
            for (int role = ROLE_ADMIN; role <= MAX_GUESTS; role++) {
                for (Record r : list) {
                    if (r.roleTag == role) arr[k++] = r;
                }
            }
            return new Store(arr);
        }
    }
}
