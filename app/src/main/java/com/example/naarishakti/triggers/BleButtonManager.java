package com.example.naarishakti.triggers;

import android.annotation.SuppressLint;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.example.naarishakti.core.ProtectionController;

import java.util.UUID;

/**
 * Keeps a connection to a cheap "iTag"-style BLE keyfinder button (service 0xFFE0, notify
 * characteristic 0xFFE1; every press sends a notification) and turns a double-press within 1 s
 * into {@code triggerPanic(ctx, "ble_button")}. A single press does nothing, to avoid accidents.
 *
 * Connects with autoConnect (the phone reconnects by itself whenever the button comes in range)
 * and re-opens the connection after errors with a growing back-off.
 *
 * Also used by {@link PanicButtonActivity} in test mode ({@code firePanic=false}) to show the
 * connection state and presses live. While that screen is open, {@link #setPanicSuppressed}
 * stops the protection-time instance from firing so testing never sends a real SOS.
 */
@SuppressLint("MissingPermission") // permissions are checked (or SecurityException caught) at runtime
public final class BleButtonManager {

    private static final String TAG = "BleButtonManager";

    public static final UUID SERVICE_FFE0 = uuid16(0xFFE0);
    public static final UUID CHAR_FFE1 = uuid16(0xFFE1);
    private static final UUID CCCD = uuid16(0x2902);

    private static final long DOUBLE_PRESS_MS = 1_000L;
    /** Notifications closer than this are one physical press. */
    private static final long DEBOUNCE_MS = 80L;
    private static final long COOLDOWN_MS = 10_000L;
    private static final long RECONNECT_MIN_MS = 2_000L;
    private static final long RECONNECT_MAX_MS = 60_000L;

    public static final int STATE_OFF = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;
    /** Connected but the device has no FFE0/FFE1 (not a supported button). */
    public static final int STATE_UNSUPPORTED = 3;
    /** Bluetooth off, permission missing or bad address. */
    public static final int STATE_UNAVAILABLE = 4;

    /** Callbacks are delivered on the main thread. */
    public interface Listener {
        void onStateChanged(int state);

        /** @param doublePress true when this press completed a double-press. */
        void onPress(boolean doublePress);
    }

    private static volatile boolean panicSuppressed;

    /** While true (the pairing screen is open), presses never fire an SOS. */
    public static void setPanicSuppressed(boolean suppressed) {
        panicSuppressed = suppressed;
    }

    private final Context app;
    private final String address;
    private final boolean firePanic;
    @Nullable private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Nullable private volatile BluetoothGatt gatt;
    private final Runnable connectRunnable = new Runnable() {
        @Override
        public void run() {
            connect();
        }
    };
    private volatile boolean running;
    private volatile int state = STATE_OFF;
    private long reconnectDelay = RECONNECT_MIN_MS;

    // Press tracking (binder thread; guarded by this).
    private long lastPressAt;
    private long lastNotificationAt;
    private long cooldownUntil;

    public BleButtonManager(Context ctx, String address, boolean firePanic, @Nullable Listener listener) {
        this.app = ctx.getApplicationContext();
        this.address = address == null ? "" : address.trim().toUpperCase(java.util.Locale.US);
        this.firePanic = firePanic;
        this.listener = listener;
    }

    public String getAddress() {
        return address;
    }

    public int getState() {
        return state;
    }

    public boolean isRunning() {
        return running;
    }

    /** Main thread. */
    public void start() {
        if (running) return;
        running = true;
        reconnectDelay = RECONNECT_MIN_MS;
        connect();
    }

    /** Main thread. */
    public void stop() {
        running = false;
        main.removeCallbacksAndMessages(null);
        closeGatt();
        setState(STATE_OFF);
    }

    public static boolean hasConnectPermission(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        return ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Nullable
    static BluetoothAdapter adapter(Context ctx) {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        return bm == null ? null : bm.getAdapter();
    }

    private void connect() {
        if (!running) return;
        BluetoothAdapter adapter = adapter(app);
        if (TextUtils.isEmpty(address) || !BluetoothAdapter.checkBluetoothAddress(address)
                || adapter == null || !hasConnectPermission(app)) {
            setState(STATE_UNAVAILABLE);
            return;
        }
        if (!adapter.isEnabled()) {
            setState(STATE_UNAVAILABLE);
            scheduleReconnect(); // try again later; Bluetooth may be switched back on
            return;
        }
        try {
            closeGatt();
            BluetoothDevice device = adapter.getRemoteDevice(address);
            setState(STATE_CONNECTING);
            gatt = device.connectGatt(app, true, gattCallback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) scheduleReconnect();
        } catch (SecurityException | IllegalArgumentException e) {
            Log.e(TAG, "connectGatt failed: " + e.getMessage());
            setState(STATE_UNAVAILABLE);
        }
    }

    private void scheduleReconnect() {
        if (!running) return;
        main.removeCallbacks(connectRunnable);
        main.postDelayed(connectRunnable, reconnectDelay);
        reconnectDelay = Math.min(RECONNECT_MAX_MS, reconnectDelay * 2);
    }

    private void closeGatt() {
        BluetoothGatt g = gatt;
        gatt = null;
        if (g == null) return;
        try {
            g.disconnect();
            g.close();
        } catch (SecurityException ignored) { }
    }

    private void setState(final int newState) {
        if (state == newState) return;
        state = newState;
        if (listener != null) {
            main.post(() -> {
                if (listener != null) listener.onStateChanged(newState);
            });
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (!running || g != gatt) {
                try { g.close(); } catch (SecurityException ignored) { }
                return;
            }
            try {
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Connected to " + address);
                    reconnectDelay = RECONNECT_MIN_MS;
                    setState(STATE_CONNECTING); // until notifications are enabled
                    if (!g.discoverServices()) {
                        main.post(BleButtonManager.this::reconnectNow);
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Disconnected (status " + status + ")");
                    setState(STATE_CONNECTING);
                    // autoConnect keeps waiting for the device after a clean link loss, but after
                    // a GATT error the handle is often dead: open a fresh one.
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        main.post(BleButtonManager.this::reconnectLater);
                    }
                }
            } catch (SecurityException e) {
                setState(STATE_UNAVAILABLE);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (!running || g != gatt) return;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                main.post(BleButtonManager.this::reconnectLater);
                return;
            }
            BluetoothGattService service = g.getService(SERVICE_FFE0);
            BluetoothGattCharacteristic ch = service == null ? null : service.getCharacteristic(CHAR_FFE1);
            if (ch == null) {
                Log.w(TAG, "Device has no FFE0/FFE1; not a supported button");
                setState(STATE_UNSUPPORTED);
                return;
            }
            try {
                g.setCharacteristicNotification(ch, true);
                BluetoothGattDescriptor cccd = ch.getDescriptor(CCCD);
                if (cccd == null) {
                    // Some clones notify without a CCCD; local enable is enough for them.
                    setState(STATE_CONNECTED);
                    return;
                }
                byte[] value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                boolean ok;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ok = g.writeDescriptor(cccd, value) == android.bluetooth.BluetoothStatusCodes.SUCCESS;
                } else {
                    ok = writeDescriptorLegacy(g, cccd, value);
                }
                if (!ok) Log.w(TAG, "CCCD write not started");
            } catch (SecurityException e) {
                setState(STATE_UNAVAILABLE);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            if (!running || g != gatt) return;
            if (CCCD.equals(descriptor.getUuid())) {
                Log.i(TAG, "Notifications " + (status == BluetoothGatt.GATT_SUCCESS ? "enabled" : "failed: " + status));
                setState(STATE_CONNECTED);
            }
        }

        // API 33+ delivers here.
        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt g,
                                            @NonNull BluetoothGattCharacteristic c,
                                            @NonNull byte[] value) {
            if (CHAR_FFE1.equals(c.getUuid())) onNotification();
        }

        // Before API 33 only this one is called.
        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return;
            if (CHAR_FFE1.equals(c.getUuid())) onNotification();
        }
    };

    @SuppressWarnings("deprecation")
    private static boolean writeDescriptorLegacy(BluetoothGatt g, BluetoothGattDescriptor d, byte[] value) {
        d.setValue(value);
        return g.writeDescriptor(d);
    }

    private void reconnectNow() {
        if (!running) return;
        closeGatt();
        connect();
    }

    private void reconnectLater() {
        if (!running) return;
        closeGatt();
        setState(STATE_CONNECTING);
        scheduleReconnect();
    }

    private synchronized void onNotification() {
        if (!running) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotificationAt < DEBOUNCE_MS) return;
        lastNotificationAt = now;

        boolean doublePress = lastPressAt != 0 && now - lastPressAt <= DOUBLE_PRESS_MS;
        lastPressAt = doublePress ? 0 : now; // a third press starts a new pair
        final boolean dp = doublePress;
        if (listener != null) {
            main.post(() -> {
                if (listener != null) listener.onPress(dp);
            });
        }
        if (!doublePress || !firePanic) return;
        if (panicSuppressed) {
            Log.i(TAG, "Double-press ignored: pairing screen open");
            return;
        }
        if (now < cooldownUntil || ProtectionController.isPanicActive()) return;
        cooldownUntil = now + COOLDOWN_MS;
        Log.i(TAG, "BLE button double-press; triggering SOS");
        ProtectionController.triggerPanic(app, "ble_button");
    }

    private static UUID uuid16(int shortUuid) {
        return UUID.fromString(String.format(java.util.Locale.US,
                "%08x-0000-1000-8000-00805f9b34fb", shortUuid));
    }
}
