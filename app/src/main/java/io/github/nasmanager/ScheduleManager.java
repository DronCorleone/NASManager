package io.github.nasmanager;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.time.ZoneId;

public final class ScheduleManager {
    static final String ACTION_WAKE = "io.github.nasmanager.action.SCHEDULED_WAKE";
    static final String ACTION_SHUTDOWN = "io.github.nasmanager.action.SCHEDULED_SHUTDOWN";
    private static final int REQUEST_WAKE = 2101;
    private static final int REQUEST_SHUTDOWN = 2102;

    private ScheduleManager() { }

    /** Applies both daily schedules. Disabled entries are always cancelled. */
    public static void sync(Context context, AppConfig config) {
        Context appContext = context.getApplicationContext();
        AlarmManager alarms = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;

        syncOne(appContext, alarms, ACTION_WAKE, REQUEST_WAKE,
                config.wakeScheduleEnabled, config.wakeHour, config.wakeMinute);
        syncOne(appContext, alarms, ACTION_SHUTDOWN, REQUEST_SHUTDOWN,
                config.shutdownScheduleEnabled, config.shutdownHour, config.shutdownMinute);
    }

    public static boolean canScheduleExactAlarms(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return alarms != null && alarms.canScheduleExactAlarms();
    }

    /** Intent for Android's per-app "Alarms & reminders" access screen. */
    public static Intent exactAlarmSettingsIntent(Context context) {
        return new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:" + context.getPackageName()));
    }

    static long nextTriggerMillis(long nowMillis, ZoneId zone, int hour, int minute) {
        return DailyScheduleCalculator.nextTriggerMillis(nowMillis, zone, hour, minute);
    }

    private static void syncOne(Context context, AlarmManager alarms, String action, int requestCode,
                                boolean enabled, int hour, int minute) {
        PendingIntent operation = operation(context, action, requestCode);
        if (!enabled) {
            alarms.cancel(operation);
            return;
        }
        try {
            DailyScheduleCalculator.validateTime(hour, minute);
        } catch (IllegalArgumentException invalidTime) {
            alarms.cancel(operation);
            return;
        }
        if (!canScheduleExactAlarms(context)) {
            // Never silently downgrade: daily power actions must run at the time the user selected.
            alarms.cancel(operation);
            return;
        }
        long triggerAt = nextTriggerMillis(System.currentTimeMillis(), ZoneId.systemDefault(), hour, minute);
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation);
        } catch (SecurityException denied) {
            // Access can be revoked between the permission check and setExactAndAllowWhileIdle().
            alarms.cancel(operation);
        }
    }

    private static PendingIntent operation(Context context, String action, int requestCode) {
        Intent intent = new Intent(context, ScheduleAlarmReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

}
