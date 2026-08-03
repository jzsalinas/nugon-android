package py.com.nugon;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.util.Log;

import androidx.core.content.ContextCompat;

public class NugonAccessibilityService extends AccessibilityService {
    private static final String TAG = "NugonA11yService";
    private static final long LONG_PRESS_TIMEOUT = 1500; // 1.5 seconds
    
    public static boolean isRunning = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isButtonPressed = false;
    private int pressedKeyCode = -1;
    private PowerManager.WakeLock wakeLock;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (isButtonPressed) {
                Log.i(TAG, "Emergency long-press detected!");
                triggerEmergency();
                releaseWakeLock(); // Release after trigger
                isButtonPressed = false; // Reset to avoid double trigger
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        isRunning = true;
        Log.i(TAG, "Nugon Accessibility Service CONNECTED and ready.");
        
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nugon:A11yWakeLock");
        }

        // Android 14+ Guard: ensure permissions are present before redundant trigger
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Intent intent = new Intent(this, EmergencyService.class);
            intent.setAction(EmergencyService.ACTION_START_MONITOR);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No implementation needed for this use case
    }

    @Override
    public void onInterrupt() {
        // No implementation needed
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // We still keep A11y interception for when the screen is ON/LOCKED-AWAKE.
        // It provides better precision for KEYCODE_VOLUME_UP/DOWN.
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            int action = event.getAction();

            if (action == KeyEvent.ACTION_DOWN) {
                if (!isButtonPressed) {
                    acquireWakeLock();
                    isButtonPressed = true;
                    pressedKeyCode = keyCode;
                    handler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT);
                }
            } else if (action == KeyEvent.ACTION_UP) {
                if (isButtonPressed && keyCode == pressedKeyCode) {
                    handler.removeCallbacks(longPressRunnable);
                    releaseWakeLock();
                    isButtonPressed = false;
                }
            }
            // Return false to allow the system to still handle volume changes if desired.
            return false; 
        }
        return super.onKeyEvent(event);
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        super.onDestroy();
    }

    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            // Acquire with timeout as a safety measure (3 seconds is enough for 1.5s detection)
            wakeLock.acquire(3000);
            Log.d(TAG, "WakeLock acquired for button detection");
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "WakeLock released");
        }
    }

    private void triggerEmergency() {
        Intent intent = new Intent(this, EmergencyService.class);
        intent.setAction(EmergencyService.ACTION_TRIGGER_EMERGENCY);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }
}