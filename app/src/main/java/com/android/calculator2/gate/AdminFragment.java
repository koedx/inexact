/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: admin dashboard destination — adb/developer toggles, Developer options shortcut, version,
 *          Keystore diagnostic, Change PIN, guest-slot management (blob v2 roles), and the session
 *          options (lock timeout, when custom settings go on and come off).
 * Created:  2026-11-06 09:44:19 UTC
 * Modified: 2026-11-10 09:00:00 UTC
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
import android.widget.EditText;
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

import java.util.Collections;
import java.util.Map;

/**
 * Admin-only (the launcher hides this destination for guests). Note: no onStop re-lock any more —
 * the session persists until the lock action or process death (spec addendum 2026-11-06-01 §3.1).
 * Change PIN ends the session before returning to the Calculator, so coming back requires re-auth.
 *
 * GUEST SLOTS. All {@link GateCredential#MAX_GUESTS} slots are always listed. The switch IS the
 * action: on adds a PIN (handing off to the calculator pad, which is the only place a PIN is ever
 * entered), off removes that role's credential record after confirmation. A slot's profile name
 * survives removal, so re-enabling it needs only a new PIN. Only the admin can set or change a guest
 * PIN; nothing here lets a guest do so, and the scripted provisioning path refuses guest roles
 * outright.
 *
 * Everything here that touches the Keystore, the blob or the role-name file is blocking, so state is
 * loaded on a background thread and bound on the UI thread.
 */
public class AdminFragment extends Fragment {

    private static final String KEY_ADB = "adb_enabled";
    private static final String KEY_DEV = "development_settings_enabled";

    private static final int SLOTS = 1 + GateCredential.MAX_GUESTS;

    private MaterialSwitch mAdbSwitch;
    private MaterialSwitch mDevSwitch;
    private MaterialButton mOpenDev;
    private TextView mOpenDevNote;
    private TextView mStatus;
    private TextView mKeyValue;
    private TextView mStoredValue;
    private TextView mRolesValue;
    private boolean mSyncing;

    // Guest rows, indexed by role tag (index 0 unused: the admin has no slot row).
    private final TextView[] mGuestName = new TextView[SLOTS];
    private final TextView[] mGuestStatus = new TextView[SLOTS];
    private final MaterialSwitch[] mGuestSwitch = new MaterialSwitch[SLOTS];
    private final MaterialButton[] mGuestRename = new MaterialButton[SLOTS];
    private final MaterialButton[] mGuestReset = new MaterialButton[SLOTS];
    private TextView mGuestsNote;

    private MaterialSwitch mTimeoutSwitch;
    private TextView mTimeoutLength;
    private View mTimeoutLengthRow;
    private MaterialSwitch mAutoApplySwitch;
    private MaterialSwitch mRevertSwitch;

    private RoleStore mRoles;
    private State mState;

    /** One background load: credential diagnostics, per-slot record presence, and role names. */
    private static final class State {
        final Pbkdf2KeystoreCredential.Diagnostics diag;
        final boolean namesAvailable;
        final Map<Integer, String> names;
        final boolean[] hasRecord;

        State(Pbkdf2KeystoreCredential.Diagnostics diag, boolean namesAvailable,
                Map<Integer, String> names, boolean[] hasRecord) {
            this.diag = diag;
            this.namesAvailable = namesAvailable;
            this.names = names;
            this.hasRecord = hasRecord;
        }
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.activity_hidden, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View root, @Nullable Bundle savedInstanceState) {
        mRoles = new RoleStore(requireContext());
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
        getLayoutInflater().inflate(R.layout.hidden_divider, credentialRows, true);
        mRolesValue = addInfoRow(credentialRows, R.string.hidden_roles_title);
        root.findViewById(R.id.hidden_change_pin).setOnClickListener(v -> {
            GateSession.requestRerecord();
            GateSession.end();          // returning must re-auth
            requireActivity().finish(); // back to the Calculator, which enters PIN-setup mode
        });

        // Guest access card.
        mGuestsNote = root.findViewById(R.id.hidden_guests_note);
        addGuestRows(root.findViewById(R.id.hidden_guest_rows));

        // Session card.
        final ViewGroup sessionRows = root.findViewById(R.id.hidden_session_rows);
        mTimeoutSwitch = addSwitchRow(sessionRows, R.string.session_timeout_title,
                R.string.session_timeout_desc);
        mTimeoutSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (mSyncing) return;
            SessionPrefs.setLockTimeoutEnabled(requireContext(), checked);
            syncSession();
        });
        getLayoutInflater().inflate(R.layout.hidden_divider, sessionRows, true);
        mTimeoutLengthRow = getLayoutInflater().inflate(R.layout.hidden_row_info, sessionRows, false);
        ((TextView) mTimeoutLengthRow.findViewById(R.id.hidden_info_title))
                .setText(R.string.session_timeout_length_title);
        mTimeoutLength = mTimeoutLengthRow.findViewById(R.id.hidden_info_value);
        mTimeoutLengthRow.setOnClickListener(v -> promptTimeoutLength());
        sessionRows.addView(mTimeoutLengthRow);
        getLayoutInflater().inflate(R.layout.hidden_divider, sessionRows, true);
        mAutoApplySwitch = addSwitchRow(sessionRows, R.string.session_auto_apply_title,
                R.string.session_auto_apply_desc);
        mAutoApplySwitch.setOnCheckedChangeListener((button, checked) -> {
            if (mSyncing) return;
            SessionPrefs.setApplyCustomAtSignIn(requireContext(), checked);
        });
        getLayoutInflater().inflate(R.layout.hidden_divider, sessionRows, true);
        mRevertSwitch = addSwitchRow(sessionRows, R.string.session_revert_title,
                R.string.session_revert_desc);
        mRevertSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (mSyncing) return;
            SessionPrefs.setStockAtSignOut(requireContext(), checked);
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
        syncSession();
        bindDeveloperOptionsAction();
        reload();
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

    /** One row per guest slot, always all of them, so an unused slot is visibly available. */
    private void addGuestRows(ViewGroup parent) {
        for (int role = 1; role <= GateCredential.MAX_GUESTS; role++) {
            final int slot = role;
            if (slot > 1) {
                getLayoutInflater().inflate(R.layout.hidden_divider, parent, true);
            }
            final View row = getLayoutInflater().inflate(R.layout.hidden_row_guest, parent, false);
            mGuestName[slot] = row.findViewById(R.id.hidden_guest_name);
            mGuestStatus[slot] = row.findViewById(R.id.hidden_guest_status);
            mGuestSwitch[slot] = row.findViewById(R.id.hidden_guest_switch);
            mGuestRename[slot] = row.findViewById(R.id.hidden_guest_rename);
            mGuestReset[slot] = row.findViewById(R.id.hidden_guest_reset);

            mGuestSwitch[slot].setOnCheckedChangeListener((button, checked) -> {
                if (mSyncing) return;
                if (checked) {
                    confirmRecordPin(slot);
                } else {
                    confirmRemove(slot);
                }
            });
            mGuestRename[slot].setOnClickListener(v -> promptRename(slot));
            mGuestReset[slot].setOnClickListener(v -> confirmRecordPin(slot));
            parent.addView(row);
        }
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

    // ---- session options ---------------------------------------------------------------------

    private String timeoutLabel(int minutes) {
        return minutes == 0 ? getString(R.string.session_timeout_immediately)
                : minutes == 1 ? getString(R.string.session_timeout_one_minute)
                : getString(R.string.session_timeout_minutes_fmt, minutes);
    }

    private void syncSession() {
        final boolean timeoutOn = SessionPrefs.lockTimeoutEnabled(requireContext());
        mSyncing = true;
        mTimeoutSwitch.setChecked(timeoutOn);
        mAutoApplySwitch.setChecked(SessionPrefs.applyCustomAtSignIn(requireContext()));
        mRevertSwitch.setChecked(SessionPrefs.stockAtSignOut(requireContext()));
        mSyncing = false;
        mTimeoutLength.setText(timeoutLabel(SessionPrefs.lockTimeoutMinutes(requireContext())));
        mTimeoutLengthRow.setEnabled(timeoutOn);   // the length only matters while the timeout is on
    }

    private void promptTimeoutLength() {
        final int[] choices = SessionPrefs.TIMEOUT_CHOICES;
        final CharSequence[] labels = new CharSequence[choices.length];
        final int current = SessionPrefs.lockTimeoutMinutes(requireContext());
        int checked = -1;
        for (int i = 0; i < choices.length; i++) {
            labels[i] = timeoutLabel(choices[i]);
            if (choices[i] == current) checked = i;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.session_timeout_length_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    SessionPrefs.setLockTimeoutMinutes(requireContext(), choices[which]);
                    syncSession();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.hidden_cancel, null)
                .show();
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

    // ---- guest slots -------------------------------------------------------------------------

    /**
     * Adding or resetting a guest PIN hands off to the calculator pad — the only surface where a PIN
     * is ever entered. The admin session stays open, so the pad hands straight back here on save.
     */
    private void confirmRecordPin(final int role) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.hidden_guest_record_title)
                .setMessage(getString(R.string.hidden_guest_record_message, role))
                .setPositiveButton(R.string.hidden_guest_record_confirm, (dialog, which) -> {
                    GateSession.requestGuestRecord(role);
                    requireActivity().finish();   // session deliberately NOT ended
                })
                .setNegativeButton(R.string.hidden_cancel, (dialog, which) -> bindGuests())
                .setOnCancelListener(dialog -> bindGuests())
                .show();
    }

    private void confirmRemove(final int role) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.hidden_guest_remove_title)
                .setMessage(R.string.hidden_guest_remove_message)
                .setPositiveButton(R.string.hidden_guest_remove_confirm,
                        (dialog, which) -> removeRole(role))
                .setNegativeButton(R.string.hidden_cancel, (dialog, which) -> bindGuests())
                .setOnCancelListener(dialog -> bindGuests())
                .show();
    }

    private void removeRole(int role) {
        final Pbkdf2KeystoreCredential credential = GateSession.credential();
        if (credential == null) {
            showStatus(R.string.hidden_change_failed);
            bindGuests();
            return;
        }
        credential.removeRole(role, ok -> {      // callback already lands on the UI thread
            if (!isAdded()) return;
            if (!ok) showStatus(R.string.hidden_change_failed);
            reload();
        });
    }

    private void promptRename(final int role) {
        final View body = getLayoutInflater().inflate(R.layout.dialog_guest_name, null);
        final EditText input = body.findViewById(R.id.hidden_guest_name_input);
        final String current = (mState == null) ? null : mState.names.get(role);
        input.setText(current == null ? "" : current);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.hidden_guest_name_title)
                .setView(body)
                .setPositiveButton(R.string.hidden_guest_name_save,
                        (dialog, which) -> saveName(role, input.getText().toString()))
                .setNegativeButton(R.string.hidden_cancel, null)
                .show();
    }

    private void saveName(final int role, final String name) {
        final RoleStore store = mRoles;
        new Thread(() -> {
            final boolean ok = store.setName(role, name);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                if (!ok) showStatus(R.string.hidden_guest_name_failed);
                reload();
            });
        }, "gate-profile").start();
    }

    // ---- state load / bind -------------------------------------------------------------------

    /** Live Keystore + blob + profile read, off the UI thread, then bound in one pass. */
    private void reload() {
        final Pbkdf2KeystoreCredential credential = GateSession.credential();
        if (credential == null) return;
        final RoleStore store = mRoles;
        mKeyValue.setText(R.string.hidden_key_checking);
        new Thread(() -> {
            final Pbkdf2KeystoreCredential.Diagnostics d = credential.diagnose();
            final boolean namesOk = store.encryptionAvailable();
            final Map<Integer, String> names = store.names();
            final boolean[] has = new boolean[SLOTS];
            for (int role = 1; role <= GateCredential.MAX_GUESTS; role++) {
                has[role] = credential.hasRole(role);
            }
            final State next = new State(d, namesOk, names, has);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                mState = next;
                showDiagnostic(d);
                bindGuests();
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

        mRolesValue.setText(getString(R.string.hidden_roles_fmt, d.guestCount, d.maxGuests));
    }

    private void bindGuests() {
        final State st = mState;
        final boolean namesOk = st == null || st.namesAvailable;
        final Map<Integer, String> names =
                (st == null) ? Collections.<Integer, String>emptyMap() : st.names;

        mSyncing = true;
        for (int role = 1; role <= GateCredential.MAX_GUESTS; role++) {
            final String stored = names.get(role);
            mGuestName[role].setText((stored == null || stored.isEmpty())
                    ? RoleStore.defaultName(role) : stored);

            final boolean has = st != null && st.hasRecord[role];
            mGuestSwitch[role].setChecked(has);
            mGuestRename[role].setEnabled(namesOk);
            // Reset only makes sense once a PIN exists; otherwise the switch is how you add one.
            mGuestReset[role].setEnabled(has);

            mGuestStatus[role].setText(has
                    ? getString(R.string.hidden_guest_status_set, role)
                    : getString(R.string.hidden_guest_status_unset, role));
        }
        mSyncing = false;

        mGuestsNote.setText(namesOk ? getString(R.string.hidden_guests_note)
                                    : getString(R.string.hidden_guests_names_unavailable));
    }
}
