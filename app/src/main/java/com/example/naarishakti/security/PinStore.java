package com.example.naarishakti.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;

import androidx.annotation.Nullable;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * App PIN and optional duress PIN. Only salted PBKDF2 hashes are stored, in the private
 * "pin_store" preferences file. Verification always derives both hashes so the time taken does
 * not reveal which PIN matched.
 *
 * Derivation takes tens of milliseconds per PIN on slow phones: call {@link #verify} and
 * {@link #save} off the main thread.
 */
public final class PinStore {

    public enum Result { OK, DURESS, WRONG, NOT_SET }

    public static final int MIN_LENGTH = 4;
    public static final int MAX_LENGTH = 6;

    private static final String FILE = "pin_store";
    private static final String K_APP_HASH = "app_hash";
    private static final String K_APP_SALT = "app_salt";
    private static final String K_DURESS_HASH = "duress_hash";
    private static final String K_DURESS_SALT = "duress_salt";
    private static final String K_ITERATIONS = "iterations";

    private static final String ALGORITHM = "PBKDF2WithHmacSHA1"; // available on every API level we support
    private static final int ITERATIONS = 24_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private PinStore() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean isSet(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return p.contains(K_APP_HASH) && p.contains(K_APP_SALT);
    }

    public static boolean isDuressSet(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return isSet(ctx) && p.contains(K_DURESS_HASH) && p.contains(K_DURESS_SALT);
    }

    /** 4–6 ASCII digits. */
    public static boolean isValidFormat(@Nullable String pin) {
        if (pin == null || pin.length() < MIN_LENGTH || pin.length() > MAX_LENGTH) return false;
        for (int i = 0; i < pin.length(); i++) {
            char c = pin.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** Checks {@code pin} against the app PIN and the duress PIN. Slow: call off the main thread. */
    public static Result verify(Context ctx, String pin) {
        SharedPreferences p = prefs(ctx);
        byte[] appHash = decode(p.getString(K_APP_HASH, null));
        byte[] appSalt = decode(p.getString(K_APP_SALT, null));
        if (appHash == null || appSalt == null) return Result.NOT_SET;
        if (pin == null) pin = "";
        int iterations = Math.max(ITERATIONS, p.getInt(K_ITERATIONS, ITERATIONS));

        boolean app = constantTimeEquals(derive(pin, appSalt, iterations, appHash.length * 8), appHash);

        byte[] duressHash = decode(p.getString(K_DURESS_HASH, null));
        byte[] duressSalt = decode(p.getString(K_DURESS_SALT, null));
        boolean duress;
        if (duressHash != null && duressSalt != null) {
            duress = constantTimeEquals(derive(pin, duressSalt, iterations, duressHash.length * 8), duressHash);
        } else {
            // Same amount of work whether or not a duress PIN exists.
            derive(pin, appSalt, iterations, appHash.length * 8);
            duress = false;
        }
        if (app) return Result.OK;
        if (duress) return Result.DURESS;
        return Result.WRONG;
    }

    /**
     * Stores a new app PIN and optional duress PIN (null removes the duress PIN).
     * @return false when a PIN is malformed or the two PINs are equal.
     */
    public static boolean save(Context ctx, String appPin, @Nullable String duressPin) {
        if (!isValidFormat(appPin)) return false;
        if (duressPin != null && (!isValidFormat(duressPin) || duressPin.equals(appPin))) return false;
        SecureRandom random = new SecureRandom();
        byte[] appSalt = new byte[SALT_BYTES];
        random.nextBytes(appSalt);
        SharedPreferences.Editor e = prefs(ctx).edit()
                .putInt(K_ITERATIONS, ITERATIONS)
                .putString(K_APP_SALT, encode(appSalt))
                .putString(K_APP_HASH, encode(derive(appPin, appSalt, ITERATIONS, KEY_BITS)));
        if (duressPin != null) {
            byte[] duressSalt = new byte[SALT_BYTES];
            random.nextBytes(duressSalt);
            e.putString(K_DURESS_SALT, encode(duressSalt))
                    .putString(K_DURESS_HASH, encode(derive(duressPin, duressSalt, ITERATIONS, KEY_BITS)));
        } else {
            e.remove(K_DURESS_SALT).remove(K_DURESS_HASH);
        }
        return e.commit();
    }

    /** Removes the app PIN and the duress PIN. */
    public static void clear(Context ctx) {
        prefs(ctx).edit().clear().commit();
    }

    // ---- Crypto helpers ----

    private static byte[] derive(String pin, byte[] salt, int iterations, int bits) {
        char[] chars = pin.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, iterations, bits);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        } finally {
            spec.clearPassword();
            Arrays.fill(chars, '\0');
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null) return false;
        int diff = a.length ^ b.length;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }

    private static String encode(byte[] b) {
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    @Nullable
    private static byte[] decode(@Nullable String s) {
        if (TextUtils.isEmpty(s)) return null;
        try {
            return Base64.decode(s, Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
