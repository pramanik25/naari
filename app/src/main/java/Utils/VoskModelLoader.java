package Utils;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class VoskModelLoader {

    private static final String TAG = "VoskModelLoader";

    public static File loadModel(Context context, String assetZipPath, String destinationPath) {
        File destinationDir = new File(destinationPath);
        if (!destinationDir.exists()) {
            try {
                if (!destinationDir.mkdirs()) {
                    Log.e(TAG, "Failed to create destination directory: " + destinationPath);
                    return null;
                }
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException creating destination directory: " + destinationPath, e);
                return null;
            }
        } else {
            // Check if the directory is empty. If not, assume model is already loaded.
            if (destinationDir.list() != null && destinationDir.list().length > 0) {
                Log.d(TAG, "Model already exists at: " + destinationPath);
                return destinationDir;
            }
        }

        AssetManager assetManager = context.getAssets();
        try (InputStream inputStream = assetManager.open(assetZipPath);
             ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {

            ZipEntry zipEntry;
            while ((zipEntry = zipInputStream.getNextEntry()) != null) {
                String fileName = zipEntry.getName();
                File file = new File(destinationDir, fileName);
                Log.d(TAG, "Extracting: " + file.getAbsolutePath());

                if (zipEntry.isDirectory()) {
                    try {
                        if (!file.mkdirs()) {
                            Log.e(TAG, "Failed to create directory: " + file.getAbsolutePath());
                        }
                    } catch (SecurityException e) {
                        Log.e(TAG, "SecurityException creating directory: " + file.getAbsolutePath(), e);
                    }
                } else {
                    try (FileOutputStream outputStream = new FileOutputStream(file)) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = zipInputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, bytesRead);
                        }
                    } catch (IOException e) {
                        Log.e(TAG, "Error writing file: " + file.getAbsolutePath(), e);
                    }
                }
                zipInputStream.closeEntry(); // Ensure entry is closed after processing
            }
            Log.d(TAG, "Vosk model loaded successfully to: " + destinationPath);
            return destinationDir; // Return the directory where the model is extracted
        } catch (IOException e) {
            Log.e(TAG, "Error loading Vosk model from asset: " + assetZipPath, e);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error during Vosk model loading", e);
            return null;
        }
    }
}