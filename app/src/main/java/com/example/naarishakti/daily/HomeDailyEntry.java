package com.example.naarishakti.daily;

import android.content.Context;
import android.content.Intent;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.naarishakti.MainActivity;
import com.example.naarishakti.R;
import com.example.naarishakti.journey.CheckIn;

/**
 * Home screen entry points owned by the daily module: the "Every day" card with the commute,
 * cycle tracker and quiz. HomeFragment only creates this and calls {@link #render()} on resume.
 */
public final class HomeDailyEntry {

    private final Context ctx;
    @Nullable private final TextView commuteCaption;
    @Nullable private final TextView quizCaption;

    public HomeDailyEntry(@NonNull View root) {
        ctx = root.getContext();
        wire(root, R.id.dlHomeCommute, R.id.dlHomeCommuteIcon, R.color.ns_rose, R.color.ns_rose_container,
                CommuteActivity.class);
        wire(root, R.id.dlHomeCycle, R.id.dlHomeCycleIcon, R.color.ns_violet, R.color.ns_violet_container,
                CycleActivity.class);
        wire(root, R.id.dlHomeQuiz, R.id.dlHomeQuizIcon, R.color.ns_gold, R.color.ns_gold_container,
                QuizActivity.class);
        commuteCaption = root.findViewById(R.id.dlHomeCommuteCaption);
        quizCaption = root.findViewById(R.id.dlHomeQuizCaption);
    }

    private void wire(View root, @IdRes int column, @IdRes int icon, @ColorRes int solid,
                      @ColorRes int container, final Class<?> target) {
        ImageView badge = root.findViewById(icon);
        if (badge != null) MainActivity.tintBadge(badge, solid, container);
        View v = root.findViewById(column);
        if (v != null) {
            v.setOnClickListener(x -> {
                x.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                Daily.open(ctx, new Intent(ctx, target));
            });
        }
    }

    /** Refreshes the live captions. The cycle column never shows anything personal on Home. */
    public void render() {
        if (commuteCaption != null) {
            commuteCaption.setText(CheckIn.isActive(ctx) ? R.string.dl_home_commute_active : R.string.dl_home_commute_cap);
        }
        if (quizCaption != null) {
            int streak = Quiz.streak(ctx);
            if (Quiz.playedToday(ctx)) {
                quizCaption.setText(ctx.getResources().getQuantityString(R.plurals.dl_home_quiz_done, streak, streak));
            } else if (streak > 0) {
                quizCaption.setText(ctx.getResources().getQuantityString(R.plurals.dl_home_quiz_keep, streak, streak));
            } else {
                quizCaption.setText(R.string.dl_home_quiz_cap);
            }
        }
    }
}
