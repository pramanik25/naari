package com.example.naarishakti.core;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Single source of truth for every persisted setting. All screens and services read and write
 * through these keys in the one "AppPrefs" file, so a value saved by a screen is the value a
 * service reads.
 */
public final class Prefs {

    public static final String FILE = "AppPrefs";

    /** Local broadcast sent after any settings screen saves; services reload their cached values. */
    public static final String ACTION_SETTINGS_UPDATED = "com.example.naarishakti.ACTION_SETTINGS_UPDATED";

    // ---- Emergency alert ----
    public static final String EMERGENCY_MESSAGE = "emergency_message";
    /** Comma separated entries, each either "number" or "name|number". Use {@link #getContacts}. */
    public static final String EMERGENCY_CONTACTS = "emergency_contacts";
    public static final String RECIPIENT_EMAIL = "email_address";
    public static final String SENDER_EMAIL = "sender_email";
    public static final String SENDER_PASSWORD = "sender_password";
    /** Comma separated Telegram chat ids, filled by TelegramBot.fetchAndStoreChatIds(). */
    public static final String TELEGRAM_CHAT_IDS = "telegram_chat_ids";
    /** Comma separated Telegram chat ids selected to receive emergency alerts (photos and location). */
    public static final String TELEGRAM_EMERGENCY_CHAT_IDS = "telegram_emergency_chat_ids";

    // ---- Voice trigger ----
    public static final String TRIGGER_PHRASE = "trigger_phrase_text";
    public static final String TRIGGER_AUDIO_PATH = "last_trigger_audio_path";
    public static final String TRIGGER_LAST_TEXT = "last_trigger_text";
    public static final String DEFAULT_TRIGGER_PHRASE = "help";

    // ---- Protection state ----
    /** True while the user wants protection on; used to resume after reboot/app update. */
    public static final String PROTECTION_ENABLED = "protection_enabled";
    public static final String SHAKE_ENABLED = "shake_detection_enabled";
    public static final String POWER_BUTTON_ENABLED = "power_button_enabled";

    // ---- Panic behaviour (edited in SettingsFragment) ----
    /** Number of power-button presses that trigger panic. */
    public static final String REQUIRED_PRESSES = "required_presses";
    public static final int DEFAULT_REQUIRED_PRESSES = 3;
    /** Window in which the presses must happen, ms. */
    public static final String PRESS_WINDOW_MS = "sequence_timeout";
    public static final long DEFAULT_PRESS_WINDOW_MS = 2_000L;
    /** Countdown shown before an SOS fires, letting the user cancel a false alarm, ms. 0 = none. */
    public static final String CONFIRMATION_TIMEOUT_MS = "confirmation_timeout";
    public static final long DEFAULT_CONFIRMATION_TIMEOUT_MS = 5_000L;
    /** Max gap between volume key presses in the deactivation pattern, ms. */
    public static final String INPUT_TIMEOUT_MS = "input_timeout";
    public static final long DEFAULT_INPUT_TIMEOUT_MS = 3_000L;
    /** "volume_button" or "shake". */
    public static final String DEACTIVATION_METHOD = "deactivation_method";
    public static final String DEACTIVATION_VOLUME = "volume_button";
    public static final String DEACTIVATION_SHAKE = "shake";

    // ---- Scheduled protection window (TimeSettingsActivity) ----
    public static final String SCHEDULE_ENABLED = "schedule_enabled";
    public static final String SCHEDULE_START_HOUR = "schedule_start_hour";
    public static final String SCHEDULE_START_MINUTE = "schedule_start_minute";
    public static final String SCHEDULE_END_HOUR = "schedule_end_hour";
    public static final String SCHEDULE_END_MINUTE = "schedule_end_minute";
    /** One of SCHEDULE_MODE_* below. */
    public static final String SCHEDULE_MODE = "schedule_mode";
    public static final String SCHEDULE_MODE_DAILY = "daily";
    public static final String SCHEDULE_MODE_TODAY = "today";
    public static final String SCHEDULE_MODE_TOMORROW = "tomorrow";
    public static final String SCHEDULE_MODE_DATE = "date";
    /** Epoch millis of the chosen day (any time on that day) for SCHEDULE_MODE_DATE. */
    public static final String SCHEDULE_DATE_MILLIS = "schedule_date_millis";

    // ---- Geofences ----
    /** JSON array of {id, latitude, longitude, radius, message}. */
    public static final String GEOFENCES = "geofences";

    // ---- Onboarding ----
    public static final String ONBOARDING_DONE = "onboarding_done";

    // =====================================================================================
    // Safety v2 features. Every feature below is opt-in by the owner of this phone; defaults
    // are listed next to each key. Screens write them, modules read them fresh when needed.
    // =====================================================================================

    // ---- Escape (engine) ----
    /** SOS without siren, SOS screen, vibration or flashlight — alerts and evidence only. Default false. */
    public static final String SILENT_SOS = "silent_sos";
    /** Flash the torch as a strobe during a (non-silent) SOS. Default true. */
    public static final String STROBE_ENABLED = "strobe_enabled";
    /** Speak "Help! Call the police" through the speaker between siren loops. Default false. */
    public static final String SPEAK_ALERT = "speak_alert";
    /** If contact N doesn't pick up, call contact N+1. Default true. */
    public static final String CALL_ESCALATION = "call_escalation";
    /** After every contact was tried, call 112. Default false (avoid accidental emergency calls). */
    public static final String ESCALATE_TO_112 = "escalate_to_112";
    /** Fake incoming call: caller name shown and delay in seconds. */
    public static final String FAKE_CALL_NAME = "fake_call_name";
    public static final String FAKE_CALL_DELAY_S = "fake_call_delay_s";

    // ---- Evidence ----
    /** Record audio in 10 s chunks during an SOS and upload each chunk. Default true. */
    public static final String EVIDENCE_AUDIO = "evidence_audio";
    /** Record video instead of still photos during an SOS. Default false (battery/data heavy). */
    public static final String EVIDENCE_VIDEO = "evidence_video";
    /** Upload evidence over mobile data too, not only Wi-Fi. Default true. */
    public static final String EVIDENCE_UPLOAD_MOBILE = "evidence_upload_mobile";

    // ---- Extra triggers ----
    /** On-device scream / distress sound detection while protection is on. Default false. */
    public static final String SCREAM_DETECTION = "scream_detection";
    /** Hard fall / struggle detection while protection is on. Default false. */
    public static final String FALL_DETECTION = "fall_detection";
    /** Triple-press of the wired/Bluetooth headset button. Default true. */
    public static final String HEADSET_TRIGGER = "headset_trigger";
    /** MAC address of a paired BLE panic button (iTag-style), empty when none. */
    public static final String BLE_BUTTON_ADDRESS = "ble_button_address";
    public static final String BLE_BUTTON_NAME = "ble_button_name";

    // ---- Device guard ----
    /** SMS contacts if the SIM card is changed. Default true. */
    public static final String SIM_ALERT = "sim_alert";
    /** Snapshot of the SIM identity (serial/subscription id hash) to detect swaps. */
    public static final String SIM_SNAPSHOT = "sim_snapshot";
    /** Send a last location when the phone is shutting down during protection. Default true. */
    public static final String SHUTDOWN_ALERT = "shutdown_alert";
    /** Send a last location when battery drops to 5% during protection. Default true. */
    public static final String LOW_BATTERY_ALERT = "low_battery_alert";
    /** Volume-key SOS pattern with the screen off (needs the accessibility service). Default true once enabled. */
    public static final String VOLUME_KEY_TRIGGER = "volume_key_trigger";

    // ---- Community ----
    /** Broadcast/receive SOS to nearby app users over Bluetooth/Wi-Fi with no internet. Default false. */
    public static final String MESH_ENABLED = "mesh_enabled";
    /** Volunteer as a nearby helper (receive alerts from people within ~2–5 km). Default false until she agrees. */
    public static final String HELPER_OPT_IN = "helper_opt_in";
    /** The one-time "Help women near you?" question has been answered (yes or no). */
    public static final String HELPER_ASKED = "helper_asked";
    /** When her SOS fires, also alert everyone nearby who agreed to help. Default true. */
    public static final String NEARBY_BROADCAST = "nearby_broadcast";
    /** Also alert her emergency contacts on WhatsApp (via the Naari Shakti WhatsApp number). Default false. */
    public static final String WHATSAPP_ALERTS = "whatsapp_alerts";

    // ---- Cloud (Naari Shakti API server) ----
    /** Account id assigned by the API on device registration, empty until registered. */
    public static final String CLOUD_USER_ID = "cloud_user_id";
    /** 6-character code others enter to become this user's guardian (cached from the API). */
    public static final String GUARDIAN_CODE = "guardian_code";

    private Prefs() {}

    public static SharedPreferences get(Context context) {
        return Kv.get(context, FILE);
    }

    /** Tell running services that settings changed. Call after every save. */
    public static void notifyChanged(Context context) {
        LocalBroadcastManager.getInstance(context.getApplicationContext())
                .sendBroadcast(new Intent(ACTION_SETTINGS_UPDATED));
    }

    // ---- Contacts ----

    public static final class Contact {
        public final String name;
        public final String number;

        public Contact(String name, String number) {
            this.name = name == null ? "" : name.trim();
            this.number = number == null ? "" : number.trim();
        }

        public String displayName() {
            return TextUtils.isEmpty(name) ? number : name;
        }

        String encode() {
            String safeName = name.replace(",", " ").replace("|", " ");
            return TextUtils.isEmpty(safeName) ? number : safeName + "|" + number;
        }
    }

    public static List<Contact> getContacts(Context context) {
        String raw = get(context).getString(EMERGENCY_CONTACTS, "");
        if (TextUtils.isEmpty(raw)) return Collections.emptyList();
        List<Contact> out = new ArrayList<>();
        for (String entry : raw.split(",")) {
            entry = entry.trim();
            if (entry.isEmpty()) continue;
            int bar = entry.indexOf('|');
            Contact c = bar >= 0
                    ? new Contact(entry.substring(0, bar), entry.substring(bar + 1))
                    : new Contact("", entry);
            if (!normalizeNumber(c.number).isEmpty()) out.add(c);
        }
        return out;
    }

    public static void setContacts(Context context, List<Contact> contacts) {
        List<String> encoded = new ArrayList<>();
        for (Contact c : contacts) encoded.add(c.encode());
        get(context).edit().putString(EMERGENCY_CONTACTS, TextUtils.join(",", encoded)).apply();
    }

    /** Dialable numbers of all emergency contacts, in priority order (first is called). */
    public static List<String> getContactNumbers(Context context) {
        List<String> out = new ArrayList<>();
        for (Contact c : getContacts(context)) out.add(normalizeNumber(c.number));
        return out;
    }

    /** Keeps digits and a leading '+'. */
    public static String normalizeNumber(String number) {
        if (number == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < number.length(); i++) {
            char ch = number.charAt(i);
            if (Character.isDigit(ch) || (ch == '+' && sb.length() == 0)) sb.append(ch);
        }
        return sb.toString();
    }

    public static String getEmergencyMessage(Context context) {
        String msg = get(context).getString(EMERGENCY_MESSAGE, "");
        return TextUtils.isEmpty(msg) ? "I need help! This is an emergency." : msg;
    }

    public static String getTriggerPhrase(Context context) {
        String phrase = get(context).getString(TRIGGER_PHRASE, DEFAULT_TRIGGER_PHRASE);
        return TextUtils.isEmpty(phrase) ? DEFAULT_TRIGGER_PHRASE : phrase.trim().toLowerCase();
    }

    // ---- Telegram chat ids ----

    public static List<String> getTelegramChatIds(Context context) {
        String raw = get(context).getString(TELEGRAM_CHAT_IDS, "");
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) return out;
        for (String id : raw.split(",")) {
            if (!id.trim().isEmpty()) out.add(id.trim());
        }
        return out;
    }

    public static void addTelegramChatIds(Context context, List<String> ids) {
        List<String> merged = getTelegramChatIds(context);
        for (String id : ids) if (!merged.contains(id)) merged.add(id);
        get(context).edit().putString(TELEGRAM_CHAT_IDS, TextUtils.join(",", merged)).apply();
    }

    public static List<String> getTelegramEmergencyChatIds(Context context) {
        String raw = get(context).getString(TELEGRAM_EMERGENCY_CHAT_IDS, "");
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) return out;
        for (String id : raw.split(",")) {
            if (!id.trim().isEmpty()) out.add(id.trim());
        }
        return out;
    }

    public static void setTelegramEmergencyChatIds(Context context, List<String> ids) {
        get(context).edit().putString(TELEGRAM_EMERGENCY_CHAT_IDS, TextUtils.join(",", ids)).apply();
    }
}
