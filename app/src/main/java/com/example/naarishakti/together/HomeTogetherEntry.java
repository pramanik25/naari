package com.example.naarishakti.together;

import android.content.Context;
import android.content.Intent;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.ColorRes;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;

import com.example.naarishakti.MainActivity;
import com.example.naarishakti.R;

/**
 * Home screen entry points owned by the together module: the "Together" card with the family
 * circle, safety map and community. HomeFragment only calls {@link #attach}.
 */
public final class HomeTogetherEntry {

    private HomeTogetherEntry() {}

    public static void attach(@NonNull View root) {
        wire(root, R.id.tgHomeCircle, R.id.tgHomeCircleIcon, R.color.ns_info, R.color.ns_info_container,
                CircleActivity.class);
        wire(root, R.id.tgHomeMap, R.id.tgHomeMapIcon, R.color.ns_safe, R.color.ns_safe_container,
                SafetyMapActivity.class);
        wire(root, R.id.tgHomeCommunity, R.id.tgHomeCommunityIcon, R.color.ns_rose, R.color.ns_rose_container,
                CommunityActivity.class);
    }

    private static void wire(View root, @IdRes int column, @IdRes int icon, @ColorRes int solid,
                             @ColorRes int container, final Class<?> target) {
        ImageView badge = root.findViewById(icon);
        if (badge != null) MainActivity.tintBadge(badge, solid, container);
        View v = root.findViewById(column);
        if (v == null) return;
        final Context ctx = root.getContext();
        v.setOnClickListener(x -> {
            x.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            Tg.open(ctx, new Intent(ctx, target));
        });
    }
}
