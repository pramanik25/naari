package com.example.naarishakti;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.example.naarishakti.core.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Telegram Bot API helper. The bot token comes from local.properties via BuildConfig; contacts link
 * themselves by messaging the bot, after which {@link #fetchAndStoreChatIds} saves their chat ids.
 */
public final class TelegramBot {

    private static final String TAG = "TelegramBot";
    private static final String API = "https://api.telegram.org/bot";

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    /** Result of {@link #fetchAndStoreChatIds}; always delivered on the main thread. */
    public interface LinkCallback {
        void onResult(int newlyLinked, int totalLinked, @Nullable String error);
    }

    private TelegramBot() {}

    public static boolean isConfigured() {
        return !TextUtils.isEmpty(BuildConfig.TELEGRAM_BOT_TOKEN);
    }

    public static String getBotUsername() {
        String name = BuildConfig.TELEGRAM_BOT_USERNAME;
        if (name == null) return "";
        return name.startsWith("@") ? name.substring(1) : name;
    }

    public static String getBotLink() {
        return "https://t.me/" + getBotUsername();
    }

    /**
     * Reads the bot's recent updates, collects the chat ids of private chats that messaged it and
     * merges them into {@link Prefs#TELEGRAM_CHAT_IDS}.
     */
    public static void fetchAndStoreChatIds(Context ctx, final LinkCallback cb) {
        final Context app = ctx.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        if (!isConfigured()) {
            final int total = Prefs.getTelegramChatIds(app).size();
            main.post(() -> cb.onResult(0, total, "Telegram bot token is not configured"));
            return;
        }
        EXECUTOR.execute(() -> {
            int newly = 0;
            String error = null;
            try {
                Request request = new Request.Builder()
                        .url(API + BuildConfig.TELEGRAM_BOT_TOKEN + "/getUpdates?allowed_updates=%5B%22message%22%5D")
                        .get()
                        .build();
                try (Response response = CLIENT.newCall(request).execute()) {
                    String body = bodyString(response.body());
                    if (!response.isSuccessful()) {
                        error = "Telegram returned " + response.code();
                        Log.e(TAG, "getUpdates failed: " + response.code() + " " + body);
                    } else {
                        List<String> found = parsePrivateChatIds(body);
                        List<String> existing = Prefs.getTelegramChatIds(app);
                        for (String id : found) if (!existing.contains(id)) newly++;
                        if (!found.isEmpty()) Prefs.addTelegramChatIds(app, found);
                    }
                }
            } catch (Exception e) {
                error = "Couldn't reach Telegram. Check your internet connection.";
                Log.e(TAG, "getUpdates error", e);
            }
            final int newlyLinked = newly;
            final int total = Prefs.getTelegramChatIds(app).size();
            final String err = error;
            main.post(() -> cb.onResult(newlyLinked, total, err));
        });
    }

    private static List<String> parsePrivateChatIds(String body) throws Exception {
        List<String> ids = new ArrayList<>();
        JSONArray result = new JSONObject(body).optJSONArray("result");
        if (result == null) return ids;
        for (int i = 0; i < result.length(); i++) {
            JSONObject message = result.getJSONObject(i).optJSONObject("message");
            if (message == null) continue;
            JSONObject chat = message.optJSONObject("chat");
            if (chat == null || !"private".equals(chat.optString("type"))) continue;
            String id = String.valueOf(chat.optLong("id"));
            if (!"0".equals(id) && !ids.contains(id)) ids.add(id);
        }
        return ids;
    }

    /** Sends a text message to every linked chat off the main thread. */
    public static void sendMessageToAllAsync(Context ctx, final String text) {
        final Context app = ctx.getApplicationContext();
        if (!isConfigured() || Prefs.getTelegramChatIds(app).isEmpty()) return;
        EXECUTOR.execute(() -> sendMessageToAll(app, text));
    }

    /** @return the number of chats the message reached. */
    @WorkerThread
    public static int sendMessageToAll(Context ctx, String text) {
        int ok = 0;
        for (String chatId : Prefs.getTelegramChatIds(ctx)) {
            if (sendMessage(chatId, text)) ok++;
        }
        return ok;
    }

    /** @return the number of chats the photo reached. */
    @WorkerThread
    public static int sendPhotoToAll(Context ctx, File imageFile, @Nullable String caption) {
        int ok = 0;
        for (String chatId : Prefs.getTelegramChatIds(ctx)) {
            if (sendPhoto(imageFile, chatId, caption)) ok++;
        }
        return ok;
    }

    @WorkerThread
    public static boolean sendMessage(String chatId, String text) {
        if (!isConfigured()) return false;
        RequestBody form = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("text", text)
                .addFormDataPart("disable_web_page_preview", "false")
                .build();
        return post("sendMessage", form);
    }

    @WorkerThread
    public static boolean sendPhoto(File imageFile, String chatId, @Nullable String caption) {
        if (!isConfigured()) return false;
        if (imageFile == null || !imageFile.exists()) {
            Log.e(TAG, "Photo file missing");
            return false;
        }
        MultipartBody.Builder form = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("photo", imageFile.getName(),
                        RequestBody.create(imageFile, MediaType.parse("image/jpeg")));
        if (!TextUtils.isEmpty(caption)) form.addFormDataPart("caption", caption);
        return post("sendPhoto", form.build());
    }

    private static boolean post(String method, RequestBody body) {
        Request request = new Request.Builder()
                .url(API + BuildConfig.TELEGRAM_BOT_TOKEN + "/" + method)
                .post(body)
                .build();
        try (Response response = CLIENT.newCall(request).execute()) {
            if (response.isSuccessful()) return true;
            Log.e(TAG, method + " failed: " + response.code() + " " + bodyString(response.body()));
        } catch (Exception e) {
            Log.e(TAG, method + " error: " + e.getMessage());
        }
        return false;
    }

    private static String bodyString(@Nullable ResponseBody body) {
        if (body == null) return "";
        try {
            return body.string();
        } catch (Exception e) {
            return "";
        }
    }
}
