package io.github.nasmanager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ScheduleRescheduleReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        ScheduleManager.sync(context.getApplicationContext(), new SecureConfigStore(context).load());
    }
}
