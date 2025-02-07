package Home_Activity;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.naarishakti.R;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SettingsEmergencyActivity extends AppCompatActivity {

    private static final String TAG = "SettingsEmergency";
    private static final int REQUEST_CONTACTS_PERMISSION = 101;
    private static final int REQUEST_PICK_CONTACT = 102;
    private EditText emergencyMessageEditText;
    private TextInputEditText telegramNumberEditText;
    private TextInputEditText emailAddressEditText;
    private TextInputEditText senderEmailEditText; // New field
    private TextInputEditText senderPasswordEditText; // New field

    private Button saveEmergencySettingsButton;
    private Button addContactButton;
    private Button importContactsButton;
    private ListView emergencyContactsListView;
    private View rootView;
    private ContactAdapter contactsAdapter;
    private List<String> emergencyContactsList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_emergency);

        rootView = findViewById(android.R.id.content);
        emergencyMessageEditText = findViewById(R.id.emergencyMessageEditText);
        telegramNumberEditText = findViewById(R.id.telegramNumberEditText);
        emailAddressEditText = findViewById(R.id.emailAddressEditText);
        senderEmailEditText = findViewById(R.id.senderEmailEditText); // Initialize
        senderPasswordEditText = findViewById(R.id.senderPasswordEditText); // Initialize

        saveEmergencySettingsButton = findViewById(R.id.saveEmergencySettingsButton);
        addContactButton = findViewById(R.id.addContactButton);
        importContactsButton = findViewById(R.id.importContactsButton);
        emergencyContactsListView = findViewById(R.id.emergencyContactsListView);

        contactsAdapter = new ContactAdapter(this, android.R.layout.simple_list_item_1, emergencyContactsList);
        emergencyContactsListView.setAdapter(contactsAdapter);

        loadEmergencySettings();

        addContactButton.setOnClickListener(v -> showAddContactDialog());
        importContactsButton.setOnClickListener(v -> checkContactsPermission());

        emergencyContactsListView.setOnItemClickListener((parent, view, position, id) -> {
            showRemoveContactDialog(position);
        });

        saveEmergencySettingsButton.setOnClickListener(v -> {
            saveEmergencySettings();
            Snackbar.make(rootView, "Emergency Settings Saved!", Snackbar.LENGTH_SHORT).show();
        });
    }

    private void loadEmergencySettings() {
        SharedPreferences sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        String emergencyMessage = sharedPreferences.getString("emergency_message", "");
        String emergencyContacts = sharedPreferences.getString("emergency_contacts", "");
        String telegramNumber = sharedPreferences.getString("telegram_number", "");
        String emailAddress = sharedPreferences.getString("email_address", "");
        String senderEmail = sharedPreferences.getString("sender_email", ""); // Load sender email
        String senderPassword = sharedPreferences.getString("sender_password", ""); // Load sender password

        emergencyMessageEditText.setText(emergencyMessage);
        telegramNumberEditText.setText(telegramNumber);
        emailAddressEditText.setText(emailAddress);
        senderEmailEditText.setText(senderEmail); // Set sender email
        senderPasswordEditText.setText(senderPassword); // Set sender password

        if (!emergencyContacts.isEmpty()) {
            String[] contactsArray = emergencyContacts.split(",");
            emergencyContactsList.addAll(Arrays.asList(contactsArray));
            contactsAdapter.notifyDataSetChanged();
        }
    }

    private void saveEmergencySettings() {
        SharedPreferences sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("emergency_message", emergencyMessageEditText.getText().toString());
        editor.putString("telegram_number", telegramNumberEditText.getText().toString());
        editor.putString("email_address", emailAddressEditText.getText().toString());
        editor.putString("sender_email", senderEmailEditText.getText().toString()); // Save sender email
        editor.putString("sender_password", senderPasswordEditText.getText().toString()); // Save sender password

        editor.putString("emergency_contacts", String.join(",", emergencyContactsList));
        editor.apply();

        Intent intent = new Intent("com.example.naarishakti.UPDATE_SETTINGS");
        sendBroadcast(intent);
    }

    // Method to show the dialog for adding a new contact
    private void showAddContactDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Add Emergency Contact");

        final EditText input = new EditText(this);
        builder.setView(input);

        builder.setPositiveButton("Add", (dialog, which) -> {
            String contact = input.getText().toString().trim();
            if (!contact.isEmpty()) {
                addContact(contact);
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void addContact(String contact) {
        if (!emergencyContactsList.contains(contact)) {
            emergencyContactsList.add(contact);
            contactsAdapter.notifyDataSetChanged();
        } else {
            Toast.makeText(this, "Contact already exists", Toast.LENGTH_SHORT).show();
        }
    }

    // Method to show the dialog for removing a contact
    private void showRemoveContactDialog(int position) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Remove Contact");
        builder.setMessage("Are you sure you want to remove this contact?");

        builder.setPositiveButton("Remove", (dialog, which) -> {
            removeContact(position);
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void removeContact(int position) {
        emergencyContactsList.remove(position);
        contactsAdapter.notifyDataSetChanged();
    }

    // Method to check if the contacts permission is granted, if not, request it
    private void checkContactsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_CONTACTS}, REQUEST_CONTACTS_PERMISSION);
        } else {
            pickContact();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CONTACTS_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pickContact();
            } else {
                Toast.makeText(this, "Contacts permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void pickContact() {
        Intent intent = new Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI);
        startActivityForResult(intent, REQUEST_PICK_CONTACT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_CONTACT && resultCode == RESULT_OK) {
            if (data != null) {
                Uri contactUri = data.getData();
                if (contactUri != null) {
                    String contactNumber = getContactNumber(contactUri);
                    if (contactNumber != null && !contactNumber.isEmpty()) {
                        addContact(contactNumber);
                    } else {
                        Toast.makeText(this, "Could not retrieve contact number", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        }
    }

    private String getContactNumber(Uri contactUri) {
        String contactNumber = null;
        try {
            Cursor cursor = getContentResolver().query(contactUri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                String contactId = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID));
                String hasPhone = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER));

                if (hasPhone.equalsIgnoreCase("1")) {
                    Cursor phoneCursor = getContentResolver().query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            null,
                            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = " + contactId,
                            null,
                            null
                    );
                    if (phoneCursor != null && phoneCursor.moveToFirst()) {
                        contactNumber = phoneCursor.getString(phoneCursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER));
                        phoneCursor.close();
                    }
                }
                cursor.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting contact number: " + e.getMessage());
        }
        return contactNumber;
    }

    private class ContactAdapter extends ArrayAdapter<String> {

        public ContactAdapter(Context context, int resource, List<String> objects) {
            super(context, resource, objects);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = super.getView(position, convertView, parent);
            TextView textView = (TextView) view.findViewById(android.R.id.text1);

            if (position == 0) {
                textView.setTextColor(Color.parseColor("#FF6200EE")); // Highlight the first item
            } else {
                textView.setTextColor(Color.BLACK); // Reset color for other items
            }
            return view;
        }
    }
}