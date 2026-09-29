package com.example.naarishakti.evidence;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.display.DisplayManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.Collections;

/**
 * Records the back camera (with microphone audio) in 15-second H.264/AAC MP4 segments
 * ({@code video_<n>.mp4}) using Camera2 and a MediaRecorder SURFACE source. The camera stays open
 * for the whole recording; each segment gets a fresh MediaRecorder and capture session.
 *
 * Runs entirely on a private HandlerThread and never throws. When the camera can't be used at all
 * the listener's {@link Listener#onFailed()} is called once so the caller can fall back to audio.
 */
final class VideoChunkRecorder {

    interface Listener {
        /** A finished, non-empty segment on disk. Called on the recorder thread. */
        void onSegment(File file, long capturedAt);

        /** Video could not be recorded (no camera, camera busy/disabled). Called at most once. */
        void onFailed();
    }

    private static final String TAG = "VideoChunkRecorder";
    static final long SEGMENT_MS = 15_000L;
    private static final long START_RETRY_WINDOW_MS = 5_000L;
    private static final long RETRY_DELAY_MS = 500L;
    private static final int MAX_EXTRA_FAILURES = 3;
    private static final long MIN_FREE_BYTES = 20L * 1024 * 1024;
    private static final int TARGET_W = 640;
    private static final int TARGET_H = 480;
    private static final int VIDEO_BITRATE = 1_200_000;
    private static final int FRAME_RATE = 30;

    private final Context ctx;
    private final File dir;
    private final Listener listener;
    private final HandlerThread thread;
    private final Handler handler;

    // Recorder-thread state
    @Nullable private String cameraId;
    @Nullable private Size videoSize;
    private int sensorOrientation = 90;
    @Nullable private CameraDevice camera;
    @Nullable private CameraCaptureSession session;
    @Nullable private MediaRecorder recorder;
    @Nullable private File currentFile;
    private boolean recording;
    private long currentStartedAt;
    private long currentStartedElapsed;
    private int nextIndex = 1;
    private long startDeadline;
    private int extraFailures;
    private boolean stopped;
    private boolean failedReported;

    VideoChunkRecorder(Context ctx, File dir, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.dir = dir;
        this.listener = listener;
        this.thread = new HandlerThread("ev-video");
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
    }

    void start() {
        handler.post(() -> {
            startDeadline = SystemClock.elapsedRealtime() + START_RETRY_WINDOW_MS;
            openCamera();
        });
    }

    /** Stops recording, flushes the current segment, releases the camera and ends the thread. */
    void stop() {
        handler.post(() -> {
            stopped = true;
            handler.removeCallbacksAndMessages(null);
            finishSegment();
            closeCamera();
            thread.quitSafely();
        });
    }

    // ---- recorder thread ----

    private final Runnable rotate = this::rotate;
    private final Runnable retryOpen = this::openCamera;
    private final Runnable retrySegment = this::beginSegment;

    @SuppressLint("MissingPermission") // Checked by EvidenceModule before starting.
    private void openCamera() {
        if (stopped) return;
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) {
            fail("No camera service");
            return;
        }
        try {
            if (cameraId == null && !selectCamera(cm)) {
                fail("No usable back camera");
                return;
            }
            cm.openCamera(cameraId, cameraCallback, handler);
        } catch (SecurityException e) {
            fail("Camera permission missing");
        } catch (Exception e) {
            // CameraAccessException (in use / disabled) or IllegalArgumentException.
            Log.w(TAG, "openCamera failed: " + e.getMessage());
            retryOrFail(retryOpen);
        }
    }

    private boolean selectCamera(CameraManager cm) throws CameraAccessException {
        String fallback = null;
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics cc = cm.getCameraCharacteristics(id);
            Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) continue;
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return useCamera(id, cc, map);
            }
            if (fallback == null) fallback = id;
        }
        if (fallback == null) return false;
        CameraCharacteristics cc = cm.getCameraCharacteristics(fallback);
        StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        return map != null && useCamera(fallback, cc, map);
    }

    private boolean useCamera(String id, CameraCharacteristics cc, StreamConfigurationMap map) {
        Size[] sizes = map.getOutputSizes(MediaRecorder.class);
        if (sizes == null || sizes.length == 0) return false;
        Size best = null;
        long bestScore = Long.MAX_VALUE;
        for (Size s : sizes) {
            if (s.getWidth() > 1920) continue;
            long score = Math.abs(s.getWidth() - TARGET_W) + Math.abs(s.getHeight() - TARGET_H);
            // Prefer 4:3 on ties.
            if (s.getWidth() * 3 != s.getHeight() * 4) score += 1;
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        if (best == null) best = sizes[sizes.length - 1];
        Integer so = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
        Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
        sensorOrientation = so == null ? 90 : so;
        frontFacing = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
        cameraId = id;
        videoSize = best;
        return true;
    }

    private boolean frontFacing;

    private final CameraDevice.StateCallback cameraCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cam) {
            if (stopped) {
                cam.close();
                return;
            }
            camera = cam;
            beginSegment();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cam) {
            Log.w(TAG, "Camera disconnected");
            onCameraLost(cam);
        }

        @Override
        public void onError(@NonNull CameraDevice cam, int error) {
            Log.w(TAG, "Camera error " + error);
            onCameraLost(cam);
        }
    };

    private void onCameraLost(CameraDevice cam) {
        boolean wasOurs = camera == cam;
        finishSegment();
        try { cam.close(); } catch (Throwable ignored) { }
        if (wasOurs) camera = null;
        if (stopped) return;
        // Another app took the camera, or it failed: try to get it back for a while.
        startDeadline = SystemClock.elapsedRealtime() + START_RETRY_WINDOW_MS;
        retryOrFail(retryOpen);
    }

    @SuppressWarnings("deprecation")
    private void beginSegment() {
        if (stopped || camera == null || videoSize == null) return;
        if (!AudioChunkRecorder.hasFreeSpace(dir, MIN_FREE_BYTES)) {
            Log.w(TAG, "Storage almost full, video evidence stopped");
            stopped = true;
            closeCamera();
            thread.quitSafely();
            return;
        }
        final File file = nextFile();
        final MediaRecorder mr;
        try {
            mr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? new MediaRecorder(ctx) : new MediaRecorder();
        } catch (Throwable t) {
            retryOrFail(retrySegment);
            return;
        }
        final Surface surface;
        try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC);
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mr.setOutputFile(file.getAbsolutePath());
            mr.setVideoEncodingBitRate(VIDEO_BITRATE);
            mr.setVideoFrameRate(FRAME_RATE);
            mr.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
            mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mr.setAudioEncodingBitRate(64_000);
            mr.setAudioChannels(1);
            mr.setAudioSamplingRate(44_100);
            mr.setOrientationHint(orientationHint());
            mr.setMaxDuration((int) (SEGMENT_MS + 2_000L));
            mr.setOnInfoListener((r, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED && recorder == mr) {
                    handler.removeCallbacks(rotate);
                    handler.post(rotate);
                }
            });
            mr.setOnErrorListener((r, what, extra) -> {
                Log.w(TAG, "MediaRecorder error " + what + "/" + extra);
                if (recorder == mr) {
                    handler.removeCallbacks(rotate);
                    handler.post(rotate);
                }
            });
            mr.prepare();
            surface = mr.getSurface();
        } catch (Throwable t) {
            Log.w(TAG, "Video recorder setup failed: " + t.getMessage());
            release(mr);
            deleteQuietly(file);
            retryOrFail(retrySegment);
            return;
        }
        recorder = mr;
        currentFile = file;
        recording = false;
        try {
            camera.createCaptureSession(Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession s) {
                            onSessionReady(s, mr, surface);
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession s) {
                            Log.w(TAG, "Capture session configuration failed");
                            try { s.close(); } catch (Throwable ignored) { }
                            if (recorder == mr) {
                                finishSegment();
                                retryOrFail(retrySegment);
                            }
                        }
                    }, handler);
        } catch (Throwable t) {
            Log.w(TAG, "createCaptureSession failed: " + t.getMessage());
            finishSegment();
            retryOrFail(retrySegment);
        }
    }

    private void onSessionReady(CameraCaptureSession s, MediaRecorder mr, Surface surface) {
        if (stopped || recorder != mr || camera == null) {
            try { s.close(); } catch (Throwable ignored) { }
            return;
        }
        session = s;
        try {
            CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(surface);
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            s.setRepeatingRequest(b.build(), null, handler);
            mr.start();
        } catch (Throwable t) {
            Log.w(TAG, "Video start failed: " + t.getMessage());
            finishSegment();
            retryOrFail(retrySegment);
            return;
        }
        recording = true;
        currentStartedAt = System.currentTimeMillis();
        currentStartedElapsed = SystemClock.elapsedRealtime();
        nextIndex++;
        extraFailures = 0;
        startDeadline = 0;
        handler.postDelayed(rotate, SEGMENT_MS);
    }

    private void rotate() {
        if (stopped) return;
        finishSegment();
        startDeadline = SystemClock.elapsedRealtime() + START_RETRY_WINDOW_MS;
        beginSegment();
    }

    /** Stops the capture session and recorder; delivers the file when it is a valid segment. */
    private void finishSegment() {
        handler.removeCallbacks(rotate);
        CameraCaptureSession s = session;
        MediaRecorder mr = recorder;
        File file = currentFile;
        boolean wasRecording = recording;
        session = null;
        recorder = null;
        currentFile = null;
        recording = false;
        if (s != null) {
            try { s.stopRepeating(); } catch (Throwable ignored) { }
            try { s.abortCaptures(); } catch (Throwable ignored) { }
        }
        boolean ok = false;
        if (mr != null && wasRecording) {
            try {
                mr.stop();
                ok = true;
            } catch (Throwable t) {
                ok = SystemClock.elapsedRealtime() - currentStartedElapsed > SEGMENT_MS
                        && file != null && file.length() > 0;
                if (!ok) Log.w(TAG, "Video segment discarded: " + t.getMessage());
            }
        }
        if (s != null) {
            try { s.close(); } catch (Throwable ignored) { }
        }
        if (mr != null) release(mr);
        if (file == null) return;
        if (ok && file.length() > 0) {
            try {
                listener.onSegment(file, currentStartedAt);
            } catch (Throwable t) {
                Log.e(TAG, "Segment listener failed", t);
            }
        } else {
            deleteQuietly(file);
        }
    }

    private void retryOrFail(Runnable what) {
        if (stopped) return;
        long now = SystemClock.elapsedRealtime();
        if (startDeadline == 0) startDeadline = now + START_RETRY_WINDOW_MS;
        if (now < startDeadline) {
            handler.postDelayed(what, RETRY_DELAY_MS);
        } else if (++extraFailures <= MAX_EXTRA_FAILURES) {
            handler.postDelayed(what, 2_000L);
        } else {
            fail("Camera kept failing");
        }
    }

    private void fail(String why) {
        Log.w(TAG, "Video evidence unavailable: " + why);
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        finishSegment();
        closeCamera();
        thread.quitSafely();
        if (!failedReported) {
            failedReported = true;
            try {
                listener.onFailed();
            } catch (Throwable t) {
                Log.e(TAG, "onFailed listener failed", t);
            }
        }
    }

    private void closeCamera() {
        CameraDevice cam = camera;
        camera = null;
        if (cam != null) {
            try { cam.close(); } catch (Throwable ignored) { }
        }
    }

    /** Rotation to store in the MP4 so players show the video upright for the current display rotation. */
    private int orientationHint() {
        int degrees = 0;
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
            int rotation = d == null ? Surface.ROTATION_0 : d.getRotation();
            if (rotation == Surface.ROTATION_90) degrees = 90;
            else if (rotation == Surface.ROTATION_180) degrees = 180;
            else if (rotation == Surface.ROTATION_270) degrees = 270;
        } catch (Throwable ignored) {
            // Assume portrait.
        }
        return frontFacing
                ? (sensorOrientation + degrees) % 360
                : (sensorOrientation - degrees + 360) % 360;
    }

    private File nextFile() {
        if (!dir.isDirectory() && !dir.mkdirs()) Log.w(TAG, "Can't create " + dir);
        File f = new File(dir, "video_" + nextIndex + ".mp4");
        while (f.exists()) {
            nextIndex++;
            f = new File(dir, "video_" + nextIndex + ".mp4");
        }
        return f;
    }

    private static void release(MediaRecorder mr) {
        try { mr.reset(); } catch (Throwable ignored) { }
        try { mr.release(); } catch (Throwable ignored) { }
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists() && !f.delete()) Log.w(TAG, "Could not delete " + f);
    }
}
