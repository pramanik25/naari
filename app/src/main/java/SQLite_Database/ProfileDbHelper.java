package SQLite_Database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.example.naarishakti.VoiceRecognitionService; // Assuming this is where ImageData is

import java.util.ArrayList;
import java.util.List;

public class ProfileDbHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "profile.db";
    private static final int DATABASE_VERSION = 6; // Increment version to 6 (important!)

    // Table: profiles
    public static final String TABLE_NAME = "profiles";
    public static final String COLUMN_ID = "_id";
    public static final String COLUMN_NAME = "name";
    public static final String COLUMN_FATHER_NAME = "father_name";
    public static final String COLUMN_ADDRESS = "address";
    public static final String COLUMN_PINCODE = "pincode";
    public static final String COLUMN_MOBILE_NUMBER = "mobile_number";
    public static final String COLUMN_FATHER_MOBILE_NUMBER = "father_mobile_number";
    public static final String COLUMN_OCCUPATION = "occupation";
    public static final String COLUMN_SCHOOL_NAME = "school_name";
    public static final String COLUMN_CLASS = "class";
    public static final String COLUMN_COLLEGE_NAME = "college_name";
    public static final String COLUMN_DEGREE = "degree";
    public static final String COLUMN_COMPANY_NAME = "company_name";
    public static final String COLUMN_DEPARTMENT = "department";
    public static final String COLUMN_OFFICE_NAME = "office_name";
    public static final String COLUMN_PROFILE_IMAGE = "profile_image";

    // Table: emergency_images
    public static final String TABLE_EMERGENCY_IMAGES = "emergency_images";
    public static final String COLUMN_IMAGE_ID = "_id";
    public static final String COLUMN_IMAGE_DATA = "image_data";
    public static final String COLUMN_IMAGE_TYPE = "image_type"; // e.g., "front", "back"
    public static final String COLUMN_TIMESTAMP = "timestamp";
    public static final String COLUMN_IMAGE_PATH = "image_path"; // Path to the image file
    public static final String COLUMN_SENT = "sent"; // 0 for not sent, 1 for sent

    // New table for storing Telegram chat IDs
    public static final String TABLE_TELEGRAM_CHATS = "telegram_chats";
    public static final String COLUMN_PHONE_NUMBER = "phone_number";
    public static final String COLUMN_CHAT_ID = "chat_id";

    private static final String SQL_CREATE_PROFILES_TABLE =
            "CREATE TABLE " + TABLE_NAME + " (" +
                    COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                    COLUMN_NAME + " TEXT," +
                    COLUMN_FATHER_NAME + " TEXT," +
                    COLUMN_ADDRESS + " TEXT," +
                    COLUMN_PINCODE + " TEXT," +
                    COLUMN_MOBILE_NUMBER + " TEXT," +
                    COLUMN_FATHER_MOBILE_NUMBER + " TEXT," +
                    COLUMN_OCCUPATION + " TEXT," +
                    COLUMN_SCHOOL_NAME + " TEXT," +
                    COLUMN_CLASS + " TEXT," +
                    COLUMN_COLLEGE_NAME + " TEXT," +
                    COLUMN_DEGREE + " TEXT," +
                    COLUMN_COMPANY_NAME + " TEXT," +
                    COLUMN_DEPARTMENT + " TEXT," +
                    COLUMN_OFFICE_NAME + " TEXT," +
                    COLUMN_PROFILE_IMAGE + " BLOB, " +
                    "UNIQUE(" + COLUMN_MOBILE_NUMBER + ") ON CONFLICT REPLACE)";

    private static final String SQL_CREATE_EMERGENCY_IMAGES_TABLE =
            "CREATE TABLE " + TABLE_EMERGENCY_IMAGES + " (" +
                    COLUMN_IMAGE_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                    COLUMN_IMAGE_DATA + " BLOB," +
                    COLUMN_IMAGE_TYPE + " TEXT," +
                    COLUMN_TIMESTAMP + " INTEGER," +
                    COLUMN_IMAGE_PATH + " TEXT," +
                    COLUMN_SENT + " INTEGER" + // 0 or 1
                    " )";

    // SQL to create the new table
    private static final String SQL_CREATE_TELEGRAM_CHATS_TABLE =
            "CREATE TABLE " + TABLE_TELEGRAM_CHATS + " (" +
                    COLUMN_PHONE_NUMBER + " TEXT PRIMARY KEY," +
                    COLUMN_CHAT_ID + " TEXT" +
                    ")";

    private static final String SQL_DELETE_PROFILES_TABLE =
            "DROP TABLE IF EXISTS " + TABLE_NAME;

    private static final String SQL_DELETE_EMERGENCY_IMAGES_TABLE =
            "DROP TABLE IF EXISTS " + TABLE_EMERGENCY_IMAGES;

    private static final String SQL_DELETE_TELEGRAM_CHATS_TABLE =
            "DROP TABLE IF EXISTS " + TABLE_TELEGRAM_CHATS;

    private static final String SQL_CREATE_INDEXES =
            "CREATE INDEX idx_profiles_mobile_number ON " + TABLE_NAME + " (" + COLUMN_MOBILE_NUMBER + ");";

    public ProfileDbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        try {
            db.execSQL(SQL_CREATE_PROFILES_TABLE);
            db.execSQL(SQL_CREATE_EMERGENCY_IMAGES_TABLE);
            db.execSQL(SQL_CREATE_TELEGRAM_CHATS_TABLE);  // Create the new table
            db.execSQL(SQL_CREATE_INDEXES);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        //Handle the upgrade here if there is a change in the table structure
        Log.d("DB Upgrade", "onUpgrade: oldVersion=" + oldVersion + ", newVersion=" + newVersion);

        if (oldVersion < 2) {
            // Add the new columns (with existence checks)
            if (!columnExists(db, TABLE_EMERGENCY_IMAGES, COLUMN_IMAGE_PATH)) {
                db.execSQL("ALTER TABLE " + TABLE_EMERGENCY_IMAGES + " ADD COLUMN " + COLUMN_IMAGE_PATH + " TEXT;");
            }
            if (!columnExists(db, TABLE_EMERGENCY_IMAGES, COLUMN_SENT)) {
                db.execSQL("ALTER TABLE " + TABLE_EMERGENCY_IMAGES + " ADD COLUMN " + COLUMN_SENT + " INTEGER DEFAULT 0;"); //default to not sent
            }
        }

        if (oldVersion < 4) {
            // Add image_data and ensure image_path and sent are also present (for robustness)
            if (!columnExists(db, TABLE_EMERGENCY_IMAGES, COLUMN_IMAGE_DATA)) {
                db.execSQL("ALTER TABLE " + TABLE_EMERGENCY_IMAGES + " ADD COLUMN " + COLUMN_IMAGE_DATA + " BLOB;");
            }

            if (!columnExists(db, TABLE_EMERGENCY_IMAGES, COLUMN_IMAGE_PATH)) {
                db.execSQL("ALTER TABLE " + TABLE_EMERGENCY_IMAGES + " ADD COLUMN " + COLUMN_IMAGE_PATH + " TEXT;");
            }

            if (!columnExists(db, TABLE_EMERGENCY_IMAGES, COLUMN_SENT)) {
                db.execSQL("ALTER TABLE " + TABLE_EMERGENCY_IMAGES + " ADD COLUMN " + COLUMN_SENT + " INTEGER DEFAULT 0;");
            }
        }

        if (oldVersion < 5) {
            db.execSQL(SQL_CREATE_TELEGRAM_CHATS_TABLE);  // Create the new table
        }

        if (oldVersion < 6) {
            // Add the timestamp column
            if (!columnExists(db, TABLE_EMERGENCY_IMAGES, COLUMN_TIMESTAMP)) {
                db.execSQL("ALTER TABLE " + TABLE_EMERGENCY_IMAGES + " ADD COLUMN " + COLUMN_TIMESTAMP + " INTEGER DEFAULT 0;");  // Add timestamp column
            }
        }


    }

    private boolean columnExists(SQLiteDatabase db, String tableName, String columnName) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("PRAGMA table_info(" + tableName + ")", null);
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String name = cursor.getString(cursor.getColumnIndexOrThrow("name"));
                    if (columnName.equalsIgnoreCase(name)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.e("DB", "Error checking column existence: " + e.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return false;
    }

    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        onUpgrade(db, oldVersion, newVersion);
    }

    // Method to get unsent images from the database
    public List<VoiceRecognitionService.ImageData> getUnsentImages() {
        SQLiteDatabase db = getReadableDatabase();
        String[] projection = {
                COLUMN_IMAGE_ID,
                COLUMN_IMAGE_PATH,
                COLUMN_IMAGE_TYPE,
                COLUMN_TIMESTAMP,
                COLUMN_SENT
        };
        String selection = COLUMN_SENT + " = ?";
        String[] selectionArgs = {"0"}; // 0 for not sent

        Cursor cursor = null;
        List<VoiceRecognitionService.ImageData> images = new ArrayList<>();

        try {
            cursor = db.query(
                    TABLE_EMERGENCY_IMAGES,
                    projection,
                    selection,
                    selectionArgs,
                    null,
                    null,
                    null
            );

            int idColumnIndex = cursor.getColumnIndex(COLUMN_IMAGE_ID);
            int imagePathColumnIndex = cursor.getColumnIndex(COLUMN_IMAGE_PATH);
            int imageTypeColumnIndex = cursor.getColumnIndex(COLUMN_IMAGE_TYPE);
            int timestampColumnIndex = cursor.getColumnIndex(COLUMN_TIMESTAMP);
            int sentColumnIndex = cursor.getColumnIndex(COLUMN_SENT);

            while (cursor.moveToNext()) {
                if (idColumnIndex != -1 && imagePathColumnIndex != -1 && imageTypeColumnIndex != -1 && timestampColumnIndex != -1 && sentColumnIndex != -1) {

                    long id = cursor.getLong(idColumnIndex);
                    String imagePath = cursor.getString(imagePathColumnIndex);
                    String imageType = cursor.getString(imageTypeColumnIndex);
                    long timestamp = cursor.getLong(timestampColumnIndex);
                    int sent = cursor.getInt(sentColumnIndex);

                    VoiceRecognitionService.ImageData imageData = new VoiceRecognitionService.ImageData(id, imagePath, imageType, timestamp, (sent == 1));
                    images.add(imageData);
                } else {
                    // Log an error or handle the missing column(s) appropriately.
                    Log.e("DB", "Missing column in emergency_images table.");
                    // You might want to return an empty list or throw an exception here,
                    // depending on how critical this data is.
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return images;
    }

    // Method to mark an image as sent
    public void markImageAsSent(long imageId) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_SENT, 1); // 1 for sent

        String selection = COLUMN_IMAGE_ID + " = ?";
        String[] selectionArgs = {String.valueOf(imageId)};

        db.update(TABLE_EMERGENCY_IMAGES, values, selection, selectionArgs);
    }

    // Method to mark an image as failed (you might want to add a "failed" column)
    public void markImageAsFailed(long imageId) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        //values.put("failed", 1);
        values.put(COLUMN_SENT, 2); // to avoid repeated sending

        String selection = COLUMN_IMAGE_ID + " = ?";
        String[] selectionArgs = {String.valueOf(imageId)};

        db.update(TABLE_EMERGENCY_IMAGES, values, selection, selectionArgs);
    }

    /**
     * Stores one evidence photo. {@code thumbnailJpeg} should be a small preview (the full-size file
     * stays on disk at {@code imagePath}) so rows stay well under the 2 MB cursor window limit.
     * @return the new row id, or -1 on failure.
     */
    public long insertEmergencyImage(byte[] thumbnailJpeg, String imageType, long timestamp, String imagePath) {
        ContentValues values = new ContentValues();
        values.put(COLUMN_IMAGE_DATA, thumbnailJpeg);
        values.put(COLUMN_IMAGE_TYPE, imageType);
        values.put(COLUMN_TIMESTAMP, timestamp);
        values.put(COLUMN_IMAGE_PATH, imagePath);
        values.put(COLUMN_SENT, 0);
        try {
            return getWritableDatabase().insert(TABLE_EMERGENCY_IMAGES, null, values);
        } catch (Exception e) {
            Log.e("DB", "insertEmergencyImage failed: " + e.getMessage());
            return -1;
        }
    }

    /** Name from the saved profile, or null when no profile/name has been saved. */
    public String getProfileName() {
        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().query(TABLE_NAME, new String[]{COLUMN_NAME},
                    null, null, null, null, COLUMN_ID + " DESC", "1");
            if (cursor.moveToFirst()) {
                String name = cursor.getString(0);
                return name == null || name.trim().isEmpty() ? null : name.trim();
            }
        } catch (Exception e) {
            Log.e("DB", "getProfileName failed: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    /** Her own mobile number from the saved profile (10 digits), or null when none has been saved. */
    public String getProfileMobile() {
        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().query(TABLE_NAME, new String[]{COLUMN_MOBILE_NUMBER},
                    null, null, null, null, COLUMN_ID + " DESC", "1");
            if (cursor.moveToFirst()) {
                String mobile = cursor.getString(0);
                return mobile == null || mobile.trim().isEmpty() ? null : mobile.trim();
            }
        } catch (Exception e) {
            Log.e("DB", "getProfileMobile failed: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    // New methods to manage Telegram chat IDs
    public void saveTelegramChatId(String phoneNumber, String chatId) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_PHONE_NUMBER, phoneNumber);
        values.put(COLUMN_CHAT_ID, chatId);

        db.insertWithOnConflict(TABLE_TELEGRAM_CHATS, null, values, SQLiteDatabase.CONFLICT_REPLACE); // or update, if exists
        Log.d("DB", "saveTelegramChatId: chatId = " + chatId + " number = " + phoneNumber );
    }

    public String getTelegramChatId(String phoneNumber) {
        SQLiteDatabase db = getReadableDatabase();
        String[] projection = {
                COLUMN_CHAT_ID
        };

        String selection = COLUMN_PHONE_NUMBER + " = ?";
        String[] selectionArgs = {phoneNumber};

        Cursor cursor = null;
        String chatId = null;
        try{
            cursor = db.query(
                    TABLE_TELEGRAM_CHATS,
                    projection,
                    selection,
                    selectionArgs,
                    null,
                    null,
                    null
            );
            if (cursor != null && cursor.moveToFirst()) {
                chatId = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CHAT_ID));
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return chatId;

    }
}