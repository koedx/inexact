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
     * Clear the main expression and the display with no evaluation and no history write, with no
     * animation. Used by openGate(); the recording flow uses the animated revealClear() instead.
     */
    void clearDisplayQuietly();

    /**
     * The gate matched. STEP-6 SEAM: clears the display and, later, launches the hidden view.
     * For step 1 the host clears the display and emits a dev-only log line.
     */
    void openGate();

    /**
     * Section 6 recording: show text (operator glyphs, or "Repeat"/"Saved"/error) in the result
     * field. Passing null or "" clears it.
     */
    void showRecordingText(CharSequence text);

    /**
     * Toggle the whole-app PIN-setup appearance: the advanced pad turns warm red and the mode
     * label shows "PIN" when on; the turquoise pad and the deg/rad label are restored when off.
     */
    void setPinModeVisuals(boolean on);

    /**
     * Run the stock circular-reveal clear (blue), anchored on the delete button so it is visually
     * identical to a normal long-press-delete clear in every orientation, then clear the display
     * with no history write, then invoke onComplete (on the UI thread, after the animation).
     */
    void revealClear(Runnable onComplete);

    /**
     * Show a modal gate dialog with a single dismiss button (reuses the host's existing
     * message-dialog path). Used for both the setup instructions ("Got it") and the error guidance
     * ("Try again"); dismissing takes no action beyond closing the dialog.
     */
    void showGateDialog(int titleRes, int messageRes, int buttonRes);
}
