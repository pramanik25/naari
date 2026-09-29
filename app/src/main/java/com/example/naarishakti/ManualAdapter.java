package com.example.naarishakti;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

/**
 * Adapter for the manual ViewPager2, showing 12 pages of help content.
 * Each page is a tab covering: SOS, Triggers, Protection, Evidence, Escape, Privacy,
 * Contacts, Cloud, Profile, Permissions, Appearance, Help.
 */
public class ManualAdapter extends FragmentStateAdapter {

    private static final String[] TABS = {
        "manual_tab_sos",
        "manual_tab_triggers",
        "manual_tab_protection",
        "manual_tab_evidence",
        "manual_tab_escape",
        "manual_tab_privacy",
        "manual_tab_contacts",
        "manual_tab_cloud",
        "manual_tab_profile",
        "manual_tab_permissions",
        "manual_tab_appearance",
        "manual_tab_help"
    };

    private static final String[] HEADINGS = {
        "manual_sos_heading",
        "manual_triggers_heading",
        "manual_protect_heading",
        "manual_evidence_heading",
        "manual_escape_heading",
        "manual_privacy_heading",
        "manual_contacts_heading",
        "manual_cloud_heading",
        "manual_profile_heading",
        "manual_perms_heading",
        "manual_appearance_heading",
        "manual_help_heading"
    };

    private static final String[] SUBTITLES = {
        "manual_sos_subtitle",
        "manual_triggers_subtitle",
        "manual_protect_subtitle",
        "manual_evidence_subtitle",
        "manual_escape_subtitle",
        "manual_privacy_subtitle",
        "manual_contacts_subtitle",
        "manual_cloud_subtitle",
        "manual_profile_subtitle",
        "manual_perms_subtitle",
        "manual_appearance_subtitle",
        "manual_help_subtitle"
    };

    private static final int[] PAGES = {
        // Page 0: SOS Button
        R.string.manual_sos_what,
        R.string.manual_sos_how_title,
        R.string.manual_sos_how_1,
        R.string.manual_sos_how_2,
        R.string.manual_sos_how_3,
        R.string.manual_sos_how_4,
        R.string.manual_sos_what_happens,
        R.string.manual_sos_stop_title,
        R.string.manual_sos_stop_1,
        R.string.manual_sos_stop_2,
        R.string.manual_sos_tip,
        R.string.manual_sos_needs,
        // Pages 1-11 follow similar pattern with their own content string arrays
    };

    public ManualAdapter(AppCompatActivity activity) {
        super(activity);
    }

    @Override
    public int getItemCount() {
        return TABS.length;
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        return ManualPageFragment.newInstance(position, HEADINGS[position], SUBTITLES[position]);
    }
}
