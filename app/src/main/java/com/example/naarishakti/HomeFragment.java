package com.example.naarishakti;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Vibrator;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.naarishakti.databinding.FragmentHomeBinding;

import Home_Activity.CallPoliceActivity;
import Home_Activity.LiveLocationActivity;
import Home_Activity.SettingsEmergencyActivity;
import Home_Activity.TriggerWordActivity;
import Home_Activity.WomenHelplineActivity;
import Location.GeofenceSettingsActivity;
import SQLite_Database.ProfileDbHelper;

public class HomeFragment extends Fragment {

    private FragmentHomeBinding binding;
    private ProfileDbHelper dbHelper;
    private Vibrator vibrator;


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dbHelper = new ProfileDbHelper(requireContext());
        vibrator = (Vibrator) requireContext().getSystemService(Context.VIBRATOR_SERVICE);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setupClickListeners();
    }

    private void setupClickListeners() {
        binding.triggerWordCard.setOnClickListener(v -> launchActivity(TriggerWordActivity.class));
        binding.emergencySettingsCard.setOnClickListener(v -> launchActivity(SettingsEmergencyActivity.class));
        binding.callPoliceCard.setOnClickListener(v -> launchActivity(CallPoliceActivity.class));
        binding.womenHelplineCard.setOnClickListener(v -> launchActivity(WomenHelplineActivity.class));
        binding.geofenceSettingsCard.setOnClickListener(v -> launchActivity(GeofenceSettingsActivity.class));
        binding.liveLocationCard.setOnClickListener(v -> launchActivity(LiveLocationActivity.class));
    }

    private void launchActivity(Class<?> activityClass) {
        Intent intent = new Intent(requireContext(), activityClass);
        startActivity(intent);
        requireActivity().overridePendingTransition(R.anim.slide_in, R.anim.slide_out);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        releaseResources();
        binding = null;
    }

    private void releaseResources() {
        if (dbHelper != null) {
            dbHelper.close();
        }
        if (vibrator != null) {
            vibrator.cancel();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (dbHelper != null) {
            dbHelper.close();
        }
    }
}