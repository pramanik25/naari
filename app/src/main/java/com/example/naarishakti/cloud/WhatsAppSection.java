package com.example.naarishakti.cloud;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.naarishakti.R;
import com.example.naarishakti.core.Prefs;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * "WhatsApp alerts" section of the emergency settings (CONTRACT v1.1). Alerts come from the
 * Naari Shakti WhatsApp Business number; every contact joins once via the shared {@code joinUrl}.
 * The switch writes {@link Prefs#WHATSAPP_ALERTS} and syncs her emergency contacts to
 * {@code PUT /me/whatsapp}; each contact shows whether it has joined ({@code GET /me/whatsapp}).
 *
 * <p>Usage from the host activity: construct after {@code setContentView}, call {@link #refresh}
 * in {@code onResume} and {@link #onContactsChanged} whenever the contacts are saved.
 */
public final class WhatsAppSection {

    private static final int MAX_CONTACTS = 5;
    private static final long SYNC_DEBOUNCE_MS = 800L;
    private static final String OUTBOX_KEY = "whatsapp";

    private final AppCompatActivity activity;
    private final View section;
    private final View content;
    private final TextView unavailable;
    private final MaterialSwitch toggle;
    private final View details;
    private final TextView status;
    private final LinearLayout list;
    private final TextView empty;
    private final View errorRow;

    private boolean suppress;
    private int generation;
    /** Last server answer, null until loaded. */
    @Nullable private Remote remote;

    private final Runnable debouncedSync = this::sync;

    /** Parsed {@code /me/whatsapp} response. */
    private static final class Remote {
        boolean configured = true;
        boolean enabled;
        @Nullable String joinUrl;
        final List<RemoteContact> contacts = new ArrayList<>();
    }

    private static final class RemoteContact {
        String number;
        boolean optedIn;
    }

    public WhatsAppSection(@NonNull AppCompatActivity activity, @NonNull View root) {
        this.activity = activity;
        section = root.findViewById(R.id.nbWaSection);
        content = section.findViewById(R.id.nbWaContent);
        unavailable = section.findViewById(R.id.nbWaUnavailable);
        toggle = section.findViewById(R.id.nbWaSwitch);
        details = section.findViewById(R.id.nbWaDetails);
        status = section.findViewById(R.id.nbWaStatus);
        list = section.findViewById(R.id.nbWaList);
        empty = section.findViewById(R.id.nbWaEmpty);
        errorRow = section.findViewById(R.id.nbWaErrorRow);
        section.findViewById(R.id.nbWaRetry).setOnClickListener(v -> refresh());

        setToggle(Prefs.get(activity).getBoolean(Prefs.WHATSAPP_ALERTS, false));
        toggle.setOnCheckedChangeListener((b, checked) -> {
            if (suppress) return;
            Prefs.get(activity).edit().putBoolean(Prefs.WHATSAPP_ALERTS, checked).apply();
            Prefs.notifyChanged(activity);
            render();
            sync();
        });
        render();
    }

    /** Re-reads the join status from the server (call from onResume). */
    public void refresh() {
        if (!Cloud.isActive(activity)) {
            showUnavailable(R.string.nb_wa_cloud_off);
            return;
        }
        final int gen = ++generation;
        final Context app = activity.getApplicationContext();
        if (remote == null) status.setText(R.string.nb_wa_checking);
        Cloud.io().execute(() -> {
            Remote r = null;
            boolean failed = false;
            try {
                r = parse(ApiClient.get(app).call("GET", "/api/v1/me/whatsapp", null), null);
            } catch (CloudException | RuntimeException e) {
                failed = true;
            }
            final Remote fr = r;
            final boolean ff = failed;
            activity.runOnUiThread(() -> {
                if (gen != generation || activity.isFinishing() || activity.isDestroyed()) return;
                if (ff) {
                    errorRow.setVisibility(View.VISIBLE);
                    render();
                    return;
                }
                errorRow.setVisibility(View.GONE);
                remote = fr;
                render();
                // Keep the server in line with this phone (e.g. after a reinstall or offline edit).
                if (fr != null && fr.configured && fr.enabled != isOn()) sync();
            });
        });
    }

    /** Contacts were edited and saved: push them if WhatsApp alerts are on. */
    public void onContactsChanged() {
        render();
        if (!isOn() || !Cloud.isActive(activity)) return;
        Cloud.main().removeCallbacks(debouncedSync);
        Cloud.main().postDelayed(debouncedSync, SYNC_DEBOUNCE_MS);
    }

    // ------------------------------------------------------------------ sync

    private boolean isOn() {
        return Prefs.get(activity).getBoolean(Prefs.WHATSAPP_ALERTS, false);
    }

    private JsonObject body() {
        JsonObject body = new JsonObject();
        boolean on = isOn();
        body.addProperty("enabled", on);
        if (on) {
            JsonArray arr = new JsonArray();
            int n = 0;
            for (Prefs.Contact c : Prefs.getContacts(activity)) {
                if (n++ >= MAX_CONTACTS) break;
                JsonObject o = new JsonObject();
                o.addProperty("name", c.name);
                o.addProperty("number", Prefs.normalizeNumber(c.number));
                arr.add(o);
            }
            body.add("contacts", arr);
        }
        return body;
    }

    private void sync() {
        if (!Cloud.isActive(activity)) return;
        final JsonObject body = body();
        final int gen = ++generation;
        final Context app = activity.getApplicationContext();
        final Remote previous = remote;
        Cloud.io().execute(() -> {
            Remote r = null;
            boolean failed = false;
            try {
                r = parse(ApiClient.get(app).call("PUT", "/api/v1/me/whatsapp", body), previous);
                CloudOutbox.removeKey(app, OUTBOX_KEY);
            } catch (CloudException e) {
                failed = true;
                // Offline: queue the latest state so it reaches the server later.
                if (e.isTransient()) CloudOutbox.add(app, "PUT", "/api/v1/me/whatsapp", body, OUTBOX_KEY);
            } catch (RuntimeException e) {
                failed = true;
            }
            final Remote fr = r;
            final boolean ff = failed;
            activity.runOnUiThread(() -> {
                if (gen != generation || activity.isFinishing() || activity.isDestroyed()) return;
                errorRow.setVisibility(ff ? View.VISIBLE : View.GONE);
                if (fr != null) remote = fr;
                render();
            });
        });
    }

    @Nullable
    private static Remote parse(@Nullable JsonObject o, @Nullable Remote previous) {
        if (o == null) return null;
        Remote r = new Remote();
        // "configured" is only guaranteed on GET; keep the last known value otherwise.
        r.configured = Json.has(o, "configured")
                ? Json.bool(o, "configured", true)
                : (previous == null || previous.configured);
        r.enabled = Json.bool(o, "enabled", false);
        r.joinUrl = Json.str(o, "joinUrl");
        if (r.joinUrl == null && previous != null) r.joinUrl = previous.joinUrl;
        JsonArray arr = Json.arr(o, "contacts");
        if (arr != null) {
            for (JsonElement e : arr) {
                if (e == null || !e.isJsonObject()) continue;
                RemoteContact c = new RemoteContact();
                c.number = Json.str(e.getAsJsonObject(), "number");
                c.optedIn = Json.bool(e.getAsJsonObject(), "optedIn", false);
                if (c.number != null) r.contacts.add(c);
            }
        }
        return r;
    }

    // ------------------------------------------------------------------ rendering

    private void render() {
        if (!Cloud.isActive(activity)) {
            showUnavailable(R.string.nb_wa_cloud_off);
            return;
        }
        if (remote != null && !remote.configured) {
            showUnavailable(R.string.nb_wa_unavailable);
            return;
        }
        content.setVisibility(View.VISIBLE);
        unavailable.setVisibility(View.GONE);
        boolean on = isOn();
        if (toggle.isChecked() != on) setToggle(on);
        details.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) return;

        List<Prefs.Contact> contacts = Prefs.getContacts(activity);
        if (contacts.size() > MAX_CONTACTS) contacts = contacts.subList(0, MAX_CONTACTS);
        list.removeAllViews();
        empty.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);
        int joined = 0;
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (Prefs.Contact c : contacts) {
            RemoteContact rc = match(c.number);
            boolean optedIn = rc != null && rc.optedIn;
            if (optedIn) joined++;
            View row = inflater.inflate(R.layout.cl_item_wa_contact, list, false);
            String name = TextUtils.isEmpty(c.name) ? activity.getString(R.string.nb_wa_unnamed) : c.name;
            ((TextView) row.findViewById(R.id.nbWaName)).setText(name);
            ((TextView) row.findViewById(R.id.nbWaNumber)).setText(c.number);
            TextView chip = row.findViewById(R.id.nbWaChip);
            MaterialButton invite = row.findViewById(R.id.nbWaInvite);
            if (remote == null) {
                chip.setVisibility(View.GONE);
                invite.setVisibility(View.GONE);
            } else {
                chip.setVisibility(View.VISIBLE);
                chip.setText(optedIn ? R.string.nb_wa_joined : R.string.nb_wa_not_joined);
                chip.setTextColor(color(optedIn ? R.color.ns_safe : R.color.ns_warn));
                chip.setBackgroundTintList(ColorStateList.valueOf(
                        color(optedIn ? R.color.ns_safe_container : R.color.ns_warn_container)));
                boolean canInvite = !optedIn && !TextUtils.isEmpty(remote.joinUrl);
                invite.setVisibility(canInvite ? View.VISIBLE : View.GONE);
                final String number = rc != null ? rc.number : Prefs.normalizeNumber(c.number);
                invite.setContentDescription(activity.getString(R.string.nb_wa_invite_cd, name));
                invite.setOnClickListener(v -> invite(number));
            }
            list.addView(row);
        }
        if (remote == null) {
            if (errorRow.getVisibility() != View.VISIBLE) status.setText(R.string.nb_wa_checking);
            else status.setText("");
        } else if (contacts.isEmpty()) {
            status.setText("");
        } else {
            status.setText(activity.getResources().getQuantityString(
                    R.plurals.nb_wa_joined_count, contacts.size(), joined, contacts.size()));
        }
        status.setVisibility(TextUtils.isEmpty(status.getText()) ? View.GONE : View.VISIBLE);
    }

    private void showUnavailable(int text) {
        content.setVisibility(View.GONE);
        unavailable.setVisibility(View.VISIBLE);
        unavailable.setText(text);
    }

    @Nullable
    private RemoteContact match(String localNumber) {
        if (remote == null) return null;
        String key = last10(Prefs.normalizeNumber(localNumber));
        for (RemoteContact rc : remote.contacts) {
            if (last10(Prefs.normalizeNumber(rc.number)).equals(key)) return rc;
        }
        return null;
    }

    private static String last10(String n) {
        String d = n.startsWith("+") ? n.substring(1) : n;
        return d.length() > 10 ? d.substring(d.length() - 10) : d;
    }

    private void setToggle(boolean on) {
        suppress = true;
        toggle.setChecked(on);
        suppress = false;
    }

    private int color(int res) {
        return ContextCompat.getColor(activity, res);
    }

    // ------------------------------------------------------------------ invite

    /** Opens a WhatsApp chat with the contact, prefilled with the join link; share sheet otherwise. */
    private void invite(String number) {
        if (remote == null || TextUtils.isEmpty(remote.joinUrl)) return;
        String text = activity.getString(R.string.nb_wa_invite_text, remote.joinUrl);
        String digits = Prefs.normalizeNumber(number);
        if (digits.startsWith("+")) digits = digits.substring(1);
        else if (digits.length() == 10) digits = "91" + digits; // same rule as the server
        Uri uri = Uri.parse("https://wa.me/" + digits + "?text=" + Uri.encode(text));
        for (String pkg : new String[]{"com.whatsapp", "com.whatsapp.w4b"}) {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, uri).setPackage(pkg));
                return;
            } catch (ActivityNotFoundException ignored) {
                // Try the next WhatsApp flavour, then the share sheet.
            }
        }
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        try {
            activity.startActivity(Intent.createChooser(send, activity.getString(R.string.nb_wa_invite_chooser)));
        } catch (ActivityNotFoundException ignored) {
        }
    }
}
