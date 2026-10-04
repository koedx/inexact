/*
 * Copyright (C) 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.calculator2.gate;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import com.android.calculator2.BuildConfig;

/**
 * Milestone 0 stub.
 *
 * This activity is declared android:exported="false". Its only purpose right now
 * is to verify that the shell (am start) can launch a non-exported activity on
 * the target ROM, and that the Gradle build wires the gate subpackage in
 * correctly. It performs no provisioning yet: it logs and finishes immediately.
 *
 * Launch (shell can start a non-exported activity; other apps cannot):
 *   am start -n com.android.calculator/com.android.calculator2.gate.ProvisionActivity
 *
 * Note the fully-qualified class name: the ".ProvisionActivity" shorthand would
 * resolve against the applicationId (com.android.calculator), but the class lives
 * in the source package com.android.calculator2.gate.
 */
public class ProvisionActivity extends Activity {

    private static final String TAG = "InExactProvision";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "ProvisionActivity started"
                + " (APP_VERSION=" + BuildConfig.APP_VERSION
                + ", REVISION_DATE=" + BuildConfig.REVISION_DATE + ")");
        finish();
    }
}
