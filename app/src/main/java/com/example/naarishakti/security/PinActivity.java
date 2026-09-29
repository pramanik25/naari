package com.example.naarishakti.security;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.naarishakti.R;
import com.example.naarishakti.core.ProtectionController;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App PIN screen.
 * <ul>
 *   <li>{@link #setupIntent}: create / change the app PIN and the optional duress PIN, or remove
 *       the PIN (changing or removing requires the current PIN first).</li>
 *   <li>{@link #verifyIntent}: start for result; returns RESULT_OK with {@link #EXTRA_RESULT}
 *       "ok" or "duress". Returns "ok" straight away when no PIN is set.</li>
 * </ul>
 * Screenshots and the recents thumbnail are blocked (FLAG_SECURE).
 */
public class PinActivity extends AppCompatActivity {

    public static final String EXTRA_RESULT = "pin_result";
    public static final String RESULT_VALUE_OK = "ok";
    public static final String RESULT_VALUE_DURESS = "duress";

    private static final String EXTRA_MODE = "pin_mode";
    private static final String EXTRA_TITLE = "pin_title";
    private static final int MODE_SETUP = 0;
    private static final int MODE_VERIFY = 1;

    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCKOUT_MS = 30_000;

    private static final int STEP_VERIFY = 0;
    private static final int STEP_AUTH = 1;
    private static final int STEP_MANAGE = 2;
    private static final int STEP_NEW = 3;
    private static final int STEP_CONFIRM = 4;
    private static final int STEP_DURESS_INTRO = 5;
    private static final int STEP_DURESS_NEW = 6;
    private static final int STEP_DURESS_CONFIRM = 7;

    public static Intent setupIntent(Context ctx) {
        return new Intent(ctx, PinActivity.class).putExtra(EXTRA_MODE, MODE_SETUP);
    }

    public static Intent verifyIntent(Context ctx, String title) {
        return new Intent(ctx, PinActivity.class)
                .putExtra(EXTRA_MODE, MODE_VERIFY)
                .putExtra(EXTRA_TITLE, title);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private PinPadView pad;
    private TextView title;
    private TextView subtitle;
    private View options;
    private TextView optionsBody;
    private MaterialButton optionPrimary;
    private MaterialButton optionSecondary;
    private MaterialButton optionDanger;

    private int mode;
    private int step;
    private String newPin;
    private String duressPin;
    private int wrongAttempts;
    private long lockedUntil;
    private boolean busy;
    /** The duress PIN was entered to reach the settings: pretend everything works, save nothing. */
    private boolean fakeSession;

    private final Runnable lockoutTick = new Runnable() {
        @Override
        public void run() {
            long left = lockedUntil - SystemClock.elapsedRealtime();
            if (left <= 0) {
                pad.setPadEnabled(true);
                pad.setMessage(null, false);
                return;
            }
            pad.setMessage(getString(R.string.en_pin_lockout, (int) Math.ceil(left / 1000.0)), true);
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.en_activity_pin);

        pad = findViewById(R.id.pinPad);
        title = findViewById(R.id.pinTitle);
        subtitle = findViewById(R.id.pinSubtitle);
        options = findViewById(R.id.pinOptions);
        optionsBody = findViewById(R.id.pinOptionsBody);
        optionPrimary = findViewById(R.id.pinOptionPrimary);
        optionSecondary = findViewById(R.id.pinOptionSecondary);
        optionDanger = findViewById(R.id.pinOptionDanger);

        findViewById(R.id.pinBackButton).setOnClickListener(v -> onBack());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onBack();
            }
        });
        pad.setListener(this::onPin);

        mode = getIntent().getIntExtra(EXTRA_MODE, MODE_SETUP);
        if (mode == MODE_VERIFY) {
            if (!PinStore.isSet(this)) {
                finishWith(RESULT_VALUE_OK);
                return;
            }
            showStep(STEP_VERIFY);
        } else {
            showStep(PinStore.isSet(this) ? STEP_AUTH : STEP_NEW);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        worker.shutdown();
        super.onDestroy();
    }

    private void onBack() {
        switch (step) {
            case STEP_CONFIRM:
                showStep(STEP_NEW);
                break;
            case STEP_DURESS_NEW:
            case STEP_DURESS_CONFIRM:
                showStep(STEP_DURESS_INTRO);
                break;
            default:
                setResult(RESULT_CANCELED);
                finish();
        }
    }

    // ---- Steps ----

    private void showStep(int s) {
        step = s;
        boolean padStep = s != STEP_MANAGE && s != STEP_DURESS_INTRO;
        pad.setVisibility(padStep ? View.VISIBLE : View.GONE);
        options.setVisibility(padStep ? View.GONE : View.VISIBLE);
        pad.clear();
        pad.setMessage(null, false);
        switch (s) {
            case STEP_VERIFY: {
                String t = getIntent().getStringExtra(EXTRA_TITLE);
                setTexts(TextUtils.isEmpty(t) ? getString(R.string.en_pin_verify_title) : t,
                        getString(R.string.en_pin_verify_subtitle));
                break;
            }
            case STEP_AUTH:
                setTexts(getString(R.string.en_pin_current_title), getString(R.string.en_pin_current_subtitle));
                break;
            case STEP_MANAGE:
                setTexts(getString(R.string.en_pin_manage_title), getString(R.string.en_pin_manage_subtitle));
                optionsBody.setText(getString(PinStore.isDuressSet(this) || fakeSession
                        ? R.string.en_pin_duress_status_on : R.string.en_pin_duress_status_off));
                setOption(optionPrimary, getString(R.string.en_pin_change), v -> showStep(STEP_NEW));
                setOption(optionSecondary, null, null);
                setOption(optionDanger, getString(R.string.en_pin_remove), v -> confirmRemove());
                break;
            case STEP_NEW:
                newPin = null;
                duressPin = null;
                setTexts(getString(R.string.en_pin_new_title), getString(R.string.en_pin_new_subtitle));
                break;
            case STEP_CONFIRM:
                setTexts(getString(R.string.en_pin_confirm_title), getString(R.string.en_pin_confirm_subtitle));
                break;
            case STEP_DURESS_INTRO:
                duressPin = null;
                setTexts(getString(R.string.en_pin_duress_title), getString(R.string.en_pin_duress_subtitle));
                optionsBody.setText(getString(R.string.en_pin_duress_body));
                setOption(optionPrimary, getString(R.string.en_pin_duress_set), v -> showStep(STEP_DURESS_NEW));
                setOption(optionSecondary, getString(R.string.en_pin_duress_skip), v -> save(null));
                setOption(optionDanger, null, null);
                break;
            case STEP_DURESS_NEW:
                setTexts(getString(R.string.en_pin_duress_new_title), getString(R.string.en_pin_duress_new_subtitle));
                break;
            case STEP_DURESS_CONFIRM:
                setTexts(getString(R.string.en_pin_duress_confirm_title), getString(R.string.en_pin_confirm_subtitle));
                break;
            default:
                break;
        }
        if (padStep && SystemClock.elapsedRealtime() < lockedUntil) {
            pad.setPadEnabled(false);
            handler.post(lockoutTick);
        }
    }

    private void setTexts(String t, String sub) {
        title.setText(t);
        subtitle.setText(sub);
    }

    private void setOption(MaterialButton b, @Nullable String text, @Nullable View.OnClickListener l) {
        b.setVisibility(text == null ? View.GONE : View.VISIBLE);
        b.setText(text);
        b.setOnClickListener(l);
    }

    // ---- PIN input ----

    private void onPin(String pin) {
        if (busy) return;
        switch (step) {
            case STEP_VERIFY:
            case STEP_AUTH:
                check(pin);
                break;
            case STEP_NEW:
                newPin = pin;
                showStep(STEP_CONFIRM);
                break;
            case STEP_CONFIRM:
                if (pin.equals(newPin)) {
                    showStep(STEP_DURESS_INTRO);
                } else {
                    pad.shake();
                    newPin = null;
                    step = STEP_NEW;
                    setTexts(getString(R.string.en_pin_new_title), getString(R.string.en_pin_new_subtitle));
                    pad.setMessage(getString(R.string.en_pin_mismatch), true);
                }
                break;
            case STEP_DURESS_NEW:
                if (pin.equals(newPin)) {
                    pad.shake();
                    pad.setMessage(getString(R.string.en_pin_duress_same), true);
                } else {
                    duressPin = pin;
                    showStep(STEP_DURESS_CONFIRM);
                }
                break;
            case STEP_DURESS_CONFIRM:
                if (pin.equals(duressPin)) {
                    save(duressPin);
                } else {
                    pad.shake();
                    duressPin = null;
                    step = STEP_DURESS_NEW;
                    setTexts(getString(R.string.en_pin_duress_new_title), getString(R.string.en_pin_duress_new_subtitle));
                    pad.setMessage(getString(R.string.en_pin_mismatch), true);
                }
                break;
            default:
                break;
        }
    }

    private void check(final String pin) {
        if (SystemClock.elapsedRealtime() < lockedUntil) return;
        busy = true;
        final Context app = getApplicationContext();
        worker.execute(() -> {
            final PinStore.Result r = PinStore.verify(app, pin);
            handler.post(() -> onChecked(r));
        });
    }

    private void onChecked(PinStore.Result r) {
        busy = false;
        if (isFinishing()) return;
        if (r == PinStore.Result.NOT_SET) {
            if (step == STEP_VERIFY) finishWith(RESULT_VALUE_OK);
            else showStep(STEP_NEW);
            return;
        }
        if (r == PinStore.Result.WRONG) {
            wrongAttempts++;
            pad.shake();
            if (wrongAttempts >= MAX_ATTEMPTS) {
                wrongAttempts = 0;
                lockedUntil = SystemClock.elapsedRealtime() + LOCKOUT_MS;
                pad.setPadEnabled(false);
                handler.post(lockoutTick);
            } else {
                int left = MAX_ATTEMPTS - wrongAttempts;
                pad.setMessage(getResources().getQuantityString(R.plurals.en_pin_attempts_left, left, left), true);
            }
            return;
        }
        wrongAttempts = 0;
        boolean duress = r == PinStore.Result.DURESS;
        if (step == STEP_VERIFY) {
            finishWith(duress ? RESULT_VALUE_DURESS : RESULT_VALUE_OK);
            return;
        }
        // STEP_AUTH (change / remove). The duress PIN here means someone is forcing the change:
        // raise a silent SOS and carry on as if it were the real PIN, without saving anything.
        if (duress) {
            fakeSession = true;
            ProtectionController.triggerPanic(this, "duress", true);
        }
        showStep(STEP_MANAGE);
    }

    // ---- Save / remove ----

    private void save(@Nullable final String duress) {
        if (busy) return;
        final String app = newPin;
        if (app == null) {
            showStep(STEP_NEW);
            return;
        }
        if (fakeSession) {
            done(getString(duress != null ? R.string.en_pin_saved_duress : R.string.en_pin_saved));
            return;
        }
        busy = true;
        final Context ctx = getApplicationContext();
        worker.execute(() -> {
            final boolean ok = PinStore.save(ctx, app, duress);
            handler.post(() -> {
                busy = false;
                if (ok) {
                    done(getString(duress != null ? R.string.en_pin_saved_duress : R.string.en_pin_saved));
                } else {
                    showStep(STEP_NEW);
                    pad.setMessage(getString(R.string.en_pin_save_failed), true);
                }
            });
        });
    }

    private void confirmRemove() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.en_pin_remove_confirm_title)
                .setMessage(R.string.en_pin_remove_confirm_body)
                .setNegativeButton(R.string.en_cancel, null)
                .setPositiveButton(R.string.en_pin_remove_confirm_ok, (d, w) -> {
                    if (!fakeSession) PinStore.clear(this);
                    done(getString(R.string.en_pin_removed));
                })
                .show();
    }

    private void done(String toast) {
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private void finishWith(String value) {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_RESULT, value));
        finish();
    }
}
