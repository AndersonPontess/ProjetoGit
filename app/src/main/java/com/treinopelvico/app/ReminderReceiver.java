package com.treinopelvico.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int sessionNo = intent == null ? 1 : intent.getIntExtra("sessionNo", 1);
        if (ReminderScheduler.shouldNotify(context, sessionNo)) {
            NotificationHelper.show(context, sessionNo);
        }
        ReminderScheduler.reschedule(context);
    }
}
