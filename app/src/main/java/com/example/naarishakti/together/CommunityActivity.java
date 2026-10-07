package com.example.naarishakti.together;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.naarishakti.R;
import com.example.naarishakti.cloud.CloudSocial;
import com.example.naarishakti.databinding.TgActivityCommunityBinding;
import com.example.naarishakti.databinding.TgItemPostBinding;
import com.example.naarishakti.databinding.TgSheetPostBinding;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Anonymous community feed: short text posts by topic, newest first. Nobody sees who wrote what;
 * each thread gives its writers a made-up name. Tapping a post opens {@link ThreadActivity}.
 */
public class CommunityActivity extends AppCompatActivity {

    private static final String TAG = "CommunityActivity";
    private static final String K_RULES_SEEN = "community_rules_seen";

    private TgActivityCommunityBinding b;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<CloudSocial.Post> posts = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    /** Topic filter, or null for every topic. */
    @Nullable private String topic;
    /** Cursor for the next (older) page; 0 when everything is loaded. */
    private long nextBefore;
    private boolean loading;
    /** Bumped on every fresh load so an answer for an old filter is dropped. */
    private int loadSeq;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = TgActivityCommunityBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        b.backButton.setOnClickListener(v -> finish());
        b.rulesButton.setOnClickListener(v -> showRules());
        b.newPostButton.setOnClickListener(v -> showPostSheet());

        final LinearLayoutManager layout = new LinearLayoutManager(this);
        b.list.setLayoutManager(layout);
        b.list.setAdapter(adapter);
        b.list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy > 0 && nextBefore > 0 && !loading
                        && layout.findLastVisibleItemPosition() >= posts.size() - 5) {
                    load(false);
                }
            }
        });

        for (String t : Tg.TOPICS) {
            Chip chip = (Chip) getLayoutInflater().inflate(R.layout.dl_item_chip, b.topicChips, false);
            chip.setId(View.generateViewId());
            chip.setText(Tg.topicLabel(t));
            chip.setTag(t);
            b.topicChips.addView(chip);
        }
        b.topicChips.setOnCheckedStateChangeListener((group, ids) -> {
            View checked = ids.isEmpty() ? null : group.findViewById(ids.get(0));
            topic = checked != null && checked.getTag() instanceof String ? (String) checked.getTag() : null;
            load(true);
        });

        boolean cloud = CloudSocial.isActive(this);
        Tg.show(b.cloudOff, !cloud);
        Tg.show(b.content, cloud);
        Tg.show(b.newPostButton, cloud);
        if (cloud && !Tg.kv(this).getBoolean(K_RULES_SEEN, false)) {
            Tg.kv(this).edit().putBoolean(K_RULES_SEEN, true).apply();
            showRules();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from a thread: reply counts, deletions and blocks may have changed.
        if (CloudSocial.isActive(this)) load(true);
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ data

    private void load(final boolean fresh) {
        if (!fresh && loading) return;
        loading = true;
        final int seq = fresh ? ++loadSeq : loadSeq;
        final String forTopic = topic;
        final long before = fresh ? 0 : nextBefore;
        Tg.show(b.progress, posts.isEmpty());
        io.execute(() -> {
            CloudSocial.Feed feed = null;
            Throwable error = null;
            try {
                feed = CloudSocial.feed(this, forTopic, before);
            } catch (Throwable t) {
                Log.w(TAG, "Feed load failed", t);
                error = t;
            }
            final CloudSocial.Feed result = feed;
            final Throwable failure = error;
            main.post(() -> {
                if (isFinishing() || isDestroyed() || seq != loadSeq) return;
                loading = false;
                Tg.show(b.progress, false);
                if (result == null) {
                    Snackbar.make(b.getRoot(), Tg.errorText(failure), Snackbar.LENGTH_LONG).show();
                } else {
                    if (fresh) posts.clear();
                    posts.addAll(result.posts);
                    nextBefore = result.nextBefore;
                    adapter.notifyDataSetChanged();
                }
                Tg.show(b.emptyState, posts.isEmpty());
            });
        });
    }

    // ------------------------------------------------------------------ rules and posting

    private void showRules() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.tg_cm_rules_title)
                .setMessage(R.string.tg_cm_rules_body)
                .setPositiveButton(R.string.tg_cm_rules_ok, null)
                .show();
    }

    private void showPostSheet() {
        final BottomSheetDialog sheet = new BottomSheetDialog(this);
        final TgSheetPostBinding s = TgSheetPostBinding.inflate(LayoutInflater.from(this));
        sheet.setContentView(s.getRoot());
        final List<Chip> chips = new ArrayList<>();
        for (String t : Tg.TOPICS) {
            Chip chip = (Chip) getLayoutInflater().inflate(R.layout.dl_item_chip, s.topicChips, false);
            chip.setId(View.generateViewId());
            chip.setText(Tg.topicLabel(t));
            chip.setChecked(t.equals(topic));
            s.topicChips.addView(chip);
            chips.add(chip);
        }
        s.postButton.setOnClickListener(v -> {
            String chosen = null;
            for (int i = 0; i < chips.size(); i++) if (chips.get(i).isChecked()) chosen = Tg.TOPICS[i];
            final String text = s.bodyInput.getText() == null ? "" : s.bodyInput.getText().toString().trim();
            if (chosen == null) {
                Snackbar.make(s.getRoot(), R.string.tg_cm_pick_topic, Snackbar.LENGTH_SHORT).show();
                return;
            }
            if (text.isEmpty()) {
                s.bodyLayout.setError(getString(R.string.tg_cm_body_required));
                return;
            }
            final String forTopic = chosen;
            s.postButton.setEnabled(false);
            io.execute(() -> {
                Throwable error = null;
                try {
                    CloudSocial.createPost(this, forTopic, text);
                } catch (Throwable t) {
                    Log.w(TAG, "Posting failed", t);
                    error = t;
                }
                final Throwable failure = error;
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (failure != null) {
                        // Keep the sheet open so what she wrote is not lost.
                        s.postButton.setEnabled(true);
                        if (sheet.isShowing()) s.bodyLayout.setError(getString(Tg.errorText(failure)));
                        return;
                    }
                    sheet.dismiss();
                    Snackbar.make(b.getRoot(), R.string.tg_cm_posted, Snackbar.LENGTH_SHORT).show();
                    load(true);
                });
            });
        });
        sheet.setOnShowListener(d -> {
            View container = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (container != null) {
                ViewCompat.setBackgroundTintList(container,
                        ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ns_surface_high)));
            }
        });
        sheet.show();
    }

    // ------------------------------------------------------------------ adapter

    private final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {

        final class Holder extends RecyclerView.ViewHolder {
            final TgItemPostBinding vb;

            Holder(TgItemPostBinding vb) {
                super(vb.getRoot());
                this.vb = vb;
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(TgItemPostBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            final CloudSocial.Post p = posts.get(position);
            h.vb.topic.setText(Tg.topicLabel(p.topic));
            h.vb.meta.setText(getString(p.mine ? R.string.tg_cm_meta_mine : R.string.tg_cm_meta,
                    p.alias, Tg.ago(p.createdAt)));
            h.vb.body.setText(p.body);
            h.vb.replies.setText(getResources().getQuantityString(R.plurals.tg_cm_replies, p.replyCount, p.replyCount));
            h.vb.getRoot().setOnClickListener(v -> Tg.open(CommunityActivity.this,
                    new Intent(CommunityActivity.this, ThreadActivity.class)
                            .putExtra(ThreadActivity.EXTRA_POST_ID, p.id)));
        }

        @Override
        public int getItemCount() {
            return posts.size();
        }
    }
}
