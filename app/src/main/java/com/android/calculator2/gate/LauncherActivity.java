/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: the unlocked surface — a single activity with a footer nav bar (Apps + Admin) and a
 *          persistent lock action. Replaces HiddenActivity as the gate's landing point
 *          (spec addendum 2026-11-06-01 §3.1, §3.2).
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

import android.os.Bundle;
import android.view.Menu;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.android.calculator2.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * Reached only through the gate: non-exported, no intent-filter, and onCreate finishes at once unless
 * {@link GateSession#isActive()} (set by the host's openGate()). Lands on the app picker; the admin
 * dashboard is a second nav destination, shown only to an admin session.
 *
 * Leak hygiene (unchanged from HiddenActivity): FLAG_SECURE (no screenshots, blank recents card),
 * nothing restored from a saved-instance bundle. The session persists across backgrounding and
 * rotation (configChanges in the manifest); it ends on the lock action or process death.
 */
public class LauncherActivity extends AppCompatActivity {

    private boolean mAdmin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(null);   // never restore a stale session view
        if (!GateSession.isActive() || GateSession.credential() == null) {
            finish();
            return;
        }
        mAdmin = GateSession.isAdmin();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_launcher);

        final MaterialToolbar toolbar = findViewById(R.id.launcher_toolbar);
        toolbar.inflateMenu(R.menu.launcher_menu);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_lock) {
                lockAndExit();
                return true;
            }
            return false;
        });

        final BottomNavigationView nav = findViewById(R.id.launcher_nav);
        nav.getMenu().findItem(R.id.nav_admin).setVisible(mAdmin);   // guests never see Admin
        nav.setOnItemSelectedListener(item -> {
            showDestination(item.getItemId());
            return true;
        });

        if (getSupportFragmentManager().findFragmentById(R.id.launcher_container) == null) {
            showDestination(R.id.nav_apps);   // always land on the picker
        }
    }

    private void showDestination(int itemId) {
        final Fragment f;
        final CharSequence title;
        if (itemId == R.id.nav_admin && mAdmin) {
            f = new AdminFragment();
            title = getString(R.string.launcher_nav_admin);
        } else {
            f = new AppsFragment();
            title = getString(R.string.launcher_title);
        }
        ((MaterialToolbar) findViewById(R.id.launcher_toolbar)).setTitle(title);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.launcher_container, f)
                .commit();
    }

    /** The persistent lock action: end the session and drop back to the plain calculator. */
    private void lockAndExit() {
        GateSession.end();
        finish();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.clear();   // keep nothing; a restored launch re-locks via the session check
    }
}
