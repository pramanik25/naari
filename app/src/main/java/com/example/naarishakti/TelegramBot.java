package com.example.naarishakti;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class TelegramBot {
    private static final String TAG = "TelegramBot";
    private static final String BOT_TOKEN = "7601470265:AAHMvtG-KzV524MnLTStoU-h5CF-H5qjGWs"; // Replace with your bot token
    private static final String BASE_URL = "https://api.telegram.org/bot" + BOT_TOKEN;
    private final OkHttpClient client;

    public TelegramBot(Context context) {
        this.client = new OkHttpClient();
    }

    public boolean sendPhoto(File imageFile, String chatId) {
        if (imageFile == null || !imageFile.exists()) {
            Log.e(TAG, "Image file is null or does not exist.");
            return false;
        }

        try {
            RequestBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId)
                    .addFormDataPart("photo", imageFile.getName(),
                            RequestBody.create(MediaType.parse("image/*"), imageFile))
                    .build();

            Request request = new Request.Builder()
                    .url(BASE_URL + "/sendPhoto")
                    .post(requestBody)
                    .build();

            Response response = client.newCall(request).execute();
            if (response.isSuccessful()) {
                Log.d(TAG, "Image sent to Telegram successfully");
                return true;
            } else {
                Log.e(TAG, "Failed to send image to Telegram: " + response.body().string());
                return false;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error sending image to Telegram: " + e.getMessage());
            return false;
        }
    }
}
