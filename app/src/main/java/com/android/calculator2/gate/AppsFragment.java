/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: the app picker destination — searchable, label-sorted launchable apps; tap to launch.
 *          No gate/credential/privileged logic (spec addendum 2026-11-06-01 §3.5).
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

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.calculator2.R;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Launch-only for this slice; panels and pinning arrive with the admin edit mode. */
public class AppsFragment extends Fragment {

    private IconCache mIcons;
    private AppPickerAdapter mAdapter;
    private final List<AppEntry> mAll = new ArrayList<>();
    private final ExecutorService mLoader = Executors.newSingleThreadExecutor();

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.launcher, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mIcons = new IconCache(requireContext());
        mAdapter = new AppPickerAdapter(mIcons, this::launch);

        final RecyclerView list = view.findViewById(R.id.app_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(mAdapter);

        final TextInputEditText search = view.findViewById(R.id.search_field);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                mAdapter.submit(AppRepository.filter(mAll, e.toString()));
            }
        });

        loadApps();
    }

    private void loadApps() {
        mLoader.execute(() -> {
            final List<AppEntry> loaded = AppRepository.loadAll(requireContext().getApplicationContext());
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                mAll.clear();
                mAll.addAll(loaded);
                mAdapter.submit(mAll);
            });
        });
    }

    private void launch(AppEntry entry) {
        final Intent i = requireContext().getPackageManager()
                .getLaunchIntentForPackage(entry.packageName);
        if (i != null) {
            startActivity(i);
        } else {
            // No default launch intent (rare): fall back to the resolved component.
            try {
                startActivity(new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(entry.component()));
            } catch (RuntimeException e) {
                Toast.makeText(requireContext(), R.string.launcher_launch_failed,
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mIcons != null) mIcons.clear();   // don't let a session's icons linger
    }
}
