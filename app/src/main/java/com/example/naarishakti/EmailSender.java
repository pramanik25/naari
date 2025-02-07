package com.example.naarishakti;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.activation.DataHandler;
import javax.activation.FileDataSource;
import javax.mail.Authenticator;
import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

public class EmailSender {
    private static final String TAG = "EmailSender";
    private static String EMAIL_USERNAME; // Make these non-final
    private static String EMAIL_PASSWORD;
    private static final String SMTP_HOST = "smtp.gmail.com";
    private static final int SMTP_PORT = 465;
    private static final int MAX_RETRIES = 3;

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public interface EmailSendCallback {
        void onSuccess();
        void onError(String error);
    }

    public EmailSender(Context context) {
        this.context = context;
    }

    public void sendEmail(File imageFile, String toEmail, String senderEmail, String senderPassword, boolean isFront, EmailSendCallback callback) {
        if (imageFile == null || !imageFile.exists()) {
            Log.e(TAG, "Image file is invalid");
            if (callback != null) callback.onError("Invalid file");
            return;
        }
        EMAIL_USERNAME = senderEmail;
        EMAIL_PASSWORD = senderPassword;

        executor.execute(() -> {
            try {
                Session session = createEmailSession();
                MimeMessage message = createEmailMessage(session, imageFile, toEmail, isFront);
                sendWithRetry(session, message, 0);
                if (callback != null) callback.onSuccess();
            } catch (Exception e) {
                Log.e(TAG, "Email sending failed: " + e.getMessage());
                if (callback != null) callback.onError(e.getMessage());
            }
        });
    }

    private Session createEmailSession() {
        Properties props = new Properties();
        props.put("mail.smtp.host", SMTP_HOST);
        props.put("mail.smtp.port", SMTP_PORT);
        props.put("mail.smtp.ssl.enable", "true");
        props.put("mail.smtp.socketFactory.port", SMTP_PORT);
        props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "5000");

        return Session.getInstance(props, new Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(EMAIL_USERNAME, EMAIL_PASSWORD);
            }
        });
    }

    private MimeMessage createEmailMessage(Session session, File imageFile,
                                           String toEmail, boolean isFront) throws MessagingException {
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(EMAIL_USERNAME));
        message.addRecipient(Message.RecipientType.TO, new InternetAddress(toEmail));
        message.setSubject("Emergency Alert: " + (isFront ? "Front" : "Back") + " Camera Photo");

        Multipart multipart = new MimeMultipart();

        // Text part
        BodyPart messageBodyPart = new MimeBodyPart();
        messageBodyPart.setText("Emergency photo captured from " +
                (isFront ? "front" : "back") + " camera.\n\n" +
                "Sent via NaariShakti Safety App");
        multipart.addBodyPart(messageBodyPart);

        // Attachment part
        MimeBodyPart attachmentPart = new MimeBodyPart();
        FileDataSource source = new FileDataSource(imageFile);
        attachmentPart.setDataHandler(new DataHandler(source));
        attachmentPart.setFileName(imageFile.getName());
        multipart.addBodyPart(attachmentPart);

        message.setContent(multipart);
        return message;
    }

    private void sendWithRetry(Session session, MimeMessage message, int attempt)
            throws MessagingException, InterruptedException {
        try {
            Transport transport = session.getTransport("smtp");
            try {
                transport.connect(SMTP_HOST, EMAIL_USERNAME, EMAIL_PASSWORD);
                transport.sendMessage(message, message.getAllRecipients());
                Log.d(TAG, "Email successfully sent to: " + Arrays.toString(message.getAllRecipients()));
            } finally {
                transport.close();
            }
        } catch (MessagingException e) {
            if (attempt < MAX_RETRIES) {
                Log.w(TAG, "Retrying email send (" + (attempt+1) + "/" + MAX_RETRIES + ")");
                Thread.sleep(2000);
                sendWithRetry(session, message, attempt + 1);
            } else {
                throw new MessagingException("Failed after " + MAX_RETRIES + " attempts: " + e.getMessage());
            }
        }
    }
}