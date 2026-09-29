package com.example.naarishakti.core;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.naarishakti.R;
import com.example.naarishakti.databinding.ActivityCoreAppearanceBinding;
import com.google.android.material.card.MaterialCardView;

/** Theme (Dark / Light / Phone) and in-app language picker. Changes apply instantly. */
public class AppearanceActivity extends AppCompatActivity {

    private ActivityCoreAppearanceBinding b;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityCoreAppearanceBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.backButton.setOnClickListener(v -> finish());

        b.themeDark.setOnClickListener(v -> chooseTheme(Appearance.THEME_DARK));
        b.themeLight.setOnClickListener(v -> chooseTheme(Appearance.THEME_LIGHT));
        b.themeSystem.setOnClickListener(v -> chooseTheme(Appearance.THEME_SYSTEM));
        renderTheme();
        renderLanguages();
    }

    private void chooseTheme(String theme) {
        if (theme.equals(Appearance.getTheme(this))) return;
        // AppCompat recreates this activity (and the rest of the back stack) in the new theme.
        Appearance.setTheme(this, theme);
        renderTheme();
    }

    private void renderTheme() {
        String theme = Appearance.getTheme(this);
        styleTile(b.themeDark, Appearance.THEME_DARK.equals(theme));
        styleTile(b.themeLight, Appearance.THEME_LIGHT.equals(theme));
        styleTile(b.themeSystem, Appearance.THEME_SYSTEM.equals(theme));
    }

    private void styleTile(MaterialCardView card, boolean selected) {
        card.setStrokeColor(ContextCompat.getColor(this, selected ? R.color.ns_rose : R.color.ns_stroke));
        card.setStrokeWidth(Math.round(getResources().getDisplayMetrics().density * (selected ? 2 : 1)));
        card.setSelected(selected);
        card.setContentDescription(selected ? getString(R.string.core_selected) : null);
    }

    private void renderLanguages() {
        LinearLayout list = b.languageList;
        list.removeAllViews();
        String current = Appearance.getLanguage();
        LayoutInflater inflater = getLayoutInflater();

        addLanguageRow(inflater, list, "", getString(R.string.core_language_system),
                getString(R.string.core_language_system_caption), current.isEmpty());
        for (Appearance.Language lang : Appearance.languages()) {
            addLanguageRow(inflater, list, lang.tag, lang.nativeName, lang.englishName,
                    lang.tag.equals(current));
        }
    }

    private void addLanguageRow(LayoutInflater inflater, LinearLayout list, final String tag,
                                String nativeName, String englishName, boolean selected) {
        View row = inflater.inflate(R.layout.core_item_language, list, false);
        ((TextView) row.findViewById(R.id.nativeName)).setText(nativeName);
        TextView english = row.findViewById(R.id.englishName);
        english.setText(englishName);
        english.setVisibility(englishName.equals(nativeName) ? View.GONE : View.VISIBLE);
        ImageView check = row.findViewById(R.id.check);
        check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        row.setSelected(selected);
        row.setOnClickListener(v -> {
            if (tag.equals(Appearance.getLanguage())) return;
            // AppCompat recreates the activities with the new locale.
            Appearance.setLanguage(tag);
        });
        list.addView(row);
    }
}
