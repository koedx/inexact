/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: the settings catalog view: what each tracked setting holds, the Stock and Custom switch,
 *          a custom value per setting, and a fix for settings that have drifted.
 * Created:  2026-11-10 06:00:00 UTC
 * Modified: 2026-11-10 09:00:00 UTC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.calculator2.gate;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.android.calculator2.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;

/**
 * Shows every catalog entry with its current value and flags the ones that do not hold what is
 * expected of them. "Expected" is stock, or the signed-in role's custom value for an entry while
 * Custom is in use.
 *
 * NOTHING IS WRITTEN UNLESS ASKED. Opening this screen only reads. Values change when the user
 * switches between Stock and Custom, saves a custom value while Custom is in use, or presses Fix on
 * a row.
 *
 * Kinds of "does not match", kept apart because they mean different things: different (set, and
 * not the expected value; neutral tone), invalid (breaks a required format; error tone), and not
 * set (the key is absent, so the platform default applies and no claim is made about what that
 * is).
 *
 * A write the system refuses is shown on its row and is not retried: the answer would be the same.
 */
public class SettingsFragment extends Fragment {

    private static final class Row {
        final SettingEntry entry;
        final TextView current;
        final TextView expected;
        final TextView custom;
        final TextView note;
        final TextView badgeDrift;
        final View badgeInvalid;
        final TextView badgeNeutral;
        final MaterialButton edit;
        final MaterialButton fix;

        Row(SettingEntry entry, View view) {
            this.entry = entry;
            current = view.findViewById(R.id.settings_row_current);
            expected = view.findViewById(R.id.settings_row_expected);
            custom = view.findViewById(R.id.settings_row_custom);
            note = view.findViewById(R.id.settings_row_note);
            badgeDrift = view.findViewById(R.id.settings_badge_drift);
            badgeInvalid = view.findViewById(R.id.settings_badge_invalid);
            badgeNeutral = view.findViewById(R.id.settings_badge_neutral);
            edit = view.findViewById(R.id.settings_row_edit);
            fix = view.findViewById(R.id.settings_row_fix);
        }
    }

    private final ArrayList<Row> mRows = new ArrayList<>();
    private TextView mSummaryTitle;
    private TextView mSummaryDetail;
    private MaterialButtonToggleGroup mModeGroup;
    private MaterialButton mModeCustom;
    private TextView mModeNote;
    private boolean mSyncing;

    private SettingsIo mIo;
    private OverlayStore mStore;
    private int mRole = GateSession.NO_ROLE;

    /** The engine reports changes on whichever thread made them; rebind on the UI thread. */
    private final Runnable mOnOverlayChanged = new Runnable() {
        @Override public void run() {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (isAdded()) refresh();
            });
        }
    };

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View root, @Nullable Bundle savedInstanceState) {
        mIo = new SystemSettingsIo(requireContext().getContentResolver());
        mStore = new OverlayStore(requireContext());
        mRole = GateSession.roleTag();

        mSummaryTitle = root.findViewById(R.id.settings_summary_title);
        mSummaryDetail = root.findViewById(R.id.settings_summary_detail);
        mModeGroup = root.findViewById(R.id.settings_mode_group);
        mModeCustom = root.findViewById(R.id.settings_mode_custom);
        mModeNote = root.findViewById(R.id.settings_mode_note);
        mModeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (mSyncing || !isChecked) return;
            if (checkedId == R.id.settings_mode_custom) {
                OverlayEngine.apply(mIo, mStore, mRole);
            } else {
                OverlayEngine.revert(mIo, mStore);
            }
        });

        mRows.clear();
        final ViewGroup sections = root.findViewById(R.id.settings_sections);
        final LayoutInflater inflater = getLayoutInflater();
        for (SettingsCatalog.Section section : SettingsCatalog.SECTIONS) {
            final View sectionView = inflater.inflate(R.layout.settings_section, sections, false);
            ((TextView) sectionView.findViewById(R.id.settings_section_title))
                    .setText(section.titleRes);
            final ViewGroup rows = sectionView.findViewById(R.id.settings_section_rows);
            for (int i = 0; i < section.entries.length; i++) {
                final SettingEntry entry = section.entries[i];
                if (i > 0) {
                    inflater.inflate(R.layout.hidden_divider, rows, true);
                }
                final View rowView = inflater.inflate(R.layout.settings_row, rows, false);
                ((TextView) rowView.findViewById(R.id.settings_row_title)).setText(entry.titleRes);
                ((TextView) rowView.findViewById(R.id.settings_row_summary))
                        .setText(entry.summaryRes);
                ((TextView) rowView.findViewById(R.id.settings_row_address))
                        .setText(entry.address());
                rows.addView(rowView);
                final Row row = new Row(entry, rowView);
                row.edit.setOnClickListener(v -> promptCustom(entry));
                row.fix.setOnClickListener(v -> OverlayEngine.fix(mIo, mStore, mRole, entry));
                mRows.add(row);
            }
            sections.addView(sectionView);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        OverlayEngine.addListener(mOnOverlayChanged);
    }

    @Override
    public void onStop() {
        OverlayEngine.removeListener(mOnOverlayChanged);
        super.onStop();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    /** Re-read every entry, bind each row, then the switch and the summary. */
    private void refresh() {
        if (mSummaryTitle == null) return;
        final boolean customActive = OverlayEngine.isCustomActive(mStore, mRole);
        int drift = 0;
        int invalid = 0;
        int unset = 0;
        boolean problem = false;
        for (Row row : mRows) {
            String actual = null;
            boolean readable = true;
            try {
                actual = mIo.read(row.entry);
            } catch (RuntimeException e) {
                readable = false;    // shown as unreadable; never guessed at
            }
            final String expected = OverlayEngine.expected(row.entry, mStore, mRole);
            final SettingCompare.State state = readable
                    ? SettingCompare.evaluate(row.entry, actual, expected) : null;
            bind(row, actual, expected, state, customActive);
            if (state == null || state == SettingCompare.State.UNSET) {
                unset++;
            } else if (state == SettingCompare.State.DRIFT) {
                drift++;
            } else if (state == SettingCompare.State.INVALID) {
                invalid++;
            }
            if (OverlayEngine.lastFailure(row.entry) != null) problem = true;
        }

        final boolean hasCustom = mStore.hasAny(mRole);
        mSyncing = true;
        mModeGroup.check(customActive ? R.id.settings_mode_custom : R.id.settings_mode_stock);
        mModeCustom.setEnabled(hasCustom || customActive);
        mSyncing = false;
        mModeNote.setText(problem ? R.string.settings_mode_note_problem
                : customActive ? R.string.settings_mode_note_custom
                : hasCustom ? R.string.settings_mode_note_stock
                : R.string.settings_mode_note_none);

        final int total = mRows.size();
        final int flagged = drift + invalid + unset;
        mSummaryTitle.setText(flagged == 0
                ? getString(R.string.settings_summary_all_stock, total)
                : getString(R.string.settings_summary_differ, flagged, total));
        mSummaryDetail.setText(getString(R.string.settings_summary_counts, drift, invalid, unset));
    }

    /** A raw value with its meaning beside it when the entry names one: "2 (Dark)". */
    private String display(SettingEntry entry, String value) {
        final int label = entry.labelFor(value);
        return label == 0 ? value
                : getString(R.string.settings_value_labeled_fmt, value, getString(label));
    }

    /** @param state the comparison result, or null if the value could not be read. */
    private void bind(Row row, String actual, String expected, SettingCompare.State state,
            boolean customActive) {
        final SettingEntry entry = row.entry;
        final boolean unreadable = state == null;
        final String custom = mStore.custom(mRole, entry);
        final boolean expectsCustom = customActive && custom != null;

        if (unreadable) {
            row.current.setText(R.string.settings_current_unreadable);
        } else if (actual == null) {
            row.current.setText(R.string.settings_current_unset);
        } else if (actual.isEmpty()) {
            row.current.setText(R.string.settings_current_empty);
        } else {
            row.current.setText(getString(R.string.settings_current_fmt, display(entry, actual)));
        }

        // What was expected, shown only on a mismatch. When the expectation is the custom value,
        // the custom line below already says it.
        final boolean matches = state == SettingCompare.State.STOCK;
        if (matches || unreadable || expectsCustom
                || entry.compare == SettingEntry.Compare.PATTERN) {
            row.expected.setVisibility(View.GONE);
        } else {
            row.expected.setVisibility(View.VISIBLE);
            if (entry.compare == SettingEntry.Compare.SUPERSET) {
                row.expected.setText(getString(R.string.settings_missing_fmt, TextUtils.join(", ",
                        SettingCompare.missingTokens(entry, actual, expected))));
            } else {
                row.expected.setText(getString(R.string.settings_stock_fmt,
                        display(entry, entry.stock)));
            }
        }

        if (custom == null) {
            row.custom.setVisibility(View.GONE);
        } else {
            row.custom.setVisibility(View.VISIBLE);
            row.custom.setText(getString(R.string.settings_custom_fmt, display(entry, custom)));
        }

        final SettingsIo.Result failure = OverlayEngine.lastFailure(entry);
        if (failure != null) {
            row.note.setVisibility(View.VISIBLE);
            row.note.setText(failure == SettingsIo.Result.REFUSED
                    ? R.string.settings_note_refused : R.string.settings_note_failed);
        } else if (!entry.writable()) {
            row.note.setVisibility(View.VISIBLE);
            row.note.setText(R.string.settings_note_tier2);
        } else {
            row.note.setVisibility(View.GONE);
        }

        final boolean drift = state == SettingCompare.State.DRIFT;
        row.badgeDrift.setVisibility(drift ? View.VISIBLE : View.GONE);
        if (drift) {
            row.badgeDrift.setText(expectsCustom ? R.string.settings_badge_not_applied
                                                 : R.string.settings_badge_drift);
        }
        row.badgeInvalid.setVisibility(
                state == SettingCompare.State.INVALID ? View.VISIBLE : View.GONE);
        final boolean unset = state == SettingCompare.State.UNSET;
        final boolean showsCustom = matches && expectsCustom;
        row.badgeNeutral.setVisibility(
                unreadable || unset || showsCustom ? View.VISIBLE : View.GONE);
        if (unreadable) {
            row.badgeNeutral.setText(R.string.settings_badge_unreadable);
        } else if (unset) {
            row.badgeNeutral.setText(R.string.settings_badge_unset);
        } else if (showsCustom) {
            row.badgeNeutral.setText(R.string.settings_badge_custom);
        }

        row.edit.setVisibility(entry.overlayable ? View.VISIBLE : View.GONE);
        final boolean fixable = !unreadable && !matches && entry.writable()
                && entry.compare != SettingEntry.Compare.PATTERN;
        row.fix.setVisibility(fixable ? View.VISIBLE : View.GONE);
    }

    // ---- custom value dialog ---------------------------------------------------------------------

    private void promptCustom(final SettingEntry entry) {
        final String custom = mStore.custom(mRole, entry);
        final String shown = custom != null ? custom : entry.stock;
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(entry.titleRes)
                .setNegativeButton(R.string.hidden_cancel, null)
                .setNeutralButton(R.string.settings_use_stock,
                        (dialog, which) -> saveCustom(entry, null));

        if (entry.choiceValues != null) {
            final CharSequence[] labels = new CharSequence[entry.choiceValues.length];
            int checked = -1;
            for (int i = 0; i < labels.length; i++) {
                labels[i] = getString(entry.choiceLabels[i]);
                if (entry.choiceValues[i].equals(shown)) checked = i;
            }
            builder.setSingleChoiceItems(labels, checked, (dialog, which) -> {
                saveCustom(entry, entry.choiceValues[which]);
                dialog.dismiss();
            });
        } else {
            final View body = getLayoutInflater().inflate(R.layout.dialog_setting_value, null);
            final EditText input = body.findViewById(R.id.settings_value_input);
            input.setText(shown);
            builder.setView(body).setPositiveButton(R.string.settings_save,
                    (dialog, which) -> saveCustom(entry, input.getText().toString().trim()));
        }
        builder.show();
    }

    /** Null clears the custom value. The engine writes it at once if Custom is in use. */
    private void saveCustom(SettingEntry entry, String value) {
        if (!OverlayEngine.setCustom(mIo, mStore, mRole, entry, value)) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.settings_value_rejected)
                    .setPositiveButton(R.string.settings_ok, null)
                    .show();
        }
    }
}
