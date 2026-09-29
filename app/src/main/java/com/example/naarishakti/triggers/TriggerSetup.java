package com.example.naarishakti.triggers;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.example.naarishakti.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Setup helpers the settings screens call for the extra triggers. */
public final class TriggerSetup {

    private static final String TAG = "TriggerSetup";

    private TriggerSetup() {}

    /** True when our SafetyAccessibilityService is enabled in system accessibility settings. */
    public static boolean isAccessibilityEnabled(Context ctx) {
        String enabled;
        try {
            enabled = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        } catch (Exception e) {
            return false;
        }
        if (TextUtils.isEmpty(enabled)) return false;
        ComponentName ours = new ComponentName(ctx, SafetyAccessibilityService.class);
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) {
            ComponentName cn = ComponentName.unflattenFromString(splitter.next());
            if (ours.equals(cn)) return true;
        }
        return false;
    }

    /** Shows a short explainer, then opens the system accessibility settings. */
    public static void openAccessibilitySettings(final Activity activity) {
        if (activity.isFinishing()) return;
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.tr_a11y_dialog_title)
                .setMessage(activity.getString(R.string.tr_a11y_dialog_body,
                        activity.getString(R.string.app_name)))
                .setPositiveButton(R.string.tr_a11y_dialog_open, (d, w) -> {
                    try {
                        activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (ActivityNotFoundException e) {
                        Log.e(TAG, "No accessibility settings screen", e);
                    }
                })
                .setNegativeButton(R.string.tr_not_now, null)
                .show();
    }

    /** Runtime permissions needed to scan for and connect to the BLE panic button. */
    public static String[] requiredBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }
}
