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
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.android.calculator2.R;

import java.util.ArrayList;

/**
 * Scripted provisioning entry point (spec §8 path a; addendum 2026-10-09-01 §5.1).
 *
 * Access control is three layers (spec §8, corrected): the activity is declared
 * android:exported="true" but guarded by android:permission="android.permission.DUMP" (a permission
 * only the shell holds; a third-party app cannot declare or hold it) with NO intent-filter, so it is
 * reachable only by explicit component name from the shell. Non-exported does NOT work on this ROM —
 * the shell (uid 2000) is denied "not exported from uid ...". The inner check here is the arming
 * marker: no marker -> finish silently.
 *
 * Launch (fully-qualified class; the ".ProvisionActivity" shorthand would resolve against the
 * applicationId com.android.calculator, but the class lives in com.android.calculator2.gate):
 *   am start -n com.android.calculator/com.android.calculator2.gate.ProvisionActivity \
 *            --es cfg_a &lt;digits&gt; --es cfg_b &lt;ops&gt;
 *
 * Extras are never logged (spec §8: only "(has extras)" may appear in the system log); on any error
 * this activity finishes without echoing a value.
 */
public class ProvisionActivity extends Activity {

    private static final String TAG = "InExactProvision";

    private static final String EXTRA_DIGITS = "cfg_a";
    private static final String EXTRA_OPS    = "cfg_b";

    private static final int MIN_DIGITS = 3;   // match PinRecorder (addendum §3)
    private static final int MIN_OPS    = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // (c) arming-marker check: provisioning is accepted only while armed.
        if (!ArmingMarker.isSet(this)) {
            finish();
            return;
        }

        final Intent intent = getIntent();
        final String digits = (intent == null) ? null : intent.getStringExtra(EXTRA_DIGITS);
        final String opStr  = (intent == null) ? null : intent.getStringExtra(EXTRA_OPS);
        if (digits == null || digits.isEmpty() || opStr == null || opStr.isEmpty()) {
            finish();
            return;
        }

        // cfg_a must be pure decimal digits (match what the matcher reads from the display).
        if (!isAllDigits(digits) || digits.length() < MIN_DIGITS) {
            finish();
            return;
        }

        final int[] opIds = parseOps(opStr);   // null on any unmappable char (no echo)
        if (opIds == null || opIds.length < MIN_OPS) {
            finish();
            return;
        }

        // Save asynchronously; the activity stays alive until the callback (it has no UI of its own).
        final GateCredential credential = new Pbkdf2KeystoreCredential(getApplicationContext());
        credential.save(digits, opIds, new GateCredential.ResultCallback() {
            @Override
            public void onResult(boolean ok) {
                if (ok) {
                    ArmingMarker.clear(ProvisionActivity.this);   // disarm after a durable save
                    Log.i(TAG, "provisioned");                    // names no values
                }
                // On failure (e.g. no hardware-backed Keystore): leave the marker so a retry is
                // possible; finish without logging the attempt.
                finish();
            }
        });
    }

    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /**
     * Map a glyph string to raw op key-ids (addendum §5.2). Whitespace ignored; any unmappable char
     * aborts (returns null) with no echo. Output ids are the R.id.op_* constants KeyMaps.isBinary
     * accepts — the same ones the credential maps to stable codes internally.
     */
    private static int[] parseOps(String raw) {
        final ArrayList<Integer> out = new ArrayList<>();
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if (Character.isWhitespace(c)) continue;
            final int id;
            switch (c) {
                case '+':
                    id = R.id.op_add;
                    break;
                case '-':
                    id = R.id.op_sub;
                    break;
                case '*':
                case 'x':
                case 'X':
                case '\u00D7':   // ×
                    id = R.id.op_mul;
                    break;
                case '/':
                case '\u00F7':   // ÷
                    id = R.id.op_div;
                    break;
                case '^':
                    id = R.id.op_pow;
                    break;
                default:
                    return null; // unmappable -> abort, no echo of the value
            }
            out.add(id);
        }
        final int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = out.get(i);
        }
        return a;
    }
}
