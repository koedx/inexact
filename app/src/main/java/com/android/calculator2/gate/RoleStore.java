/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: per-role metadata store: friendly names now, more per-role configuration later.
 *          Encrypted at rest with AES-GCM under a device-bound, hardware-backed AndroidKeyStore
 *          key, so a copy of the file is useless off this tablet. Deliberately NOT keyed to the
 *          admin PIN: a guest unlock must be able to read its own entry, and no admin PIN is
 *          present then.
 * Created:  2026-11-08 07:31:00 UTC
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
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Admin-set metadata, one entry per role (admin and guest slots). "Role" is the credential axis;
 * the word "profile" is deliberately not used for it, to keep it distinct from settings overlays.
 *
 * Scope note: this file holds HINTS, not secrets — a label like "rental (stock)" that reminds the
 * admin what a guest slot is for. The credential records live in the gate blob
 * ({@link Pbkdf2KeystoreCredential}) and never appear here, so losing or wiping this file costs
 * names, never access.
 *
 * Whether a guest slot is IN USE is not stored here. That is derived from whether a credential
 * record exists for the role, which keeps one source of truth: removing a guest destroys its PIN
 * record, and the name survives so re-enabling the slot only needs a new PIN.
 *
 * Encryption: AES-256-GCM, key alias {@link #KEY_ALIAS}, generated on first write. Hardware-backing
 * is required, matching the credential's rule — if the key is not inside secure hardware it is
 * deleted and the write fails rather than falling back to plaintext. A read that cannot decrypt
 * (missing key, key invalidated, truncated or tampered file) fails SAFE to "no names stored", so
 * the surface shows default names instead of refusing to open.
 *
 * All methods here do file I/O and touch the Keystore binder: BLOCKING, call off the UI thread.
 */
public final class RoleStore {

    /**
     * Role tags, taken from the credential seam rather than redeclared, so the slot range here can
     * never drift from the range the blob accepts.
     */
    public static final int ROLE_ADMIN = GateCredential.ROLE_ADMIN;
    public static final int MAX_GUESTS = GateCredential.MAX_GUESTS;

    /** Bound the stored text so the encrypted file stays small and predictable. */
    public static final int MAX_NAME_LEN = 40;

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS   = "inexact_roles_aes_v1";
    private static final String TRANSFORM   = "AES/GCM/NoPadding";
    private static final String ROLES_FILE = ".roles";

    private static final byte FORMAT_VERSION = 0x01;
    private static final int  TAG_BITS       = 128;   // GCM authentication tag, full length
    private static final int  IV_LEN         = 12;    // 96-bit nonce: the GCM-recommended size
    private static final int  KEY_BITS       = 256;

    // JSON keys. Short names keep the plaintext (and so the ciphertext) compact.
    private static final String J_VERSION  = "v";
    private static final String J_ROLES = "p";
    private static final String J_ID       = "i";
    private static final String J_NAME     = "n";

    private final Context mContext;

    public RoleStore(Context context) {
        mContext = context.getApplicationContext();
    }

    // ---- public API --------------------------------------------------------------------------

    /**
     * Stored names by role tag. Absent, unreadable or undecryptable file => empty map (the caller
     * then shows {@link #defaultName}). Never throws.
     */
    public Map<Integer, String> names() {
        final Map<Integer, String> out = new LinkedHashMap<>();
        byte[] plain = null;
        try {
            final byte[] file = readFile();
            if (file == null) return out;
            plain = decrypt(file);
            if (plain == null) return out;
            final JSONObject root = new JSONObject(new String(plain, "UTF-8"));
            final JSONArray arr = root.optJSONArray(J_ROLES);
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                final int id = o.optInt(J_ID, -1);
                final String name = o.optString(J_NAME, "");
                if (isValidRole(id) && !name.isEmpty()) {
                    out.put(id, clamp(name));
                }
            }
        } catch (Throwable t) {
            out.clear();            // fail safe: defaults, never a crash on the unlock path
        } finally {
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
        return out;
    }

    /**
     * Set or clear one role's friendly name (empty or null clears it, restoring the default).
     * Returns false if the record could not be written, in which case nothing changed on disk.
     */
    public boolean setName(int role, String name) {
        if (!isValidRole(role)) return false;
        final Map<Integer, String> current = names();
        final String trimmed = (name == null) ? "" : clamp(name.trim());
        if (trimmed.isEmpty()) {
            current.remove(role);
        } else {
            current.put(role, trimmed);
        }
        return write(current);
    }

    /** The label shown when the admin has not set one. */
    public static String defaultName(int role) {
        return (role == ROLE_ADMIN) ? "Admin" : "Guest " + role;
    }

    /** Convenience for the admin surface: stored name if set, otherwise the default. */
    public String displayName(int role) {
        final String stored = names().get(role);
        return (stored == null || stored.isEmpty()) ? defaultName(role) : stored;
    }

    /**
     * Whether stored names are expected to work. Reported in the admin surface so a silent "names
     * won't stick" is visible.
     *
     * Deliberately does NOT create the key: merely opening the admin screen must not leave Keystore
     * state behind on an install that never stores a name (the same discipline as the credential's
     * warm-up). An absent key is therefore reported as available — it is created on the first write,
     * and a write that cannot create a hardware-backed key reports its own failure.
     */
    public boolean encryptionAvailable() {
        try {
            final KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            if (!ks.containsAlias(KEY_ALIAS)) {
                return true;            // not created yet; the first write will create it
            }
            final SecretKey key = (SecretKey) ks.getKey(KEY_ALIAS, null);
            return key != null && isHardwareBacked(key);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- JSON <-> file -----------------------------------------------------------------------

    private boolean write(Map<Integer, String> names) {
        byte[] plain = null;
        try {
            final JSONArray arr = new JSONArray();
            for (Map.Entry<Integer, String> e : names.entrySet()) {
                if (!isValidRole(e.getKey())) continue;
                final JSONObject o = new JSONObject();
                o.put(J_ID, (int) e.getKey());
                o.put(J_NAME, e.getValue());
                arr.put(o);
            }
            final JSONObject root = new JSONObject();
            root.put(J_VERSION, 1);
            root.put(J_ROLES, arr);

            plain = root.toString().getBytes("UTF-8");
            final byte[] blob = encrypt(plain);
            if (blob == null) return false;
            writeAtomic(blob);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    /** Atomic: write .roles.tmp, fsync, rename over .roles (same discipline as the blob). */
    private void writeAtomic(byte[] blob) throws IOException {
        final File dir = mContext.getFilesDir();
        final File tmp = new File(dir, ROLES_FILE + ".tmp");
        final File dst = new File(dir, ROLES_FILE);
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

    /** Whole file, or null if absent / unreadable. */
    private byte[] readFile() {
        final File f = new File(mContext.getFilesDir(), ROLES_FILE);
        if (!f.exists()) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            final ByteArrayOutputStream out = new ByteArrayOutputStream(256);
            final byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) { }
            }
        }
    }

    // ---- crypto -----------------------------------------------------------------------------

    /**
     * Envelope: [0] format version, [1] IV length, [2..] IV, then ciphertext with the GCM tag
     * appended by the provider. The IV is generated by Keystore (randomized encryption is required
     * for AndroidKeyStore AES keys, so the caller must NOT supply one) and read back off the Cipher.
     */
    private byte[] encrypt(byte[] plain) throws Exception {
        final SecretKey key = ensureKey();
        if (key == null) return null;
        final Cipher c = Cipher.getInstance(TRANSFORM);
        c.init(Cipher.ENCRYPT_MODE, key);
        final byte[] ct = c.doFinal(plain);
        final byte[] iv = c.getIV();
        if (iv == null || iv.length == 0 || iv.length > 255) return null;
        final ByteArrayOutputStream out = new ByteArrayOutputStream(2 + iv.length + ct.length);
        out.write(FORMAT_VERSION);
        out.write(iv.length & 0xFF);
        out.write(iv, 0, iv.length);
        out.write(ct, 0, ct.length);
        return out.toByteArray();
    }

    /**
     * Inverse of {@link #encrypt}. Returns null on any failure — including a failed GCM tag check,
     * which is what an edited or truncated file produces. Tampering therefore reads as "no
     * names", never as attacker-chosen text.
     */
    private byte[] decrypt(byte[] file) throws Exception {
        if (file.length < 2) return null;
        int p = 0;
        if (file[p++] != FORMAT_VERSION) return null;
        final int ivLen = file[p++] & 0xFF;
        if (ivLen == 0 || file.length < p + ivLen + 1) return null;
        final byte[] iv = Arrays.copyOfRange(file, p, p + ivLen);
        p += ivLen;
        final byte[] ct = Arrays.copyOfRange(file, p, file.length);

        final KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        final SecretKey key = (SecretKey) ks.getKey(KEY_ALIAS, null);
        if (key == null) return null;     // key gone => treat stored names as lost, show defaults
        final Cipher c = Cipher.getInstance(TRANSFORM);
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        return c.doFinal(ct);
    }

    /**
     * Fetch the store key, generating it on first use. Hardware-backing is required: a
     * software-backed key is deleted and null returned, so nothing is ever written in a form a
     * copied file could reveal elsewhere. Separate alias and purpose from the credential's HMAC key
     * — one key, one job.
     */
    private SecretKey ensureKey() throws Exception {
        final KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        if (!ks.containsAlias(KEY_ALIAS)) {
            final KeyGenerator kg = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
            kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_BITS)
                    // No setUserAuthenticationRequired: a guest unlock must read its own entry,
                    // and lockscreen binding would invalidate the key on a lockscreen change.
                    // No StrongBox on this SoC — do not request it.
                    .build());
            kg.generateKey();
        }
        final SecretKey key = (SecretKey) ks.getKey(KEY_ALIAS, null);
        if (key == null) return null;
        if (!isHardwareBacked(key)) {
            try { ks.deleteEntry(KEY_ALIAS); } catch (Exception ignored) { }
            return null;
        }
        return key;
    }

    private static boolean isHardwareBacked(SecretKey key) throws Exception {
        final SecretKeyFactory f =
                SecretKeyFactory.getInstance(key.getAlgorithm(), ANDROID_KEYSTORE);
        final KeyInfo info = (KeyInfo) f.getKeySpec(key, KeyInfo.class);
        return info.isInsideSecureHardware();   // API 29; getSecurityLevel() is API 31+
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static boolean isValidRole(int role) {
        return role >= ROLE_ADMIN && role <= MAX_GUESTS;
    }

    private static String clamp(String s) {
        return (s.length() <= MAX_NAME_LEN) ? s : s.substring(0, MAX_NAME_LEN);
    }
}
