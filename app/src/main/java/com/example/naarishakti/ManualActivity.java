package com.example.naarishakti;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;
import com.google.android.material.button.MaterialButton;
import com.example.naarishakti.databinding.ActivityManualBinding;

/**
 * In-app user manual showing all safety features, how to use them, and tips.
 * 12 tabs organized by feature area: SOS, Triggers, Protection, Evidence, Escape, Privacy,
 * Contacts, Cloud, Profile, Permissions, Appearance, Help & Tips.
 * All text is localized in strings_manual.xml across 13 languages.
 */
public class ManualActivity extends AppCompatActivity {

    private ActivityManualBinding binding;
    private ManualAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityManualBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Back button
        binding.backButton.setOnClickListener(v -> finish());

        // Set up ViewPager2 with adapter
        adapter = new ManualAdapter(this);
        binding.viewPager.setAdapter(adapter);

        // Optional: Add page transition effect for better UX
        binding.viewPager.setPageTransformer((page, position) -> {
            page.setAlpha(Math.max(0f, 1 - Math.abs(position)));
        });
    }

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }
}
