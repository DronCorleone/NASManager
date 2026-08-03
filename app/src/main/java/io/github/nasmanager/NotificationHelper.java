package io.github.nasmanager;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

final class NotificationHelper {
    private static final String CHANNEL_ID = "truenas_alerts";

    private NotificationHelper() { }

    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, context.getString(R.string.alerts), NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(context.getString(R.string.notify_alerts));
            context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    static void show(Context context, DashboardData.AlertInfo alert) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ensureChannel(context);
        android.app.Notification notification = new android.app.Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(alert.title)
                .setContentText(alert.message)
                .setStyle(new android.app.Notification.BigTextStyle().bigText(alert.message))
                .setAutoCancel(true)
                .build();
        int id = alert.id == null ? alert.hashCode() : alert.id.hashCode();
        context.getSystemService(NotificationManager.class).notify(id, notification);
    }
}
