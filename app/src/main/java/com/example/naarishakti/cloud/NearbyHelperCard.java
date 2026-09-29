package com.example.naarishakti.cloud;

import android.Manifest;
import android.content.Context;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;

import com.example.naarishakti.R;
import com.example.naarishakti.core.Prefs;
import com.example.naarishakti.core.ProtectionController;
import com.example.naarishakti.mesh.Mesh;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.Map;

/**
 * One-time "Help women near you?" card on Home (CONTRACT v1.1 "Nearby broadcast").
 * Shown while {@link Prefs#HELPER_ASKED} is false and cloud is available. "Yes" asks for location,
 * opts in as a nearby helper ({@code PUT /helper}) and offers the offline Bluetooth alert;
 * "Not now" only records the answer.
 *
 * <p>Create in {@code Fragment.onCreate} (it registers permission launchers), then
 * {@link #attach} in {@code onViewCreated}, {@link #render} on resume, {@link #detach} in
 * {@code onDestroyView}.
 */
public final class NearbyHelperCard {

    private final Fragment fragment;
    private final ActivityResultLauncher<String[]> locationLauncher;
    private final ActivityResultLauncher<String[]> meshLauncher;
    @Nullable private View card;

    public NearbyHelperCard(@NonNull Fragment fragment) {
        this.fragment = fragment;
        locationLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), this::onLocationResult);
        meshLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), this::onMeshResult);
    }

    /** @param root the fragment view containing the included {@code nbHelperCard}. */
    public void attach(@NonNull View root) {
        card = root.findViewById(R.id.nbHelperCard);
        if (card == null) return;
        card.findViewById(R.id.nbHelperYes).setOnClickListener(v -> onYes());
        card.findViewById(R.id.nbHelperNotNow).setOnClickListener(v -> {
            markAsked();
            render();
        });
        render();
    }

    public void detach() {
        card = null;
    }

    /** Shows or hides the card from the current state. */
    public void render() {
        Context ctx = fragment.getContext();
        if (card == null || ctx == null) return;
        boolean show = Cloud.isActive(ctx) && !Prefs.get(ctx).getBoolean(Prefs.HELPER_ASKED, false);
        card.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ yes

    private void onYes() {
        Context ctx = fragment.getContext();
        if (ctx == null) return;
        if (CloudHelper.hasLocationPermission(ctx)) {
            optIn(ctx);
        } else {
            locationLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION});
        }
    }

    private void onLocationResult(Map<String, Boolean> result) {
        Context ctx = fragment.getContext();
        if (ctx == null) return;
        if (CloudHelper.hasLocationPermission(ctx)) {
            optIn(ctx);
        } else {
            // She said yes but refused location: don't nag; she can opt in from Cloud & guardians.
            markAsked();
            render();
            snack(R.string.nb_home_location_denied);
        }
    }

    private void optIn(Context ctx) {
        final Context app = ctx.getApplicationContext();
        markAsked();
        CloudHelper.setOptIn(app, true, ProtectionController.isProtectionActive());
        render();
        Cloud.io().execute(() -> {
            boolean ok;
            try {
                ok = CloudHelper.pushLocation(app);
            } catch (CloudException | RuntimeException e) {
                ok = false;
            }
            if (!ok) CloudHelper.refreshSoon(app);
            final boolean registered = ok;
            Cloud.main().post(() -> {
                snack(registered ? R.string.nb_home_thanks : R.string.nb_home_thanks_offline);
                offerMesh();
            });
        });
    }

    // ------------------------------------------------------------------ offline Bluetooth alert

    private void offerMesh() {
        Context ctx = fragment.getContext();
        if (ctx == null || !fragment.isAdded() || Mesh.isEnabled(ctx)) return;
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.nb_mesh_title)
                .setMessage(R.string.nb_mesh_body)
                .setNegativeButton(R.string.nb_mesh_no, null)
                .setPositiveButton(R.string.nb_mesh_yes, (d, w) -> {
                    Context c = fragment.getContext();
                    if (c == null) return;
                    if (Mesh.hasPermissions(c)) {
                        enableMesh(c);
                    } else {
                        meshLauncher.launch(Mesh.requiredPermissions());
                    }
                })
                .show();
    }

    private void onMeshResult(Map<String, Boolean> result) {
        Context ctx = fragment.getContext();
        if (ctx == null) return;
        if (Mesh.hasPermissions(ctx)) enableMesh(ctx);
        else snack(R.string.nb_mesh_denied);
    }

    private void enableMesh(Context ctx) {
        Prefs.get(ctx).edit().putBoolean(Prefs.MESH_ENABLED, true).apply();
        Prefs.notifyChanged(ctx);
        snack(R.string.nb_mesh_on);
    }

    // ------------------------------------------------------------------ helpers

    private void markAsked() {
        Context ctx = fragment.getContext();
        if (ctx == null) return;
        Prefs.get(ctx).edit().putBoolean(Prefs.HELPER_ASKED, true).apply();
        Prefs.notifyChanged(ctx);
    }

    private void snack(@StringRes int text) {
        View root = fragment.getView();
        if (root == null || !fragment.isAdded()) return;
        Snackbar bar = Snackbar.make(root, text, Snackbar.LENGTH_LONG);
        View nav = fragment.requireActivity().findViewById(R.id.bottom_navigation);
        if (nav != null) bar.setAnchorView(nav);
        bar.show();
    }
}
