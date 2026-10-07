package com.example.naarishakti.daily;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.naarishakti.R;
import com.example.naarishakti.databinding.DlActivityQuizBinding;
import com.google.android.material.button.MaterialButton;

import java.util.List;

/**
 * Daily safety quiz: three questions, an explanation after each answer, then the day's result
 * with the streak, points and level. After today's round she can keep practising without points.
 */
public class QuizActivity extends AppCompatActivity {

    private static final String STATE_INDEX = "index";
    private static final String STATE_SCORE = "score";
    private static final String STATE_CHOSEN = "chosen";

    private DlActivityQuizBinding b;
    private MaterialButton[] options;
    private List<Quiz.Question> questions;
    private boolean practice;
    private int index;
    private int score;
    /** Option tapped for the current question, or -1 while it is unanswered. */
    private int chosen = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = DlActivityQuizBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        options = new MaterialButton[]{b.option0, b.option1, b.option2};

        b.backButton.setOnClickListener(v -> finish());
        for (int i = 0; i < options.length; i++) {
            final int option = i;
            options[i].setOnClickListener(v -> answer(option));
        }
        b.nextButton.setOnClickListener(v -> next());
        b.practiceButton.setOnClickListener(v -> startRound(true));
        b.shareButton.setOnClickListener(v -> share());
        b.reminderSwitch.setChecked(Quiz.reminderOn(this));
        b.reminderSwitch.setOnCheckedChangeListener((btn, checked) -> {
            Daily.kv(this).edit().putBoolean(Quiz.K_REMINDER, checked).apply();
            DailyNudge.sync(this);
        });
        b.reminderRow.setOnClickListener(v -> b.reminderSwitch.toggle());

        if (Quiz.playedToday(this)) {
            showResult(Quiz.lastScore(this), false);
        } else {
            startRound(false);
            if (savedInstanceState != null && !questions.isEmpty()) {
                // Rotation mid-round: today's questions are deterministic, so only the position is kept.
                index = Math.min(savedInstanceState.getInt(STATE_INDEX), questions.size() - 1);
                score = savedInstanceState.getInt(STATE_SCORE);
                showQuestion();
                int restored = savedInstanceState.getInt(STATE_CHOSEN, -1);
                if (restored >= 0) reveal(restored);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (!practice) {
            outState.putInt(STATE_INDEX, index);
            outState.putInt(STATE_SCORE, chosen >= 0 && isCorrect(chosen) ? score - 1 : score);
            outState.putInt(STATE_CHOSEN, chosen);
        }
    }

    // ------------------------------------------------------------------ round

    private void startRound(boolean asPractice) {
        practice = asPractice;
        questions = asPractice ? Quiz.practice(this) : Quiz.forDay(this, Daily.today());
        index = 0;
        score = 0;
        if (questions.isEmpty()) {
            finish();
            return;
        }
        Daily.show(b.resultGroup, false);
        Daily.show(b.questionGroup, true);
        b.progress.setMax(questions.size());
        showQuestion();
    }

    private void showQuestion() {
        chosen = -1;
        Quiz.Question q = questions.get(index);
        b.progressText.setText(getString(practice ? R.string.dl_quiz_progress_practice : R.string.dl_quiz_progress,
                index + 1, questions.size()));
        b.progress.setProgressCompat(index, true);
        b.questionText.setText(q.text);
        for (int i = 0; i < options.length; i++) {
            options[i].setText(q.options[i]);
            options[i].setClickable(true);
            tint(options[i], R.color.ns_stroke, android.R.color.transparent);
        }
        Daily.show(b.feedbackCard, false);
        Daily.show(b.nextButton, false);
        renderStats();
    }

    private boolean isCorrect(int option) {
        return option == questions.get(index).correct;
    }

    private void answer(int option) {
        if (chosen >= 0) return;
        b.getRoot().performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        reveal(option);
    }

    /** Marks the options, shows the explanation and counts the answer. */
    private void reveal(int option) {
        chosen = option;
        Quiz.Question q = questions.get(index);
        boolean correct = isCorrect(option);
        if (correct) score++;
        for (MaterialButton o : options) o.setClickable(false);
        tint(options[q.correct], R.color.ns_safe, R.color.ns_safe_container);
        if (!correct) tint(options[option], R.color.ns_danger, R.color.ns_danger_container);

        b.feedbackTitle.setText(correct ? R.string.dl_quiz_correct : R.string.dl_quiz_wrong);
        b.feedbackTitle.setTextColor(ContextCompat.getColor(this, correct ? R.color.ns_safe : R.color.ns_danger));
        b.feedbackBody.setText(q.explanation);
        Daily.show(b.feedbackCard, true);
        b.nextButton.setText(index + 1 < questions.size() ? R.string.dl_quiz_next : R.string.dl_quiz_finish);
        Daily.show(b.nextButton, true);
        b.progress.setProgressCompat(index + 1, true);
        b.scroll.post(() -> b.scroll.smoothScrollTo(0, b.nextButton.getBottom()));
    }

    private void next() {
        if (chosen < 0) return;
        if (index + 1 < questions.size()) {
            index++;
            showQuestion();
            b.scroll.scrollTo(0, 0);
            return;
        }
        if (!practice) {
            Quiz.finish(this, score);
            DailyNudge.sync(this);
        }
        showResult(score, !practice);
    }

    // ------------------------------------------------------------------ result

    /** @param earned true right after today's round, when the points were just added. */
    private void showResult(int roundScore, boolean earned) {
        chosen = -1;
        Daily.show(b.questionGroup, false);
        Daily.show(b.resultGroup, true);
        b.scroll.scrollTo(0, 0);
        b.resultScore.setText(getString(R.string.dl_quiz_score, roundScore, Quiz.PER_DAY));
        b.resultMessage.setText(roundScore == Quiz.PER_DAY ? R.string.dl_quiz_result_perfect
                : roundScore > 0 ? R.string.dl_quiz_result_good : R.string.dl_quiz_result_low);
        if (practice) {
            b.resultPoints.setText(R.string.dl_quiz_result_practice);
        } else if (earned) {
            b.resultPoints.setText(getString(R.string.dl_quiz_result_points, Quiz.pointsFor(roundScore)));
        } else {
            b.resultPoints.setText(R.string.dl_quiz_result_done);
        }
        int toNext = Quiz.pointsToNextLevel(this);
        b.resultLevel.setText(toNext > 0
                ? getString(R.string.dl_quiz_level_next, Quiz.levelName(this), toNext)
                : getString(R.string.dl_quiz_level_top, Quiz.levelName(this)));
        renderStats();
    }

    private void renderStats() {
        int streak = Quiz.streak(this);
        b.streakValue.setText(getResources().getQuantityString(R.plurals.dl_quiz_days, streak, streak));
        b.pointsValue.setText(String.valueOf(Quiz.points(this)));
        b.levelValue.setText(Quiz.levelName(this));
    }

    private void share() {
        int streak = Quiz.streak(this);
        String text = getString(R.string.dl_quiz_share_text,
                getResources().getQuantityString(R.plurals.dl_quiz_days, streak, streak), Quiz.points(this));
        Daily.open(this, Intent.createChooser(
                new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                getString(R.string.dl_quiz_share)));
    }

    private void tint(MaterialButton button, @ColorRes int stroke, @ColorRes int fill) {
        button.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(this, stroke)));
        button.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, fill)));
    }
}
