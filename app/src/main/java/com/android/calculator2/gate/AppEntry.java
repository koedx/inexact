/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: immutable model for one launchable app in the picker (spec addendum 2026-11-06-01 §3.5).
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

import android.content.ComponentName;

/** One launchable activity: its display label and the component to start. Identity is the component. */
final class AppEntry {

    final String label;
    final String packageName;
    final String className;

    AppEntry(String label, String packageName, String className) {
        this.label = label;
        this.packageName = packageName;
        this.className = className;
    }

    ComponentName component() {
        return new ComponentName(packageName, className);
    }

    /** Stable cache key for the icon (component-unique). */
    String key() {
        return packageName + "/" + className;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AppEntry)) return false;
        final AppEntry other = (AppEntry) o;
        return packageName.equals(other.packageName) && className.equals(other.className);
    }

    @Override
    public int hashCode() {
        return 31 * packageName.hashCode() + className.hashCode();
    }
}
