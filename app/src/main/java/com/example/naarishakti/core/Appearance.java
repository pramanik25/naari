package com.example.naarishakti.core;

import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Theme (dark / light / follow system) and in-app language. Both are applied app-wide through
 * AppCompat, so every AppCompatActivity picks them up; the language choice is persisted by
 * AppCompat itself (see AppLocalesMetadataHolderService in the manifest) and by Android 13+.
 */
public final class Appearance {

    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_SYSTEM = "system";

    private static final String KEY_THEME = "appearance_theme";

    /** A supported app language. {@code tag} is a BCP-47 tag; "" means "use the phone's language". */
    public static final class Language {
        public final String tag;
        /** The language's name written in itself, e.g. "हिन्दी". */
        public final String nativeName;
        public final String englishName;

        Language(String tag, String nativeName, String englishName) {
            this.tag = tag;
            this.nativeName = nativeName;
            this.englishName = englishName;
        }
    }

    /** English plus the major Indian languages and Nepali, which the app is translated into. */
    private static final List<Language> LANGUAGES = Collections.unmodifiableList(Arrays.asList(
            new Language("en", "English", "English"),
            new Language("hi", "हिन्दी", "Hindi"),
            new Language("bn", "বাংলা", "Bengali"),
            new Language("mr", "मराठी", "Marathi"),
            new Language("te", "తెలుగు", "Telugu"),
            new Language("ta", "தமிழ்", "Tamil"),
            new Language("gu", "ગુજરાતી", "Gujarati"),
            new Language("kn", "ಕನ್ನಡ", "Kannada"),
            new Language("ml", "മലയാളം", "Malayalam"),
            new Language("pa", "ਪੰਜਾਬੀ", "Punjabi"),
            new Language("or", "ଓଡ଼ିଆ", "Odia"),
            new Language("as", "অসমীয়া", "Assamese"),
            new Language("ur", "اردو", "Urdu"),
            new Language("ne", "नेपाली", "Nepali")
    ));

    private Appearance() {}

    /** Call once from Application.onCreate, before any activity is created. */
    public static void apply(Context context) {
        AppCompatDelegate.setDefaultNightMode(nightMode(getTheme(context)));
    }

    public static String getTheme(Context context) {
        String theme = Prefs.get(context).getString(KEY_THEME, THEME_DARK);
        return THEME_LIGHT.equals(theme) || THEME_SYSTEM.equals(theme) ? theme : THEME_DARK;
    }

    /** Saves and applies the theme; open activities are recreated by AppCompat. */
    public static void setTheme(Context context, String theme) {
        Prefs.get(context).edit().putString(KEY_THEME, theme).apply();
        AppCompatDelegate.setDefaultNightMode(nightMode(theme));
    }

    private static int nightMode(String theme) {
        if (THEME_LIGHT.equals(theme)) return AppCompatDelegate.MODE_NIGHT_NO;
        if (THEME_SYSTEM.equals(theme)) return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        return AppCompatDelegate.MODE_NIGHT_YES;
    }

    public static List<Language> languages() {
        return LANGUAGES;
    }

    /** The chosen app language tag, or "" when following the phone's language. */
    public static String getLanguage() {
        LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();
        if (locales.isEmpty()) return "";
        Locale first = locales.get(0);
        return first == null ? "" : first.getLanguage();
    }

    /** Switches the app language ("" = phone's language); open activities are recreated. */
    public static void setLanguage(String tag) {
        AppCompatDelegate.setApplicationLocales(tag == null || tag.isEmpty()
                ? LocaleListCompat.getEmptyLocaleList()
                : LocaleListCompat.forLanguageTags(tag));
    }
}
