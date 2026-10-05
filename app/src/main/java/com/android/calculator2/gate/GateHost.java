/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.calculator2.gate;

import android.content.Context;

import com.android.calculator2.Evaluator;

/**
 * The surface the gate uses to reach the host Activity, so the gate classes never touch AOSP
 * view fields directly. Implemented by Calculator.
 */
public interface GateHost {

    /** Context for resource/string lookups (the host Activity). */
    Context context();

    /** The shared Evaluator; the gate reads the raw main-expression digits through it. */
    Evaluator evaluator();

    /**
     * Clear the main expression and the display with no evaluation and no history write.
     * (Spec: "never evaluate, never write history, clear the display when the hidden view opens.")
     */
    void clearDisplayQuietly();

    /**
     * The gate matched. STEP-6 SEAM: clears the display and, later, launches the hidden view.
     * For step 1 the host clears the display and emits a dev-only log line.
     */
    void openGate();

    /**
     * Section 6 recording: show text (operator glyphs, or a short prompt) in the result field.
     * Passing null or "" clears it.
     */
    void showRecordingText(CharSequence text);

    /** Section 6 recording: toggle the dot indicator on the '=' key. */
    void setRecordingIndicator(boolean on);
}
