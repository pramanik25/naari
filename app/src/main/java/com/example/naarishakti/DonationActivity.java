package com.example.naarishakti;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;

/**
 * Donation Activity: Allow users to support server costs via UPI donation.
 * Displays QR code for easy scanning; tapping the QR or the UPI ID opens
 * the user's own UPI payment app (Google Pay, PhonePe, Paytm, BHIM…).
 */
public class DonationActivity extends AppCompatActivity {

    private static final String UPI_ID = "vikashstart92@okaxis";
    private static final String PAYEE_NAME = "Naari Kavach";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_donation);

        // Back button
        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        // Copy UPI ID button
        MaterialButton copyButton = findViewById(R.id.copyUpiButton);
        copyButton.setOnClickListener(v -> copyUpiIdToClipboard());

        // QR Code: encodes the full upi://pay link so any UPI app can scan it,
        // and tapping it (or the UPI ID card) opens the user's payment app directly.
        ImageView qrCodeImage = findViewById(R.id.qrCodeImage);
        qrCodeImage.setImageBitmap(generateQRCode(upiUri().toString()));
        findViewById(R.id.qrCard).setOnClickListener(v -> openUpiApp());
        findViewById(R.id.upiIdCard).setOnClickListener(v -> openUpiApp());
    }

    /** The UPI deep link: payee address + name, amount left for the payer to choose. */
    private Uri upiUri() {
        return new Uri.Builder()
                .scheme("upi")
                .authority("pay")
                .appendQueryParameter("pa", UPI_ID)
                .appendQueryParameter("pn", PAYEE_NAME)
                .appendQueryParameter("tn", "Server donation")
                .appendQueryParameter("cu", "INR")
                .build();
    }

    /** Hands the upi://pay link to whichever payment app(s) the user has installed. */
    private void openUpiApp() {
        Intent intent = new Intent(Intent.ACTION_VIEW, upiUri());
        if (intent.resolveActivity(getPackageManager()) == null) {
            Toast.makeText(this, R.string.donation_no_upi_app, Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(Intent.createChooser(intent, getString(R.string.donation_pay_chooser)));
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
     * Generate QR code bitmap from the UPI payment link using ZXing.
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
