/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: keeps a signed-in session alive and reachable. A foreground service whose ongoing
 *          notification is the way back to the unlocked surface and one of the two ways to lock it.
 * Created:  2026-11-10 06:00:00 UTC
 * Modified: 2026-11-10 06:00:00 UTC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.calculator2.gate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.IBinder;

import com.android.calculator2.R;

/**
 * Runs for exactly as long as a session is open (spec addendum 2026-11-09-01 §9).
 *
 * WHY A SERVICE. A session is a login that should last while the tablet is in use, but without a
 * foreground service a swipe from recents kills the process on this hardware and the session with
 * it. A foreground service keeps the process alive, so the session ends when the user says so and
 * not when the task list is tidied.
 *
 * ENDING IS MANUAL. The session ends from the in-app lock action or from this notification's Lock
 * action, and from nothing else: there is no timeout and removing the task does not lock. Both
 * paths call {@link GateSession#end()}, whose listeners stop this service and close the surface, so
 * the notification, the session flag and the surface always go away together.
 *
 * NO SESSION, NO SERVICE. The service is started only by a successful gate match and stops the
 * moment the session ends, so a locked tablet shows no notification. Whoever can see it has already
 * signed in. It is also kept off a secure lock screen ({@link Notification#VISIBILITY_SECRET}).
 *
 * If the system kills the process anyway, the session state dies with it and the gate is locked.
 * {@code START_NOT_STICKY} keeps the service from coming back without a session behind it.
 *
 * No logging, like everything on the gate path.
 */
public final class SessionService extends Service {

    private static final String ACTION_LOCK = "com.android.calculator2.gate.action.LOCK";
    private static final String CHANNEL_ID = "status";
    private static final int NOTIFICATION_ID = 1;

    private final Runnable mOnSessionEnded = new Runnable() {
        @Override public void run() {
            stopForeground(true);
            stopSelf();
        }
    };

    /** Start (or keep) the service for the session that has just begun. Safe to call repeatedly. */
    public static void start(Context context) {
        context.startForegroundService(new Intent(context, SessionService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        GateSession.addEndListener(mOnSessionEnded);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Always promote first: a service started with startForegroundService must do so promptly,
        // including on the Lock path and on a stray start with no session behind it.
        startForeground(NOTIFICATION_ID, buildNotification());

        if (intent != null && ACTION_LOCK.equals(intent.getAction())) {
            GateSession.end();              // listeners stop this service and close the surface
        }
        if (!GateSession.isActive()) {      // locked just now, or started with nothing to keep
            stopForeground(true);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        GateSession.removeEndListener(mOnSessionEnded);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;   // not bindable
    }

    private Notification buildNotification() {
        final NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            final NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.session_channel_name), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            manager.createNotificationChannel(channel);
        }

        // Tap: back to the unlocked surface. CLEAR_TOP + SINGLE_TOP reuses the instance if the
        // task still has one; NEW_TASK covers the task having been swiped away.
        final PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, LauncherActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        final PendingIntent lock = PendingIntent.getForegroundService(this, 1,
                new Intent(this, SessionService.class).setAction(ACTION_LOCK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_session_24)
                .setContentTitle(getString(R.string.session_notification_title))
                .setContentText(getString(R.string.session_notification_text))
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat_session_24),
                        getString(R.string.launcher_action_lock), lock).build())
                .build();
    }
}
