package com.example.naarishakti.cloud;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Downloads evidence photos (short-lived signed URLs, no auth header) and decodes them
 * downsampled, so a 12 MP JPEG never lands in memory at full size.
 */
final class CloudImages {

    private static final String TAG = "NsCloud.Images";
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    /** A few recent photos, so the notification and the alert screen share one download. */
    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(4);

    private CloudImages() {}

    /** Blocking; null on any failure (expired link, offline, not an image). */
    @Nullable
    static Bitmap load(Context ctx, @Nullable String url, int maxPx) {
        if (TextUtils.isEmpty(url)) return null;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("CloudImages.load blocks; call it off the main thread");
        }
        String absolute = url.startsWith("/") ? Cloud.baseUrl() + url : url;
        String key = absolute + "#" + maxPx;
        Bitmap cached = CACHE.get(key);
        if (cached != null) return cached;

        Request request;
        try {
            request = new Request.Builder().url(absolute).header("Accept", "image/*").get().build();
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Bad photo URL");
            return null;
        }
        try (Response response = ApiClient.get(ctx).httpClient().newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) return null;
            if (body.contentLength() > MAX_BYTES) return null;
            byte[] bytes = readCapped(body.byteStream());
            if (bytes == null) return null;
            Bitmap bmp = decodeSampled(bytes, maxPx);
            if (bmp != null) CACHE.put(key, bmp);
            return bmp;
        } catch (Exception e) {
            Log.i(TAG, "Photo download failed: " + e.getMessage());
            return null;
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "Photo too large to decode");
            return null;
        }
    }

    @Nullable
    private static byte[] readCapped(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
        byte[] buf = new byte[32 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_BYTES) return null;
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    @Nullable
    private static Bitmap decodeSampled(byte[] bytes, int maxPx) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= maxPx || o.outHeight / (sample * 2) >= maxPx) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
    }
}
