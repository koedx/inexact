/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: admin dashboard destination — adb/developer toggles, Developer options shortcut, version,
 *          Keystore diagnostic, Change PIN. Relocated verbatim in behaviour from HiddenActivity into
 *          a fragment under the launcher's footer nav (spec addendum 2026-11-06-01 §3). The privileged
 *          surface (WRITE_SECURE_SETTINGS via Settings.Global) is unchanged from the verified 0.3.x
 *          HiddenActivity — this is a relocation, not new capability.
 * Created:  2026-11-06 09:44:19 UTC
 * Modified: 2026-11-06 09:44:19 UTC
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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.android.calculator2.BuildConfig;
import com.android.calculator2.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * Admin-only (the launcher hides this destination for guests). Reuses the hidden view's layout and
 * strings unchanged. Note: no onStop re-lock any more — the session persists until the lock action
 * or process death (spec addendum 2026-11-06-01 §3.1). Change PIN ends the session before returning
 * to the Calculator, so coming back requires re-auth.
 */
public class AdminFragment extends Fragment {

    private static final String KEY_ADB = "adb_enabled";
    private static final String KEY_DEV = "development_settings_enabled";

    private MaterialSwitch mAdbSwitch;
    private MaterialSwitch mDevSwitch;
    private MaterialButton mOpenDev;
    private TextView mOpenDevNote;
    private TextView mStatus;
    private TextView mKeyValue;
    private TextView mStoredValue;
    private boolean mSyncing;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.activity_hidden, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View root, @Nullable Bundle savedInstanceState) {
        mStatus = root.findViewById(R.id.hidden_status);

        // Debugging card.
        final ViewGroup debugRows = root.findViewById(R.id.hidden_debug_rows);
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
        mOpenDev = root.findViewById(R.id.hidden_open_dev_button);
        mOpenDevNote = root.findViewById(R.id.hidden_open_dev_note);
        mOpenDev.setOnClickListener(v -> openDeveloperOptions());

        // Credential card.
        final ViewGroup credentialRows = root.findViewById(R.id.hidden_credential_rows);
        mKeyValue = addInfoRow(credentialRows, R.string.hidden_key_title);
        getLayoutInflater().inflate(R.layout.hidden_divider, credentialRows, true);
        mStoredValue = addInfoRow(credentialRows, R.string.hidden_stored_title);
        root.findViewById(R.id.hidden_change_pin).setOnClickListener(v -> {
            GateSession.requestRerecord();
            GateSession.end();          // returning must re-auth
            requireActivity().finish(); // back to the Calculator, which enters PIN-setup mode
        });

        // About card.
        final ViewGroup aboutRows = root.findViewById(R.id.hidden_about_rows);
        addInfoRow(aboutRows, R.string.hidden_version_title).setText(getString(
                R.string.hidden_version_fmt, BuildConfig.APP_VERSION, BuildConfig.REVISION_DATE));
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mAdbSwitch == null) return;
        syncSwitches();
        bindDeveloperOptionsAction();
        loadKeystoreDiagnostic();
    }

    // ---- row builders ------------------------------------------------------------------------

    private MaterialSwitch addSwitchRow(ViewGroup parent, int titleRes, int descRes) {
        final View row = getLayoutInflater().inflate(R.layout.hidden_row_switch, parent, false);
        ((TextView) row.findViewById(R.id.hidden_row_title)).setText(titleRes);
        ((TextView) row.findViewById(R.id.hidden_row_desc)).setText(descRes);
        final MaterialSwitch sw = row.findViewById(R.id.hidden_row_switch);
        row.setOnClickListener(v -> sw.toggle());
        parent.addView(row);
        return sw;
    }

    private TextView addInfoRow(ViewGroup parent, int titleRes) {
        final View row = getLayoutInflater().inflate(R.layout.hidden_row_info, parent, false);
        ((TextView) row.findViewById(R.id.hidden_info_title)).setText(titleRes);
        parent.addView(row);
        return row.findViewById(R.id.hidden_info_value);
    }

    // ---- adb / developer toggles -------------------------------------------------------------

    private void syncSwitches() {
        mSyncing = true;
        mAdbSwitch.setChecked(Settings.Global.getInt(
                requireContext().getContentResolver(), KEY_ADB, 0) == 1);
        mDevSwitch.setChecked(Settings.Global.getInt(
                requireContext().getContentResolver(), KEY_DEV, 0) == 1);
        mSyncing = false;
    }

    private void applySetting(String key, boolean on) {
        boolean ok;
        try {
            ok = Settings.Global.putInt(requireContext().getContentResolver(), key, on ? 1 : 0);
        } catch (SecurityException e) {
            ok = false;
        }
        showStatus(ok ? 0 : R.string.hidden_change_failed);
        syncSwitches();
    }

    private void confirmAdbOff() {
        new MaterialAlertDialogBuilder(requireContext())
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

    private void bindDeveloperOptionsAction() {
        final boolean available = requireContext().getPackageManager()
                .resolveActivity(developerOptionsIntent(), 0) != null;
        mOpenDev.setEnabled(available);
        mOpenDevNote.setText(available ? R.string.hidden_dev_open_desc
                                       : R.string.hidden_dev_open_unavailable);
    }

    private void openDeveloperOptions() {
        try {
            startActivity(developerOptionsIntent());
        } catch (ActivityNotFoundException | SecurityException e) {
            bindDeveloperOptionsAction();
        }
    }

    // ---- Keystore diagnostic -----------------------------------------------------------------

    private void loadKeystoreDiagnostic() {
        final Pbkdf2KeystoreCredential credential = GateSession.credential();
        mKeyValue.setText(R.string.hidden_key_checking);
        new Thread(() -> {
            final Pbkdf2KeystoreCredential.Diagnostics d = credential.diagnose();
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
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
