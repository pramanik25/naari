package Home_Activity;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.naarishakti.R;

import SQLite_Database.ProfileDbHelper;

public class DatabaseViewActivity extends AppCompatActivity {

    private static final String TAG = "DatabaseViewActivity";
    private ProfileDbHelper dbHelper;
    private SQLiteDatabase db;
    private LinearLayout imagesContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_database_view);

        imagesContainer = findViewById(R.id.imagesContainer);
        dbHelper = new ProfileDbHelper(this);
        db = dbHelper.getReadableDatabase();

        loadEmergencyImages();
        loadProfileData();
    }

    private void loadProfileData() {
        TextView nameTextView = findViewById(R.id.profileNameTextView);
        ImageView profileImageView = findViewById(R.id.profileImageView);

        try {
            Cursor cursor = db.query(ProfileDbHelper.TABLE_NAME, null, null, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(cursor.getColumnIndexOrThrow(ProfileDbHelper.COLUMN_NAME));
                byte[] imageBytes = cursor.getBlob(cursor.getColumnIndexOrThrow(ProfileDbHelper.COLUMN_PROFILE_IMAGE));

                nameTextView.setText("Name: " + name);

                if (imageBytes != null) {
                    Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                    profileImageView.setImageBitmap(bitmap);
                }
                cursor.close();
            } else {
                Toast.makeText(this, "No profile data found.", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading profile data: " + e.getMessage());
            Toast.makeText(this, "Error loading profile data.", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadEmergencyImages() {
        try {
            Cursor cursor = db.query(ProfileDbHelper.TABLE_EMERGENCY_IMAGES,
                    null, null, null, null, null,
                    ProfileDbHelper.COLUMN_TIMESTAMP + " DESC"); // Order by latest first

            if (cursor != null && cursor.moveToFirst()) {
                do {
                    final int imageId = cursor.getInt(cursor.getColumnIndexOrThrow(ProfileDbHelper.COLUMN_ID));
                    byte[] imageBytes = cursor.getBlob(cursor.getColumnIndexOrThrow(ProfileDbHelper.COLUMN_IMAGE_DATA));
                    String imageType = cursor.getString(cursor.getColumnIndexOrThrow(ProfileDbHelper.COLUMN_IMAGE_TYPE));

                    if (imageBytes != null) {
                        Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                        ImageView imageView = new ImageView(this);
                        imageView.setLayoutParams(new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT));
                        imageView.setImageBitmap(bitmap);
                        imagesContainer.addView(imageView);

                        TextView typeTextView = new TextView(this);
                        typeTextView.setText(imageType.toUpperCase() + " Camera");
                        imagesContainer.addView(typeTextView);

                        Button deleteButton = new Button(this);
                        deleteButton.setText("Delete");
                        deleteButton.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                deleteImage(imageId);
                            }
                        });
                        imagesContainer.addView(deleteButton);
                    }
                } while (cursor.moveToNext());
                cursor.close();
            } else {
                TextView noImagesText = new TextView(this);
                noImagesText.setText("No emergency images found.");
                imagesContainer.addView(noImagesText);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading emergency images: " + e.getMessage());
            Toast.makeText(this, "Error loading emergency images.", Toast.LENGTH_SHORT).show();
        }
    }

    private void deleteImage(int imageId) {
        try {
            int rowsDeleted = db.delete(ProfileDbHelper.TABLE_EMERGENCY_IMAGES,
                    ProfileDbHelper.COLUMN_ID + " = ?",
                    new String[]{String.valueOf(imageId)});
            if (rowsDeleted > 0) {
                Toast.makeText(this, "Image deleted successfully.", Toast.LENGTH_SHORT).show();
                imagesContainer.removeAllViews();  // Clear the container to refresh it
                loadEmergencyImages();  // Reload images to reflect the deleted image
            } else {
                Toast.makeText(this, "Failed to delete the image.", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error deleting image: " + e.getMessage());
            Toast.makeText(this, "Error deleting image.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (db != null && db.isOpen()) {
            db.close();
        }
        if (dbHelper != null) {
            dbHelper.close();
        }
    }
}
