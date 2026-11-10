/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: settings catalog and drift view. Slice 1 of the dashboard settings feature: read and
 *          compare only (spec addendum 2026-11-09-01 §13).
 * Created:  2026-11-10 06:00:00 UTC
 * Modified: 2026-11-10 06:00:00 UTC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.calculator2.gate;

import android.content.ContentResolver;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.android.calculator2.R;

import java.util.ArrayList;

/**
 * Shows every catalog entry with its current value and flags the ones that do not match stock.
 *
 * READ ONLY. This slice reads settings and compares them; it writes nothing, so it cannot change
 * the tablet's state and needs no permission beyond what any app has. Values are re-read each time
 * the screen is shown.
 *
 * Three kinds of "does not match" are kept apart, because they mean different things:
 * drift (set, and different: expected whenever an overlay is in use, so shown in a neutral tone),
 * invalid (a value that breaks a required format: a real error, shown as one), and not set (the key
 * is absent, so the platform default applies and no claim is made about what that is).
 */
public class SettingsFragment extends Fragment {

    /** A value that could not be read at all; kept apart from the comparison states. */
    private static final int UNREADABLE = -1;

    private static final class Row {
        final SettingEntry entry;
        final TextView current;
        final TextView expected;
        final View badgeDrift;
        final View badgeInvalid;
        final TextView badgeNeutral;

        Row(SettingEntry entry, View view) {
            this.entry = entry;
            current = view.findViewById(R.id.settings_row_current);
            expected = view.findViewById(R.id.settings_row_expected);
            badgeDrift = view.findViewById(R.id.settings_badge_drift);
            badgeInvalid = view.findViewById(R.id.settings_badge_invalid);
            badgeNeutral = view.findViewById(R.id.settings_badge_neutral);
        }
    }

    private final ArrayList<Row> mRows = new ArrayList<>();
    private TextView mSummaryTitle;
    private TextView mSummaryDetail;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View root, @Nullable Bundle savedInstanceState) {
        mSummaryTitle = root.findViewById(R.id.settings_summary_title);
        mSummaryDetail = root.findViewById(R.id.settings_summary_detail);

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
                mRows.add(new Row(entry, rowView));
            }
            sections.addView(sectionView);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    /** Re-read every entry, bind each row, then the summary. */
    private void refresh() {
        if (mSummaryTitle == null) return;
        final ContentResolver resolver = requireContext().getContentResolver();
        int drift = 0;
        int invalid = 0;
        int unset = 0;
        for (Row row : mRows) {
            String actual = null;
            boolean readable = true;
            try {
                actual = read(resolver, row.entry);
            } catch (RuntimeException e) {
                readable = false;    // shown as unreadable; never guessed at
            }
            if (!readable) {
                bind(row, null, UNREADABLE);
                unset++;
                continue;
            }
            final SettingCompare.State state = SettingCompare.evaluate(row.entry, actual);
            bind(row, actual, state.ordinal());
            switch (state) {
                case DRIFT:   drift++;   break;
                case INVALID: invalid++; break;
                case UNSET:   unset++;   break;
                default:                 break;
            }
        }

        final int total = mRows.size();
        final int flagged = drift + invalid + unset;
        mSummaryTitle.setText(flagged == 0
                ? getString(R.string.settings_summary_all_stock, total)
                : getString(R.string.settings_summary_differ, flagged, total));
        mSummaryDetail.setText(getString(R.string.settings_summary_counts, drift, invalid, unset));
    }

    private static String read(ContentResolver resolver, SettingEntry entry) {
        switch (entry.namespace) {
            case SECURE: return Settings.Secure.getString(resolver, entry.key);
            case GLOBAL: return Settings.Global.getString(resolver, entry.key);
            case SYSTEM:
            default:     return Settings.System.getString(resolver, entry.key);
        }
    }

    /** @param stateOrdinal a {@link SettingCompare.State} ordinal, or {@link #UNREADABLE}. */
    private void bind(Row row, String actual, int stateOrdinal) {
        final boolean unreadable = stateOrdinal == UNREADABLE;
        final SettingCompare.State state =
                unreadable ? null : SettingCompare.State.values()[stateOrdinal];

        if (unreadable) {
            row.current.setText(R.string.settings_current_unreadable);
        } else if (actual == null) {
            row.current.setText(R.string.settings_current_unset);
        } else if (actual.isEmpty()) {
            row.current.setText(R.string.settings_current_empty);
        } else {
            row.current.setText(getString(R.string.settings_current_fmt, actual));
        }

        // What was expected, shown only when the value does not match.
        final boolean matches = state == SettingCompare.State.STOCK;
        if (matches || unreadable) {
            row.expected.setVisibility(View.GONE);
        } else {
            row.expected.setVisibility(View.VISIBLE);
            switch (row.entry.compare) {
                case SUPERSET:
                    row.expected.setText(getString(R.string.settings_missing_fmt,
                            TextUtils.join(", ", SettingCompare.missingTokens(
                                    row.entry, actual, row.entry.stock))));
                    break;
                case PATTERN:
                    row.expected.setText(R.string.settings_expected_format);
                    break;
                case EXACT:
                default:
                    row.expected.setText(getString(R.string.settings_stock_fmt, row.entry.stock));
                    break;
            }
        }

        row.badgeDrift.setVisibility(state == SettingCompare.State.DRIFT ? View.VISIBLE : View.GONE);
        row.badgeInvalid.setVisibility(
                state == SettingCompare.State.INVALID ? View.VISIBLE : View.GONE);
        final boolean neutral = unreadable || state == SettingCompare.State.UNSET;
        row.badgeNeutral.setVisibility(neutral ? View.VISIBLE : View.GONE);
        if (neutral) {
            row.badgeNeutral.setText(unreadable ? R.string.settings_badge_unreadable
                                                : R.string.settings_badge_unset);
        }
    }
}
