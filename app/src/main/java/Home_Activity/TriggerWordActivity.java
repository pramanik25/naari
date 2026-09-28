package Home_Activity;

import android.Manifest;
import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.animation.DecelerateInterpolator;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.naarishakti.R;
import com.example.naarishakti.core.Prefs;
import com.example.naarishakti.core.ProtectionController;
import com.example.naarishakti.databinding.ActivityTriggerWordBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lets the user choose the phrase the offline (Vosk) listener reacts to. The phrase is captured
 * either by speaking it (SpeechRecognizer only, so nothing else competes for the mic) or by typing
 * it. Only the text is stored; no audio is recorded or kept.
 */
public class TriggerWordActivity extends AppCompatActivity {

    private static final int MAX_WORDS = 4;
    private static final int ERROR_LANGUAGE_NOT_SUPPORTED = 12; // SpeechRecognizer, API 31+
    private static final int ERROR_LANGUAGE_UNAVAILABLE = 13;   // SpeechRecognizer, API 31+

    /** Words heard constantly in everyday speech; a phrase made only of these causes false alarms. */
    private static final Set<String> COMMON_WORDS = new HashSet<>(Arrays.asList(
            "the", "a", "an", "and", "or", "is", "it", "to", "of", "in", "on", "at", "for", "this",
            "that", "i", "you", "me", "my", "we", "he", "she", "they", "yes", "no", "not", "ok",
            "okay", "hello", "hi", "hey", "bye", "please", "thanks", "thank", "sorry", "what",
            "why", "where", "when", "how", "come", "go", "stop", "wait", "help", "now", "here",
            "there", "one", "two", "haan", "nahi", "accha", "achha", "mom", "papa", "hmm", "so",
            "well", "right", "good", "fine", "call", "do", "be", "can", "will", "just", "get"));

    private ActivityTriggerWordBinding b;
    private ActivityResultLauncher<String> micPermission;

    @Nullable private SpeechRecognizer recognizer;
    private boolean listening;
    private boolean cancelledByUser;
    private boolean useDefaultLanguage;
    @Nullable private String pendingPhrase;
    private final List<Animator> ringAnimators = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityTriggerWordBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        micPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) {
                startListening();
            } else {
                boolean permanently = !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO);
                Snackbar sb = Snackbar.make(b.getRoot(), R.string.ub_trigger_mic_denied, Snackbar.LENGTH_LONG);
                if (permanently) sb.setAction(R.string.ub_open_settings, v -> FeatureKit.openAppSettings(this));
                sb.show();
                b.phraseInput.requestFocus();
            }
        });

        b.backButton.setOnClickListener(v -> finish());
        b.micButton.setOnClickListener(this::onMicTapped);
        b.tryAgainButton.setOnClickListener(v -> {
            hideConfirm();
            onMicTapped(v);
        });
        b.usePhraseButton.setOnClickListener(v -> {
            if (pendingPhrase != null) attemptSave(pendingPhrase);
        });
        b.saveTypedButton.setOnClickListener(v -> saveTyped());
        b.phraseInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveTyped();
                return true;
            }
            return false;
        });
        b.phraseInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                b.phraseInputLayout.setError(null);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        renderCurrentPhrase(false);
        if (!recognitionPossible()) showRecognizerUnavailable();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderCurrentPhrase(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (listening) stopListening();
    }

    @Override
    protected void onDestroy() {
        stopRings();
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ current phrase

    private void renderCurrentPhrase(boolean animate) {
        String phrase = Prefs.getTriggerPhrase(this);
        b.currentPhraseText.setText(getString(R.string.ub_quoted, phrase));
        boolean on = ProtectionController.isListening();
        b.phraseStatusText.setText(on ? R.string.ub_trigger_status_active : R.string.ub_trigger_status_inactive);
        b.phraseStatusDot.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, on ? R.color.ns_safe : R.color.ns_text_faint)));
        if (animate) {
            b.currentPhraseText.setAlpha(0f);
            b.currentPhraseText.setScaleX(0.94f);
            b.currentPhraseText.setScaleY(0.94f);
            b.currentPhraseText.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    // ------------------------------------------------------------------ recording

    private void onMicTapped(View v) {
        FeatureKit.tick(v);
        if (listening) {
            stopListening();
            return;
        }
        if (!recognitionPossible()) {
            showRecognizerUnavailable();
            return;
        }
        if (!FeatureKit.isGranted(this, Manifest.permission.RECORD_AUDIO)) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.ub_trigger_mic_rationale_title)
                        .setMessage(R.string.ub_trigger_mic_rationale_body)
                        .setPositiveButton(R.string.ub_continue, (d, w) -> micPermission.launch(Manifest.permission.RECORD_AUDIO))
                        .setNegativeButton(R.string.ub_not_now, null)
                        .show();
            } else {
                micPermission.launch(Manifest.permission.RECORD_AUDIO);
            }
            return;
        }
        startListening();
    }

    private void startListening() {
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(listener);
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        if (!useDefaultLanguage) i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());

        hideConfirm();
        cancelledByUser = false;
        listening = true;
        setListeningUi(true);
        b.recordStatus.setText(R.string.ub_trigger_starting);
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            listening = false;
            setListeningUi(false);
            b.recordStatus.setText(R.string.ub_trigger_err_generic);
        }
    }

    private void stopListening() {
        cancelledByUser = true;
        listening = false;
        if (recognizer != null) recognizer.cancel();
        setListeningUi(false);
        b.recordStatus.setText(R.string.ub_trigger_tap_to_record);
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) {
            b.recordStatus.setText(R.string.ub_trigger_listening);
        }

        @Override public void onBeginningOfSpeech() {}

        @Override public void onRmsChanged(float rmsdB) {
            if (!listening) return;
            float level = Math.max(0f, Math.min(10f, rmsdB)) / 10f;
            float scale = 1f + level * 0.08f;
            b.micButton.setScaleX(scale);
            b.micButton.setScaleY(scale);
        }

        @Override public void onBufferReceived(byte[] buffer) {}

        @Override public void onEndOfSpeech() {
            b.recordStatus.setText(R.string.ub_trigger_processing);
        }

        @Override public void onError(int error) {
            boolean wasListening = listening;
            listening = false;
            setListeningUi(false);
            if (cancelledByUser || !wasListening) return;
            if ((error == ERROR_LANGUAGE_NOT_SUPPORTED || error == ERROR_LANGUAGE_UNAVAILABLE) && !useDefaultLanguage) {
                useDefaultLanguage = true;
                startListening();
                return;
            }
            if (error == SpeechRecognizer.ERROR_CLIENT && !SpeechRecognizer.isRecognitionAvailable(TriggerWordActivity.this)) {
                // No reachable recognition service (none installed, or hidden by package visibility).
                showRecognizerUnavailable();
                return;
            }
            b.recordStatus.setText(errorMessage(error));
        }

        @Override public void onResults(Bundle results) {
            listening = false;
            setListeningUi(false);
            String best = pickBest(results);
            if (TextUtils.isEmpty(best)) {
                b.recordStatus.setText(R.string.ub_trigger_err_no_match);
                return;
            }
            showConfirm(best);
        }

        @Override public void onPartialResults(Bundle partialResults) {
            ArrayList<String> list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list == null || list.isEmpty() || TextUtils.isEmpty(list.get(0))) return;
            b.liveTranscript.setVisibility(View.VISIBLE);
            b.liveTranscript.setText(getString(R.string.ub_quoted_partial, list.get(0).toLowerCase(Locale.US)));
        }

        @Override public void onEvent(int eventType, Bundle params) {}
    };

    @Nullable
    private static String pickBest(Bundle results) {
        ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null) return null;
        for (String candidate : list) {
            String c = clean(candidate);
            if (!c.isEmpty()) return c;
        }
        return null;
    }

    private int errorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return R.string.ub_trigger_err_no_match;
            case SpeechRecognizer.ERROR_AUDIO:
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return R.string.ub_trigger_err_busy;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
                return R.string.ub_trigger_err_network;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return R.string.ub_trigger_mic_denied;
            default:
                return R.string.ub_trigger_err_generic;
        }
    }

    private void setListeningUi(boolean on) {
        if (on) {
            startRings();
            b.micIcon.setImageResource(R.drawable.ub_ic_stop);
            b.micButton.setContentDescription(getString(R.string.ub_trigger_stop_cd));
            b.liveTranscript.setVisibility(View.VISIBLE);
            b.liveTranscript.setText(R.string.ub_ellipsis);
            b.recordHint.setVisibility(View.GONE);
        } else {
            stopRings();
            b.micButton.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            b.micIcon.setImageResource(R.drawable.ub_ic_mic);
            b.micButton.setContentDescription(getString(R.string.ub_trigger_record_cd));
            b.liveTranscript.setVisibility(View.GONE);
            b.recordHint.setVisibility(b.confirmGroup.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        }
    }

    private void startRings() {
        stopRings();
        ringAnimators.add(pulse(b.ringOuter, 0));
        ringAnimators.add(pulse(b.ringInner, 800));
    }

    private void stopRings() {
        for (Animator a : ringAnimators) a.cancel();
        ringAnimators.clear();
        if (b != null) {
            b.ringOuter.setAlpha(0f);
            b.ringInner.setAlpha(0f);
        }
    }

    private static Animator pulse(View ring, long delay) {
        float from = 112f / 200f; // core size / stage size, so rings emerge from the button edge
        ring.setScaleX(from);
        ring.setScaleY(from);
        ring.setAlpha(0f);
        ObjectAnimator anim = ObjectAnimator.ofPropertyValuesHolder(ring,
                PropertyValuesHolder.ofFloat(View.SCALE_X, from, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, from, 1f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.9f, 0f));
        anim.setDuration(1600);
        anim.setStartDelay(delay);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.start();
        return anim;
    }

    /**
     * On Android 11+ isRecognitionAvailable() only sees services declared in the manifest's
     * &lt;queries&gt;, so a false there is not conclusive; a failed start falls back instead.
     */
    private boolean recognitionPossible() {
        return SpeechRecognizer.isRecognitionAvailable(this) || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    private void showRecognizerUnavailable() {
        b.micButton.setEnabled(false);
        b.micStage.setAlpha(0.4f);
        b.recordStatus.setText(R.string.ub_trigger_unavailable_title);
        b.recordHint.setText(R.string.ub_trigger_unavailable_body);
        b.recordHint.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ confirm

    private void showConfirm(String phrase) {
        pendingPhrase = phrase;
        b.heardText.setText(getString(R.string.ub_quoted, phrase));
        String error = validate(phrase);
        String warning = error == null ? commonWarning(phrase) : null;
        if (error != null) {
            b.heardWarning.setVisibility(View.VISIBLE);
            b.heardWarning.setTextColor(ContextCompat.getColor(this, R.color.ns_danger));
            b.heardWarning.setText(error);
        } else if (warning != null) {
            b.heardWarning.setVisibility(View.VISIBLE);
            b.heardWarning.setTextColor(ContextCompat.getColor(this, R.color.ns_warn));
            b.heardWarning.setText(warning);
        } else {
            b.heardWarning.setVisibility(View.GONE);
        }
        b.usePhraseButton.setEnabled(error == null);
        b.recordStatus.setText(R.string.ub_trigger_confirm_prompt);
        b.recordHint.setVisibility(View.GONE);
        b.confirmGroup.setVisibility(View.VISIBLE);
        b.confirmGroup.setAlpha(0f);
        b.confirmGroup.animate().alpha(1f).setDuration(200).start();
        b.scroll.post(() -> b.scroll.smoothScrollTo(0, b.recordCard.getTop()));
    }

    private void hideConfirm() {
        pendingPhrase = null;
        b.confirmGroup.setVisibility(View.GONE);
        b.recordHint.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ typed

    private void saveTyped() {
        CharSequence raw = b.phraseInput.getText();
        String typed = raw == null ? "" : raw.toString().trim().toLowerCase(Locale.US).replaceAll("\\s+", " ");
        if (!typed.matches("[a-z ]*")) {
            b.phraseInputLayout.setError(getString(R.string.ub_trigger_err_letters));
            return;
        }
        String error = validate(typed);
        if (error != null) {
            b.phraseInputLayout.setError(error);
            return;
        }
        attemptSave(typed);
    }

    // ------------------------------------------------------------------ validation + save

    /** Lowercase, letters and single spaces only. */
    static String clean(String raw) {
        if (raw == null) return "";
        return raw.toLowerCase(Locale.US).replaceAll("[^a-z\\s]", " ").replaceAll("\\s+", " ").trim();
    }

    @Nullable
    private String validate(String phrase) {
        if (TextUtils.isEmpty(phrase)) return getString(R.string.ub_trigger_err_empty);
        if (phrase.replace(" ", "").length() < 2) return getString(R.string.ub_trigger_err_short);
        if (phrase.split(" ").length > MAX_WORDS) return getString(R.string.ub_trigger_err_words);
        return null;
    }

    @Nullable
    private String commonWarning(String phrase) {
        String[] words = phrase.split(" ");
        boolean allCommon = true;
        for (String w : words) {
            if (!COMMON_WORDS.contains(w)) {
                allCommon = false;
                break;
            }
        }
        if (allCommon) return getString(R.string.ub_trigger_warn_common, phrase);
        if (words.length == 1) return getString(R.string.ub_trigger_warn_single);
        return null;
    }

    private void attemptSave(String phrase) {
        String warning = commonWarning(phrase);
        if (warning == null) {
            save(phrase);
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ub_trigger_warn_title)
                .setMessage(warning)
                .setPositiveButton(R.string.ub_trigger_save_anyway, (d, w) -> save(phrase))
                .setNegativeButton(R.string.ub_trigger_change, null)
                .show();
    }

    private void save(String phrase) {
        Prefs.get(this).edit()
                .putString(Prefs.TRIGGER_PHRASE, phrase)
                .putString(Prefs.TRIGGER_LAST_TEXT, phrase)
                .apply();
        Prefs.notifyChanged(this);

        hideConfirm();
        b.phraseInput.setText(null);
        b.phraseInput.clearFocus();
        hideKeyboard();
        b.recordStatus.setText(R.string.ub_trigger_tap_to_record);
        renderCurrentPhrase(true);
        b.scroll.smoothScrollTo(0, 0);
        FeatureKit.thud(b.heroCard);
        Snackbar.make(b.getRoot(), getString(R.string.ub_trigger_saved, phrase), Snackbar.LENGTH_SHORT).show();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(b.getRoot().getWindowToken(), 0);
    }
}
