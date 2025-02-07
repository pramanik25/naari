// TriggerWordActivity.java
package Home_Activity;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.airbnb.lottie.LottieAnimationView;
import com.example.naarishakti.R;
import com.google.android.material.button.MaterialButtonToggleGroup;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TriggerWordActivity extends AppCompatActivity {

    private static final String TAG = "TriggerWordActivity";
    private static final int REQUEST_AUDIO_PERMISSION_CODE = 200;

    private TextView recordedWordTextView;
    private LottieAnimationView recordingAnimation;
    private TextView recordingStatus;
    private Button recordButton;
    private Button playbackButton;
    private MaterialButtonToggleGroup recordButtons;
    private MediaRecorder mediaRecorder;
    private MediaPlayer mediaPlayer;
    private boolean isRecording = false;
    private String audioFilePath;
    private SpeechRecognizer speechRecognizer;
    private Intent speechRecognizerIntent;
    private final String KEY_TRIGGER_PHRASE = "trigger_phrase_text";
    private boolean speechRecognitionInProgress = false;
    private TextView lastRecordingText;
    private Button playLastRecordingButton;
    private String lastRecordingTextValue = "";
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trigger_word);

        recordedWordTextView = findViewById(R.id.recordedWordTextView);
        recordingAnimation = findViewById(R.id.recordingAnimation);
        recordingStatus = findViewById(R.id.recordingStatus);
        recordButton = findViewById(R.id.recordButton);
        playbackButton = findViewById(R.id.playbackButton);
        recordButtons = findViewById(R.id.recordButtons);
        lastRecordingText = findViewById(R.id.lastRecordingText);
        playLastRecordingButton = findViewById(R.id.playLastRecordingButton);

        playbackButton.setEnabled(false);
        loadLastRecording();

        recordButton.setOnClickListener(v -> handleRecordButtonClick());
        playbackButton.setOnClickListener(v -> playRecording());
        playLastRecordingButton.setOnClickListener(v -> playLastRecording());

        setupSpeechRecognizer();
    }

    private void loadLastRecording() {
        SharedPreferences sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        audioFilePath = sharedPreferences.getString("last_trigger_audio_path", null);
        lastRecordingTextValue = sharedPreferences.getString("last_trigger_text", "");
        runOnUiThread(() -> {
            playbackButton.setEnabled(audioFilePath != null && new File(audioFilePath).exists());
            lastRecordingText.setText("Last Recording Text: " + lastRecordingTextValue);
            playLastRecordingButton.setEnabled(audioFilePath != null && new File(audioFilePath).exists());
        });
    }

    private void setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                if (!isFinishing() && !isDestroyed()) {
                    runOnUiThread(() -> recordedWordTextView.setText("Listening..."));
                    speechRecognitionInProgress = true;
                }
            }

            @Override
            public void onBeginningOfSpeech() {
                if (!isFinishing() && !isDestroyed()) {
                    runOnUiThread(() -> recordedWordTextView.setText("Speaking..."));
                }
            }

            @Override
            public void onRmsChanged(float rmsdB) {
                // You can visualize the sound level here if needed
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
                // Not used
            }

            @Override
            public void onEndOfSpeech() {
                if (!isFinishing() && !isDestroyed()) {
                    runOnUiThread(() -> recordedWordTextView.setText("Processing..."));
                }
            }

            @Override
            public void onError(int error) {
                speechRecognitionInProgress = false;
                handleSpeechRecognizerError(error);
            }

            @Override
            public void onResults(Bundle results) {
                speechRecognitionInProgress = false;
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String recognizedText = matches.get(0);
                    if (!isFinishing() && !isDestroyed()) {
                        runOnUiThread(() -> recordedWordTextView.setText("You said: " + recognizedText));
                        saveTriggerPhraseText(recognizedText);
                        lastRecordingTextValue = recognizedText;
                        runOnUiThread(() -> lastRecordingText.setText("Last Recording Text: " + lastRecordingTextValue));
                    }
                } else {
                    if (!isFinishing() && !isDestroyed()) {
                        runOnUiThread(() -> recordedWordTextView.setText("No speech recognized."));
                    }
                }
                stopRecording();
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                ArrayList<String> partialMatches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (partialMatches != null && !partialMatches.isEmpty()) {
                    String text = partialMatches.get(0);
                    if (!isFinishing() && !isDestroyed()) {
                        runOnUiThread(() -> recordedWordTextView.setText("Listening: " + text + "..."));
                    }
                }
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
                // Ignore
            }
        });
    }

    private void handleSpeechRecognizerError(int error) {
        String errorMessage = getSpeechErrorText(error);
        if (!isFinishing() && !isDestroyed()) {
            runOnUiThread(() -> {
                recordedWordTextView.setText("Error: " + errorMessage);
                recordingStatus.setText("Ready to record");
                recordButton.setText("Record");
                recordingAnimation.cancelAnimation();
                isRecording = false;
            });
        }
        Log.e(TAG, "Speech Recognition Error: " + errorMessage);
        stopMediaRecorder();

    }

    private String getSpeechErrorText(int errorCode) {
        String message;
        switch (errorCode) {
            case SpeechRecognizer.ERROR_AUDIO:
                message = "Audio recording error";
                break;
            case SpeechRecognizer.ERROR_CLIENT:
                message = "Client side error";
                break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                message = "Insufficient permissions";
                break;
            case SpeechRecognizer.ERROR_NETWORK:
                message = "Network error";
                break;
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                message = "Network timeout";
                break;
            case SpeechRecognizer.ERROR_NO_MATCH:
                message = "No match found";
                break;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                message = "Recognition service busy";
                break;
            case SpeechRecognizer.ERROR_SERVER:
                message = "Server sends error status";
                break;
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                message = "No speech input detected";
                break;
            default:
                message = "Didn't understand, please try again.";
                break;
        }
        return message;
    }

    private void saveTriggerPhraseText(String text) {
        SharedPreferences sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(KEY_TRIGGER_PHRASE, text);
        editor.apply();

        // Notify VoskService about the updated trigger phrase
        Intent intent = new Intent("com.example.naarishakti.ACTION_TRIGGER_PHRASE_UPDATED");
        sendBroadcast(intent);
    }

    private void handleRecordButtonClick() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO_PERMISSION_CODE);
        } else {
            if (isRecording) {
                stopRecording();
            } else {
                startRecording();
            }
        }
    }

    private void startRecording() {
        updateUIForRecordingStart();
        backgroundExecutor.execute(() -> {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
                String timestamp = sdf.format(new Date());
                File audioDir = new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "NaariShaktiRecordings");
                if (!audioDir.exists()) {
                    audioDir.mkdirs();
                }
                File audioFile = new File(audioDir, "recording_" + timestamp + ".mp3");
                audioFilePath = audioFile.getAbsolutePath();

                mediaRecorder = new MediaRecorder();
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                mediaRecorder.setOutputFile(audioFilePath);

                mediaRecorder.setOnErrorListener((mr, what, extra) -> {
                    Log.e(TAG, "MediaRecorder Error: what=" + what + ", extra=" + extra);
                    stopRecordingWithError("Error during recording");
                });

                mediaRecorder.prepare();
                try {
                    mediaRecorder.start();
                    runOnUiThread(() -> {
                        recordButton.setText("Stop");
                        recordingStatus.setText("Recording...");
                        recordingAnimation.playAnimation();
                        playbackButton.setEnabled(false);
                        playLastRecordingButton.setEnabled(false);
                        isRecording = true;
                        speechRecognizer.startListening(speechRecognizerIntent);
                    });
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Error starting MediaRecorder", e);
                    stopRecordingWithError("Error starting recording");
                }

            } catch (IOException e) {
                Log.e(TAG, "Error preparing MediaRecorder", e);
                stopRecordingWithError("Error preparing recorder");
            }
        });
    }

    private void updateUIForRecordingStart() {
        runOnUiThread(() -> {
            recordButton.setText("Stop");
            recordingStatus.setText("Preparing...");
            recordingAnimation.playAnimation();
            playbackButton.setEnabled(false);
            playLastRecordingButton.setEnabled(false);
        });
    }

    private void stopRecordingWithError(String message) {
        if (!isFinishing() && !isDestroyed()) {
            runOnUiThread(() -> {
                recordingStatus.setText(message);
                recordButton.setText("Record");
                recordingAnimation.cancelAnimation();
                isRecording = false;
                playbackButton.setEnabled(false);
                playLastRecordingButton.setEnabled(false);
            });
        }
        stopMediaRecorder();
    }

    private void stopRecording() {
        if (isRecording) {
            Log.d(TAG, "Stopping recording");
            runOnUiThread(() -> recordingStatus.setText("Stopping..."));
            isRecording = false;

            if (speechRecognizer != null && speechRecognitionInProgress) {
                speechRecognizer.stopListening();
            }

            stopMediaRecorder();

            if (!isFinishing() && !isDestroyed()) {
                runOnUiThread(() -> {
                    recordButton.setText("Record");
                    recordingStatus.setText("Ready to record");
                    recordingAnimation.cancelAnimation();
                    playbackButton.setEnabled(audioFilePath != null && new File(audioFilePath).exists());
                    playLastRecordingButton.setEnabled(audioFilePath != null && new File(audioFilePath).exists());
                });
            }
            saveLastRecording();
        }
    }

    private void stopMediaRecorder() {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.stop();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error stopping mediaRecorder", e);
            } finally {
                mediaRecorder.reset();
                mediaRecorder.release();
                mediaRecorder = null;
            }
        }
    }

    private void saveLastRecording() {
        SharedPreferences sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("last_trigger_audio_path", audioFilePath);
        editor.putString("last_trigger_text", lastRecordingTextValue);
        editor.apply();

        // Notify VoskService about the new recording
        Intent intent = new Intent("com.example.naarishakti.ACTION_TRIGGER_AUDIO_UPDATED");
        sendBroadcast(intent);
    }

    private void playRecording() {
        if (audioFilePath == null || !new File(audioFilePath).exists()) {
            runOnUiThread(() -> Toast.makeText(this, "No recording available to play", Toast.LENGTH_SHORT).show());
            return;
        }

        runOnUiThread(() -> {
            playbackButton.setEnabled(false);
            playLastRecordingButton.setEnabled(false);
        });

        backgroundExecutor.execute(() -> {
            cleanupMediaPlayer(); // Ensure previous player is cleaned up
            try {
                mediaPlayer = new MediaPlayer();
                Log.d(TAG, "playRecording: Creating MediaPlayer instance: " + mediaPlayer.hashCode());
                mediaPlayer.setDataSource(audioFilePath);
                Log.d(TAG, "playRecording: Setting data source for MediaPlayer: " + mediaPlayer.hashCode());

                mediaPlayer.setOnPreparedListener(mp -> {
                    Log.d(TAG, "playRecording: MediaPlayer prepared: " + mp.hashCode());
                    if (!isFinishing() && !isDestroyed()) {
                        mp.start();
                        runOnUiThread(() -> recordingStatus.setText("Playing recording"));
                    } else {
                        cleanupMediaPlayer();
                    }
                });

                mediaPlayer.setOnCompletionListener(mp -> {
                    Log.d(TAG, "playRecording: MediaPlayer playback completed: " + mp.hashCode());
                    if (!isFinishing() && !isDestroyed()) {
                        runOnUiThread(() -> recordingStatus.setText("Ready to record"));
                    }
                    cleanupMediaPlayer();
                    runOnUiThread(() -> {
                        playbackButton.setEnabled(true);
                        playLastRecordingButton.setEnabled(true);
                    });
                });

                mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                    Log.e(TAG, "Media Player Error: what=" + what + ", extra=" + extra);
                    runOnUiThread(() -> {
                        recordingStatus.setText("Error playing recording");
                        playbackButton.setEnabled(true);
                        playLastRecordingButton.setEnabled(true);
                    });
                    cleanupMediaPlayer();
                    return false;
                });

                mediaPlayer.prepareAsync();
                Log.d(TAG, "playRecording: Preparing MediaPlayer asynchronously: " + mediaPlayer.hashCode());

            } catch (IOException e) {
                Log.e(TAG, "Error playing recording", e);
                runOnUiThread(() -> Toast.makeText(this, "Error playing recording", Toast.LENGTH_SHORT).show());
                runOnUiThread(() -> {
                    playbackButton.setEnabled(true);
                    playLastRecordingButton.setEnabled(true);
                });
                cleanupMediaPlayer();
            }
        });
    }

    private void playLastRecording() {
        if (audioFilePath == null || !new File(audioFilePath).exists()) {
            runOnUiThread(() -> Toast.makeText(this, "No last recording available to play", Toast.LENGTH_SHORT).show());
            return;
        }

        runOnUiThread(() -> {
            playbackButton.setEnabled(false);
            playLastRecordingButton.setEnabled(false);
        });

        backgroundExecutor.execute(() -> {
            cleanupMediaPlayer(); // Ensure previous player is cleaned up
            try {
                mediaPlayer = new MediaPlayer();
                Log.d(TAG, "playLastRecording: Creating MediaPlayer instance: " + mediaPlayer.hashCode());
                mediaPlayer.setDataSource(audioFilePath);
                Log.d(TAG, "playLastRecording: Setting data source for MediaPlayer: " + mediaPlayer.hashCode());

                mediaPlayer.setOnPreparedListener(mp -> {
                    Log.d(TAG, "playLastRecording: MediaPlayer prepared: " + mp.hashCode());
                    if (!isFinishing() && !isDestroyed()) {
                        mp.start();
                        runOnUiThread(() -> recordingStatus.setText("Playing last recording"));
                    } else {
                        cleanupMediaPlayer();
                    }
                });

                mediaPlayer.setOnCompletionListener(mp -> {
                    Log.d(TAG, "playLastRecording: MediaPlayer playback completed: " + mp.hashCode());
                    if (!isFinishing() && !isDestroyed()) {
                        runOnUiThread(() -> recordingStatus.setText("Ready to record"));
                    }
                    cleanupMediaPlayer();
                    runOnUiThread(() -> {
                        playbackButton.setEnabled(true);
                        playLastRecordingButton.setEnabled(true);
                    });
                });

                mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                    Log.e(TAG, "Media Player Error: what=" + what + ", extra=" + extra);
                    runOnUiThread(() -> {
                        recordingStatus.setText("Error playing last recording");
                        playbackButton.setEnabled(true);
                        playLastRecordingButton.setEnabled(true);
                    });
                    cleanupMediaPlayer();
                    return false;
                });

                mediaPlayer.prepareAsync();
                Log.d(TAG, "playLastRecording: Preparing MediaPlayer asynchronously: " + mediaPlayer.hashCode());

            } catch (IOException e) {
                Log.e(TAG, "Error playing last recording", e);
                runOnUiThread(() -> Toast.makeText(this, "Error playing last recording", Toast.LENGTH_SHORT).show());
                runOnUiThread(() -> {
                    playbackButton.setEnabled(true);
                    playLastRecordingButton.setEnabled(true);
                });
                cleanupMediaPlayer();
            }
        });
    }

    private void cleanupMediaPlayer() {
        if (mediaPlayer != null) {
            Log.d(TAG, "cleanupMediaPlayer: MediaPlayer releasing: " + mediaPlayer.hashCode());
            try {
                mediaPlayer.stop();
            } catch (IllegalStateException e) {
                // ignore
            }
            mediaPlayer.release();
            mediaPlayer = null;
            Log.d(TAG, "cleanupMediaPlayer: MediaPlayer released");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
        stopRecording();
        cleanupMediaPlayer();
        backgroundExecutor.shutdownNow();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_AUDIO_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Audio recording permission granted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Audio recording permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopRecording();
        cleanupMediaPlayer();
    }
}