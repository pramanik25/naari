package com.example.naarishakti.journey;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Check-in alarms: the "due in 2 min" warning and the deadline. It also re-arms after a reboot
 * or app update when the manifest delivers BOOT_COMPLETED / MY_PACKAGE_REPLACED to it; until
 * then the check-in is re-armed lazily whenever the app is opened.
 */
public class CheckInReceiver extends BroadcastReceiver {

    static final String ACTION_WARN = "com.example.naarishakti.journey.CHECKIN_WARN";
    static final String ACTION_DEADLINE = "com.example.naarishakti.journey.CHECKIN_DEADLINE";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;
        if (ACTION_WARN.equals(action)) {
            CheckIn.onWarning(context);
        } else if (ACTION_DEADLINE.equals(action)) {
            CheckIn.onDeadline(context);
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            CheckIn.rearmFromBackground(context);
            JourneyService.sync(context, false);
        }
    }
}
