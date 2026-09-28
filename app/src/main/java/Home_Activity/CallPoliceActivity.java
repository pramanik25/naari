package Home_Activity;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.naarishakti.R;
import com.example.naarishakti.core.Prefs;
import com.example.naarishakti.databinding.ActivityCallPoliceBinding;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One-tap emergency calling. 112 is the primary orb; 100 and 1091 are secondary. Every call goes
 * through a 3-second cancellable countdown so a stray tap never dials emergency services.
 */
public class CallPoliceActivity extends AppCompatActivity {

    private static final long COUNTDOWN_MS = 3000L;
    private static final long TIP_INTERVAL_MS = 6000L;

    private static final int SHARE_NONE = 0;
    private static final int SHARE_SMS = 1;
    private static final int SHARE_SHEET = 2;

    private ActivityCallPoliceBinding b;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Animator> ringAnimators = new ArrayList<>();

    private ActivityResultLauncher<String> callPermission;
    private ActivityResultLauncher<String[]> sharePermissions;

    @Nullable private ValueAnimator countdown;
    private boolean countdownCancelled;
    @Nullable private String pendingCallNumber;
    private int pendingShare = SHARE_NONE;
    private boolean locating;

    private String[] tips;
    private int tipIndex;

    private final Runnable rotateTip = new Runnable() {
        @Override
        public void run() {
            tipIndex = (tipIndex + 1) % tips.length;
            showTip(true);
            handler.postDelayed(this, TIP_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityCallPoliceBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        tips = getResources().getStringArray(R.array.ub_police_tips);

        callPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            String number = pendingCallNumber;
            pendingCallNumber = null;
            if (number == null) return;
            if (granted) directCall(number);
            else FeatureKit.dial(this, number);
        });
        sharePermissions = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                this::onSharePermissions);

        b.backButton.setOnClickListener(v -> finish());
        b.callOrb.setOnClickListener(v -> {
            if (countdown != null) cancelCountdown(true);
            else startCountdown("112", getString(R.string.ub_police_orb_label));
        });
        b.cancelCallButton.setOnClickListener(v -> cancelCountdown(true));
        b.police100Card.setOnClickListener(v ->
                startCountdown("100", getString(R.string.ub_police_label_100)));
        b.women1091Card.setOnClickListener(v ->
                startCountdown("1091", getString(R.string.ub_police_label_1091)));
        b.textContactsButton.setOnClickListener(v -> {
            FeatureKit.tick(v);
            onTextContacts();
        });
        b.shareSheetButton.setOnClickListener(v -> {
            FeatureKit.tick(v);
            requestShare(SHARE_SHEET);
        });

        tipIndex = 0;
        showTip(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderShareCaption();
        startIdleRings();
        handler.removeCallbacks(rotateTip);
        handler.postDelayed(rotateTip, TIP_INTERVAL_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(rotateTip);
        // Never let a countdown finish while the screen isn't visible.
        if (countdown != null) cancelCountdown(false);
        stopRings();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopRings();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ countdown + call

    private void startCountdown(final String number, String label) {
        if (countdown != null) cancelCountdown(false);
        FeatureKit.thud(b.callOrb);
        b.scroll.smoothScrollTo(0, 0);

        b.orbNumber.setText(number);
        b.orbLabel.setText(getString(R.string.ub_police_calling_label, label));
        b.orbSubLabel.setText(R.string.ub_police_tap_to_cancel);
        b.callOrb.setContentDescription(getString(R.string.ub_police_cancel_cd, number));
        b.cancelCallButton.setVisibility(View.VISIBLE);
        b.countdownRing.setProgressCompat(0, false);
        b.countdownRing.setVisibility(View.VISIBLE);
        b.callOrb.announceForAccessibility(getString(R.string.ub_police_countdown_announce, number));

        countdownCancelled = false;
        final int[] lastSecond = {-1};
        ValueAnimator anim = ValueAnimator.ofInt(0, (int) COUNTDOWN_MS);
        anim.setDuration(COUNTDOWN_MS);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            int elapsed = (Integer) a.getAnimatedValue();
            b.countdownRing.setProgressCompat((int) (elapsed * 1000L / COUNTDOWN_MS), false);
            int secondsLeft = (int) Math.ceil((COUNTDOWN_MS - elapsed) / 1000.0);
            if (secondsLeft != lastSecond[0] && secondsLeft > 0) {
                lastSecond[0] = secondsLeft;
                b.orbCaption.setText(getString(R.string.ub_police_calling_in, secondsLeft));
                FeatureKit.tick(b.callOrb);
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                countdownCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                boolean cancelled = countdownCancelled;
                countdown = null;
                resetOrb();
                if (!cancelled) placeCall(number);
            }
        });
        countdown = anim;
        anim.start();
    }

    private void cancelCountdown(boolean announce) {
        ValueAnimator anim = countdown;
        if (anim != null) anim.cancel(); // onAnimationEnd resets the orb
        countdown = null;
        resetOrb();
        if (announce) {
            FeatureKit.tick(b.callOrb);
            Snackbar.make(b.getRoot(), R.string.ub_police_call_cancelled, Snackbar.LENGTH_SHORT).show();
        }
    }

    private void resetOrb() {
        b.countdownRing.setVisibility(View.INVISIBLE);
        b.cancelCallButton.setVisibility(View.GONE);
        b.orbNumber.setText(R.string.ub_num_112);
        b.orbCaption.setText(R.string.ub_police_tap_to_call);
        b.orbLabel.setText(R.string.ub_police_orb_label);
        b.orbSubLabel.setText(R.string.ub_police_orb_sublabel);
        b.callOrb.setContentDescription(getString(R.string.ub_police_orb_cd));
    }

    private void placeCall(String number) {
        if (FeatureKit.isGranted(this, Manifest.permission.CALL_PHONE)) {
            directCall(number);
        } else {
            pendingCallNumber = number;
            callPermission.launch(Manifest.permission.CALL_PHONE);
        }
    }

    private void directCall(String number) {
        Intent call = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number));
        if (!FeatureKit.startSafely(this, call)) FeatureKit.dial(this, number);
    }

    // ------------------------------------------------------------------ rings

    private void startIdleRings() {
        stopRings();
        ringAnimators.add(pulse(b.ringOuter, 0));
        ringAnimators.add(pulse(b.ringInner, 1200));
    }

    private void stopRings() {
        for (Animator a : ringAnimators) a.cancel();
        ringAnimators.clear();
        b.ringOuter.setAlpha(0f);
        b.ringInner.setAlpha(0f);
    }

    private static Animator pulse(View ring, long delay) {
        float from = 184f / 264f;
        ring.setScaleX(from);
        ring.setScaleY(from);
        ring.setAlpha(0f);
        ObjectAnimator anim = ObjectAnimator.ofPropertyValuesHolder(ring,
                PropertyValuesHolder.ofFloat(View.SCALE_X, from, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, from, 1f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.8f, 0f));
        anim.setDuration(2400);
        anim.setStartDelay(delay);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.start();
        return anim;
    }

    // ------------------------------------------------------------------ tips

    private void showTip(boolean animate) {
        final String text = tips[tipIndex];
        final String counter = getString(R.string.ub_police_tip_counter, tipIndex + 1, tips.length);
        if (!animate) {
            b.tipText.setText(text);
            b.tipCounter.setText(counter);
            return;
        }
        b.tipText.animate().alpha(0f).setDuration(150).withEndAction(() -> {
            b.tipText.setText(text);
            b.tipCounter.setText(counter);
            b.tipText.animate().alpha(1f).setDuration(200).start();
        }).start();
    }

    // ------------------------------------------------------------------ share location

    private void renderShareCaption() {
        int n = Prefs.getContactNumbers(this).size();
        if (n == 0) {
            b.shareCaption.setText(R.string.ub_share_caption_none);
        } else {
            b.shareCaption.setText(getResources().getQuantityString(R.plurals.ub_share_caption, n, n));
        }
    }

    private void onTextContacts() {
        final List<String> numbers = Prefs.getContactNumbers(this);
        if (numbers.isEmpty()) {
            Snackbar.make(b.getRoot(), R.string.ub_no_contacts, Snackbar.LENGTH_LONG)
                    .setAction(R.string.ub_add_contacts, v ->
                            startActivity(new Intent(this, SettingsEmergencyActivity.class)))
                    .show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(getResources().getQuantityString(R.plurals.ub_share_confirm_title, numbers.size(), numbers.size()))
                .setMessage(R.string.ub_share_confirm_body)
                .setPositiveButton(R.string.ub_send, (d, w) -> requestShare(SHARE_SMS))
                .setNegativeButton(R.string.ub_cancel, null)
                .show();
    }

    private void requestShare(int mode) {
        if (locating) return;
        pendingShare = mode;
        List<String> needed = new ArrayList<>();
        if (!FeatureKit.hasLocationPermission(this)) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (mode == SHARE_SMS && !FeatureKit.isGranted(this, Manifest.permission.SEND_SMS)) {
            needed.add(Manifest.permission.SEND_SMS);
        }
        if (needed.isEmpty()) {
            locateAndShare(mode);
        } else {
            sharePermissions.launch(needed.toArray(new String[0]));
        }
    }

    private void onSharePermissions(Map<String, Boolean> result) {
        int mode = pendingShare;
        pendingShare = SHARE_NONE;
        if (!FeatureKit.hasLocationPermission(this)) {
            Snackbar.make(b.getRoot(), R.string.ub_location_denied, Snackbar.LENGTH_LONG)
                    .setAction(R.string.ub_open_settings, v -> FeatureKit.openAppSettings(this))
                    .show();
            return;
        }
        if (mode == SHARE_SMS && !FeatureKit.isGranted(this, Manifest.permission.SEND_SMS)) {
            // No SMS permission: fall back to the share sheet so the location still goes out.
            Snackbar.make(b.getRoot(), R.string.ub_sms_denied_fallback, Snackbar.LENGTH_LONG).show();
            mode = SHARE_SHEET;
        }
        if (mode != SHARE_NONE) locateAndShare(mode);
    }

    private void locateAndShare(final int mode) {
        final MaterialButton button = mode == SHARE_SMS ? b.textContactsButton : b.shareSheetButton;
        final CharSequence original = button.getText();
        locating = true;
        button.setEnabled(false);
        button.setText(R.string.ub_locating);
        FeatureKit.fetchLocation(this, location -> {
            if (isFinishing() || isDestroyed()) return;
            locating = false;
            button.setEnabled(true);
            button.setText(original);
            if (location == null) {
                Snackbar.make(b.getRoot(), R.string.ub_location_unavailable, Snackbar.LENGTH_LONG).show();
                return;
            }
            String link = FeatureKit.mapsLink(location.getLatitude(), location.getLongitude());
            String text = getString(R.string.ub_location_message_help, link);
            if (mode == SHARE_SMS) {
                int sent = FeatureKit.sendSms(this, Prefs.getContactNumbers(this), text);
                FeatureKit.thud(button);
                if (sent > 0) {
                    Snackbar.make(b.getRoot(), getResources().getQuantityString(R.plurals.ub_sms_sent, sent, sent),
                            Snackbar.LENGTH_LONG).show();
                } else {
                    Snackbar.make(b.getRoot(), R.string.ub_sms_failed, Snackbar.LENGTH_LONG)
                            .setAction(R.string.ub_share, v -> FeatureKit.shareText(this, text))
                            .show();
                }
            } else {
                FeatureKit.shareText(this, text);
            }
        });
    }
}
