package com.example.naarishakti;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;

/**
 * Donation Activity: Allow users to support server costs via UPI donation.
 * Displays QR code for easy scanning and copy-paste UPI ID.
 */
public class DonationActivity extends AppCompatActivity {

    private static final String UPI_ID = "vikashstart92@okaxis";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_donation);

        // Back button
        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        // Copy UPI ID button
        MaterialButton copyButton = findViewById(R.id.copyUpiButton);
        copyButton.setOnClickListener(v -> copyUpiIdToClipboard());

        // QR Code (generate from UPI ID)
        ImageView qrCodeImage = findViewById(R.id.qrCodeImage);
        qrCodeImage.setImageBitmap(generateQRCode(UPI_ID));
    }

    /**
     * Copy UPI ID to clipboard
     */
    private void copyUpiIdToClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("UPI ID", UPI_ID);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, getString(R.string.donation_upi_copied), Toast.LENGTH_SHORT).show();
    }

    /**
     * Generate QR code bitmap from UPI ID using ZXing library.
     * Note: Requires com.google.zxing:core and com.google.zxing:android-core dependencies
     */
    private Bitmap generateQRCode(String text) {
        try {
            com.google.zxing.BarcodeFormat format = com.google.zxing.BarcodeFormat.QR_CODE;
            com.google.zxing.MultiFormatWriter writer = new com.google.zxing.MultiFormatWriter();
            com.google.zxing.common.BitMatrix bitMatrix = writer.encode(text, format, 512, 512);
            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bitmap.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bitmap;
        } catch (Exception e) {
            // Fallback: show placeholder
            Bitmap placeholder = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565);
            placeholder.eraseColor(Color.LTGRAY);
            return placeholder;
        }
    }
}
