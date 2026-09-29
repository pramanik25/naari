package com.example.naarishakti;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.example.naarishakti.databinding.ManualPageBinding;

/**
 * Fragment for a single manual page. Each page has:
 * - A heading and subtitle (from resource IDs)
 * - Formatted content text (with lists and formatting)
 * - Scrollable for long pages
 */
public class ManualPageFragment extends Fragment {

    private static final String ARG_POSITION = "position";
    private static final String ARG_HEADING = "heading";
    private static final String ARG_SUBTITLE = "subtitle";

    private int position;
    private String headingResName;
    private String subtitleResName;
    private ManualPageBinding binding;

    // Content for each page (will be built dynamically from resources)
    private static final String[] PAGE_CONTENT = {
        // Page 0: SOS Button (multiple sections)
        "manual_sos_what\nmanual_sos_how_title\nmanual_sos_how_1\nmanual_sos_how_2\nmanual_sos_how_3\nmanual_sos_how_4\nmanual_sos_what_happens\nmanual_sos_stop_title\nmanual_sos_stop_1\nmanual_sos_stop_2\nmanual_sos_tip\nmanual_sos_needs",
        // Page 1: Triggers
        "manual_triggers_what\nmanual_triggers_title_shake\nmanual_triggers_shake_desc\nmanual_triggers_shake_how\nmanual_triggers_title_power\nmanual_triggers_power_desc\nmanual_triggers_power_how\nmanual_triggers_title_voice\nmanual_triggers_voice_desc\nmanual_triggers_voice_how\nmanual_triggers_voice_needs\nmanual_triggers_title_scream\nmanual_triggers_scream_desc\nmanual_triggers_scream_how\nmanual_triggers_scream_needs\nmanual_triggers_title_fall\nmanual_triggers_fall_desc\nmanual_triggers_fall_how\nmanual_triggers_title_headset\nmanual_triggers_headset_desc\nmanual_triggers_headset_how\nmanual_triggers_title_volume\nmanual_triggers_volume_desc\nmanual_triggers_volume_how\nmanual_triggers_volume_needs\nmanual_triggers_title_panic\nmanual_triggers_panic_desc\nmanual_triggers_panic_how\nmanual_triggers_panic_needs",
        // Pages 2-11 follow similar pattern
        "manual_protect_title\nmanual_protect_desc\nmanual_protect_turn_on\nmanual_protect_countdown\nmanual_protect_countdown_desc\nmanual_protect_countdown_change\nmanual_protect_countdown_zero",
        "manual_evidence_what\nmanual_evidence_audio\nmanual_evidence_audio_desc\nmanual_evidence_audio_turn_on\nmanual_evidence_video\nmanual_evidence_video_desc\nmanual_evidence_video_turn_on\nmanual_evidence_upload\nmanual_evidence_upload_desc\nmanual_evidence_upload_turn_on\nmanual_evidence_vault\nmanual_evidence_vault_desc\nmanual_evidence_vault_view\nmanual_evidence_vault_export",
        "manual_escape_title_fake\nmanual_escape_fake_desc\nmanual_escape_fake_how\nmanual_escape_title_checkin\nmanual_escape_checkin_desc\nmanual_escape_checkin_how\nmanual_escape_checkin_tip\nmanual_escape_title_cab\nmanual_escape_cab_desc\nmanual_escape_cab_how\nmanual_escape_cab_tip\nmanual_escape_title_meeting\nmanual_escape_meeting_desc\nmanual_escape_meeting_how\nmanual_escape_title_places\nmanual_escape_places_desc\nmanual_escape_places_how\nmanual_escape_places_needs\nmanual_escape_title_silent\nmanual_escape_silent_desc\nmanual_escape_silent_how",
        "manual_privacy_title_diary\nmanual_privacy_diary_desc\nmanual_privacy_diary_how\nmanual_privacy_title_complaint\nmanual_privacy_complaint_desc\nmanual_privacy_complaint_how\nmanual_privacy_title_pin\nmanual_privacy_pin_desc\nmanual_privacy_pin_how",
        "manual_contacts_what\nmanual_contacts_add\nmanual_contacts_call\nmanual_contacts_call_desc\nmanual_contacts_escalation\nmanual_contacts_escalation_how\nmanual_contacts_emergency_call\nmanual_contacts_emergency_desc\nmanual_contacts_emergency_how",
        "manual_cloud_desc\nmanual_cloud_title_upload\nmanual_cloud_upload_desc\nmanual_cloud_upload_how\nmanual_cloud_upload_tip\nmanual_cloud_title_nearby\nmanual_cloud_nearby_desc\nmanual_cloud_nearby_how\nmanual_cloud_title_whatsapp\nmanual_cloud_whatsapp_desc\nmanual_cloud_whatsapp_how",
        "manual_profile_title_info\nmanual_profile_info_desc\nmanual_profile_info_how\nmanual_profile_title_schedule\nmanual_profile_schedule_desc\nmanual_profile_schedule_how",
        "manual_perms_what\nmanual_perms_mic\nmanual_perms_sms\nmanual_perms_phone\nmanual_perms_location\nmanual_perms_camera\nmanual_perms_contacts\nmanual_perms_notifications\nmanual_perms_battery\nmanual_perms_overlay\nmanual_perms_fullscreen\nmanual_perms_exact_alarm\nmanual_perms_grant\nmanual_perms_change",
        "manual_appearance_theme\nmanual_appearance_theme_desc\nmanual_appearance_theme_how\nmanual_appearance_language\nmanual_appearance_language_desc\nmanual_appearance_language_how",
        "manual_help_q1\nmanual_help_a1\nmanual_help_q2\nmanual_help_a2\nmanual_help_q3\nmanual_help_a3\nmanual_help_q4\nmanual_help_a4\nmanual_help_q5\nmanual_help_a5\nmanual_help_q6\nmanual_help_a6\nmanual_help_q7\nmanual_help_a7\nmanual_help_q8\nmanual_help_a8\nmanual_help_q9\nmanual_help_a9\nmanual_help_q10\nmanual_help_a10\nmanual_help_tip1\nmanual_help_tip2\nmanual_help_tip3\nmanual_help_tip4\nmanual_help_tip5"
    };

    public static ManualPageFragment newInstance(int position, String heading, String subtitle) {
        ManualPageFragment fragment = new ManualPageFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_POSITION, position);
        args.putString(ARG_HEADING, heading);
        args.putString(ARG_SUBTITLE, subtitle);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            position = getArguments().getInt(ARG_POSITION);
            headingResName = getArguments().getString(ARG_HEADING);
            subtitleResName = getArguments().getString(ARG_SUBTITLE);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = ManualPageBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (getContext() == null) return;

        // Set heading
        int headingResId = getResources().getIdentifier(headingResName, "string", getContext().getPackageName());
        if (headingResId != 0) {
            binding.pageHeading.setText(headingResId);
        }

        // Set subtitle
        int subtitleResId = getResources().getIdentifier(subtitleResName, "string", getContext().getPackageName());
        if (subtitleResId != 0) {
            binding.pageSubtitle.setText(subtitleResId);
        }

        // Build content by joining multiple string resources for this page
        StringBuilder content = new StringBuilder();
        if (position >= 0 && position < PAGE_CONTENT.length) {
            String[] resourceNames = PAGE_CONTENT[position].split("\n");
            for (String resName : resourceNames) {
                int resId = getResources().getIdentifier(resName, "string", getContext().getPackageName());
                if (resId != 0) {
                    if (content.length() > 0) {
                        content.append("\n\n");
                    }
                    content.append(getString(resId));
                }
            }
        }

        binding.pageContent.setText(content);
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}
