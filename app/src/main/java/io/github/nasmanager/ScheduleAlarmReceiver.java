package io.github.nasmanager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

public final class ScheduleAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Context appContext = context.getApplicationContext();
        AppConfig config = new SecureConfigStore(appContext).load();

        // Exact alarms are one-shot. Schedule the following day before doing network I/O.
        ScheduleManager.sync(appContext, config);

        String action = intent == null ? null : intent.getAction();
        if (!ScheduleManager.ACTION_WAKE.equals(action)
                && !ScheduleManager.ACTION_SHUTDOWN.equals(action)) return;

        PendingResult result = goAsync();
        PowerManager powerManager = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = powerManager == null ? null
                : powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "NASManager:scheduled-power");
        if (wakeLock != null) wakeLock.acquire(120_000L);
        Thread worker = new Thread(() -> {
            try {
                if (ScheduleManager.ACTION_WAKE.equals(action)) {
                    if (config.wakeScheduleEnabled) {
                        WakeOnLan.send(config.macAddress, config.broadcastAddress);
                    }
                } else if (config.shutdownScheduleEnabled) {
                    new TrueNasClient(config).shutdown();
                }
            } catch (Exception ignored) {
                // The next daily alarm is already scheduled; transient LAN errors do not disable it.
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                result.finish();
            }
        }, "nasmanager-scheduled-power");
        worker.start();
    }
}
