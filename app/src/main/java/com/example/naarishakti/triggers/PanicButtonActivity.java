package com.example.naarishakti.triggers;

import android.annotation.SuppressLint;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.ColorRes;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.location.LocationManagerCompat;

import com.example.naarishakti.R;
import com.example.naarishakti.core.Prefs;
import com.example.naarishakti.core.ProtectionController;
import com.example.naarishakti.databinding.TrActivityPanicButtonBinding;
import com.example.naarishakti.databinding.TrItemBleDeviceBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pairs an iTag-style BLE panic button: scan (15 s), tap to save, then a live "press twice" test.
 * Also shows the connection state and lets her forget the button. While this screen is open the
 * protection-time connection is told not to fire, so testing never sends a real SOS.
 */
@SuppressLint("MissingPermission") // permissions are checked (or SecurityException caught) at runtime
public class PanicButtonActivity extends AppCompatActivity {

    private static final long SCAN_MS = 15_000L;
    private static final long RENDER_THROTTLE_MS = 400L;
    /** After a single test press, go back to idle if the second press doesn't come. */
    private static final long SINGLE_PRESS_RESET_MS = 1_200L;
    private static final ParcelUuid FFE0 = new ParcelUuid(BleButtonManager.SERVICE_FFE0);

    private TrActivityPanicButtonBinding b;
    private ActivityResultLauncher<String[]> permissionLauncher;
    private ActivityResultLauncher<Intent> enableBluetoothLauncher;

    private final Handler handler = new Handler(Looper.getMainLooper());
    @Nullable private BluetoothLeScanner scanner;
    private boolean scanning;
    private boolean askedLocation;
    private boolean renderScheduled;
    private final Map<String, Found> found = new LinkedHashMap<>();

    @Nullable private BleButtonManager monitor;
    private int monitorState = BleButtonManager.STATE_OFF;
    private boolean testPassed;

    private static final class Found {
        String address;
        String name;
        int rssi;
        boolean compatible;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = TrActivityPanicButtonBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                    if (hasBlePermissions()) {
                        startScanFlow();
                    } else {
                        showPermissionDenied();
                    }
                });
        enableBluetoothLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    BluetoothAdapter a = BleButtonManager.adapter(this);
                    if (a != null && a.isEnabled()) {
                        startScanFlow();
                        restartMonitor();
                    } else {
                        Snackbar.make(b.getRoot(), R.string.tr_pb_bt_needed, Snackbar.LENGTH_LONG).show();
                    }
                });

        b.backButton.setOnClickListener(v -> finish());
        b.scanButton.setOnClickListener(v -> {
            if (scanning) stopScan(); else startScanFlow();
        });
        b.forgetButton.setOnClickListener(v -> confirmForget());
        b.testAgainButton.setOnClickListener(v -> {
            testPassed = false;
            renderTestIdle();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        BleButtonManager.setPanicSuppressed(true);
        renderPaired();
        restartMonitor();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopScan();
        stopMonitor();
        handler.removeCallbacksAndMessages(null);
        renderScheduled = false;
        BleButtonManager.setPanicSuppressed(false);
    }

    // ------------------------------------------------------------------ permissions & bluetooth

    /** Scanning needs the BLE permissions; location too, because the manifest doesn't disavow it. */
    private String[] scanPermissions() {
        String[] ble = TriggerSetup.requiredBlePermissions();
        List<String> all = new ArrayList<>();
        Collections.addAll(all, ble);
        if (!all.contains(Manifest.permission.ACCESS_FINE_LOCATION)) {
            all.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return all.toArray(new String[0]);
    }

    private boolean isGranted(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBlePermissions() {
        for (String p : TriggerSetup.requiredBlePermissions()) {
            if (!isGranted(p)) return false;
        }
        return true;
    }

    private boolean hasScanPermissions() {
        for (String p : scanPermissions()) {
            if (!isGranted(p)) return false;
        }
        return true;
    }

    private void showPermissionDenied() {
        Snackbar sb = Snackbar.make(b.getRoot(), R.string.tr_pb_perm_denied, Snackbar.LENGTH_LONG);
        boolean permanently = true;
        for (String p : TriggerSetup.requiredBlePermissions()) {
            if (!isGranted(p) && shouldShowRequestPermissionRationale(p)) permanently = false;
        }
        if (permanently) sb.setAction(R.string.tr_open_settings, v -> openAppSettings());
        sb.show();
    }

    private void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
        } catch (Exception ignored) {
            // No settings screen.
        }
    }

    /** Permissions -> Bluetooth on -> scan. Each step re-enters here once satisfied. */
    private void startScanFlow() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            showEmpty(getString(R.string.tr_pb_no_bluetooth));
            return;
        }
        if (!hasBlePermissions()) {
            askedLocation = true;
            permissionLauncher.launch(scanPermissions());
            return;
        }
        if (!hasScanPermissions() && !askedLocation) {
            // Only location is missing: ask once, then scan anyway (some phones allow it).
            askedLocation = true;
            permissionLauncher.launch(scanPermissions());
            return;
        }
        BluetoothAdapter adapter = BleButtonManager.adapter(this);
        if (adapter == null) {
            showEmpty(getString(R.string.tr_pb_no_bluetooth));
            return;
        }
        if (!adapter.isEnabled()) {
            try {
                enableBluetoothLauncher.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            } catch (Exception e) {
                Snackbar.make(b.getRoot(), R.string.tr_pb_bt_needed, Snackbar.LENGTH_LONG).show();
            }
            return;
        }
        startScan(adapter);
    }

    // ------------------------------------------------------------------ scanning

    private void startScan(BluetoothAdapter adapter) {
        if (scanning) return;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            Snackbar.make(b.getRoot(), R.string.tr_pb_bt_needed, Snackbar.LENGTH_LONG).show();
            return;
        }
        found.clear();
        renderDevices();
        b.emptyText.setVisibility(View.GONE);
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(null, settings, scanCallback);
        } catch (SecurityException | IllegalStateException e) {
            showEmpty(getString(R.string.tr_pb_scan_failed, -1));
            return;
        }
        scanning = true;
        handler.postDelayed(stopScanRunnable, SCAN_MS);
        renderScanUi();
    }

    private final Runnable stopScanRunnable = new Runnable() {
        @Override
        public void run() {
            stopScan();
        }
    };

    private void stopScan() {
        handler.removeCallbacks(stopScanRunnable);
        if (!scanning) return;
        scanning = false;
        BluetoothLeScanner s = scanner;
        if (s != null) {
            try {
                s.stopScan(scanCallback);
            } catch (SecurityException | IllegalStateException ignored) {
                // Bluetooth turned off mid-scan.
            }
        }
        renderScanUi();
        renderDevices();
        if (found.isEmpty()) {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            boolean locationOff = lm != null && !LocationManagerCompat.isLocationEnabled(lm);
            showEmpty(getString(locationOff ? R.string.tr_pb_empty_location : R.string.tr_pb_empty));
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            onFound(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult r : results) onFound(r);
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            handler.removeCallbacks(stopScanRunnable);
            renderScanUi();
            showEmpty(getString(R.string.tr_pb_scan_failed, errorCode));
        }
    };

    private void onFound(ScanResult result) {
        BluetoothDevice device = result.getDevice();
        if (device == null) return;
        ScanRecord record = result.getScanRecord();
        String name = record == null ? null : record.getDeviceName();
        if (TextUtils.isEmpty(name)) {
            try {
                name = device.getName();
            } catch (SecurityException ignored) {
                name = null;
            }
        }
        boolean compatible = record != null && record.getServiceUuids() != null
                && record.getServiceUuids().contains(FFE0);
        String address = device.getAddress();
        Found f = found.get(address);
        if (f == null) {
            // Skip anonymous devices that don't look like a button: keeps busy places readable.
            if (TextUtils.isEmpty(name) && !compatible) return;
            f = new Found();
            f.address = address;
            found.put(address, f);
        }
        if (!TextUtils.isEmpty(name)) f.name = name.trim();
        f.compatible = f.compatible || compatible;
        f.rssi = result.getRssi();
        scheduleRender();
    }

    private void scheduleRender() {
        if (renderScheduled) return;
        renderScheduled = true;
        handler.postDelayed(() -> {
            renderScheduled = false;
            renderDevices();
        }, RENDER_THROTTLE_MS);
    }

    private void renderDevices() {
        List<Found> list = new ArrayList<>(found.values());
        Collections.sort(list, new Comparator<Found>() {
            @Override
            public int compare(Found a, Found c) {
                if (a.compatible != c.compatible) return a.compatible ? -1 : 1;
                return Integer.compare(c.rssi, a.rssi);
            }
        });
        String paired = pairedAddress();
        b.deviceList.removeAllViews();
        for (int i = 0; i < list.size(); i++) {
            final Found f = list.get(i);
            TrItemBleDeviceBinding row = TrItemBleDeviceBinding.inflate(getLayoutInflater(), b.deviceList, false);
            row.divider.setVisibility(i == 0 ? View.GONE : View.VISIBLE);
            String name = TextUtils.isEmpty(f.name) ? getString(R.string.tr_pb_unnamed) : f.name;
            row.name.setText(name);
            row.address.setText(f.address);
            row.rssi.setText(getString(R.string.tr_pb_rssi, f.rssi));
            boolean isPaired = f.address.equalsIgnoreCase(paired);
            if (isPaired || f.compatible) {
                row.tag.setVisibility(View.VISIBLE);
                row.tag.setText(isPaired ? R.string.tr_pb_tag_paired : R.string.tr_pb_tag_compatible);
            } else {
                row.tag.setVisibility(View.GONE);
            }
            int bars = bars(f.rssi);
            View[] barViews = {row.bar1, row.bar2, row.bar3, row.bar4};
            for (int k = 0; k < barViews.length; k++) {
                tint(barViews[k], k < bars ? barColor(bars) : R.color.ns_stroke);
            }
            row.row.setContentDescription(getString(R.string.tr_pb_device_cd, name, bars));
            row.row.setOnClickListener(v -> pair(f));
            b.deviceList.addView(row.getRoot());
        }
        if (!list.isEmpty()) b.emptyText.setVisibility(View.GONE);
    }

    private static int bars(int rssi) {
        if (rssi >= -60) return 4;
        if (rssi >= -70) return 3;
        if (rssi >= -82) return 2;
        return 1;
    }

    @ColorRes
    private static int barColor(int bars) {
        if (bars >= 3) return R.color.ns_safe;
        if (bars == 2) return R.color.ns_warn;
        return R.color.ns_danger;
    }

    private void renderScanUi() {
        b.scanProgress.setVisibility(scanning ? View.VISIBLE : View.GONE);
        b.scanButton.setText(scanning ? R.string.tr_pb_stop_scan : R.string.tr_pb_scan);
    }

    private void showEmpty(String text) {
        b.emptyText.setText(text);
        b.emptyText.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ pairing

    private String pairedAddress() {
        return Prefs.get(this).getString(Prefs.BLE_BUTTON_ADDRESS, "");
    }

    private String pairedDisplayName() {
        String name = Prefs.get(this).getString(Prefs.BLE_BUTTON_NAME, "");
        return TextUtils.isEmpty(name) ? getString(R.string.tr_pb_unnamed) : name;
    }

    private void pair(Found f) {
        stopScan();
        Prefs.get(this).edit()
                .putString(Prefs.BLE_BUTTON_ADDRESS, f.address.toUpperCase(Locale.US))
                .putString(Prefs.BLE_BUTTON_NAME, f.name == null ? "" : f.name)
                .apply();
        Prefs.notifyChanged(this);
        testPassed = false;
        renderPaired();
        renderDevices();
        restartMonitor();
        b.getRoot().performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        b.scroll.post(() -> b.scroll.smoothScrollTo(0, b.testSection.getTop()));
        Snackbar.make(b.getRoot(), getString(R.string.tr_pb_paired_snack, pairedDisplayName()),
                Snackbar.LENGTH_LONG).show();
    }

    private void confirmForget() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.tr_pb_forget_title)
                .setMessage(getString(R.string.tr_pb_forget_body, pairedDisplayName()))
                .setPositiveButton(R.string.tr_pb_forget_confirm, (d, w) -> forget())
                .setNegativeButton(R.string.tr_cancel, null)
                .show();
    }

    private void forget() {
        stopMonitor();
        Prefs.get(this).edit()
                .remove(Prefs.BLE_BUTTON_ADDRESS)
                .remove(Prefs.BLE_BUTTON_NAME)
                .apply();
        Prefs.notifyChanged(this);
        testPassed = false;
        renderPaired();
        renderDevices();
        Snackbar.make(b.getRoot(), R.string.tr_pb_forgotten, Snackbar.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ live connection + test

    private void restartMonitor() {
        stopMonitor();
        String address = pairedAddress();
        if (TextUtils.isEmpty(address)) return;
        monitor = new BleButtonManager(this, address, false, new BleButtonManager.Listener() {
            @Override
            public void onStateChanged(int state) {
                monitorState = state;
                renderStatus();
            }

            @Override
            public void onPress(boolean doublePress) {
                onTestPress(doublePress);
            }
        });
        monitor.start();
        monitorState = monitor.getState();
        renderStatus();
    }

    private void stopMonitor() {
        if (monitor != null) monitor.stop();
        monitor = null;
        monitorState = BleButtonManager.STATE_OFF;
    }

    private void renderPaired() {
        boolean paired = !TextUtils.isEmpty(pairedAddress());
        b.deviceName.setText(paired ? pairedDisplayName() : getString(R.string.tr_pb_no_button));
        b.deviceAddress.setText(paired ? pairedAddress() : "");
        b.deviceAddress.setVisibility(paired ? View.VISIBLE : View.GONE);
        b.forgetButton.setVisibility(paired ? View.VISIBLE : View.GONE);
        b.testSection.setVisibility(paired ? View.VISIBLE : View.GONE);
        b.protectionHint.setVisibility(paired && !ProtectionController.isProtectionActive()
                ? View.VISIBLE : View.GONE);
        renderStatus();
        if (testPassed) renderTestSuccess(false); else renderTestIdle();
    }

    private void renderStatus() {
        int text;
        int color;
        if (TextUtils.isEmpty(pairedAddress())) {
            text = R.string.tr_pb_status_not_set;
            color = R.color.ns_text_faint;
        } else if (!hasBlePermissions()) {
            text = R.string.tr_pb_status_permission;
            color = R.color.ns_danger;
        } else {
            BluetoothAdapter adapter = BleButtonManager.adapter(this);
            boolean btOn = adapter != null && adapter.isEnabled();
            switch (monitorState) {
                case BleButtonManager.STATE_CONNECTED:
                    text = R.string.tr_pb_status_connected;
                    color = R.color.ns_safe;
                    break;
                case BleButtonManager.STATE_CONNECTING:
                    text = R.string.tr_pb_status_connecting;
                    color = R.color.ns_warn;
                    break;
                case BleButtonManager.STATE_UNSUPPORTED:
                    text = R.string.tr_pb_status_unsupported;
                    color = R.color.ns_danger;
                    break;
                case BleButtonManager.STATE_UNAVAILABLE:
                    text = btOn ? R.string.tr_pb_status_unavailable : R.string.tr_pb_status_bt_off;
                    color = R.color.ns_danger;
                    break;
                default:
                    text = btOn ? R.string.tr_pb_status_off : R.string.tr_pb_status_bt_off;
                    color = R.color.ns_text_faint;
                    break;
            }
        }
        b.statusText.setText(text);
        tint(b.statusDot, color);
        if (!testPassed && b.testSection.getVisibility() == View.VISIBLE) renderTestIdle();
    }

    private final Runnable singlePressReset = new Runnable() {
        @Override
        public void run() {
            if (!testPassed) renderTestIdle();
        }
    };

    private void onTestPress(boolean doublePress) {
        handler.removeCallbacks(singlePressReset);
        if (doublePress) {
            testPassed = true;
            renderTestSuccess(true);
            return;
        }
        if (testPassed) return; // already done; "Test again" resets
        tint(b.pressDot1, R.color.ns_rose);
        tint(b.pressDot2, R.color.ns_stroke);
        b.testTitle.setText(R.string.tr_pb_test_one);
        b.testHint.setText(R.string.tr_pb_test_hint);
        b.testCore.animate().scaleX(1.08f).scaleY(1.08f).setDuration(90)
                .withEndAction(() -> b.testCore.animate().scaleX(1f).scaleY(1f).setDuration(120).start())
                .start();
        b.testCore.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        handler.postDelayed(singlePressReset, SINGLE_PRESS_RESET_MS);
    }

    private void renderTestIdle() {
        boolean connected = monitorState == BleButtonManager.STATE_CONNECTED;
        tint(b.pressDot1, R.color.ns_stroke);
        tint(b.pressDot2, R.color.ns_stroke);
        tint(b.testCore, R.color.ns_surface_highest);
        b.testIcon.setImageResource(R.drawable.tr_ic_bluetooth);
        b.testIcon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this,
                connected ? R.color.ns_violet : R.color.ns_text_faint)));
        b.testTitle.setText(R.string.tr_pb_test_title);
        b.testHint.setText(connected ? R.string.tr_pb_test_hint : R.string.tr_pb_test_waiting);
        b.testAgainButton.setVisibility(View.GONE);
    }

    private void renderTestSuccess(boolean animate) {
        tint(b.pressDot1, R.color.ns_safe);
        tint(b.pressDot2, R.color.ns_safe);
        tint(b.testCore, R.color.ns_safe_container);
        b.testIcon.setImageResource(R.drawable.ub_ic_check);
        b.testIcon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ns_safe)));
        b.testTitle.setText(R.string.tr_pb_test_success);
        b.testHint.setText(R.string.tr_pb_test_success_body);
        b.testAgainButton.setVisibility(View.VISIBLE);
        if (!animate) return;
        b.testCore.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        float from = 92f / 140f; // core size / stage size
        b.testRing.setScaleX(from);
        b.testRing.setScaleY(from);
        ObjectAnimator ring = ObjectAnimator.ofPropertyValuesHolder(b.testRing,
                PropertyValuesHolder.ofFloat(View.SCALE_X, from, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, from, 1f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.9f, 0f));
        ring.setDuration(900);
        ring.setInterpolator(new DecelerateInterpolator());
        ring.start();
    }

    private void tint(View v, @ColorRes int color) {
        v.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, color)));
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** Intent for other screens (settings UI) to open this one. */
    public static Intent intent(Context ctx) {
        return new Intent(ctx, PanicButtonActivity.class);
    }
}
