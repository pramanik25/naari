package SQLite_Database;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class ProfileDbHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "profile.db";
    private static final int DATABASE_VERSION = 2; // Increment database version for schema changes

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
                    COLUMN_TIMESTAMP + " INTEGER)";

    private static final String SQL_DELETE_PROFILES_TABLE =
            "DROP TABLE IF EXISTS " + TABLE_NAME;

    private static final String SQL_DELETE_EMERGENCY_IMAGES_TABLE =
            "DROP TABLE IF EXISTS " + TABLE_EMERGENCY_IMAGES;

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
            db.execSQL(SQL_CREATE_INDEXES);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // This is a basic implementation for upgrading.
        // In a real-world scenario, you might need to migrate data.
        try {
            db.execSQL(SQL_DELETE_PROFILES_TABLE);
            db.execSQL(SQL_DELETE_EMERGENCY_IMAGES_TABLE);
            onCreate(db);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        onUpgrade(db, oldVersion, newVersion);
    }
}