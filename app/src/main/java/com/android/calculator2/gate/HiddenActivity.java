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

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.android.calculator2.BuildConfig;
import com.android.calculator2.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * The hidden admin view (spec addendum 2026-10-14-01 §3; Material 3 UI per 2026-10-15-01).
 * Reached only through the gate: the manifest entry is non-exported with no intent-filter, and
 * onCreate additionally requires the one-shot unlock token set by the host's openGate(), so a stray
 * or restored launch finishes at once.
 *
 * Leak hygiene: FLAG_SECURE (no screenshots, blank recents card), no logging, nothing kept in the
 * saved-instance bundle, and it RE-LOCKS by finishing whenever it leaves the foreground. The project
 * name never appears in this UI (spec header).
 *
 * Scope (admin only; single credential until the multi-role step): adb toggles, a Developer options
 * shortcut, version, Keystore diagnostic, and Change PIN (returns to the Calculator in PIN-setup
 * mode).
 */
public class HiddenActivity extends AppCompatActivity {

    private static final String KEY_ADB = "adb_enabled";
    private static final String KEY_DEV = "development_settings_enabled";

    private MaterialSwitch mAdbSwitch;
    private MaterialSwitch mDevSwitch;
    private MaterialButton mOpenDev;
    private TextView mOpenDevNote;
    private TextView mStatus;
    private TextView mKeyValue;
    private TextView mStoredValue;
    private boolean mSyncing;   // true while setChecked() is driven by code, not the user

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(null);   // never restore: a recreated hidden view re-locks
        if (!GateSession.consumeUnlock() || GateSession.credential() == null) {
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_hidden);

        mStatus = findViewById(R.id.hidden_status);

        // Debugging card.
        final ViewGroup debugRows = findViewById(R.id.hidden_debug_rows);
        mAdbSwitch = addSwitchRow(debugRows, R.string.hidden_adb_title, R.string.hidden_adb_desc);
        mAdbSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (mSyncing) return;
            if (checked) {
                applySetting(KEY_ADB, true);
            } else {
                confirmAdbOff();   // turning adb OFF asks first; turning it on does not
            }
        });
        getLayoutInflater().inflate(R.layout.hidden_divider, debugRows, true);
        mDevSwitch = addSwitchRow(debugRows, R.string.hidden_dev_title, R.string.hidden_dev_desc);
        mDevSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (mSyncing) return;
            applySetting(KEY_DEV, checked);
        });
        mOpenDev = findViewById(R.id.hidden_open_dev_button);
        mOpenDevNote = findViewById(R.id.hidden_open_dev_note);
        mOpenDev.setOnClickListener(v -> openDeveloperOptions());

        // Credential card.
        final ViewGroup credentialRows = findViewById(R.id.hidden_credential_rows);
        mKeyValue = addInfoRow(credentialRows, R.string.hidden_key_title);
        getLayoutInflater().inflate(R.layout.hidden_divider, credentialRows, true);
        mStoredValue = addInfoRow(credentialRows, R.string.hidden_stored_title);
        findViewById(R.id.hidden_change_pin).setOnClickListener(v -> {
            GateSession.requestRerecord();
            finish();   // back to the Calculator, which enters PIN-setup mode
        });

        // About card.
        final ViewGroup aboutRows = findViewById(R.id.hidden_about_rows);
        addInfoRow(aboutRows, R.string.hidden_version_title).setText(getString(
                R.string.hidden_version_fmt, BuildConfig.APP_VERSION, BuildConfig.REVISION_DATE));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mAdbSwitch == null) {
            return;   // finished in onCreate
        }
        syncSwitches();
        bindDeveloperOptionsAction();
        loadKeystoreDiagnostic();
    }

    /** Re-lock: leaving the foreground (Home, screen off, another app) ends the session. */
    @Override
    protected void onStop() {
        super.onStop();
        finish();
    }

    /** Deliberately keeps nothing: drop whatever the framework just wrote. */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.clear();
    }

    // ---- row builders ------------------------------------------------------------------------

    private MaterialSwitch addSwitchRow(ViewGroup parent, int titleRes, int descRes) {
        final View row = getLayoutInflater().inflate(R.layout.hidden_row_switch, parent, false);
        ((TextView) row.findViewById(R.id.hidden_row_title)).setText(titleRes);
        ((TextView) row.findViewById(R.id.hidden_row_desc)).setText(descRes);
        final MaterialSwitch sw = row.findViewById(R.id.hidden_row_switch);
        row.setOnClickListener(v -> sw.toggle());   // the whole row is the touch target
        parent.addView(row);
        return sw;
    }

    /** Adds a title/value row and returns the value TextView. */
    private TextView addInfoRow(ViewGroup parent, int titleRes) {
        final View row = getLayoutInflater().inflate(R.layout.hidden_row_info, parent, false);
        ((TextView) row.findViewById(R.id.hidden_info_title)).setText(titleRes);
        parent.addView(row);
        return row.findViewById(R.id.hidden_info_value);
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
        showStatus(ok ? 0 : R.string.hidden_change_failed);
        syncSwitches();   // always show the real state
    }

    private void confirmAdbOff() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.hidden_adb_off_title)
                .setMessage(R.string.hidden_adb_off_message)
                .setPositiveButton(R.string.hidden_adb_off_confirm,
                        (dialog, which) -> applySetting(KEY_ADB, false))
                .setNegativeButton(R.string.hidden_cancel, (dialog, which) -> syncSwitches())
                .setOnCancelListener(dialog -> syncSwitches())
                .show();
    }

    private void showStatus(int messageRes) {
        if (messageRes == 0) {
            mStatus.setVisibility(View.GONE);
        } else {
            mStatus.setText(messageRes);
            mStatus.setVisibility(View.VISIBLE);
        }
    }

    // ---- Developer options shortcut ----------------------------------------------------------

    private Intent developerOptionsIntent() {
        return new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
    }

    /**
     * The Developer options screen lives inside com.android.settings, which the kiosk lockdown
     * disables. An app cannot re-enable another package, so the shortcut only works while Settings
     * is enabled; otherwise it is greyed out with the recovery hint (spec §9).
     */
    private void bindDeveloperOptionsAction() {
        final boolean available =
                getPackageManager().resolveActivity(developerOptionsIntent(), 0) != null;
        mOpenDev.setEnabled(available);
        mOpenDevNote.setText(available ? R.string.hidden_dev_open_desc
                                       : R.string.hidden_dev_open_unavailable);
    }

    private void openDeveloperOptions() {
        try {
            startActivity(developerOptionsIntent());
        } catch (ActivityNotFoundException | SecurityException e) {
            bindDeveloperOptionsAction();   // state changed under us: refresh the row
        }
    }

    // ---- Keystore diagnostic -----------------------------------------------------------------

    private void loadKeystoreDiagnostic() {
        final Pbkdf2KeystoreCredential credential = GateSession.credential();
        mKeyValue.setText(R.string.hidden_key_checking);
        new Thread(() -> {
            final Pbkdf2KeystoreCredential.Diagnostics d = credential.diagnose();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                showDiagnostic(d);
            });
        }, "gate-diag").start();
    }

    private void showDiagnostic(Pbkdf2KeystoreCredential.Diagnostics d) {
        final boolean keyOk = d.keyPresent && d.keyHardwareBacked;
        mKeyValue.setText(!d.keyPresent ? R.string.hidden_key_missing
                : d.keyHardwareBacked ? R.string.hidden_key_hw : R.string.hidden_key_sw);
        mKeyValue.setTextColor(MaterialColors.getColor(mKeyValue, keyOk
                ? com.google.android.material.R.attr.colorOnSurface
                : com.google.android.material.R.attr.colorError));

        mStoredValue.setText(d.configured
                ? getString(R.string.hidden_stored_fmt, d.formatVersion, d.iterations,
                        getString(d.blobHardwareFlag ? R.string.hidden_flag_set
                                                     : R.string.hidden_flag_clear))
                : getString(R.string.hidden_stored_none));
    }
}
