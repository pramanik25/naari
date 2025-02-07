package com.example.naarishakti;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.example.naarishakti.databinding.FragmentSettingsBinding;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

public class SettingsFragment extends Fragment {

    private FragmentSettingsBinding binding;
    private Vibrator vibrator;
    private static final int HAPTIC_FEEDBACK_DURATION = 15; // Milliseconds
    private static final int ANIMATION_DURATION = 150;
    private static final String PREFS_NAME = "SafetySettings";

    // Default values
    private static final int DEFAULT_FREEZE_DURATION = 5000;
    private static final int DEFAULT_REQUIRED_PRESSES = 4;
    private static final long DEFAULT_SEQUENCE_TIMEOUT = 3000;
    private static final long DEFAULT_CONFIRMATION_TIMEOUT = 5000;
    private static final long DEFAULT_INPUT_TIMEOUT = 5000;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        vibrator = (Vibrator) requireContext().getSystemService(Context.VIBRATOR_SERVICE);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setupInputFields();
        setupRadioGroup();
        setupSaveButton();
        loadSettings();
    }

    private void setupInputFields() {
        // Freeze Duration
        setupSliderWithInput(binding.freezeDurationSlider, binding.freezeDurationEditText,
                1000, 10000, 1000, DEFAULT_FREEZE_DURATION);

        // Required Presses
        setupSliderWithInput(binding.requiredPressesSlider, binding.requiredPressesEditText,
                1, 10, 1, DEFAULT_REQUIRED_PRESSES);

        // Sequence Timeout
        setupSliderWithInput(binding.sequenceTimeoutSlider, binding.sequenceTimeoutEditText,
                1000, 10000, 1000, (int)DEFAULT_SEQUENCE_TIMEOUT);

        // Confirmation Timeout
        setupSliderWithInput(binding.confirmationTimeoutSlider, binding.confirmationTimeoutEditText,
                1000, 10000, 1000, (int)DEFAULT_CONFIRMATION_TIMEOUT);

        // Input Timeout
        setupSliderWithInput(binding.inputTimeoutSlider, binding.inputTimeoutEditText,
                1000, 10000, 1000, (int)DEFAULT_INPUT_TIMEOUT);
    }

    private void setupSliderWithInput(Slider slider, TextInputEditText editText,
                                      int min, int max, int step, int defaultValue) {

        slider.setValueFrom(min);
        slider.setValueTo(max);
        slider.setStepSize(step);
        slider.setValue(defaultValue);

        slider.addOnChangeListener((slider1, value, fromUser) -> {
            if (fromUser) {
                editText.setText(String.valueOf((int) value));
                triggerHapticFeedback();
            }
        });

        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                try {
                    int value = Integer.parseInt(s.toString());
                    if (value >= min && value <= max) {
                        slider.setValue(value);
                        binding.inputTimeoutTextInput.setError(null);
                    } else {
                        binding.inputTimeoutTextInput.setError("Value must be between " + min + " and " + max);
                    }
                } catch (NumberFormatException e) {
                    binding.inputTimeoutTextInput.setError("Invalid number");
                }
            }
        });
    }

    private void setupRadioGroup() {
        binding.radioDeactivationMethod.setOnCheckedChangeListener((group, checkedId) -> {
            RadioButton radioButton = group.findViewById(checkedId);
            if (radioButton != null) {
                animateRadioSelection(radioButton);
                triggerHapticFeedback();
            }
        });
    }

    private void animateRadioSelection(RadioButton radioButton) {
        radioButton.animate()
                .scaleX(1.1f)
                .scaleY(1.1f)
                .setDuration(ANIMATION_DURATION)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> radioButton.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(ANIMATION_DURATION / 2))
                .start();
    }

    private void setupSaveButton() {
        binding.saveSettingsButton.setOnClickListener(v -> {
            if (validateInputs()) {
                saveSettings();
                triggerHapticFeedback();
                showSuccessAnimation();
            }
        });
    }

    private boolean validateInputs() {
        boolean isValid = true;

        if (binding.freezeDurationEditText.getText().toString().isEmpty()) {
            binding.freezeDurationTextInput.setError("Required");
            isValid = false;
        }

        // Add validation for other fields...

        return isValid;
    }

    private void saveSettings() {
        SharedPreferences sharedPreferences = requireContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        SharedPreferences.Editor editor = sharedPreferences.edit();

        editor.putInt("freeze_duration",
                Integer.parseInt(binding.freezeDurationEditText.getText().toString()));

        editor.putInt("required_presses",
                Integer.parseInt(binding.requiredPressesEditText.getText().toString()));

        editor.putLong("sequence_timeout",
                Long.parseLong(binding.sequenceTimeoutEditText.getText().toString()));

        editor.putLong("confirmation_timeout",
                Long.parseLong(binding.confirmationTimeoutEditText.getText().toString()));

        editor.putLong("input_timeout",
                Long.parseLong(binding.inputTimeoutEditText.getText().toString()));

        editor.putString("deactivation_method",
                binding.radioVolumeButton.isChecked() ? "volume_button" : "shake");

        editor.apply();
    }

    private void loadSettings() {
        SharedPreferences sharedPreferences = requireContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        binding.freezeDurationEditText.setText(String.valueOf(
                sharedPreferences.getInt("freeze_duration", DEFAULT_FREEZE_DURATION)));

        binding.requiredPressesEditText.setText(String.valueOf(
                sharedPreferences.getInt("required_presses", DEFAULT_REQUIRED_PRESSES)));

        binding.sequenceTimeoutEditText.setText(String.valueOf(
                sharedPreferences.getLong("sequence_timeout", DEFAULT_SEQUENCE_TIMEOUT)));

        binding.confirmationTimeoutEditText.setText(String.valueOf(
                sharedPreferences.getLong("confirmation_timeout", DEFAULT_CONFIRMATION_TIMEOUT)));

        binding.inputTimeoutEditText.setText(String.valueOf(
                sharedPreferences.getLong("input_timeout", DEFAULT_INPUT_TIMEOUT)));

        if ("volume_button".equals(sharedPreferences.getString("deactivation_method", "volume_button"))) {
            binding.radioVolumeButton.setChecked(true);
        } else {
            binding.radioShake.setChecked(true);
        }
    }

    private void showSuccessAnimation() {
        binding.saveSettingsButton.animate()
                .scaleX(1.2f)
                .scaleY(1.2f)
                .setDuration(ANIMATION_DURATION)
                .withEndAction(() -> binding.saveSettingsButton.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(ANIMATION_DURATION))
                .start();
    }

    private void triggerHapticFeedback() {
        if (vibrator != null && vibrator.hasVibrator()) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
            } else {
                vibrator.vibrate(HAPTIC_FEEDBACK_DURATION);
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (vibrator != null) {
            vibrator.cancel();
        }
        binding = null;
    }
}