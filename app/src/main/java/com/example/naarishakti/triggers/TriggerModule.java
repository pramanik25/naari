package com.example.naarishakti.triggers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.naarishakti.core.Prefs;
import com.example.naarishakti.core.ProtectionController;
import com.example.naarishakti.core.SafetyHooks;

/**
 * Extra SOS triggers (scream, fall, headset, BLE button) and device guards (SIM, shutdown,
 * battery). Detectors run only while protection is on and only when their setting is enabled;
 * settings are re-read on {@link Prefs#ACTION_SETTINGS_UPDATED}. All callbacks arrive on the
 * main thread (SafetyHooks contract), so no extra locking is needed for the fields here.
 */
public final class TriggerModule implements SafetyHooks.Module {

    private static final String TAG = "TriggerModule";

    @Nullable private Context app;
    private boolean started;

    @Nullable private ScreamDetector scream;
    @Nullable private FallDetector fall;
    @Nullable private HeadsetTrigger headset;
    @Nullable private BleButtonManager ble;

    private final BroadcastReceiver localReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Settings saved, or protection/listener/panic state changed.
            if (started && app != null) applySettings(app);
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                DeviceGuard.onBatteryChanged(context.getApplicationContext(), intent);
            } catch (Exception e) {
                Log.e(TAG, "Battery check failed", e);
            }
        }
    };

    /** Dynamic copy of the manifest receiver: some Android versions skip manifest receivers for these. */
    private final BroadcastReceiver deviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            DeviceGuardReceiver.handle(context.getApplicationContext(), intent);
        }
    };

    @Override
    public void onProtectionStarted(@NonNull Context ctx) {
        if (started) return;
        final Context appCtx = ctx.getApplicationContext();
        app = appCtx;
        started = true;

        IntentFilter local = new IntentFilter(Prefs.ACTION_SETTINGS_UPDATED);
        local.addAction(ProtectionController.ACTION_STATE_CHANGED);
        LocalBroadcastManager.getInstance(appCtx).registerReceiver(localReceiver, local);

        // Sticky: returns the current battery state immediately.
        Intent battery = ContextCompat.registerReceiver(appCtx, batteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED);
        DeviceGuard.onBatteryChanged(appCtx, battery);

        IntentFilter device = new IntentFilter(Intent.ACTION_SHUTDOWN);
        device.addAction(DeviceGuardReceiver.ACTION_QUICKBOOT_POWEROFF);
        device.addAction(DeviceGuardReceiver.ACTION_QUICKBOOT_POWEROFF_HTC);
        device.addAction(DeviceGuardReceiver.ACTION_SIM_STATE_CHANGED);
        device.addAction(DeviceGuardReceiver.ACTION_CARRIER_CONFIG_CHANGED);
        ContextCompat.registerReceiver(appCtx, deviceReceiver, device, ContextCompat.RECEIVER_NOT_EXPORTED);

        // First-run SIM snapshot (no alert when none was stored yet).
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    DeviceGuard.checkSim(appCtx);
                } catch (Exception e) {
                    Log.e(TAG, "SIM snapshot failed", e);
                }
            }
        }, "ns-sim-snapshot").start();

        applySettings(appCtx);
        Log.i(TAG, "Extra triggers started");
    }

    @Override
    public void onProtectionStopped(@NonNull Context ctx) {
        if (!started) return;
        started = false;
        Context appCtx = app != null ? app : ctx.getApplicationContext();
        LocalBroadcastManager.getInstance(appCtx).unregisterReceiver(localReceiver);
        safeUnregister(appCtx, batteryReceiver);
        safeUnregister(appCtx, deviceReceiver);
        stopScream();
        stopFall();
        stopHeadset();
        stopBle();
        Log.i(TAG, "Extra triggers stopped");
    }

    /** Starts/stops each detector to match the current settings. Idempotent. */
    private void applySettings(Context ctx) {
        SharedPreferences p = Prefs.get(ctx);

        // Scream: needs the voice listener running, because it reads the listener's audio.
        boolean wantScream = p.getBoolean(Prefs.SCREAM_DETECTION, false) && ProtectionController.isListening();
        if (wantScream && scream == null) {
            ScreamDetector d = new ScreamDetector(ctx);
            if (d.start()) scream = d; else d.stop();
        } else if (!wantScream) {
            stopScream();
        }

        boolean wantFall = p.getBoolean(Prefs.FALL_DETECTION, false);
        if (wantFall && fall == null) {
            FallDetector d = new FallDetector(ctx);
            if (d.start()) fall = d;
        } else if (!wantFall) {
            stopFall();
        }

        boolean wantHeadset = p.getBoolean(Prefs.HEADSET_TRIGGER, true);
        if (wantHeadset && headset == null) {
            HeadsetTrigger h = new HeadsetTrigger(ctx);
            h.start();
            if (h.isRunning()) headset = h;
        } else if (!wantHeadset) {
            stopHeadset();
        }

        String address = p.getString(Prefs.BLE_BUTTON_ADDRESS, "");
        boolean wantBle = !TextUtils.isEmpty(address) && BleButtonManager.hasConnectPermission(ctx);
        if (ble != null && (!wantBle || !ble.getAddress().equalsIgnoreCase(address.trim()))) {
            stopBle(); // turned off, forgotten or a different button paired
        }
        if (wantBle && ble == null) {
            ble = new BleButtonManager(ctx, address, true, null);
            ble.start();
        }
    }

    private void stopScream() {
        if (scream != null) scream.stop();
        scream = null;
    }

    private void stopFall() {
        if (fall != null) fall.stop();
        fall = null;
    }

    private void stopHeadset() {
        if (headset != null) headset.stop();
        headset = null;
    }

    private void stopBle() {
        if (ble != null) ble.stop();
        ble = null;
    }

    private static void safeUnregister(Context ctx, BroadcastReceiver r) {
        try {
            ctx.unregisterReceiver(r);
        } catch (IllegalArgumentException ignored) {
            // Not registered.
        }
    }
}
