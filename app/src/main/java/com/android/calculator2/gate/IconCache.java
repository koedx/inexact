/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: byte-bounded in-memory icon cache with lazy background decode for the picker
 *          (spec addendum 2026-11-06-01 §3.6). No disk cache, no temp files.
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

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Icons rendered once at a fixed 48dp ARGB_8888 and held in a byte-bounded LRU. Cap =
 * min(4 MiB, memoryClass/16) — roughly 100 icons at the cap on this low-RAM device. Decode runs on a
 * single background thread; the result posts back to the main thread. Call {@link #clear()} on session
 * lock and from onTrimMemory so a guest's icons do not linger.
 */
final class IconCache {

    interface Callback {
        void onIcon(String key, Bitmap bitmap);
    }

    private final Context appContext;
    private final int sizePx;
    private final LruCache<String, Bitmap> cache;
    private final ExecutorService decodeThread = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    IconCache(Context context) {
        this.appContext = context.getApplicationContext();
        this.sizePx = Math.round(48f * appContext.getResources().getDisplayMetrics().density);

        final ActivityManager am = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
        final int memClassBytes = am.getMemoryClass() * 1024 * 1024;
        final int capBytes = Math.min(4 * 1024 * 1024, memClassBytes / 16);

        this.cache = new LruCache<String, Bitmap>(capBytes) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    /** Synchronous cache hit, or null. Main thread. */
    Bitmap peek(String key) {
        return cache.get(key);
    }

    /**
     * Request an icon. If cached, the callback fires synchronously on this (main) thread; otherwise it
     * is decoded on the background thread and the callback posts back to the main thread. The caller
     * must verify the key still matches the (possibly recycled) view before using the bitmap.
     */
    void request(final String key, final ComponentName component, final Callback cb) {
        final Bitmap hit = cache.get(key);
        if (hit != null) {
            cb.onIcon(key, hit);
            return;
        }
        decodeThread.execute(new Runnable() {
            @Override public void run() {
                final Bitmap bmp = render(component);
                if (bmp == null) return;
                cache.put(key, bmp);
                main.post(new Runnable() {
                    @Override public void run() { cb.onIcon(key, bmp); }
                });
            }
        });
    }

    private Bitmap render(ComponentName component) {
        try {
            final PackageManager pm = appContext.getPackageManager();
            final Drawable d = pm.getActivityIcon(component);
            final Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            final Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, sizePx, sizePx);
            d.draw(c);
            return bmp;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;   // app uninstalled mid-scroll, or an icon that won't render: skip, no crash
        }
    }

    /** Drop everything. Call on session lock and onTrimMemory. */
    void clear() {
        cache.evictAll();
    }
}
