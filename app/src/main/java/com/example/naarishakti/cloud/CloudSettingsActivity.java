package com.example.naarishakti.cloud;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.ColorRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.naarishakti.R;
import com.example.naarishakti.core.Prefs;
import com.example.naarishakti.core.ProtectionController;
import com.example.naarishakti.evidence.EvidenceStore;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Cloud & guardians": server status, the user's guardian code, linking to someone else's code,
 * both guardian lists, the nearby-helper opt-in and "Delete my cloud data".
 */
public class CloudSettingsActivity extends AppCompatActivity {

    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private View root;
    private LinearProgressIndicator progress;
    private View errorCard;
    private TextView errorText;
    private View offCard;
    private TextView offCaption;
    private MaterialButton enableCloudButton;
    private View content;

    private ImageView serverDot;
    private TextView serverValue;
    private ImageView accountDot;
    private TextView accountValue;
    private TextView pendingValue;

    private TextView codeText;
    private MaterialButton copyButton;
    private MaterialButton shareButton;

    private TextInputLayout linkLayout;
    private TextInputEditText linkInput;
    private MaterialButton linkButton;

    private LinearLayout guardiansList;
    private TextView guardiansEmpty;
    private LinearLayout guardingList;
    private TextView guardingEmpty;

    private MaterialSwitch helperSwitch;
    private MaterialButton deleteButton;

    private int loadGeneration;
    private boolean suppressSwitch;
    @Nullable private String guardianCode;

    private ActivityResultLauncher<String[]> locationPermission;

    /** Result of one background load. */
    private static final class State {
        boolean reachable;
        boolean registered;
        int pending = -1;
        String code;
        final List<Person> guardians = new ArrayList<>();
        final List<Person> guarding = new ArrayList<>();
        boolean listsLoaded;
        CloudException error;
    }

    private static final class Person {
        String userId;
        String name;
        long linkedAt;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.cl_activity_cloud_settings);
        bindViews();

        locationPermission = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean granted = false;
                    for (Map.Entry<String, Boolean> e : result.entrySet()) {
                        if (Boolean.TRUE.equals(e.getValue())) granted = true;
                    }
                    if (granted) enableHelper();
                    else snack(R.string.cl_helper_perm_denied);
                });

        findViewById(R.id.clBack).setOnClickListener(v -> finish());
        findViewById(R.id.clRetry).setOnClickListener(v -> load());
        enableCloudButton.setOnClickListener(v -> reEnableCloud());
        copyButton.setOnClickListener(v -> copyCode());
        shareButton.setOnClickListener(v -> shareCode());
        linkButton.setOnClickListener(v -> linkGuardian());
        deleteButton.setOnClickListener(v -> confirmDelete());

        linkInput.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new InputFilter.LengthFilter(6)});
        linkInput.setOnEditorActionListener((v, actionId, event) -> {
            linkGuardian();
            return true;
        });

        helperSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (suppressSwitch) return;
                if (checked) {
                    // Stay off until the explainer and the permission are accepted.
                    setHelperSwitch(false);
                    askHelperOptIn();
                } else {
                    CloudHelper.setOptIn(CloudSettingsActivity.this, false, false);
                }
            }
        });

        load();
    }

    private void bindViews() {
        root = findViewById(R.id.clRoot);
        progress = findViewById(R.id.clProgress);
        errorCard = findViewById(R.id.clErrorCard);
        errorText = findViewById(R.id.clErrorText);
        offCard = findViewById(R.id.clOffCard);
        offCaption = findViewById(R.id.clOffCaption);
        enableCloudButton = findViewById(R.id.clEnableCloud);
        content = findViewById(R.id.clContent);
        serverDot = findViewById(R.id.clServerDot);
        serverValue = findViewById(R.id.clServerValue);
        accountDot = findViewById(R.id.clAccountDot);
        accountValue = findViewById(R.id.clAccountValue);
        pendingValue = findViewById(R.id.clPendingValue);
        codeText = findViewById(R.id.clCode);
        copyButton = findViewById(R.id.clCopy);
        shareButton = findViewById(R.id.clShare);
        linkLayout = findViewById(R.id.clLinkLayout);
        linkInput = findViewById(R.id.clLinkInput);
        linkButton = findViewById(R.id.clLinkButton);
        guardiansList = findViewById(R.id.clGuardiansList);
        guardiansEmpty = findViewById(R.id.clGuardiansEmpty);
        guardingList = findViewById(R.id.clGuardingList);
        guardingEmpty = findViewById(R.id.clGuardingEmpty);
        helperSwitch = findViewById(R.id.clHelperSwitch);
        deleteButton = findViewById(R.id.clDelete);
    }

    // ================================================================== loading

    private void load() {
        if (!Cloud.isAvailable()) {
            showOff(R.string.cl_off_caption_unconfigured, false);
            return;
        }
        if (Cloud.isOptedOut(this)) {
            showOff(R.string.cl_off_caption_deleted, true);
            return;
        }
        offCard.setVisibility(View.GONE);
        content.setVisibility(View.VISIBLE);
        errorCard.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        setStatus(serverDot, serverValue, R.string.cl_status_checking, R.color.ns_text_faint);
        setHelperSwitch(CloudHelper.isOptedIn(this));
        String cached = Prefs.get(this).getString(Prefs.GUARDIAN_CODE, null);
        if (guardianCode == null && !TextUtils.isEmpty(cached)) showCode(cached);

        final int gen = ++loadGeneration;
        final Context app = getApplicationContext();
        Cloud.io().execute(() -> {
            State loaded;
            try {
                loaded = fetch(app);
            } catch (RuntimeException e) {
                loaded = new State();
                loaded.error = new CloudException.BadResponse(String.valueOf(e.getMessage()));
            }
            final State s = loaded;
            runOnUiThread(() -> {
                if (gen != loadGeneration || isFinishing() || isDestroyed()) return;
                render(s);
            });
        });
    }

    /** Blocking; runs on the cloud IO pool. */
    private static State fetch(Context app) {
        State s = new State();
        try {
            s.pending = EvidenceStore.pending(app, 100).size();
        } catch (Throwable ignored) {
            s.pending = -1;
        }
        ApiClient api = ApiClient.get(app);
        s.reachable = api.ping();
        try {
            api.ensureRegistered();
            s.registered = true;
            JsonObject me = api.call("GET", "/api/v1/me", null);
            s.code = Json.str(me, "guardianCode");
            if (s.code != null) {
                Prefs.get(app).edit().putString(Prefs.GUARDIAN_CODE, s.code).apply();
            }
            if (Json.str(me, "name") == null) {
                String name = api.profileName();
                if (!TextUtils.isEmpty(name)) {
                    JsonObject patch = new JsonObject();
                    patch.addProperty("name", name);
                    try {
                        api.call("PATCH", "/api/v1/me", patch);
                    } catch (CloudException ignored) {
                        // Cosmetic; guardians see "Someone you protect" until it syncs.
                    }
                }
            }
            JsonObject g = api.call("GET", "/api/v1/guardians", null);
            parsePeople(Json.arr(g, "guardians"), s.guardians);
            parsePeople(Json.arr(g, "guarding"), s.guarding);
            s.listsLoaded = true;
        } catch (CloudException e) {
            s.error = e;
            s.registered = api.isRegistered();
        }
        return s;
    }

    private static void parsePeople(@Nullable JsonArray arr, List<Person> out) {
        if (arr == null) return;
        for (JsonElement e : arr) {
            if (e == null || !e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            Person p = new Person();
            p.userId = Json.str(o, "userId");
            p.name = Json.str(o, "name");
            p.linkedAt = Json.lng(o, "linkedAt", 0L);
            if (p.userId != null) out.add(p);
        }
    }

    private void render(State s) {
        progress.setVisibility(View.GONE);
        setStatus(serverDot, serverValue,
                s.reachable ? R.string.cl_status_reachable : R.string.cl_status_unreachable,
                s.reachable ? R.color.ns_safe : R.color.ns_danger);
        setStatus(accountDot, accountValue,
                s.registered ? R.string.cl_status_registered : R.string.cl_status_not_registered,
                s.registered ? R.color.ns_safe : R.color.ns_warn);
        pendingValue.setText(s.pending < 0
                ? getString(R.string.cl_status_unknown)
                : getResources().getQuantityString(R.plurals.cl_pending_items, s.pending, s.pending));

        if (s.code != null) showCode(s.code);
        else if (guardianCode == null) showCode(null);

        if (s.listsLoaded) {
            renderPeople(guardiansList, guardiansEmpty, s.guardians, true);
            renderPeople(guardingList, guardingEmpty, s.guarding, false);
        }
        if (s.error != null) showError(s.error);
    }

    private void showOff(@StringRes int caption, boolean canEnable) {
        progress.setVisibility(View.GONE);
        errorCard.setVisibility(View.GONE);
        content.setVisibility(View.GONE);
        offCard.setVisibility(View.VISIBLE);
        offCaption.setText(caption);
        enableCloudButton.setVisibility(canEnable ? View.VISIBLE : View.GONE);
    }

    private void showError(CloudException e) {
        errorText.setText(errorMessage(e));
        errorCard.setVisibility(View.VISIBLE);
    }

    private String errorMessage(CloudException e) {
        if (e instanceof CloudException.Network) {
            return getString(isOnline() ? R.string.cl_error_server : R.string.cl_error_offline);
        }
        if (e.isTransient()) return getString(R.string.cl_error_server);
        return getString(R.string.cl_error_generic, e.status);
    }

    @SuppressWarnings("deprecation")
    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    private void setStatus(ImageView dot, TextView value, @StringRes int text, @ColorRes int color) {
        value.setText(text);
        dot.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, color)));
    }

    // ================================================================== guardian code

    private void showCode(@Nullable String code) {
        guardianCode = code;
        boolean has = !TextUtils.isEmpty(code);
        codeText.setText(has ? code : getString(R.string.cl_code_placeholder));
        copyButton.setEnabled(has);
        shareButton.setEnabled(has);
    }

    private void copyCode() {
        if (guardianCode == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.cl_section_code), guardianCode));
            snack(R.string.cl_copied);
        }
    }

    private void shareCode() {
        if (guardianCode == null) return;
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, getString(R.string.cl_share_text, guardianCode));
        startActivity(Intent.createChooser(send, getString(R.string.cl_share_chooser)));
    }

    // ================================================================== linking

    private void linkGuardian() {
        String code = linkInput.getText() == null ? "" : linkInput.getText().toString().trim().toUpperCase(Locale.US);
        if (code.length() != 6 || !validCode(code)) {
            linkLayout.setError(getString(R.string.cl_link_err_length));
            return;
        }
        if (code.equals(guardianCode)) {
            linkLayout.setError(getString(R.string.cl_link_err_self));
            return;
        }
        linkLayout.setError(null);
        linkButton.setEnabled(false);
        final Context app = getApplicationContext();
        final JsonObject body = new JsonObject();
        body.addProperty("code", code);
        Cloud.io().execute(() -> {
            JsonObject res = null;
            CloudException err = null;
            try {
                res = ApiClient.get(app).call("POST", "/api/v1/guardians/link", body);
            } catch (CloudException e) {
                err = e;
            }
            final JsonObject fRes = res;
            final CloudException fErr = err;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                linkButton.setEnabled(true);
                if (fErr == null) {
                    linkInput.setText(null);
                    String name = Json.str(fRes, "wardName");
                    if (name == null) name = getString(R.string.cl_unnamed);
                    snack(getString(R.string.cl_link_success, name));
                    load();
                } else {
                    linkLayout.setError(linkError(fErr));
                }
            });
        });
    }

    private static boolean validCode(String code) {
        for (int i = 0; i < code.length(); i++) {
            if (CODE_ALPHABET.indexOf(code.charAt(i)) < 0) return false;
        }
        return true;
    }

    private String linkError(CloudException e) {
        if ("invalid_code".equals(e.code)) return getString(R.string.cl_link_err_invalid);
        if ("self_link".equals(e.code)) return getString(R.string.cl_link_err_self);
        if ("too_many_attempts".equals(e.code) || e.status == 429) return getString(R.string.cl_link_err_limit);
        return errorMessage(e);
    }

    // ================================================================== lists

    private void renderPeople(LinearLayout list, TextView empty, List<Person> people, final boolean guardians) {
        list.removeAllViews();
        empty.setVisibility(people.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (final Person p : people) {
            View row = inflater.inflate(R.layout.cl_item_person, list, false);
            final String name = TextUtils.isEmpty(p.name) ? getString(R.string.cl_unnamed) : p.name;
            ((TextView) row.findViewById(R.id.clPersonInitials)).setText(initials(name));
            ((TextView) row.findViewById(R.id.clPersonName)).setText(name);
            TextView caption = row.findViewById(R.id.clPersonCaption);
            if (p.linkedAt > 0) {
                caption.setText(getString(R.string.cl_linked_on,
                        DateUtils.formatDateTime(this, p.linkedAt,
                                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_ABBREV_MONTH)));
            } else {
                caption.setVisibility(View.GONE);
            }
            View remove = row.findViewById(R.id.clPersonRemove);
            remove.setContentDescription(getString(R.string.cl_remove_person, name));
            remove.setOnClickListener(v -> confirmRemove(p, name, guardians));
            list.addView(row);
        }
    }

    private static String initials(String name) {
        StringBuilder sb = new StringBuilder();
        for (String part : name.trim().split("\\s+")) {
            if (!part.isEmpty()) sb.appendCodePoint(Character.toUpperCase(part.codePointAt(0)));
            if (sb.length() >= 2) break;
        }
        return sb.toString();
    }

    private void confirmRemove(final Person p, String name, boolean guardian) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.cl_remove_title, name))
                .setMessage(getString(guardian ? R.string.cl_remove_guardian_msg : R.string.cl_remove_guarding_msg, name))
                .setNegativeButton(R.string.cl_cancel, null)
                .setPositiveButton(R.string.cl_remove, (d, w) -> removePerson(p))
                .show();
    }

    private void removePerson(final Person p) {
        final Context app = getApplicationContext();
        progress.setVisibility(View.VISIBLE);
        Cloud.io().execute(() -> {
            CloudException err = null;
            try {
                ApiClient.get(app).call("DELETE", "/api/v1/guardians/" + android.net.Uri.encode(p.userId), null);
            } catch (CloudException e) {
                err = e;
            }
            final CloudException fErr = err;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (fErr != null) {
                    progress.setVisibility(View.GONE);
                    snack(errorMessage(fErr));
                } else {
                    load();
                }
            });
        });
    }

    // ================================================================== nearby helper

    private void setHelperSwitch(boolean on) {
        suppressSwitch = true;
        helperSwitch.setChecked(on);
        suppressSwitch = false;
    }

    private void askHelperOptIn() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.cl_helper_dialog_title)
                .setMessage(R.string.cl_helper_dialog_msg)
                .setNegativeButton(R.string.cl_cancel, null)
                .setPositiveButton(R.string.cl_helper_dialog_ok, (d, w) -> {
                    if (CloudHelper.hasLocationPermission(this)) {
                        enableHelper();
                    } else {
                        locationPermission.launch(new String[]{
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION});
                    }
                })
                .show();
    }

    private void enableHelper() {
        setHelperSwitch(true);
        CloudHelper.setOptIn(this, true, ProtectionController.isProtectionActive());
        final Context app = getApplicationContext();
        Cloud.io().execute(() -> {
            boolean ok;
            try {
                ok = CloudHelper.pushLocation(app);
            } catch (CloudException | RuntimeException e) {
                ok = false;
            }
            if (!ok) CloudHelper.refreshSoon(app);
            final boolean fOk = ok;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                snack(fOk ? R.string.cl_helper_enabled : R.string.cl_helper_saved_offline);
            });
        });
    }

    // ================================================================== delete / re-enable

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.cl_delete_title)
                .setMessage(R.string.cl_delete_msg)
                .setNegativeButton(R.string.cl_cancel, null)
                .setPositiveButton(R.string.cl_delete_confirm, (d, w) -> deleteCloudData())
                .show();
    }

    private void deleteCloudData() {
        deleteButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        final Context app = getApplicationContext();
        Cloud.io().execute(() -> {
            CloudException err = null;
            try {
                ApiClient.get(app).call("DELETE", "/api/v1/me", null);
            } catch (CloudException e) {
                err = e;
            }
            if (err == null) {
                ApiClient.get(app).clearCredentials();
                CloudOutbox.clear(app);
                Prefs.get(app).edit().putBoolean(Prefs.HELPER_OPT_IN, false).apply();
                Prefs.notifyChanged(app);
                CloudHelper.cancelPeriodic(app);
                Cloud.setOptedOut(app, true);
                CloudAlerts.update(app);
            }
            final CloudException fErr = err;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                deleteButton.setEnabled(true);
                progress.setVisibility(View.GONE);
                if (fErr == null) {
                    guardianCode = null;
                    snack(R.string.cl_deleted);
                    showOff(R.string.cl_off_caption_deleted, true);
                } else {
                    snack(errorMessage(fErr));
                }
            });
        });
    }

    private void reEnableCloud() {
        Cloud.setOptedOut(this, false);
        CloudAlerts.update(this);
        load();
    }

    // ================================================================== misc

    private void snack(@StringRes int text) {
        snack(getString(text));
    }

    private void snack(String text) {
        Snackbar.make(root, text, Snackbar.LENGTH_LONG).show();
    }

}
