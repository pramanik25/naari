package Home_Activity;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.naarishakti.R;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import SQLite_Database.ProfileDbHelper;

public class ProfileActivity extends AppCompatActivity {

    private ImageView profileImageView;
    private EditText nameEditText, fatherNameEditText, addressEditText, pincodeEditText,
            mobileNumberEditText, fatherMobileNumberEditText, schoolNameEditText,
            classEditText, collegeNameEditText, degreeEditText, companyNameEditText,
            departmentEditText, officeNameEditText;
    private CardView studentCardView, collegeStudentCardView, workingCardView;
    private LinearLayout studentDetailsLayout, collegeStudentDetailsLayout, workingDetailsLayout;
    private Button saveButton;

    private String selectedOccupation = "";
    private static final int PICK_IMAGE_REQUEST = 1;
    private static final String TAG = "ProfileActivity";

    private ProfileDbHelper dbHelper;
    private Long profileId = null; // To track if we are creating or updating

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        dbHelper = new ProfileDbHelper(this);

        profileImageView = findViewById(R.id.profileImageView);
        nameEditText = findViewById(R.id.nameEditText);
        fatherNameEditText = findViewById(R.id.fatherNameEditText);
        addressEditText = findViewById(R.id.addressEditText);
        pincodeEditText = findViewById(R.id.pincodeEditText);
        mobileNumberEditText = findViewById(R.id.mobileNumberEditText);
        fatherMobileNumberEditText = findViewById(R.id.fatherMobileNumberEditText);

        studentCardView = findViewById(R.id.studentCardView);
        collegeStudentCardView = findViewById(R.id.collegeStudentCardView);
        workingCardView = findViewById(R.id.workingCardView);

        studentDetailsLayout = findViewById(R.id.studentDetailsLayout);
        schoolNameEditText = findViewById(R.id.schoolNameEditText);
        classEditText = findViewById(R.id.classEditText);

        collegeStudentDetailsLayout = findViewById(R.id.collegeStudentDetailsLayout);
        collegeNameEditText = findViewById(R.id.collegeNameEditText);
        degreeEditText = findViewById(R.id.degreeEditText);

        workingDetailsLayout = findViewById(R.id.workingDetailsLayout);
        companyNameEditText = findViewById(R.id.companyNameEditText);
        departmentEditText = findViewById(R.id.departmentEditText);
        officeNameEditText = findViewById(R.id.officeNameEditText);

        saveButton = findViewById(R.id.saveButton);

        profileImageView.setOnClickListener(v -> openImagePicker());

        studentCardView.setOnClickListener(v -> setOccupation("student"));
        collegeStudentCardView.setOnClickListener(v -> setOccupation("college_student"));
        workingCardView.setOnClickListener(v -> setOccupation("working"));

        saveButton.setOnClickListener(v -> saveProfileData());

        // Load existing profile if available
        new LoadProfileTask().execute();
    }

    private void openImagePicker() {
        Intent intent = new Intent();
        intent.setType("image/*");
        intent.setAction(Intent.ACTION_GET_CONTENT);
        startActivityForResult(Intent.createChooser(intent, "Select Picture"), PICK_IMAGE_REQUEST);
    }

    private void setOccupation(String occupation) {
        selectedOccupation = occupation;
        studentCardView.setCardBackgroundColor(ContextCompat.getColor(this, R.color.white));
        collegeStudentCardView.setCardBackgroundColor(ContextCompat.getColor(this, R.color.white));
        workingCardView.setCardBackgroundColor(ContextCompat.getColor(this, R.color.white));

        studentDetailsLayout.setVisibility(View.GONE);
        collegeStudentDetailsLayout.setVisibility(View.GONE);
        workingDetailsLayout.setVisibility(View.GONE);

        switch (occupation) {
            case "student":
                studentCardView.setCardBackgroundColor(ContextCompat.getColor(this, R.color.light_gray));
                studentDetailsLayout.setVisibility(View.VISIBLE);
                break;
            case "college_student":
                collegeStudentCardView.setCardBackgroundColor(ContextCompat.getColor(this, R.color.light_gray));
                collegeStudentDetailsLayout.setVisibility(View.VISIBLE);
                break;
            case "working":
                workingCardView.setCardBackgroundColor(ContextCompat.getColor(this, R.color.light_gray));
                workingDetailsLayout.setVisibility(View.VISIBLE);
                break;
        }
    }

    private void saveProfileData() {
        String name = nameEditText.getText().toString().trim();
        String fatherName = fatherNameEditText.getText().toString().trim();
        String address = addressEditText.getText().toString().trim();
        String pincode = pincodeEditText.getText().toString().trim();
        String mobileNumber = mobileNumberEditText.getText().toString().trim();
        String fatherMobileNumber = fatherMobileNumberEditText.getText().toString().trim();

        if (name.isEmpty() || fatherName.isEmpty() || address.isEmpty() || pincode.isEmpty() || mobileNumber.isEmpty()) {
            Toast.makeText(this, "Please fill in the required fields", Toast.LENGTH_SHORT).show();
            return;
        }

        String schoolName = "";
        String className = "";
        String collegeName = "";
        String degree = "";
        String companyName = "";
        String department = "";
        String officeName = "";

        switch (selectedOccupation) {
            case "student":
                schoolName = schoolNameEditText.getText().toString().trim();
                className = classEditText.getText().toString().trim();
                break;
            case "college_student":
                collegeName = collegeNameEditText.getText().toString().trim();
                degree = degreeEditText.getText().toString().trim();
                break;
            case "working":
                companyName = companyNameEditText.getText().toString().trim();
                department = departmentEditText.getText().toString().trim();
                officeName = officeNameEditText.getText().toString().trim();
                break;
        }

        byte[] profileImageBytes = null;
        BitmapDrawable drawable = (BitmapDrawable) profileImageView.getDrawable();
        if (drawable != null) {
            Bitmap bitmap = drawable.getBitmap();
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
            profileImageBytes = byteArrayOutputStream.toByteArray();
        }

        ContentValues values = new ContentValues();
        values.put(ProfileDbHelper.COLUMN_NAME, name);
        values.put(ProfileDbHelper.COLUMN_FATHER_NAME, fatherName);
        values.put(ProfileDbHelper.COLUMN_ADDRESS, address);
        values.put(ProfileDbHelper.COLUMN_PINCODE, pincode);
        values.put(ProfileDbHelper.COLUMN_MOBILE_NUMBER, mobileNumber);
        values.put(ProfileDbHelper.COLUMN_FATHER_MOBILE_NUMBER, fatherMobileNumber);
        values.put(ProfileDbHelper.COLUMN_OCCUPATION, selectedOccupation);
        values.put(ProfileDbHelper.COLUMN_SCHOOL_NAME, schoolName);
        values.put(ProfileDbHelper.COLUMN_CLASS, className);
        values.put(ProfileDbHelper.COLUMN_COLLEGE_NAME, collegeName);
        values.put(ProfileDbHelper.COLUMN_DEGREE, degree);
        values.put(ProfileDbHelper.COLUMN_COMPANY_NAME, companyName);
        values.put(ProfileDbHelper.COLUMN_DEPARTMENT, department);
        values.put(ProfileDbHelper.COLUMN_OFFICE_NAME, officeName);
        values.put(ProfileDbHelper.COLUMN_PROFILE_IMAGE, profileImageBytes);

        if (profileId == null) {
            new SaveProfileTask().execute(values);
        } else {
            new UpdateProfileTask().execute(values);
        }
    }

    private void clearInputFields() {
        nameEditText.getText().clear();
        fatherNameEditText.getText().clear();
        addressEditText.getText().clear();
        pincodeEditText.getText().clear();
        mobileNumberEditText.getText().clear();
        fatherMobileNumberEditText.getText().clear();
        schoolNameEditText.getText().clear();
        classEditText.getText().clear();
        collegeNameEditText.getText().clear();
        degreeEditText.getText().clear();
        companyNameEditText.getText().clear();
        departmentEditText.getText().clear();
        officeNameEditText.getText().clear();
        profileImageView.setImageResource(R.drawable.ic_profile); // Reset profile image
        profileId = null; // Reset profile ID
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri imageUri = data.getData();
            try {
                Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), imageUri);
                profileImageView.setImageBitmap(bitmap);
            } catch (IOException e) {
                Log.e(TAG, "Error loading image from URI", e);
                Toast.makeText(this, "Error loading image.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (dbHelper != null) {
            dbHelper.close();
        }
        super.onDestroy();
    }

    // AsyncTask for saving profile data
    private class SaveProfileTask extends AsyncTask<ContentValues, Void, Long> {
        @Override
        protected Long doInBackground(ContentValues... values) {
            try (SQLiteDatabase db = dbHelper.getWritableDatabase()) {
                return db.insert(ProfileDbHelper.TABLE_NAME, null, values[0]);
            } catch (Exception e) {
                Log.e(TAG, "Error saving profile", e);
                return -1L;
            }
        }

        @Override
        protected void onPostExecute(Long rowId) {
            if (rowId != -1) {
                Toast.makeText(ProfileActivity.this, "Profile Saved Successfully!", Toast.LENGTH_SHORT).show();
                clearInputFields();
            } else {
                Toast.makeText(ProfileActivity.this, "Error saving profile.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // AsyncTask for loading profile data
    private class LoadProfileTask extends AsyncTask<Void, Void, Cursor> {
        @Override
        protected Cursor doInBackground(Void... voids) {
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            String[] projection = {
                    ProfileDbHelper.COLUMN_ID,
                    ProfileDbHelper.COLUMN_NAME,
                    ProfileDbHelper.COLUMN_FATHER_NAME,
                    ProfileDbHelper.COLUMN_ADDRESS,
                    ProfileDbHelper.COLUMN_PINCODE,
                    ProfileDbHelper.COLUMN_MOBILE_NUMBER,
                    ProfileDbHelper.COLUMN_FATHER_MOBILE_NUMBER,
                    ProfileDbHelper.COLUMN_OCCUPATION,
                    ProfileDbHelper.COLUMN_SCHOOL_NAME,
                    ProfileDbHelper.COLUMN_CLASS,
                    ProfileDbHelper.COLUMN_COLLEGE_NAME,
                    ProfileDbHelper.COLUMN_DEGREE,
                    ProfileDbHelper.COLUMN_COMPANY_NAME,
                    ProfileDbHelper.COLUMN_DEPARTMENT,
                    ProfileDbHelper.COLUMN_OFFICE_NAME,
                    ProfileDbHelper.COLUMN_PROFILE_IMAGE
            };
            return db.query(ProfileDbHelper.TABLE_NAME, projection, null, null, null, null, null);
        }

        @Override
        protected void onPostExecute(Cursor cursor) {
            if (cursor != null && cursor.moveToFirst()) {
                profileId = cursor.getLong(cursor.getColumnIndexOrThrow(ProfileDbHelper.COLUMN_ID));
                nameEditText.setText(cursor.getString(cursor.getColumnIndexOrThrow(ProfileDbHelper.COLUMN_NAME)));
                fatherNameEditText.setText(cursor.getString(cursor.getColumnIndexOrThrow(ProfileDbHelper.COLUMN_FATHER_NAME)));
                addressEditText.setText(cursor.getString(cursor.getColumnIndexOrThrow(ProfileDbHelper.COLUMN_ADDRESS)));
                pincodeEditText.setText(cursor.getString(cursor.getColumnIndexOrThrow(ProfileDbHelper.COLUMN_PINCODE)));
                mobileNumberEditText.setText(cursor.getString(cursor.getColumnIndexOrThrow(ProfileDbHelper.COLUMN_MOBILE_NUMBER)));
                fatherMobileNumberEditText.setText(cursor.getString(cursor.getColumnIndexOrThrow(ProfileDbHelper.COLUMN_FATHER_MOBILE_NUMBER)));

                selectedOccupation = cursor.getString(cursor.getColumnIndexOrThrow(ProfileDbHelper.COLUMN_OCCUPATION));
                setOccupation(selectedOccupation);

                schoolNameEditText.setText(cursor.getString(cursor.getColumnIndexOrThrow(ProfileDbHelper.COLUMN_SCHOOL_NAME)));
                classEditText.setText(cursor.getString(cursor.getColumnIndexOrThrow(ProfileDbHelper.COLUMN_CLASS)));
                collegeNameEditText.setText(cursor.getString(cursor.getColumnIndexOrThrow(ProfileDbHelper.COLUMN_COLLEGE_NAME)));
                degreeEditText.setText(cursor.getString(cursor.getColumnIndexOrThrow(ProfileDbHelper.COLUMN_DEGREE)));
                companyNameEditText.setText(cursor.getString(cursor.getColumnIndexOrThrow(ProfileDbHelper.COLUMN_COMPANY_NAME)));
                departmentEditText.setText(cursor.getString(cursor.getColumnIndexOrThrow(ProfileDbHelper.COLUMN_DEPARTMENT)));
                officeNameEditText.setText(cursor.getString(cursor.getColumnIndexOrThrow(ProfileDbHelper.COLUMN_OFFICE_NAME)));

                byte[] imageBytes = cursor.getBlob(cursor.getColumnIndexOrThrow(ProfileDbHelper.COLUMN_PROFILE_IMAGE));
                if (imageBytes != null) {
                    Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                    profileImageView.setImageBitmap(bitmap);
                }
                cursor.close();
                saveButton.setText("Update Profile");
            } else {
                saveButton.setText("Save Profile");
            }
        }
    }

    // AsyncTask for updating profile data
    private class UpdateProfileTask extends AsyncTask<ContentValues, Void, Integer> {
        @Override
        protected Integer doInBackground(ContentValues... values) {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            String selection = ProfileDbHelper.COLUMN_ID + " LIKE ?";
            String[] selectionArgs = {String.valueOf(profileId)};
            return db.update(ProfileDbHelper.TABLE_NAME, values[0], selection, selectionArgs);
        }

        @Override
        protected void onPostExecute(Integer rowsAffected) {
            if (rowsAffected > 0) {
                Toast.makeText(ProfileActivity.this, "Profile Updated Successfully!", Toast.LENGTH_SHORT).show();
                clearInputFields();
                new LoadProfileTask().execute(); // Reload the profile to reflect changes
            } else {
                Toast.makeText(ProfileActivity.this, "Error updating profile.", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
