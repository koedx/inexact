/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: the unlocked surface: a single activity with a footer nav bar (Apps, Settings, Admin)
 *          and a persistent lock action. The gate's landing point.
 * Created:  2026-11-06 09:44:19 UTC
 * Modified: 2026-11-10 06:00:00 UTC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.calculator2.gate;

import android.os.Bundle;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.android.calculator2.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * Reached only through the gate: non-exported, no intent-filter, and onCreate finishes at once unless
 * {@link GateSession#isActive()}. Lands on the app picker; Settings and Admin are further nav
 * destinations, shown only to an admin session for now (per-role delegation of settings arrives
 * with overlays).
 *
 * The session is a login and outlives this activity: backing out, or swiping the task away, leaves
 * it open, and {@link SessionService}'s notification leads back here. It ends only when the user
 * locks it, here or from that notification. Either way {@link GateSession#end()} runs the listener
 * registered below, so this activity closes whenever the session does.
 *
 * Leak hygiene: FLAG_SECURE (no screenshots, blank recents card), nothing restored from a
 * saved-instance bundle.
 */
public class LauncherActivity extends AppCompatActivity {

    private boolean mAdmin;
    private int mRole = GateSession.NO_ROLE;
    private boolean mListening;

    private final Runnable mOnSessionEnded = new Runnable() {
        @Override public void run() {
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(null);   // never restore a stale session view
        if (!GateSession.isActive() || GateSession.credential() == null) {
            finish();
            return;
        }
        GateSession.addEndListener(mOnSessionEnded);
        mListening = true;
        SessionService.start(this);   // idempotent; covers the service having been stopped

        mRole = GateSession.roleTag();
        mAdmin = GateSession.isAdmin();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_launcher);

        final MaterialToolbar toolbar = findViewById(R.id.launcher_toolbar);
        toolbar.inflateMenu(R.menu.launcher_menu);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_lock) {
                GateSession.end();   // the end listener finishes this activity
                return true;
            }
            return false;
        });

        final BottomNavigationView nav = findViewById(R.id.launcher_nav);
        nav.getMenu().findItem(R.id.nav_settings).setVisible(mAdmin);
        nav.getMenu().findItem(R.id.nav_admin).setVisible(mAdmin);   // guests never see Admin
        nav.setOnItemSelectedListener(item -> {
            showDestination(item.getItemId());
            return true;
        });

        if (getSupportFragmentManager().findFragmentById(R.id.launcher_container) == null) {
            showDestination(R.id.nav_apps);   // always land on the picker
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // This instance was built for one role's session. If that session is gone, or a different
        // role has signed in since, close instead of showing one role's surface to another.
        if (!GateSession.isActive() || GateSession.roleTag() != mRole) {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (mListening) {
            GateSession.removeEndListener(mOnSessionEnded);
            mListening = false;
        }
        super.onDestroy();
    }

    private void showDestination(int itemId) {
        final Fragment f;
        final CharSequence title;
        if (itemId == R.id.nav_admin && mAdmin) {
            f = new AdminFragment();
            title = getString(R.string.launcher_nav_admin);
        } else if (itemId == R.id.nav_settings && mAdmin) {
            f = new SettingsFragment();
            title = getString(R.string.launcher_nav_settings);
        } else {
            f = new AppsFragment();
            title = getString(R.string.launcher_title);
        }
        ((MaterialToolbar) findViewById(R.id.launcher_toolbar)).setTitle(title);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.launcher_container, f)
                .commit();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.clear();   // keep nothing; a restored launch re-locks via the session check
    }
}
