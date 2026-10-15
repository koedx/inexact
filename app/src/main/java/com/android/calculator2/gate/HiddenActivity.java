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

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.android.calculator2.BuildConfig;

/**
 * The hidden admin view (spec addendum 2026-10-14-01 §3). Reached only through the gate: the manifest
 * entry is non-exported with no intent-filter, and onCreate additionally requires the one-shot unlock
 * token set by the host's openGate(), so a stray or restored launch finishes at once.
 *
 * Leak hygiene: FLAG_SECURE (no screenshots, blank recents card), no logging, no saved-instance
 * state, programmatic views only, and it RE-LOCKS by finishing whenever it leaves the foreground.
 * The project name never appears in this UI (spec header).
 *
 * Scope (admin only; single credential until the multi-role step): adb toggles, version, Keystore
 * diagnostic, and Change PIN (returns to the Calculator in PIN-setup mode).
 */
public class HiddenActivity extends Activity {

    private static final String KEY_ADB = "adb_enabled";
    private static final String KEY_DEV = "development_settings_enabled";

    private Switch mAdbSwitch;
    private Switch mDevSwitch;
    private TextView mStatus;
    private TextView mKeystoreText;
    private boolean mSyncing;   // true while setChecked() is driven by code, not the user

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(null);   // never restore: a recreated hidden view re-locks
        if (!GateSession.consumeUnlock() || GateSession.credential() == null) {
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mAdbSwitch == null) {
            return;   // finished in onCreate
        }
        syncSwitches();
        loadKeystoreDiagnostic();
    }

    /** Re-lock: leaving the foreground (Home, screen off, another app) ends the session. */
    @Override
    protected void onStop() {
        super.onStop();
        finish();
    }

    /** Deliberately saves nothing. */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
    }

    // ---- UI ----------------------------------------------------------------------------------

    private View buildUi() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        root.addView(heading("Maintenance", 22, 0));

        root.addView(heading("Debugging", 16, dp(20)));
        mAdbSwitch = new Switch(this);
        mAdbSwitch.setText("adb (adb_enabled)");
        mAdbSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (mSyncing) return;
            if (checked) {
                applySetting(KEY_ADB, true);
            } else {
                confirmAdbOff();
            }
        });
        root.addView(mAdbSwitch);

        mDevSwitch = new Switch(this);
        mDevSwitch.setText("Developer options (development_settings_enabled)");
        mDevSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (mSyncing) return;
            applySetting(KEY_DEV, checked);
        });
        root.addView(mDevSwitch);

        mStatus = new TextView(this);
        root.addView(mStatus);

        root.addView(heading("Credential", 16, dp(20)));
        mKeystoreText = new TextView(this);
        root.addView(mKeystoreText);

        final Button change = new Button(this);
        change.setText("Change PIN");
        change.setOnClickListener(v -> {
            GateSession.requestRerecord();
            finish();   // back to the Calculator, which enters PIN-setup mode
        });
        root.addView(change);

        final TextView note = new TextView(this);
        note.setText("Returns to the calculator in PIN-setup mode. The current PIN stays valid until "
                + "the new one is saved.");
        root.addView(note);

        root.addView(heading("About", 16, dp(20)));
        final TextView version = new TextView(this);
        version.setText("Version " + BuildConfig.APP_VERSION + " (" + BuildConfig.REVISION_DATE + ")");
        root.addView(version);

        final ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private TextView heading(String text, int sp, int topPadding) {
        final TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, topPadding, 0, dp(6));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---- adb / developer toggles -------------------------------------------------------------

    private void syncSwitches() {
        mSyncing = true;
        mAdbSwitch.setChecked(Settings.Global.getInt(getContentResolver(), KEY_ADB, 0) == 1);
        mDevSwitch.setChecked(Settings.Global.getInt(getContentResolver(), KEY_DEV, 0) == 1);
        mSyncing = false;
    }

    private void applySetting(String key, boolean on) {
        boolean ok;
        try {
            ok = Settings.Global.putInt(getContentResolver(), key, on ? 1 : 0);
        } catch (SecurityException e) {
            ok = false;
        }
        mStatus.setText(ok ? "" : "Could not change the setting.");
        syncSwitches();   // always show the real state
    }

    /** Turning adb OFF asks first; turning it on does not (addendum §3.5). */
    private void confirmAdbOff() {
        new AlertDialog.Builder(this)
                .setTitle("Turn off adb?")
                .setMessage("It stays off until you turn it on here.")
                .setPositiveButton("Turn off", (d, which) -> applySetting(KEY_ADB, false))
                .setNegativeButton("Cancel", (d, which) -> syncSwitches())
                .setOnCancelListener(d -> syncSwitches())
                .show();
    }

    // ---- Keystore diagnostic -----------------------------------------------------------------

    private void loadKeystoreDiagnostic() {
        final Pbkdf2KeystoreCredential credential = GateSession.credential();
        mKeystoreText.setText("Checking...");
        new Thread(() -> {
            final Pbkdf2KeystoreCredential.Diagnostics d = credential.diagnose();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                mKeystoreText.setText(describe(d));
            });
        }, "gate-diag").start();
    }

    private static String describe(Pbkdf2KeystoreCredential.Diagnostics d) {
        final StringBuilder sb = new StringBuilder();
        if (!d.keyPresent) {
            sb.append("Key: missing");
        } else {
            sb.append("Key: ").append(d.keyHardwareBacked ? "hardware-backed" : "NOT hardware-backed");
        }
        sb.append('\n');
        if (d.configured) {
            sb.append("Stored: format v").append(d.formatVersion).append(", ")
                    .append(d.iterations).append(" iterations, hardware flag ")
                    .append(d.blobHardwareFlag ? "set" : "clear");
        } else {
            sb.append("Stored: none");
        }
        return sb.toString();
    }
}
