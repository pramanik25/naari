package Utils;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts the bundled Vosk model zip once. A marker file is written only after the whole archive
 * has been extracted, so an extraction interrupted by a crash or kill is redone next time instead
 * of leaving a half-written model behind.
 */
public class VoskModelLoader {

    private static final String TAG = "VoskModelLoader";
    private static final String COMPLETE_MARKER = ".extracted";

    /**
     * @return {@code destinationPath} as a File once the model is fully extracted, or null on failure.
     * Blocking: call off the main thread.
     */
    public static File loadModel(Context context, String assetZipPath, String destinationPath) {
        File destinationDir = new File(destinationPath);
        File marker = new File(destinationDir, COMPLETE_MARKER);
        if (marker.exists()) {
            return destinationDir;
        }

        deleteRecursively(destinationDir);
        if (!destinationDir.mkdirs()) {
            Log.e(TAG, "Failed to create destination directory: " + destinationPath);
            return null;
        }

        try (InputStream inputStream = context.getAssets().open(assetZipPath);
             ZipInputStream zip = new ZipInputStream(inputStream)) {
            String rootCanonical = destinationDir.getCanonicalPath() + File.separator;
            byte[] buffer = new byte[16 * 1024];
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File file = new File(destinationDir, entry.getName());
                // Guard against "../" entries escaping the destination (zip slip).
                if (!file.getCanonicalPath().startsWith(rootCanonical)) {
                    throw new IOException("Blocked zip entry outside destination: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    if (!file.isDirectory() && !file.mkdirs()) {
                        throw new IOException("Failed to create directory: " + file);
                    }
                } else {
                    File parent = file.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Failed to create directory: " + parent);
                    }
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    }
                }
                zip.closeEntry();
            }
            if (!marker.createNewFile()) {
                Log.w(TAG, "Could not write extraction marker");
            }
            Log.d(TAG, "Vosk model extracted to: " + destinationPath);
            return destinationDir;
        } catch (IOException e) {
            Log.e(TAG, "Error extracting Vosk model from asset: " + assetZipPath, e);
            deleteRecursively(destinationDir);
            return null;
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) {
            Log.w(TAG, "Could not delete " + file);
        }
    }
}
