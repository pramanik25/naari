package com.example.naarishakti.triggers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Manifest receiver for device-guard events: shutdown (last-location SMS), boot / SIM loaded /
 * carrier config changed (SIM snapshot and SIM-change SMS). While protection runs, TriggerModule
 * also registers the same actions dynamically, because some Android versions don't deliver
 * shutdown or SIM broadcasts to manifest receivers; DeviceGuard de-duplicates.
 */
public class DeviceGuardReceiver extends BroadcastReceiver {

    private static final String TAG = "DeviceGuardReceiver";

    static final String ACTION_QUICKBOOT_POWEROFF = "android.intent.action.QUICKBOOT_POWEROFF";
    static final String ACTION_QUICKBOOT_POWEROFF_HTC = "com.htc.intent.action.QUICKBOOT_POWEROFF";
    static final String ACTION_SIM_STATE_CHANGED = "android.intent.action.SIM_STATE_CHANGED";
    static final String ACTION_CARRIER_CONFIG_CHANGED = "android.telephony.action.CARRIER_CONFIG_CHANGED";
    /** Extra on SIM_STATE_CHANGED holding the state ("LOADED", "ABSENT", ...). */
    private static final String EXTRA_SIM_STATE = "ss";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        handle(context.getApplicationContext(), intent);
    }

    /** Shared with TriggerModule's dynamic receiver. Runs synchronously on the caller's thread. */
    static void handle(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        try {
            switch (action) {
                case Intent.ACTION_SHUTDOWN:
                case ACTION_QUICKBOOT_POWEROFF:
                case ACTION_QUICKBOOT_POWEROFF_HTC:
                    DeviceGuard.onShutdown(ctx);
                    break;
                case ACTION_SIM_STATE_CHANGED:
                    if ("LOADED".equals(intent.getStringExtra(EXTRA_SIM_STATE))) DeviceGuard.checkSim(ctx);
                    break;
                case Intent.ACTION_BOOT_COMPLETED:
                case ACTION_CARRIER_CONFIG_CHANGED:
                    DeviceGuard.checkSim(ctx);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Device guard failed for " + action, e);
        }
    }
}
