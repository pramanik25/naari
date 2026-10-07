package com.example.naarishakti.daily;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.StringRes;

import com.example.naarishakti.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Daily safety quiz: three questions a day from a bundled bank, a streak for playing on
 * consecutive days, and points that add up to a level. Everything stays on the phone.
 *
 * The bank is the string-array {@code dl_quiz_items}; each item is
 * {@code question|option A|option B|option C|index of the right option|explanation}.
 */
final class Quiz {

    private static final String TAG = "Quiz";

    static final int PER_DAY = 3;
    static final int OPTIONS = 3;
    static final int POINTS_PER_CORRECT = 10;
    static final int PERFECT_BONUS = 5;

    private static final String K_LAST_DAY = "quiz_last_day";
    private static final String K_LAST_SCORE = "quiz_last_score";
    private static final String K_STREAK = "quiz_streak";
    private static final String K_BEST = "quiz_best_streak";
    private static final String K_POINTS = "quiz_points";
    static final String K_REMINDER = "quiz_reminder";

    /** Points needed for each level, lowest first. */
    private static final int[] LEVEL_POINTS = {0, 100, 300, 600, 1000};
    @StringRes
    private static final int[] LEVEL_NAMES = {R.string.dl_quiz_level_1, R.string.dl_quiz_level_2,
            R.string.dl_quiz_level_3, R.string.dl_quiz_level_4, R.string.dl_quiz_level_5};

    static final class Question {
        String text;
        final String[] options = new String[OPTIONS];
        int correct;
        String explanation;
    }

    private Quiz() {}

    // ------------------------------------------------------------------ bank

    static List<Question> bank(Context ctx) {
        List<Question> out = new ArrayList<>();
        for (String item : ctx.getResources().getStringArray(R.array.dl_quiz_items)) {
            String[] p = item.split("\\|");
            if (p.length != OPTIONS + 3) {
                Log.w(TAG, "Malformed quiz item skipped: " + item);
                continue;
            }
            try {
                Question q = new Question();
                q.text = p[0].trim();
                for (int i = 0; i < OPTIONS; i++) q.options[i] = p[1 + i].trim();
                q.correct = Integer.parseInt(p[OPTIONS + 1].trim());
                q.explanation = p[OPTIONS + 2].trim();
                if (q.correct >= 0 && q.correct < OPTIONS) out.add(q);
            } catch (NumberFormatException e) {
                Log.w(TAG, "Malformed quiz item skipped: " + item);
            }
        }
        return out;
    }

    /** Today's questions: the bank is walked in order, three a day, and wraps around. */
    static List<Question> forDay(Context ctx, int epochDay) {
        List<Question> bank = bank(ctx);
        List<Question> out = new ArrayList<>();
        if (bank.isEmpty()) return out;
        int start = Math.floorMod(epochDay * PER_DAY, bank.size());
        for (int i = 0; i < PER_DAY && i < bank.size(); i++) out.add(bank.get((start + i) % bank.size()));
        return out;
    }

    /** Three random questions for a practice round (no points). */
    static List<Question> practice(Context ctx) {
        List<Question> bank = bank(ctx);
        Collections.shuffle(bank);
        return new ArrayList<>(bank.subList(0, Math.min(PER_DAY, bank.size())));
    }

    // ------------------------------------------------------------------ state

    static boolean playedToday(Context ctx) {
        return Daily.kv(ctx).getInt(K_LAST_DAY, Integer.MIN_VALUE) == Daily.today();
    }

    static boolean everPlayed(Context ctx) {
        return Daily.kv(ctx).contains(K_LAST_DAY);
    }

    static int lastScore(Context ctx) {
        return Daily.kv(ctx).getInt(K_LAST_SCORE, 0);
    }

    /** Consecutive days played, counting today or yesterday as the latest; 0 once a day is missed. */
    static int streak(Context ctx) {
        SharedPreferences kv = Daily.kv(ctx);
        int last = kv.getInt(K_LAST_DAY, Integer.MIN_VALUE);
        return last >= Daily.today() - 1 ? kv.getInt(K_STREAK, 0) : 0;
    }

    static int bestStreak(Context ctx) {
        return Daily.kv(ctx).getInt(K_BEST, 0);
    }

    static int points(Context ctx) {
        return Daily.kv(ctx).getInt(K_POINTS, 0);
    }

    static int pointsFor(int score) {
        return score * POINTS_PER_CORRECT + (score == PER_DAY ? PERFECT_BONUS : 0);
    }

    /** Records today's round. A second round on the same day changes nothing. */
    static void finish(Context ctx, int score) {
        if (playedToday(ctx)) return;
        int today = Daily.today();
        int streak = streak(ctx) + 1;
        Daily.kv(ctx).edit()
                .putInt(K_LAST_DAY, today)
                .putInt(K_LAST_SCORE, score)
                .putInt(K_STREAK, streak)
                .putInt(K_BEST, Math.max(bestStreak(ctx), streak))
                .putInt(K_POINTS, points(ctx) + pointsFor(score))
                .apply();
    }

    // ------------------------------------------------------------------ levels

    private static int level(int points) {
        int level = 0;
        for (int i = 0; i < LEVEL_POINTS.length; i++) if (points >= LEVEL_POINTS[i]) level = i;
        return level;
    }

    static String levelName(Context ctx) {
        return ctx.getString(LEVEL_NAMES[level(points(ctx))]);
    }

    /** Points still needed for the next level, or 0 at the top level. */
    static int pointsToNextLevel(Context ctx) {
        int points = points(ctx);
        int level = level(points);
        return level + 1 < LEVEL_POINTS.length ? LEVEL_POINTS[level + 1] - points : 0;
    }

    static boolean reminderOn(Context ctx) {
        return Daily.kv(ctx).getBoolean(K_REMINDER, true);
    }
}
