package com.example.naarishakti;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.appcompat.app.AppCompatActivity;

import com.example.naarishakti.databinding.ActivitySplashBinding;

/**
 * Brand splash: a short fade/scale-in of the logo mark and wordmark, then straight to Home.
 * Permissions are NOT requested here; MainActivity runs a friendly one-time onboarding instead.
 */
public class SplashActivity extends AppCompatActivity {

    private static final long SPLASH_DURATION_MS = 1200L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable goHome = this::openMain;
    private ActivitySplashBinding binding;
    private ObjectAnimator ringPulse;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_NaariShakti_Splash);
        super.onCreate(savedInstanceState);
        binding = ActivitySplashBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        animateIn();
        handler.postDelayed(goHome, SPLASH_DURATION_MS);
    }

    private void animateIn() {
        DecelerateInterpolator ease = new DecelerateInterpolator(2f);
        float rise = getResources().getDisplayMetrics().density * 12f;

        View logo = binding.logoMark;
        logo.setAlpha(0f);
        logo.setScaleX(0.86f);
        logo.setScaleY(0.86f);
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(520).setInterpolator(ease).start();

        View[] texts = {binding.wordmark, binding.tagline, binding.footer};
        for (int i = 0; i < texts.length; i++) {
            View v = texts[i];
            v.setAlpha(0f);
            v.setTranslationY(rise);
            v.animate().alpha(1f).translationY(0f)
                    .setStartDelay(160 + i * 90L)
                    .setDuration(420).setInterpolator(ease).start();
        }

        ringPulse = ObjectAnimator.ofPropertyValuesHolder(binding.logoRing,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.45f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.45f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.7f, 0f));
        ringPulse.setDuration(1100);
        ringPulse.setStartDelay(200);
        ringPulse.setRepeatCount(ValueAnimator.INFINITE);
        ringPulse.setInterpolator(ease);
        ringPulse.start();
    }

    private void openMain() {
        if (isFinishing()) return;
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(R.anim.ua_fade_in, R.anim.ua_fade_out);
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(goHome);
        if (ringPulse != null) ringPulse.cancel();
        super.onDestroy();
    }
}
