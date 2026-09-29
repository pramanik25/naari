package Home_Activity;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.naarishakti.R;
import com.example.naarishakti.databinding.ActivityWomenHelplineBinding;
import com.example.naarishakti.databinding.ItemUbHelplineBinding;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Searchable directory of India's national emergency and women's safety helplines. */
public class WomenHelplineActivity extends AppCompatActivity {

    private static final int CAT_ALL = 0;
    private static final int CAT_EMERGENCY = 1;
    private static final int CAT_WOMEN = 2;
    private static final int CAT_HEALTH = 3;
    private static final int CAT_CYBER = 4;

    private static final int ACTION_NONE = 0;
    private static final int ACTION_WHATSAPP = 1;
    private static final int ACTION_WEB = 2;

    private static final class Helpline {
        @StringRes final int name;
        @StringRes final int description;
        final String number;
        final String display;
        final int category;
        @DrawableRes final int icon;
        @ColorRes final int container;
        @ColorRes final int tint;
        final int action;
        @Nullable final String actionUrl;

        Helpline(int name, int description, String number, String display, int category,
                 int icon, int container, int tint, int action, @Nullable String actionUrl) {
            this.name = name;
            this.description = description;
            this.number = number;
            this.display = display;
            this.category = category;
            this.icon = icon;
            this.container = container;
            this.tint = tint;
            this.action = action;
            this.actionUrl = actionUrl;
        }
    }

    private ActivityWomenHelplineBinding b;
    private final List<Helpline> all = new ArrayList<>();
    private int selectedCategory = CAT_ALL;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityWomenHelplineBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        buildDirectory();

        b.backButton.setOnClickListener(v -> finish());
        b.searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                render();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        b.categoryChips.setOnCheckedStateChangeListener((group, checkedIds) -> {
            int id = checkedIds.isEmpty() ? R.id.chipAll : checkedIds.get(0);
            if (id == R.id.chipEmergency) selectedCategory = CAT_EMERGENCY;
            else if (id == R.id.chipWomen) selectedCategory = CAT_WOMEN;
            else if (id == R.id.chipHealth) selectedCategory = CAT_HEALTH;
            else if (id == R.id.chipCyber) selectedCategory = CAT_CYBER;
            else selectedCategory = CAT_ALL;
            FeatureKit.tick(group);
            render();
        });

        render();
    }

    private void buildDirectory() {
        all.clear();
        all.add(new Helpline(R.string.ub_help_112_name, R.string.ub_help_112_desc, "112", "112",
                CAT_EMERGENCY, R.drawable.ub_ic_emergency, R.color.ns_rose_container, R.color.ns_rose,
                ACTION_NONE, null));
        all.add(new Helpline(R.string.ub_help_181_name, R.string.ub_help_181_desc, "181", "181",
                CAT_WOMEN, R.drawable.ub_ic_woman, R.color.ns_rose_container, R.color.ns_rose,
                ACTION_NONE, null));
        all.add(new Helpline(R.string.ub_help_1091_name, R.string.ub_help_1091_desc, "1091", "1091",
                CAT_WOMEN, R.drawable.ub_ic_shield, R.color.ns_violet_container, R.color.ns_violet,
                ACTION_NONE, null));
        all.add(new Helpline(R.string.ub_help_100_name, R.string.ub_help_100_desc, "100", "100",
                CAT_EMERGENCY, R.drawable.ub_ic_shield, R.color.ns_info_container, R.color.ns_info,
                ACTION_NONE, null));
        all.add(new Helpline(R.string.ub_help_108_name, R.string.ub_help_108_desc, "108", "108",
                CAT_HEALTH, R.drawable.ub_ic_medical, R.color.ns_safe_container, R.color.ns_safe,
                ACTION_NONE, null));
        all.add(new Helpline(R.string.ub_help_1098_name, R.string.ub_help_1098_desc, "1098", "1098",
                CAT_EMERGENCY, R.drawable.ub_ic_child, R.color.ns_gold_container, R.color.ns_gold,
                ACTION_NONE, null));
        all.add(new Helpline(R.string.ub_help_1930_name, R.string.ub_help_1930_desc, "1930", "1930",
                CAT_CYBER, R.drawable.ub_ic_computer, R.color.ns_violet_container, R.color.ns_violet,
                ACTION_WEB, "https://cybercrime.gov.in"));
        all.add(new Helpline(R.string.ub_help_ncw_name, R.string.ub_help_ncw_desc, "7827170170", "78271 70170",
                CAT_WOMEN, R.drawable.ub_ic_chat, R.color.ns_gold_container, R.color.ns_gold,
                ACTION_WHATSAPP, "https://wa.me/917827170170"));
        all.add(new Helpline(R.string.ub_help_community_name, R.string.ub_help_community_desc, "", "",
                CAT_WOMEN, R.drawable.ub_ic_chat, R.color.ns_rose_container, R.color.ns_rose,
                ACTION_WHATSAPP, "https://whatsapp.com/channel/0029VbCO82oGpLHWokmyXD34"));
    }

    private void render() {
        CharSequence raw = b.searchInput.getText();
        String query = raw == null ? "" : raw.toString().trim().toLowerCase(Locale.getDefault());
        String queryDigits = query.replaceAll("[^0-9]", "");

        b.helplineList.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        int shown = 0;
        for (final Helpline h : all) {
            if (selectedCategory != CAT_ALL && h.category != selectedCategory) continue;
            final String name = getString(h.name);
            String desc = getString(h.description);
            if (!query.isEmpty()) {
                boolean match = name.toLowerCase(Locale.getDefault()).contains(query)
                        || desc.toLowerCase(Locale.getDefault()).contains(query)
                        || (!queryDigits.isEmpty() && h.number.contains(queryDigits));
                if (!match) continue;
            }
            ItemUbHelplineBinding row = ItemUbHelplineBinding.inflate(inflater, b.helplineList, false);
            row.badge.setImageResource(h.icon);
            row.badge.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, h.container)));
            row.badge.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, h.tint)));
            row.name.setText(name);
            row.description.setText(desc);
            row.number.setText(h.display);
            row.tag.setText(categoryLabel(h.category));

            row.callButton.setContentDescription(getString(R.string.ub_help_call_cd, name, h.display));
            row.callButton.setOnClickListener(v -> call(v, h));
            row.card.setOnClickListener(v -> call(v, h));
            row.card.setContentDescription(getString(R.string.ub_help_card_cd, name, h.display, desc));

            if (h.action == ACTION_WHATSAPP) {
                row.secondaryButton.setVisibility(View.VISIBLE);
                row.secondaryButton.setIconResource(R.drawable.ub_ic_chat);
                row.secondaryButton.setContentDescription(getString(R.string.ub_help_whatsapp_cd, name));
                row.secondaryButton.setOnClickListener(v -> openAction(h, R.string.ub_help_whatsapp_failed));
            } else if (h.action == ACTION_WEB) {
                row.secondaryButton.setVisibility(View.VISIBLE);
                row.secondaryButton.setIconResource(R.drawable.ub_ic_globe);
                row.secondaryButton.setIconTint(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ns_violet)));
                row.secondaryButton.setContentDescription(getString(R.string.ub_help_web_cd));
                row.secondaryButton.setOnClickListener(v -> openAction(h, R.string.ub_help_web_failed));
            }
            b.helplineList.addView(row.getRoot());
            shown++;
        }
        b.emptyState.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
    }

    private String categoryLabel(int category) {
        switch (category) {
            case CAT_EMERGENCY: return getString(R.string.ub_help_cat_emergency);
            case CAT_WOMEN: return getString(R.string.ub_help_cat_women);
            case CAT_HEALTH: return getString(R.string.ub_help_cat_health);
            case CAT_CYBER: return getString(R.string.ub_help_cat_cyber);
            default: return "";
        }
    }

    private void call(View v, Helpline h) {
        FeatureKit.tick(v);
        if (!FeatureKit.dial(this, h.number)) {
            Snackbar.make(b.getRoot(), getString(R.string.ub_help_dial_failed, h.display), Snackbar.LENGTH_LONG).show();
        }
    }

    private void openAction(Helpline h, @StringRes int failure) {
        if (h.actionUrl == null || !FeatureKit.openUrl(this, h.actionUrl)) {
            Snackbar.make(b.getRoot(), failure, Snackbar.LENGTH_LONG).show();
        }
    }
}
