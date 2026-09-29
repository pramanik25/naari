package Home_Activity;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.PopupMenu;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.naarishakti.R;
import com.example.naarishakti.TelegramBot;
import com.example.naarishakti.core.Prefs;
import com.example.naarishakti.databinding.ActivitySettingsEmergencyBinding;
import com.example.naarishakti.databinding.DialogUbAddContactBinding;
import com.example.naarishakti.databinding.ItemUbContactBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Emergency message, prioritised contacts, Telegram photo alerts and email photo alerts.
 * Contacts are saved as soon as they change; the text fields are saved with the Save button.
 */
public class SettingsEmergencyActivity extends AppCompatActivity {

    private static final int MAX_CONTACTS = 5;
    private static final String APP_PASSWORDS_URL = "https://myaccount.google.com/apppasswords";

    private ActivitySettingsEmergencyBinding b;
    private final List<Prefs.Contact> contacts = new ArrayList<>();

    private ActivityResultLauncher<Intent> pickContact;
    private ActivityResultLauncher<String> contactsPermission;
    @Nullable private Uri pendingPickedUri;

    private String savedMessage = "";
    private String savedRecipient = "";
    private String savedSender = "";
    private String savedPassword = "";
    private boolean syncing;
    /** WhatsApp alerts section (cloud module). */
    private com.example.naarishakti.cloud.WhatsAppSection whatsApp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivitySettingsEmergencyBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        pickContact = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            Intent data = result.getData();
            if (result.getResultCode() == RESULT_OK && data != null && data.getData() != null) {
                handlePicked(data.getData());
            }
        });
        contactsPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            Uri uri = pendingPickedUri;
            pendingPickedUri = null;
            if (granted && uri != null) {
                handlePicked(uri);
            } else if (!granted) {
                Snackbar.make(b.getRoot(), R.string.ub_emg_contacts_denied, Snackbar.LENGTH_LONG)
                        .setAction(R.string.ub_emg_add_manually, v -> showAddManuallyDialog())
                        .show();
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (hasUnsavedChanges()) {
                    confirmDiscard();
                } else {
                    finish();
                }
            }
        });

        b.backButton.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        b.addFromContactsButton.setOnClickListener(v -> {
            FeatureKit.tick(v);
            launchContactPicker();
        });
        b.addManuallyButton.setOnClickListener(v -> showAddManuallyDialog());
        b.openBotButton.setOnClickListener(v -> openBot());
        b.syncButton.setOnClickListener(v -> {
            FeatureKit.tick(v);
            syncTelegram();
        });
        b.manageTelegramButton.setOnClickListener(v -> {
            FeatureKit.tick(v);
            manageTelegramEmergencyContacts();
        });
        b.appPasswordLink.setOnClickListener(v -> FeatureKit.openUrl(this, APP_PASSWORDS_URL));
        b.saveButton.setOnClickListener(v -> {
            FeatureKit.tick(v);
            save();
        });

        clearErrorOnEdit(b.recipientInput, b.recipientLayout);
        clearErrorOnEdit(b.senderInput, b.senderLayout);
        clearErrorOnEdit(b.passwordInput, b.passwordLayout);

        loadFields();
        contacts.clear();
        contacts.addAll(Prefs.getContacts(this));
        renderContacts();
        renderTelegram();
        whatsApp = new com.example.naarishakti.cloud.WhatsAppSection(this, b.getRoot());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!syncing) renderTelegram();
        whatsApp.refresh();
    }

    // ------------------------------------------------------------------ fields

    private void loadFields() {
        SharedPreferences p = Prefs.get(this);
        savedMessage = p.getString(Prefs.EMERGENCY_MESSAGE, "");
        savedRecipient = p.getString(Prefs.RECIPIENT_EMAIL, "");
        savedSender = p.getString(Prefs.SENDER_EMAIL, "");
        savedPassword = p.getString(Prefs.SENDER_PASSWORD, "");
        b.messageInput.setText(savedMessage);
        b.recipientInput.setText(savedRecipient);
        b.senderInput.setText(savedSender);
        b.passwordInput.setText(savedPassword);
    }

    private static String text(EditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    private String currentPassword() {
        return text(b.passwordInput).replaceAll("\\s+", "");
    }

    private boolean hasUnsavedChanges() {
        return !text(b.messageInput).equals(savedMessage.trim())
                || !text(b.recipientInput).equals(savedRecipient.trim())
                || !text(b.senderInput).equals(savedSender.trim())
                || !currentPassword().equals(savedPassword.trim());
    }

    private static void clearErrorOnEdit(EditText input, final TextInputLayout layout) {
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                layout.setError(null);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    /** Validates and persists the text fields. Returns true when saved. */
    private boolean save() {
        String message = text(b.messageInput);
        String recipient = text(b.recipientInput);
        String sender = text(b.senderInput);
        String password = currentPassword();

        TextInputLayout firstError = null;
        if (!recipient.isEmpty() && !Patterns.EMAIL_ADDRESS.matcher(recipient).matches()) {
            b.recipientLayout.setError(getString(R.string.ub_emg_err_email));
            firstError = b.recipientLayout;
        }
        if (!sender.isEmpty() && !Patterns.EMAIL_ADDRESS.matcher(sender).matches()) {
            b.senderLayout.setError(getString(R.string.ub_emg_err_email));
            if (firstError == null) firstError = b.senderLayout;
        }
        if (!recipient.isEmpty() && sender.isEmpty()) {
            b.senderLayout.setError(getString(R.string.ub_emg_err_sender_needed));
            if (firstError == null) firstError = b.senderLayout;
        }
        if (!sender.isEmpty() && recipient.isEmpty()) {
            b.recipientLayout.setError(getString(R.string.ub_emg_err_recipient_needed));
            if (firstError == null) firstError = b.recipientLayout;
        }
        if (!sender.isEmpty() && password.isEmpty()) {
            b.passwordLayout.setError(getString(R.string.ub_emg_err_password_needed));
            if (firstError == null) firstError = b.passwordLayout;
        }
        if (firstError != null) {
            final TextInputLayout target = firstError;
            b.scroll.post(() -> b.scroll.smoothScrollTo(0, Math.max(0, topWithinScroll(target) - dp(24))));
            if (target.getEditText() != null) target.getEditText().requestFocus();
            FeatureKit.thud(b.saveButton);
            return false;
        }

        Prefs.get(this).edit()
                .putString(Prefs.EMERGENCY_MESSAGE, message)
                .putString(Prefs.RECIPIENT_EMAIL, recipient)
                .putString(Prefs.SENDER_EMAIL, sender)
                .putString(Prefs.SENDER_PASSWORD, password)
                .apply();
        Prefs.setContacts(this, contacts);
        Prefs.notifyChanged(this);
        whatsApp.onContactsChanged();

        savedMessage = message;
        savedRecipient = recipient;
        savedSender = sender;
        savedPassword = password;
        b.passwordInput.setText(password);
        hideKeyboard();
        Snackbar.make(b.getRoot(), contacts.isEmpty() ? R.string.ub_emg_saved_no_contacts : R.string.ub_emg_saved,
                Snackbar.LENGTH_LONG).show();
        return true;
    }

    private int topWithinScroll(View v) {
        int top = 0;
        View cur = v;
        while (cur != null && cur != b.scroll) {
            top += cur.getTop();
            cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
        }
        return top;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void confirmDiscard() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ub_emg_unsaved_title)
                .setMessage(R.string.ub_emg_unsaved_body)
                .setPositiveButton(R.string.ub_emg_save, (d, w) -> {
                    if (save()) finish();
                })
                .setNegativeButton(R.string.ub_discard, (d, w) -> finish())
                .show();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(b.getRoot().getWindowToken(), 0);
        View focus = getCurrentFocus();
        if (focus != null) focus.clearFocus();
    }

    // ------------------------------------------------------------------ contacts

    private void renderContacts() {
        b.contactsList.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        for (int i = 0; i < contacts.size(); i++) {
            final int index = i;
            Prefs.Contact c = contacts.get(i);
            final ItemUbContactBinding row = ItemUbContactBinding.inflate(inflater, b.contactsList, false);
            final String display = displayName(c);
            boolean primary = i == 0;

            row.divider.setVisibility(i == 0 ? View.GONE : View.VISIBLE);
            row.name.setText(display);
            row.number.setText(c.number);
            row.avatar.setText(FeatureKit.initials(c.name));
            row.avatar.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this,
                    primary ? R.color.ns_rose_container : R.color.ns_violet_container)));
            row.avatar.setTextColor(ContextCompat.getColor(this, primary ? R.color.ns_rose : R.color.ns_violet));
            row.primaryPill.setVisibility(primary ? View.VISIBLE : View.GONE);
            row.moreButton.setContentDescription(getString(R.string.ub_emg_options_cd, display));
            row.moreButton.setOnClickListener(v -> showRowMenu(v, index));
            row.rowBody.setOnClickListener(v -> showRowMenu(row.moreButton, index));
            b.contactsList.addView(row.getRoot());
        }
        boolean empty = contacts.isEmpty();
        b.contactsEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        b.contactsList.setVisibility(empty ? View.GONE : View.VISIBLE);
        b.contactsCount.setText(getString(R.string.ub_emg_count, contacts.size(), MAX_CONTACTS));

        boolean full = contacts.size() >= MAX_CONTACTS;
        b.addFromContactsButton.setEnabled(!full);
        b.addManuallyButton.setEnabled(!full);
        b.addFromContactsButton.setText(full ? R.string.ub_emg_limit_reached : R.string.ub_emg_add_from_contacts);
    }

    private String displayName(Prefs.Contact c) {
        return TextUtils.isEmpty(c.name) ? getString(R.string.ub_emg_unnamed) : c.name;
    }

    private void showRowMenu(View anchor, final int index) {
        if (index < 0 || index >= contacts.size()) return;
        PopupMenu menu = new PopupMenu(this, anchor);
        if (index > 0) menu.getMenu().add(0, 1, 0, R.string.ub_emg_make_primary);
        menu.getMenu().add(0, 2, 1, R.string.ub_emg_remove);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                makePrimary(index);
            } else if (item.getItemId() == 2) {
                confirmRemove(index);
            }
            return true;
        });
        menu.show();
    }

    private void makePrimary(int index) {
        Prefs.Contact c = contacts.remove(index);
        contacts.add(0, c);
        persistContacts();
        renderContacts();
        FeatureKit.tick(b.contactsList);
        Snackbar.make(b.getRoot(), getString(R.string.ub_emg_now_primary, displayName(c)), Snackbar.LENGTH_SHORT).show();
    }

    private void confirmRemove(final int index) {
        final Prefs.Contact c = contacts.get(index);
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.ub_emg_remove_title, displayName(c)))
                .setMessage(R.string.ub_emg_remove_body)
                .setPositiveButton(R.string.ub_emg_remove, (d, w) -> {
                    contacts.remove(c);
                    persistContacts();
                    renderContacts();
                    Snackbar.make(b.getRoot(), getString(R.string.ub_emg_removed, displayName(c)), Snackbar.LENGTH_LONG)
                            .setAction(R.string.ub_undo, v -> {
                                if (contacts.size() < MAX_CONTACTS) {
                                    contacts.add(Math.min(index, contacts.size()), c);
                                    persistContacts();
                                    renderContacts();
                                }
                            })
                            .show();
                })
                .setNegativeButton(R.string.ub_cancel, null)
                .show();
    }

    private void persistContacts() {
        Prefs.setContacts(this, contacts);
        Prefs.notifyChanged(this);
        if (whatsApp != null) whatsApp.onContactsChanged();
    }

    /** Compares the last 10 digits so "+91 98765 43210" and "09876543210" are the same person. */
    private boolean isDuplicate(String normalized) {
        String key = lastDigits(normalized);
        for (Prefs.Contact c : contacts) {
            if (lastDigits(Prefs.normalizeNumber(c.number)).equals(key)) return true;
        }
        return false;
    }

    private static String lastDigits(String n) {
        String digits = n.startsWith("+") ? n.substring(1) : n;
        return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
    }

    private static int digitCount(String normalized) {
        return normalized.startsWith("+") ? normalized.length() - 1 : normalized.length();
    }

    private boolean addContact(String name, String rawNumber) {
        String normalized = Prefs.normalizeNumber(rawNumber);
        if (contacts.size() >= MAX_CONTACTS) {
            Snackbar.make(b.getRoot(), R.string.ub_emg_limit_reached_long, Snackbar.LENGTH_LONG).show();
            return false;
        }
        int digits = digitCount(normalized);
        if (digits < 7 || digits > 15) {
            Snackbar.make(b.getRoot(), R.string.ub_emg_err_picked_number, Snackbar.LENGTH_LONG).show();
            return false;
        }
        if (isDuplicate(normalized)) {
            Snackbar.make(b.getRoot(), R.string.ub_emg_err_duplicate, Snackbar.LENGTH_SHORT).show();
            return false;
        }
        Prefs.Contact c = new Prefs.Contact(name, normalized);
        contacts.add(c);
        persistContacts();
        renderContacts();
        FeatureKit.tick(b.contactsList);
        Snackbar.make(b.getRoot(), getString(R.string.ub_emg_added, displayName(c)), Snackbar.LENGTH_SHORT).show();
        return true;
    }

    private void launchContactPicker() {
        Intent i = new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
        try {
            pickContact.launch(i);
        } catch (ActivityNotFoundException e) {
            Snackbar.make(b.getRoot(), R.string.ub_emg_no_contacts_app, Snackbar.LENGTH_LONG).show();
            showAddManuallyDialog();
        }
    }

    private void handlePicked(Uri uri) {
        String[] projection = {
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER};
        try (Cursor c = getContentResolver().query(uri, projection, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                addContact(c.getString(0), c.getString(1));
            } else {
                Snackbar.make(b.getRoot(), R.string.ub_emg_err_read_contact, Snackbar.LENGTH_LONG).show();
            }
        } catch (SecurityException e) {
            // Some contact apps don't grant a read permission for the picked row.
            if (!FeatureKit.isGranted(this, Manifest.permission.READ_CONTACTS)) {
                pendingPickedUri = uri;
                contactsPermission.launch(Manifest.permission.READ_CONTACTS);
            } else {
                Snackbar.make(b.getRoot(), R.string.ub_emg_err_read_contact, Snackbar.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Snackbar.make(b.getRoot(), R.string.ub_emg_err_read_contact, Snackbar.LENGTH_LONG).show();
        }
    }

    private void showAddManuallyDialog() {
        if (contacts.size() >= MAX_CONTACTS) {
            Snackbar.make(b.getRoot(), R.string.ub_emg_limit_reached_long, Snackbar.LENGTH_LONG).show();
            return;
        }
        final DialogUbAddContactBinding d = DialogUbAddContactBinding.inflate(getLayoutInflater());
        final AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ub_emg_dialog_title)
                .setView(d.getRoot())
                .setPositiveButton(R.string.ub_add, null)
                .setNegativeButton(R.string.ub_cancel, null)
                .create();
        clearErrorOnEdit(d.numberInput, d.numberLayout);
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = text(d.nameInput);
            String normalized = Prefs.normalizeNumber(text(d.numberInput));
            int digits = digitCount(normalized);
            if (digits < 7 || digits > 15) {
                d.numberLayout.setError(getString(R.string.ub_emg_err_number));
                return;
            }
            if (isDuplicate(normalized)) {
                d.numberLayout.setError(getString(R.string.ub_emg_err_duplicate));
                return;
            }
            if (addContact(name, normalized)) dialog.dismiss();
        }));
        d.numberInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                return true;
            }
            return false;
        });
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        dialog.show();
        d.nameInput.requestFocus();
    }

    // ------------------------------------------------------------------ Telegram

    private void renderTelegram() {
        boolean configured = TelegramBot.isConfigured();
        List<String> allChatIds = Prefs.getTelegramChatIds(this);
        List<String> emergencyChatIds = Prefs.getTelegramEmergencyChatIds(this);
        int linked = allChatIds.size();
        int selected = emergencyChatIds.size();
        int dotColor;
        if (!configured) {
            b.telegramStatus.setText(R.string.ub_emg_telegram_not_configured);
            b.telegramExplain.setText(R.string.ub_emg_telegram_not_configured_body);
            dotColor = R.color.ns_warn;
        } else if (linked > 0) {
            b.telegramStatus.setText(getResources().getQuantityString(R.plurals.ub_emg_telegram_linked, linked, linked));
            if (selected > 0) {
                b.telegramExplain.setText(getResources().getQuantityString(
                        R.plurals.ub_emg_telegram_emergency_selected, selected, selected));
            } else {
                b.telegramExplain.setText(R.string.ub_emg_telegram_explain);
            }
            dotColor = selected > 0 ? R.color.ns_safe : R.color.ns_text_faint;
        } else {
            b.telegramStatus.setText(R.string.ub_emg_telegram_not_linked);
            b.telegramExplain.setText(R.string.ub_emg_telegram_explain);
            dotColor = R.color.ns_text_faint;
        }
        b.telegramStatusDot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, dotColor)));
        b.openBotButton.setEnabled(configured);
        b.syncButton.setEnabled(configured && !syncing);
    }

    private void openBot() {
        String link = TelegramBot.getBotLink();
        if (TextUtils.isEmpty(link) || !FeatureKit.openUrl(this, link)) {
            Snackbar.make(b.getRoot(), R.string.ub_emg_open_bot_failed, Snackbar.LENGTH_LONG).show();
        }
    }

    private void syncTelegram() {
        if (syncing || !TelegramBot.isConfigured()) return;
        syncing = true;
        b.syncProgress.setVisibility(View.VISIBLE);
        b.syncButton.setEnabled(false);
        b.syncButton.setText(R.string.ub_emg_syncing);
        TelegramBot.fetchAndStoreChatIds(getApplicationContext(), (newlyLinked, totalLinked, error) -> {
            if (isFinishing() || isDestroyed()) return;
            syncing = false;
            b.syncProgress.setVisibility(View.GONE);
            b.syncButton.setText(R.string.ub_emg_sync);
            renderTelegram();
            String msg;
            if (error != null) {
                msg = getString(R.string.ub_emg_sync_failed, error);
            } else if (newlyLinked > 0) {
                msg = getResources().getQuantityString(R.plurals.ub_emg_sync_new, newlyLinked, newlyLinked);
            } else if (totalLinked > 0) {
                msg = getResources().getQuantityString(R.plurals.ub_emg_sync_up_to_date, totalLinked, totalLinked);
            } else {
                msg = getString(R.string.ub_emg_sync_none);
            }
            Snackbar.make(b.getRoot(), msg, Snackbar.LENGTH_LONG).show();
        });
    }

    private void manageTelegramEmergencyContacts() {
        List<String> allChatIds = Prefs.getTelegramChatIds(this);
        List<String> emergencyChatIds = Prefs.getTelegramEmergencyChatIds(this);

        if (allChatIds.isEmpty()) {
            Snackbar.make(b.getRoot(), R.string.ub_emg_telegram_no_contacts, Snackbar.LENGTH_LONG).show();
            return;
        }

        CharSequence[] items = new CharSequence[allChatIds.size()];
        boolean[] checked = new boolean[allChatIds.size()];
        for (int i = 0; i < allChatIds.size(); i++) {
            String chatId = allChatIds.get(i);
            items[i] = getString(R.string.ub_emg_telegram_contact, chatId);
            checked[i] = emergencyChatIds.contains(chatId);
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ub_emg_telegram_select_title)
                .setMultiChoiceItems(items, checked, (dialog, which, isChecked) -> {
                    if (isChecked) {
                        if (!emergencyChatIds.contains(allChatIds.get(which))) {
                            emergencyChatIds.add(allChatIds.get(which));
                        }
                    } else {
                        emergencyChatIds.remove(allChatIds.get(which));
                    }
                })
                .setPositiveButton(R.string.ub_emg_save, (dialog, which) -> {
                    Prefs.setTelegramEmergencyChatIds(this, emergencyChatIds);
                    renderTelegram();
                    Snackbar.make(b.getRoot(), R.string.ub_emg_telegram_saved, Snackbar.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.ub_cancel, null)
                .show();
    }
}
