package py.com.nugon;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.util.Log;

public class NugonAccessibilityService extends AccessibilityService {
    private static final String TAG = "NugonA11yService";
    private static final long LONG_PRESS_TIMEOUT = 1500; // 1.5 seconds

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isButtonPressed = false;
    private int pressedKeyCode = -1;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (isButtonPressed) {
                Log.i(TAG, "Emergency long-press detected!");
                triggerEmergency();
                isButtonPressed = false; // Reset to avoid double trigger
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Nugon Accessibility Service CONNECTED and ready.");
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
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            int action = event.getAction();

            if (action == KeyEvent.ACTION_DOWN) {
                if (!isButtonPressed) {
                    isButtonPressed = true;
                    pressedKeyCode = keyCode;
                    handler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT);
                }
            } else if (action == KeyEvent.ACTION_UP) {
                if (isButtonPressed && keyCode == pressedKeyCode) {
                    handler.removeCallbacks(longPressRunnable);
                    isButtonPressed = false;
                }
            }
            // Return false to allow the system to still handle volume changes if desired, 
            // or true to consume it. For an emergency app, consuming might be safer.
            return false; 
        }
        return super.onKeyEvent(event);
    }

    private void triggerEmergency() {
        Intent intent = new Intent(this, EmergencyService.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }
}