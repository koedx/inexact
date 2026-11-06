/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: enumerate and filter launchable apps for the picker. Pure PackageManager queries; no gate,
 *          credential, or privileged logic. (spec addendum 2026-11-06-01 §3.5)
 * Created:  2026-11-06 09:27:32 UTC
 * Modified: 2026-11-06 09:27:32 UTC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.calculator2.gate;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Reads the set of launchable activities from PackageManager and provides case-insensitive label
 * search. Call {@link #loadAll(Context)} off the main thread — label loading touches resources for
 * every app.
 *
 * Target is API 29, below the API 30 package-visibility restrictions, so a plain
 * queryIntentActivities returns every launcher-visible app with no {@code <queries>} manifest entry
 * (unverified on this ROM — confirm the full list appears).
 */
final class AppRepository {

    private AppRepository() {}

    /** All launchable apps, label-sorted (locale collation). Background-thread call. */
    static List<AppEntry> loadAll(Context context) {
        final PackageManager pm = context.getPackageManager();
        final Intent launcherIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        final List<ResolveInfo> resolved = pm.queryIntentActivities(launcherIntent, 0);

        final String selfPkg = context.getPackageName();
        final List<AppEntry> out = new ArrayList<>(resolved.size());
        for (ResolveInfo ri : resolved) {
            if (ri.activityInfo == null) continue;
            final String pkg = ri.activityInfo.packageName;
            if (selfPkg.equals(pkg)) continue;   // never list ourselves
            final CharSequence label = ri.loadLabel(pm);
            out.add(new AppEntry(
                    label != null ? label.toString() : pkg,
                    pkg,
                    ri.activityInfo.name));
        }

        final Collator collator = Collator.getInstance(Locale.getDefault());
        Collections.sort(out, new Comparator<AppEntry>() {
            @Override public int compare(AppEntry a, AppEntry b) {
                return collator.compare(a.label, b.label);
            }
        });
        return out;
    }

    /** Case/locale-insensitive label substring filter. Empty query returns the input unchanged. */
    static List<AppEntry> filter(List<AppEntry> all, String query) {
        if (query == null) return all;
        final String q = query.trim().toLowerCase(Locale.getDefault());
        if (q.isEmpty()) return all;
        final List<AppEntry> out = new ArrayList<>();
        for (AppEntry e : all) {
            if (e.label.toLowerCase(Locale.getDefault()).contains(q)) {
                out.add(e);
            }
        }
        return out;
    }
}
