package com.example.naarishakti.together;

import android.content.Intent;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;

import com.example.naarishakti.MainActivity;
import com.example.naarishakti.R;
import com.example.naarishakti.databinding.ShFragmentTabBinding;
import com.example.naarishakti.databinding.ShItemFeatureBinding;

/** "Together" bottom tab: the family circle, the community safety map and the community. */
public class TogetherFragment extends Fragment {

    @Nullable private ShFragmentTabBinding b;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        b = ShFragmentTabBinding.inflate(inflater, container, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        b.title.setText(R.string.sh_together_title);
        b.body.setText(R.string.sh_together_body);
        row(R.drawable.ua_ic_group, R.color.ns_info, R.color.ns_info_container,
                R.string.tg_home_circle, R.string.tg_home_circle_cap, CircleActivity.class);
        row(R.drawable.ub_ic_map, R.color.ns_safe, R.color.ns_safe_container,
                R.string.tg_home_map, R.string.tg_home_map_cap, SafetyMapActivity.class);
        row(R.drawable.ub_ic_chat, R.color.ns_rose, R.color.ns_rose_container,
                R.string.tg_home_community, R.string.tg_home_community_cap, CommunityActivity.class);
    }

    @Override
    public void onDestroyView() {
        b = null;
        super.onDestroyView();
    }

    private void row(@DrawableRes int icon, @ColorRes int solid, @ColorRes int container,
                     @StringRes int title, @StringRes int caption, final Class<?> target) {
        ShItemFeatureBinding row = ShItemFeatureBinding.inflate(getLayoutInflater(), b.features, false);
        row.icon.setImageResource(icon);
        MainActivity.tintBadge(row.icon, solid, container);
        row.title.setText(title);
        row.caption.setText(caption);
        row.getRoot().setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            Tg.open(requireContext(), new Intent(requireContext(), target));
        });
        b.features.addView(row.getRoot());
    }
}
