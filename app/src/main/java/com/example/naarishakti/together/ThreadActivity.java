package com.example.naarishakti.together;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;

import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;

import com.example.naarishakti.R;
import com.example.naarishakti.cloud.CloudException;
import com.example.naarishakti.cloud.CloudSocial;
import com.example.naarishakti.databinding.TgActivityThreadBinding;
import com.example.naarishakti.databinding.TgItemReplyBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One community thread: the post, its replies and a box to reply. The "more" button on the post
 * and on every reply offers Delete for her own text, and Report / Block for someone else's.
 */
public class ThreadActivity extends AppCompatActivity {

    public static final String EXTRA_POST_ID = "tg_post_id";

    private static final String TAG = "ThreadActivity";
    private static final int MENU_DELETE = 1;
    private static final int MENU_FLAG = 2;
    private static final int MENU_BLOCK = 3;

    /** One call to the API; lets the actions below share the thread hop and error handling. */
    private interface Call {
        void run() throws Exception;
    }

    private TgActivityThreadBinding b;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private String postId;
    private boolean sending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = TgActivityThreadBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        postId = getIntent().getStringExtra(EXTRA_POST_ID);
        if (TextUtils.isEmpty(postId)) {
            finish();
            return;
        }
        b.backButton.setOnClickListener(v -> finish());
        b.sendButton.setOnClickListener(v -> send());
        load(false);
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ data

    private void load(final boolean scrollToEnd) {
        Tg.show(b.progress, b.replies.getChildCount() == 0);
        io.execute(() -> {
            CloudSocial.PostThread thread = null;
            Throwable error = null;
            try {
                thread = CloudSocial.thread(this, postId);
            } catch (Throwable t) {
                Log.w(TAG, "Thread load failed", t);
                error = t;
            }
            final CloudSocial.PostThread result = thread;
            final Throwable failure = error;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                Tg.show(b.progress, false);
                if (result == null) {
                    if (failure instanceof CloudException && ((CloudException) failure).status == 404) {
                        // Deleted, hidden or by someone she blocked: nothing left to show.
                        finish();
                        return;
                    }
                    Snackbar.make(b.getRoot(), Tg.errorText(failure), Snackbar.LENGTH_LONG).show();
                    return;
                }
                render(result);
                if (scrollToEnd) b.scroll.post(() -> b.scroll.fullScroll(View.FOCUS_DOWN));
            });
        });
    }

    private void render(CloudSocial.PostThread thread) {
        final CloudSocial.Post p = thread.post;
        Tg.show(b.postCard, true);
        Tg.show(b.replyBar, true);
        b.topic.setText(Tg.topicLabel(p.topic));
        b.meta.setText(getString(p.mine ? R.string.tg_cm_meta_mine : R.string.tg_cm_meta, p.alias, Tg.ago(p.createdAt)));
        b.body.setText(p.body);
        b.moreButton.setOnClickListener(v -> showMenu(v, true, p.id, p.mine));

        b.replies.removeAllViews();
        b.repliesLabel.setText(getResources().getQuantityString(R.plurals.tg_cm_replies,
                thread.replies.size(), thread.replies.size()));
        LayoutInflater inflater = LayoutInflater.from(this);
        for (final CloudSocial.Reply r : thread.replies) {
            TgItemReplyBinding row = TgItemReplyBinding.inflate(inflater, b.replies, false);
            @StringRes int meta = r.mine ? R.string.tg_cm_meta_mine
                    : r.byAuthor ? R.string.tg_cm_meta_author : R.string.tg_cm_meta;
            row.meta.setText(getString(meta, r.alias, Tg.ago(r.createdAt)));
            row.body.setText(r.body);
            row.moreButton.setOnClickListener(v -> showMenu(v, false, r.id, r.mine));
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) row.getRoot().getLayoutParams();
            lp.topMargin = Tg.dp(this, 8);
            b.replies.addView(row.getRoot(), lp);
        }
    }

    // ------------------------------------------------------------------ actions

    private void send() {
        if (sending) return;
        final String text = b.replyInput.getText() == null ? "" : b.replyInput.getText().toString().trim();
        if (text.isEmpty()) return;
        sending = true;
        b.sendButton.setEnabled(false);
        io.execute(() -> {
            Throwable error = null;
            try {
                CloudSocial.reply(this, postId, text);
            } catch (Throwable t) {
                Log.w(TAG, "Reply failed", t);
                error = t;
            }
            final Throwable failure = error;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                sending = false;
                b.sendButton.setEnabled(true);
                if (failure != null) {
                    // Her text stays in the box so she can fix it or try again.
                    Snackbar.make(b.getRoot(), Tg.errorText(failure), Snackbar.LENGTH_LONG).show();
                    return;
                }
                b.replyInput.setText("");
                load(true);
            });
        });
    }

    private void showMenu(View anchor, final boolean isPost, final String id, boolean mine) {
        PopupMenu menu = new PopupMenu(this, anchor);
        if (mine) {
            menu.getMenu().add(Menu.NONE, MENU_DELETE, Menu.NONE, R.string.tg_delete);
        } else {
            menu.getMenu().add(Menu.NONE, MENU_FLAG, Menu.NONE, R.string.tg_cm_flag);
            menu.getMenu().add(Menu.NONE, MENU_BLOCK, Menu.NONE, R.string.tg_cm_block);
        }
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_DELETE) {
                confirm(R.string.tg_cm_delete_title, R.string.tg_cm_delete_body, R.string.tg_delete, () -> act(
                        () -> {
                            if (isPost) CloudSocial.deletePost(this, id);
                            else CloudSocial.deleteReply(this, id);
                        }, R.string.tg_cm_deleted, isPost));
            } else if (item.getItemId() == MENU_FLAG) {
                confirm(R.string.tg_cm_flag_title, R.string.tg_cm_flag_body, R.string.tg_cm_flag,
                        () -> act(() -> CloudSocial.flag(this, isPost, id), R.string.tg_flagged, false));
            } else if (item.getItemId() == MENU_BLOCK) {
                // Blocking the thread's author hides the whole thread, so the screen closes.
                confirm(R.string.tg_cm_block_title, R.string.tg_cm_block_body, R.string.tg_cm_block,
                        () -> act(() -> CloudSocial.block(this, isPost, id), R.string.tg_cm_blocked, isPost));
            }
            return true;
        });
        menu.show();
    }

    private void confirm(@StringRes int title, @StringRes int message, @StringRes int positive,
                         final Runnable onConfirm) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positive, (d, w) -> onConfirm.run())
                .setNegativeButton(R.string.tg_cancel, null)
                .show();
    }

    /** Runs one API call, says how it went, then reloads the thread or closes the screen. */
    private void act(final Call call, @StringRes final int doneText, final boolean closeAfter) {
        io.execute(() -> {
            Throwable error = null;
            try {
                call.run();
            } catch (Throwable t) {
                Log.w(TAG, "Thread action failed", t);
                error = t;
            }
            final Throwable failure = error;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (failure != null) {
                    Snackbar.make(b.getRoot(), Tg.errorText(failure), Snackbar.LENGTH_LONG).show();
                } else if (closeAfter) {
                    android.widget.Toast.makeText(this, doneText, android.widget.Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    Snackbar.make(b.getRoot(), doneText, Snackbar.LENGTH_SHORT).show();
                    load(false);
                }
            });
        });
    }
}
