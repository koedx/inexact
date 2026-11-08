/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: shipping credential store. Salted PBKDF2 verifiers bound to a hardware-backed Keystore
 *          HMAC key, one record per role (admin + up to four guests).
 * Modified: 2026-11-08 07:31:00 UTC
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
 * Shipping credential (spec v0.4 §5, multi-role layout §16 as revised by the role-prefix rule).
 *
 * At rest: per record, a salted PBKDF2-HMAC-SHA256 hash of the PIN, then HMAC-SHA256'd with a
 * non-exportable, HARDWARE-BACKED AndroidKeyStore key. The Keystore HMAC binds every verifier to
 * this device: a blob copied elsewhere cannot be recomputed without the on-device key, and offline
 * brute force is impossible while that key is TEE-backed. Hardware-backing is REQUIRED — if the key
 * is not inside secure hardware, save() refuses (deletes the key, fails closed) and nothing is
 * stored, so the app stays a plain calculator. There is no software-backed path.
 *
 * ROLES. One record per role: {@link #ROLE_ADMIN} plus up to {@link #MAX_GUESTS} guests. Each role's
 * PIN must begin with its own digit (admin 0, guest N with N), which is what makes it impossible for
 * one role's PIN to shadow another's: shadowing needs two roles to share the captured digit string,
 * and distinct mandatory first digits forbid that. Enforcement of the rule lives in the recorder and
 * the provisioning entry point; this class records whether it held for each saved record, in
 * {@link #FLAG_PREFIX}. A record without that flag predates the rule (a v1 blob from an older
 * build), keeps working, and is reported so the surface can require an admin re-record before guest
 * roles are allowed.
 *
 * ON-DISK FORMATS. Two are read; which is written depends only on how many records exist:
 *
 *   v1 (0x01, 57 bytes, single admin record) — the 0.2.x/0.3.x layout. Still WRITTEN whenever the
 *   store holds nothing but the admin record, so a single-PIN install can be rolled back to an
 *   older build without losing its credential.
 *     [0] 0x01 | [1..4] iterations BE | [5] flags | [6] salt len 0x10 | [7..22] salt
 *     | [23] verifier len 0x20 | [24..55] verifier | [56] opSeqLen
 *
 *   v2 (0x02, 3 + 58N bytes) — written as soon as a guest record exists.
 *     header: [0] 0x02 | [1] record count N (1..5) | [2] reserved 0x00
 *     record: [0] role tag | [1] profile id | [2..5] iterations BE | [6] flags
 *             | [7] salt len 0x10 | [8..23] salt | [24] verifier len 0x20
 *             | [25..56] verifier | [57] opSeqLen
 *
 * A v2 blob fails an older build's version check and reads as unconfigured there, so the 0.4.0 build
 * must be installed BEFORE any guest PIN is added. Writes are atomic (tmp + fsync + rename) and any
 * corrupt, short or unknown-version file reads as unconfigured — fail safe to a plain calculator,
 * never a crash. Recovery from that state is the owner reset in spec §8.
 *
 * All crypto runs on a single background thread; results post to the UI thread. isConfigured() and
 * the op-length lookups are served from an in-memory copy of the store (loaded once) so the matcher
 * can poll them per keypress without file I/O.
 */
public final class Pbkdf2KeystoreCredential implements GateCredential {

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS        = "inexact_gate_hmac_v1";
    private static final String HMAC_ALG         = "HmacSHA256";
    private static final String PBKDF2_ALG       = "PBKDF2withHmacSHA256";
    private static final String GATE_FILE        = ".gate";

    /** ROLE_ADMIN and MAX_GUESTS come from the seam; a role tag is also that role's first digit. */
    private static final int MAX_RECORDS = 1 + MAX_GUESTS;

    private static final byte FORMAT_V1 = 0x01;
    private static final byte FORMAT_V2 = 0x02;

    private static final int SALT_LEN     = 16;
    private static final int VERIFIER_LEN = 32;   // HMAC-SHA256 output
    private static final int DERIVED_BITS = 256;  // PBKDF2 output

    private static final int V1_LEN        = 1 + 4 + 1 + 1 + SALT_LEN + 1 + VERIFIER_LEN + 1;  // 57
    private static final int V2_HEADER_LEN = 3;
    private static final int V2_RECORD_LEN = 1 + 1 + 4 + 1 + 1 + SALT_LEN + 1 + VERIFIER_LEN + 1; // 58

    /** bit0: the Keystore key was inside secure hardware when this record was written. */
    private static final byte FLAG_TEE = 0x01;
    /**
     * bit1: this record's PIN was checked to begin with its role digit when saved. Clear on records
     * written before the rule existed. Older builds mask only bit0, so setting this is safe for
     * rollback.
     */
    private static final byte FLAG_PREFIX = 0x02;

    /**
     * PBKDF2 iterations for NEW records.
     *
     * Measured on the MT8168 (Cortex-A53, pure-Java PBKDF2): 150,000 cost roughly 1 s of
     * steady-state derivation — about 4x the ~250 ms design target. Lowered to 50,000 (addendum
     * 2026-10-12-01); re-measured at ~540-580 ms and accepted as UX-only. Sound on the design's own
     * terms: hardware-backing is REQUIRED and there is no software-backed path, so an offline attack
     * is impossible regardless of the count — iterations are defense-in-depth, not the primary
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
     * Measured cold-vs-warm difference on the MT8168 was roughly 1 s — entirely one-time cost, not
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
        /**
         * True when the admin record was saved under the role-prefix rule. False for a record from
         * an older build: its first digit cannot be verified from a hash, so guest roles stay
         * unavailable until the admin PIN is re-recorded.
         */
        public final boolean adminPrefixEnforced;

        Diagnostics(boolean configured, int formatVersion, int iterations, boolean blobHardwareFlag,
                boolean keyPresent, boolean keyHardwareBacked, int guestCount, int maxGuests,
                boolean adminPrefixEnforced) {
            this.configured = configured;
            this.formatVersion = formatVersion;
            this.iterations = iterations;
            this.blobHardwareFlag = blobHardwareFlag;
            this.keyPresent = keyPresent;
            this.keyHardwareBacked = keyHardwareBacked;
            this.guestCount = guestCount;
            this.maxGuests = maxGuests;
            this.adminPrefixEnforced = adminPrefixEnforced;
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
                s == null ? 0 : s.version,
                admin == null ? 0 : admin.iterations,
                admin != null && (admin.flags & FLAG_TEE) != 0,
                present, hardware,
                guests, MAX_GUESTS,
                admin != null && (admin.flags & FLAG_PREFIX) != 0);
    }

    // ---- role queries (used by the admin surface and, from 5b, the matcher) -------------------

    /** True if a credential record exists for this role. */
    boolean hasRole(int roleTag) {
        ensureLoaded();
        final Store s = mStore;
        return s != null && s.find(roleTag) != null;
    }

    /** Operator-run length stored for this role, or -1 if the role has no record. */
    int opLenForRole(int roleTag) {
        ensureLoaded();
        final Store s = mStore;
        if (s == null) return -1;
        final Record r = s.find(roleTag);
        return (r == null) ? -1 : r.opLen;
    }

    /**
     * True when the admin record predates the role-prefix rule, so its first digit is unknown. The
     * admin surface blocks guest roles in that state (an unverifiable admin digit could collide with
     * a guest's and shadow it).
     */
    boolean adminPrefixUnverified() {
        ensureLoaded();
        final Store s = mStore;
        if (s == null) return false;
        final Record admin = s.find(ROLE_ADMIN);
        return admin != null && (admin.flags & FLAG_PREFIX) == 0;
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

        final Record admin = s.find(ROLE_ADMIN);
        if (admin != null && (admin.flags & FLAG_PREFIX) == 0) {
            // Pre-prefix admin record: its first digit was never checked, so it cannot be routed
            // on. Offer it at any first digit. Guest roles are refused while this holds, so the
            // admin record is the only candidate and nothing can be shadowed.
            return (admin.opLen == opRunLen) ? ROLE_ADMIN : -1;
        }

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

    // ---- record mutation ---------------------------------------------------------------------

    /**
     * Write (or replace) one role's record, then report success on the UI thread. Fails closed: on
     * any error nothing is persisted and the previous store stands.
     *
     * @param roleTag   {@link #ROLE_ADMIN} or 1..{@link #MAX_GUESTS}
     * @param profileId profile this role maps to (currently equal to the role tag)
     */
    @Override
    public void save(final int roleTag, final int profileId, final String digits, final int[] opIds,
            final ResultCallback cb) {
        if (roleTag < ROLE_ADMIN || roleTag > MAX_GUESTS) {
            postResult(cb, false);
            return;
        }
        final int[] ops = (opIds == null) ? new int[0] : opIds.clone();
        mWorker.execute(new Runnable() {
            @Override
            public void run() {
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

                    byte flags = FLAG_TEE;
                    if (hasRolePrefix(roleTag, digits)) {
                        flags |= FLAG_PREFIX;     // record the rule as it actually held
                    }
                    final Record rec = new Record(roleTag, profileId, ITERATIONS, flags, salt,
                            verifier, ops.length);

                    ensureLoaded();
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
     * Remove one guest role's record. The admin record cannot be removed — losing it would leave an
     * install with no way back in short of the owner reset. Reports success on the UI thread; a role
     * that has no record counts as already removed.
     */
    void removeRole(final int roleTag, final ResultCallback cb) {
        if (roleTag == ROLE_ADMIN || roleTag < ROLE_ADMIN || roleTag > MAX_GUESTS) {
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
     *
     * UNCHANGED from v1: existing credentials must keep verifying across the format change.
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

    // ---- serialization -----------------------------------------------------------------------

    /**
     * Encode the store. A lone admin record is written in the v1 layout so a single-PIN install
     * stays readable by an older build; anything else is v2.
     */
    static byte[] serialize(Record[] records) {
        if (records.length == 1 && records[0].roleTag == ROLE_ADMIN) {
            return serializeV1(records[0]);
        }
        return serializeV2(records);
    }

    private static byte[] serializeV1(Record r) {
        final ByteArrayOutputStream b = new ByteArrayOutputStream(V1_LEN);
        b.write(FORMAT_V1);
        writeIntBE(b, r.iterations);
        b.write(r.flags);
        b.write(r.salt.length & 0xFF);
        b.write(r.salt, 0, r.salt.length);
        b.write(r.verifier.length & 0xFF);
        b.write(r.verifier, 0, r.verifier.length);
        b.write(r.opLen & 0xFF);
        return b.toByteArray();
    }

    private static byte[] serializeV2(Record[] records) {
        final ByteArrayOutputStream b =
                new ByteArrayOutputStream(V2_HEADER_LEN + records.length * V2_RECORD_LEN);
        b.write(FORMAT_V2);
        b.write(records.length & 0xFF);
        b.write(0x00);                       // reserved (was the MRU hint; dropped with prefix routing)
        for (Record r : records) {
            b.write(r.roleTag & 0xFF);
            b.write(r.profileId & 0xFF);
            writeIntBE(b, r.iterations);
            b.write(r.flags);
            b.write(r.salt.length & 0xFF);
            b.write(r.salt, 0, r.salt.length);
            b.write(r.verifier.length & 0xFF);
            b.write(r.verifier, 0, r.verifier.length);
            b.write(r.opLen & 0xFF);
        }
        return b.toByteArray();
    }

    private static void writeIntBE(ByteArrayOutputStream b, int v) {
        b.write((v >>> 24) & 0xFF);
        b.write((v >>> 16) & 0xFF);
        b.write((v >>> 8) & 0xFF);
        b.write(v & 0xFF);
    }

    private static int readIntBE(byte[] d, int p) {
        return ((d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16)
                | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
    }

    /** Parse either format; anything unexpected returns null (fail safe to unconfigured). */
    static Store parse(byte[] data) {
        if (data == null || data.length < 1) return null;
        try {
            final int version = data[0] & 0xFF;
            if (version == FORMAT_V1) return parseV1(data);
            if (version == FORMAT_V2) return parseV2(data);
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Store parseV1(byte[] data) {
        if (data.length != V1_LEN) return null;
        int p = 1;
        final int iters = readIntBE(data, p);
        p += 4;
        final byte flags = data[p++];
        if ((data[p++] & 0xFF) != SALT_LEN) return null;
        final byte[] salt = Arrays.copyOfRange(data, p, p + SALT_LEN);
        p += SALT_LEN;
        if ((data[p++] & 0xFF) != VERIFIER_LEN) return null;
        final byte[] verifier = Arrays.copyOfRange(data, p, p + VERIFIER_LEN);
        p += VERIFIER_LEN;
        final int opLen = data[p] & 0xFF;
        final Record r = new Record(ROLE_ADMIN, ROLE_ADMIN, iters, flags, salt, verifier, opLen);
        return new Store(1, new Record[] { r });
    }

    private static Store parseV2(byte[] data) {
        if (data.length < V2_HEADER_LEN) return null;
        final int n = data[1] & 0xFF;
        if (n < 1 || n > MAX_RECORDS) return null;
        if (data.length != V2_HEADER_LEN + n * V2_RECORD_LEN) return null;
        // data[2] is reserved and deliberately not validated, so a future writer can use it.
        final Record[] records = new Record[n];
        final boolean[] seen = new boolean[MAX_RECORDS];
        int p = V2_HEADER_LEN;
        for (int i = 0; i < n; i++) {
            final int roleTag = data[p++] & 0xFF;
            final int profileId = data[p++] & 0xFF;
            if (roleTag > MAX_GUESTS || seen[roleTag]) return null;   // unknown or duplicate role
            seen[roleTag] = true;
            final int iters = readIntBE(data, p);
            p += 4;
            final byte flags = data[p++];
            if ((data[p++] & 0xFF) != SALT_LEN) return null;
            final byte[] salt = Arrays.copyOfRange(data, p, p + SALT_LEN);
            p += SALT_LEN;
            if ((data[p++] & 0xFF) != VERIFIER_LEN) return null;
            final byte[] verifier = Arrays.copyOfRange(data, p, p + VERIFIER_LEN);
            p += VERIFIER_LEN;
            final int opLen = data[p++] & 0xFF;
            records[i] = new Record(roleTag, profileId, iters, flags, salt, verifier, opLen);
        }
        // A store with no admin record is unusable: fail safe rather than offer guest-only entry.
        boolean hasAdmin = false;
        for (Record r : records) {
            if (r.roleTag == ROLE_ADMIN) hasAdmin = true;
        }
        if (!hasAdmin) return null;
        return new Store(2, records);
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
            final ByteArrayOutputStream out = new ByteArrayOutputStream(V1_LEN);
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
        final int profileId;
        final int iterations;
        final byte flags;
        final byte[] salt;
        final byte[] verifier;
        final int opLen;

        Record(int roleTag, int profileId, int iterations, byte flags, byte[] salt, byte[] verifier,
                int opLen) {
            this.roleTag = roleTag;
            this.profileId = profileId;
            this.iterations = iterations;
            this.flags = flags;
            this.salt = salt;
            this.verifier = verifier;
            this.opLen = opLen;
        }
    }

    /** The parsed store: the format version it was read from, plus its records in on-disk order. */
    static final class Store {
        final int version;
        final Record[] records;

        Store(int version, Record[] records) {
            this.version = version;
            this.records = records;
        }

        Record find(int roleTag) {
            for (Record r : records) {
                if (r.roleTag == roleTag) return r;
            }
            return null;
        }

        /**
         * Copy of {@code base} with {@code rec} added, or replacing the record of the same role.
         * Admin first, then guests in ascending role order, so the on-disk order is deterministic.
         */
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

        private static Store ordered(ArrayList<Record> list) {
            final Record[] arr = new Record[list.size()];
            int k = 0;
            for (int role = ROLE_ADMIN; role <= MAX_GUESTS; role++) {
                for (Record r : list) {
                    if (r.roleTag == role) arr[k++] = r;
                }
            }
            // version reflects what serialize() will emit for this shape
            final int version = (arr.length == 1 && arr[0].roleTag == ROLE_ADMIN) ? 1 : 2;
            return new Store(version, arr);
        }
    }
}
