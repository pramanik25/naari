package com.example.naarishakti;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.example.naarishakti.core.Prefs;

import java.io.File;
import java.util.Properties;

import javax.activation.DataHandler;
import javax.activation.FileDataSource;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

/**
 * Sends evidence mail through Gmail SMTP using the sender account saved in settings.
 * Each instance holds its own credentials, so concurrent senders never interfere.
 */
public class EmailSender {

    private static final String TAG = "EmailSender";
    private static final String SMTP_HOST = "smtp.gmail.com";
    private static final String SMTP_PORT = "465";
    private static final int MAX_ATTEMPTS = 3;

    private final String senderEmail;
    private final String senderPassword;
    private final String recipientEmail;

    public EmailSender(Context context) {
        SharedPreferences prefs = Prefs.get(context);
        this.senderEmail = prefs.getString(Prefs.SENDER_EMAIL, "").trim();
        this.senderPassword = prefs.getString(Prefs.SENDER_PASSWORD, "");
        this.recipientEmail = prefs.getString(Prefs.RECIPIENT_EMAIL, "").trim();
    }

    public boolean isConfigured() {
        return !TextUtils.isEmpty(senderEmail) && !TextUtils.isEmpty(senderPassword)
                && !TextUtils.isEmpty(recipientEmail);
    }

    /**
     * Blocking send with a few retries. Call from a background thread.
     * @return true only if the SMTP server accepted the message.
     */
    @WorkerThread
    public boolean send(String subject, String body, @Nullable File attachment) {
        if (!isConfigured()) {
            Log.w(TAG, "Email not configured");
            return false;
        }
        MimeMessage message;
        Session session = createSession();
        try {
            message = buildMessage(session, subject, body, attachment);
        } catch (MessagingException e) {
            Log.e(TAG, "Couldn't build email: " + e.getMessage());
            return false;
        }
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Transport transport = null;
            try {
                transport = session.getTransport("smtps");
                transport.connect(SMTP_HOST, senderEmail, senderPassword);
                transport.sendMessage(message, message.getAllRecipients());
                Log.d(TAG, "Email sent to " + recipientEmail);
                return true;
            } catch (MessagingException e) {
                Log.w(TAG, "Email attempt " + attempt + "/" + MAX_ATTEMPTS + " failed: " + e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(2000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            } finally {
                if (transport != null) {
                    try {
                        transport.close();
                    } catch (MessagingException ignored) {
                        // Nothing useful to do.
                    }
                }
            }
        }
        return false;
    }

    private Session createSession() {
        Properties props = new Properties();
        props.put("mail.smtps.host", SMTP_HOST);
        props.put("mail.smtps.port", SMTP_PORT);
        props.put("mail.smtps.auth", "true");
        props.put("mail.smtps.connectiontimeout", "15000");
        props.put("mail.smtps.timeout", "30000");
        props.put("mail.smtps.writetimeout", "60000");
        props.put("mail.smtps.quitwait", "false");
        return Session.getInstance(props);
    }

    private MimeMessage buildMessage(Session session, String subject, String body,
                                     @Nullable File attachment) throws MessagingException {
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(senderEmail));
        message.addRecipient(Message.RecipientType.TO, new InternetAddress(recipientEmail));
        message.setSubject(subject);

        Multipart multipart = new MimeMultipart();
        MimeBodyPart text = new MimeBodyPart();
        text.setText(body);
        multipart.addBodyPart(text);

        if (attachment != null && attachment.exists()) {
            MimeBodyPart file = new MimeBodyPart();
            file.setDataHandler(new DataHandler(new FileDataSource(attachment)));
            file.setFileName(attachment.getName());
            multipart.addBodyPart(file);
        }
        message.setContent(multipart);
        return message;
    }
}
