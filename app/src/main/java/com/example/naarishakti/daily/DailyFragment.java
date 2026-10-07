package com.example.naarishakti.daily;

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
import com.example.naarishakti.journey.CheckIn;

/**
 * "Daily" bottom tab: the everyday features (commute, cycle tracker, safety quiz) with live
 * captions. The cycle row never shows anything personal: this page can be seen over her shoulder.
 */
public class DailyFragment extends Fragment {

    @Nullable private ShFragmentTabBinding b;
    @Nullable private ShItemFeatureBinding commuteRow;
    @Nullable private ShItemFeatureBinding quizRow;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        b = ShFragmentTabBinding.inflate(inflater, container, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        b.title.setText(R.string.sh_daily_title);
        b.body.setText(R.string.sh_daily_body);
        commuteRow = row(R.drawable.ua_ic_near_me, R.color.ns_rose, R.color.ns_rose_container,
                R.string.dl_home_commute, R.string.dl_home_commute_cap, CommuteActivity.class);
        row(R.drawable.ua_ic_event, R.color.ns_violet, R.color.ns_violet_container,
                R.string.dl_home_cycle, R.string.dl_home_cycle_cap, CycleActivity.class);
        quizRow = row(R.drawable.ub_ic_lightbulb, R.color.ns_gold, R.color.ns_gold_container,
                R.string.dl_home_quiz, R.string.dl_home_quiz_cap, QuizActivity.class);
    }

    @Override
    public void onResume() {
        super.onResume();
        render();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) render();
    }

    @Override
    public void onDestroyView() {
        b = null;
        commuteRow = null;
        quizRow = null;
        super.onDestroyView();
    }

    private ShItemFeatureBinding row(@DrawableRes int icon, @ColorRes int solid, @ColorRes int container,
                                     @StringRes int title, @StringRes int caption, final Class<?> target) {
        ShItemFeatureBinding row = ShItemFeatureBinding.inflate(getLayoutInflater(), b.features, false);
        row.icon.setImageResource(icon);
        MainActivity.tintBadge(row.icon, solid, container);
        row.title.setText(title);
        row.caption.setText(caption);
        row.getRoot().setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            Daily.open(requireContext(), new Intent(requireContext(), target));
        });
        b.features.addView(row.getRoot());
        return row;
    }

    private void render() {
        if (b == null || commuteRow == null || quizRow == null) return;
        commuteRow.caption.setText(CheckIn.isActive(requireContext())
                ? R.string.dl_home_commute_active : R.string.dl_home_commute_cap);
        int streak = Quiz.streak(requireContext());
        if (Quiz.playedToday(requireContext())) {
            quizRow.caption.setText(getResources().getQuantityString(R.plurals.dl_home_quiz_done, streak, streak));
        } else if (streak > 0) {
            quizRow.caption.setText(getResources().getQuantityString(R.plurals.dl_home_quiz_keep, streak, streak));
        } else {
            quizRow.caption.setText(R.string.dl_home_quiz_cap);
        }
    }
}
