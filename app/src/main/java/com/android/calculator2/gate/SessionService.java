/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: keeps a signed-in session alive and reachable. A foreground service whose ongoing
 *          notification is the way back to the unlocked surface, the Stock/Custom settings switch,
 *          and one of the ways to sign out. Also owns the optional lock timeout.
 * Created:  2026-11-10 06:00:00 UTC
 * Modified: 2026-11-10 09:00:00 UTC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.calculator2.gate;

import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import com.android.calculator2.R;

/**
 * Runs for exactly as long as a session is open.
 *
 * WHY A SERVICE. A session is a login that should last while the tablet is in use, but without a
 * foreground service a swipe from recents kills the process on this hardware and the session with
 * it. A foreground service keeps the process alive, so the session ends when the user says so and
 * not when the task list is tidied.
 *
 * HOW A SESSION ENDS. By hand: the in-app lock action, or this notification's Lock action. And,
 * only if the admin has turned it on, by the LOCK TIMEOUT: once the tablet has been locked for the
 * chosen time, the session ends on its own. Every path calls {@link GateSession#end()}, whose
 * listeners stop this service and close the surface, so the notification, the session flag and the
 * surface always go away together.
 *
 * THE LOCK TIMEOUT. The clock starts when the screen turns off, which is when this tablet locks
 * (give or take the system's own lock delay). A length of 0 ends the session at that moment.
 * Otherwise an alarm is set; when it comes due the session ends unless the user is demonstrably
 * back (screen on and past the lock screen). Unlocking in time cancels it. Unlocking after it was
 * due, if the alarm was held back by the system, ends the session at that unlock.
 *
 * SETTINGS. If the session's role has custom values, the notification carries a second action that
 * switches the tracked settings between Stock and Custom. When the session ends, settings return to
 * stock unless the admin has turned that off.
 *
 * NO SESSION, NO SERVICE. The service is started only by a successful gate match and stops the
 * moment the session ends, so a locked tablet shows no notification. It is also kept off a secure
 * lock screen ({@link Notification#VISIBILITY_SECRET}).
 *
 * If the system kills the process anyway, the session state dies with it and the gate is locked.
 * {@code START_NOT_STICKY} keeps the service from coming back without a session behind it, and
 * {@link OverlayEngine#reconcileAsync} returns settings to stock at the next launch.
 *
 * No logging, like everything on the gate path.
 */
public final class SessionService extends Service {

    private static final String ACTION_LOCK = "com.android.calculator2.gate.action.LOCK";
    private static final String ACTION_MODE = "com.android.calculator2.gate.action.MODE";
    private static final String ACTION_TIMEOUT = "com.android.calculator2.gate.action.TIMEOUT";
    private static final String CHANNEL_ID = "status";
    private static final int NOTIFICATION_ID = 1;

    private SettingsIo mIo;
    private OverlayStore mStore;
    private boolean mReceiverRegistered;
    /** Elapsed-realtime moment the lock timeout comes due, or 0 if none is pending. */
    private long mDeadline;

    private final Runnable mOnSessionEnded = new Runnable() {
        @Override public void run() {
            cancelTimeout();
            if (SessionPrefs.stockAtSignOut(SessionService.this)) {
                OverlayEngine.revert(mIo, mStore);
            }
            stopForeground(true);
            stopSelf();
        }
    };

    private final Runnable mOnOverlayChanged = new Runnable() {
        @Override public void run() {
            if (!GateSession.isActive()) return;
            final NotificationManager manager = getSystemService(NotificationManager.class);
            manager.notify(NOTIFICATION_ID, buildNotification());   // refresh the Stock/Custom line
        }
    };

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            final String action = intent == null ? null : intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                onScreenOff();
            } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                onUserPresent();
            }
        }
    };

    /**
     * A role has just signed in: settle the tracked settings for it (another role's custom values
     * come off; this role's go on if the admin asked for that), then start the service.
     */
    public static void begin(Context context) {
        final Context app = context.getApplicationContext();
        try {
            OverlayEngine.onSignIn(new SystemSettingsIo(app.getContentResolver()),
                    new OverlayStore(app), GateSession.roleTag(),
                    SessionPrefs.applyCustomAtSignIn(app));
        } catch (RuntimeException e) {
            // Settings are a convenience; they must never stand between a match and the session.
        }
        start(context);
    }

    /** Start (or keep) the service for the open session. Safe to call repeatedly. */
    public static void start(Context context) {
        context.startForegroundService(new Intent(context, SessionService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mIo = new SystemSettingsIo(getContentResolver());
        mStore = new OverlayStore(this);
        GateSession.addEndListener(mOnSessionEnded);
        OverlayEngine.addListener(mOnOverlayChanged);

        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(mScreenReceiver, filter);   // system broadcasts only
        mReceiverRegistered = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Always promote first: a service started with startForegroundService must do so promptly,
        // including on the Lock path and on a stray start with no session behind it.
        startForeground(NOTIFICATION_ID, buildNotification());

        final String action = intent == null ? null : intent.getAction();
        if (ACTION_LOCK.equals(action)) {
            GateSession.end();              // listeners stop this service and close the surface
        } else if (ACTION_MODE.equals(action) && GateSession.isActive()) {
            toggleMode();
        } else if (ACTION_TIMEOUT.equals(action)) {
            onTimeoutDue();
        }
        if (!GateSession.isActive()) {      // ended just now, or started with nothing to keep
            stopForeground(true);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        cancelTimeout();
        if (mReceiverRegistered) {
            unregisterReceiver(mScreenReceiver);
            mReceiverRegistered = false;
        }
        OverlayEngine.removeListener(mOnOverlayChanged);
        GateSession.removeEndListener(mOnSessionEnded);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;   // not bindable
    }

    // ---- Stock / Custom ------------------------------------------------------------------------

    private void toggleMode() {
        final int role = GateSession.roleTag();
        if (OverlayEngine.isCustomActive(mStore, role)) {
            OverlayEngine.revert(mIo, mStore);
        } else if (mStore.hasAny(role)) {
            OverlayEngine.apply(mIo, mStore, role);
        }
        // The engine's listener has already refreshed the notification.
    }

    // ---- lock timeout --------------------------------------------------------------------------

    private void onScreenOff() {
        if (!GateSession.isActive() || !SessionPrefs.lockTimeoutEnabled(this)) return;
        final int minutes = SessionPrefs.lockTimeoutMinutes(this);
        if (minutes == 0) {
            GateSession.end();
            return;
        }
        mDeadline = SystemClock.elapsedRealtime() + minutes * 60_000L;
        final AlarmManager alarms = getSystemService(AlarmManager.class);
        alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, mDeadline,
                timeoutIntent());
    }

    private void onUserPresent() {
        if (mDeadline == 0) return;
        final boolean overdue = SystemClock.elapsedRealtime() >= mDeadline;
        cancelTimeout();
        if (overdue && GateSession.isActive()) {
            GateSession.end();   // locked for longer than allowed; the alarm was merely late
        }
    }

    private void onTimeoutDue() {
        if (mDeadline == 0) return;          // cancelled, or a stale alarm
        mDeadline = 0;
        final PowerManager power = getSystemService(PowerManager.class);
        final KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (GateSession.isActive()
                && SessionPrefs.stillLocked(power.isInteractive(), keyguard.isKeyguardLocked())) {
            GateSession.end();
        }
    }

    private void cancelTimeout() {
        if (mDeadline == 0) return;
        mDeadline = 0;
        final AlarmManager alarms = getSystemService(AlarmManager.class);
        alarms.cancel(timeoutIntent());
    }

    private PendingIntent timeoutIntent() {
        return PendingIntent.getForegroundService(this, 3,
                new Intent(this, SessionService.class).setAction(ACTION_TIMEOUT),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ---- notification --------------------------------------------------------------------------

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

        final int role = GateSession.roleTag();
        final boolean custom = OverlayEngine.isCustomActive(mStore, role);
        final boolean offerSwitch = custom || mStore.hasAny(role);

        final Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_session_24)
                .setContentTitle(getString(R.string.session_notification_title))
                .setContentText(getString(!offerSwitch ? R.string.session_notification_text
                        : custom ? R.string.session_notification_custom
                                 : R.string.session_notification_stock))
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat_session_24),
                        getString(R.string.launcher_action_lock), lock).build());

        if (offerSwitch) {
            // The label names what pressing it switches TO.
            final PendingIntent mode = PendingIntent.getForegroundService(this, 2,
                    new Intent(this, SessionService.class).setAction(ACTION_MODE),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_session_24),
                    getString(custom ? R.string.session_action_use_stock
                                     : R.string.session_action_use_custom), mode).build());
        }
        return builder.build();
    }
}
