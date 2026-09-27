package py.com.nugon;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes one emergency alert from the system-managed AccessibilityService.
 * This is deliberately a plain helper, not an Android started or foreground service.
 */
public final class EmergencyDispatcher {
    private static final String TAG = "EmergencyDispatcher";
    private static final String PREFS_NAME = "nugon_prefs";
    private static final String ACTION_SMS_SENT = "py.com.nugon.SMS_SENT";
    private static final long LOCATION_TIMEOUT_MS = 5_000;
    private static final long LOCATION_MAX_AGE_MS = 5 * 60_000;
    private static final long WAKE_LOCK_TIMEOUT_MS = 15_000;
    private static final long TRIGGER_COOLDOWN_MS = 10_000;
    private static final long SMS_RESULT_RECEIVER_TIMEOUT_MS = 60_000;

    private static final AtomicInteger nextSmsRequestCode = new AtomicInteger(1);

    private final Context context;
    private final FusedLocationProviderClient locationClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean dispatchInProgress = new AtomicBoolean(false);
    private final AtomicBoolean locationResolved = new AtomicBoolean(false);
    private final PowerManager.WakeLock wakeLock;
    private CancellationTokenSource locationCancellation;
    private boolean receiverRegistered;

    private final BroadcastReceiver smsSentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context receiverContext, Intent intent) {
            int part = intent.getIntExtra("part", -1);
            int total = intent.getIntExtra("total", -1);
            if (getResultCode() == Activity.RESULT_OK) {
                Log.i(TAG, "SMS accepted by telephony: part=" + (part + 1) + "/" + total);
            } else {
                Log.e(TAG, "SMS rejected by telephony: part=" + (part + 1) + "/" + total
                        + ", result=" + getResultCode());
            }
        }
    };

    public EmergencyDispatcher(@NonNull Context sourceContext) {
        context = sourceContext.getApplicationContext();
        locationClient = LocationServices.getFusedLocationProviderClient(context);

        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager == null
                ? null
                : powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nugon:EmergencyDispatch");

    }

    public void dispatch() {
        dispatch(false);
    }

    public void dispatchFromForeground() {
        dispatch(true);
    }

    private void dispatch(boolean foregroundCaller) {
        if (!dispatchInProgress.compareAndSet(false, true)) {
            Log.w(TAG, "Ignoring duplicate emergency trigger while an alert is in progress");
            return;
        }

        locationResolved.set(false);
        vibrateActivation();
        acquireWakeLock();

        if (!canAccessLocation(foregroundCaller)) {
            Log.w(TAG, "Location permission unavailable; sending alert without coordinates");
            finishLocation(null);
            return;
        }

        mainHandler.postDelayed(locationTimeout, LOCATION_TIMEOUT_MS);
        try {
            locationClient.getLastLocation()
                    .addOnSuccessListener(location -> {
                        if (isRecent(location)) {
                            finishLocation(location);
                        } else {
                            requestCurrentLocation();
                        }
                    })
                    .addOnFailureListener(error -> {
                        Log.w(TAG, "Unable to read last location", error);
                        requestCurrentLocation();
                    });
        } catch (SecurityException error) {
            Log.e(TAG, "Location permission was revoked before dispatch", error);
            finishLocation(null);
        }
    }

    public void shutdown() {
        mainHandler.removeCallbacks(locationTimeout);
        mainHandler.removeCallbacks(clearCooldown);
        mainHandler.removeCallbacks(unregisterSmsReceiver);
        if (locationCancellation != null) {
            locationCancellation.cancel();
            locationCancellation = null;
        }
        releaseWakeLock();
        unregisterSmsResultReceiver();
    }

    private final Runnable locationTimeout = () -> {
        Log.w(TAG, "Location attempt timed out; sending alert without coordinates");
        if (locationCancellation != null) {
            locationCancellation.cancel();
        }
        finishLocation(null);
    };

    private final Runnable clearCooldown = () -> dispatchInProgress.set(false);
    private final Runnable unregisterSmsReceiver = this::unregisterSmsResultReceiver;

    private void requestCurrentLocation() {
        if (locationResolved.get()) {
            return;
        }
        try {
            locationCancellation = new CancellationTokenSource();
            locationClient.getCurrentLocation(
                            Priority.PRIORITY_HIGH_ACCURACY,
                            locationCancellation.getToken())
                    .addOnSuccessListener(this::finishLocation)
                    .addOnFailureListener(error -> {
                        Log.w(TAG, "Unable to obtain current location", error);
                        finishLocation(null);
                    });
        } catch (SecurityException error) {
            Log.e(TAG, "Location permission was revoked during dispatch", error);
            finishLocation(null);
        }
    }

    private boolean isRecent(Location location) {
        if (location == null) {
            return false;
        }
        long age = SystemClock.elapsedRealtime() - location.getElapsedRealtimeNanos() / 1_000_000;
        return age >= 0 && age <= LOCATION_MAX_AGE_MS;
    }

    private void finishLocation(Location location) {
        if (!locationResolved.compareAndSet(false, true)) {
            return;
        }
        mainHandler.removeCallbacks(locationTimeout);
        if (locationCancellation != null) {
            locationCancellation.cancel();
            locationCancellation = null;
        }

        sendAlerts(location);
        releaseWakeLock();
        mainHandler.removeCallbacks(clearCooldown);
        mainHandler.postDelayed(clearCooldown, TRIGGER_COOLDOWN_MS);
    }

    private boolean canAccessLocation(boolean foregroundCaller) {
        boolean foregroundLocation = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!foregroundLocation) {
            return false;
        }
        if (foregroundCaller) {
            return true;
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void sendAlerts(Location location) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String contacts = prefs.getString("contacts", "");
        String backendUrl = prefs.getString("backend_url", "");
        String customMessage = prefs.getString(
                "emergency_message", context.getString(R.string.message_default));
        String senderId = prefs.getString("sender_id", "Anónimo");

        double latitude = location == null ? 0 : location.getLatitude();
        double longitude = location == null ? 0 : location.getLongitude();
        String message = location == null
                ? customMessage
                : customMessage + " https://maps.google.com/?q=" + latitude + "," + longitude;

        sendSms(contacts, message);
        NetworkClient.sendAlert(backendUrl, senderId, message, latitude, longitude);
    }

    private void sendSms(String contacts, String message) {
        if (contacts == null || contacts.trim().isEmpty()) {
            Log.w(TAG, "No emergency contacts configured");
            return;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "SEND_SMS permission unavailable; SMS channel skipped");
            return;
        }

        SmsManager smsManager = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? context.getSystemService(SmsManager.class)
                : SmsManager.getDefault();
        if (smsManager == null) {
            Log.e(TAG, "SmsManager unavailable");
            return;
        }

        ArrayList<String> parts = smsManager.divideMessage(message);
        if (parts.size() != 1) {
            // Phase 3A intentionally does not change message UX. This warning prepares
            // enforcement after the product decision about GSM-7/UCS-2 input is reviewed.
            Log.w(TAG, "Final emergency message requires " + parts.size() + " SMS segments");
        }

        registerSmsResultReceiver();

        for (String rawContact : contacts.split(",")) {
            String destination = rawContact.trim();
            if (destination.isEmpty()) {
                continue;
            }
            try {
                ArrayList<PendingIntent> sentIntents = createSentIntents(parts.size());
                if (parts.size() == 1) {
                    smsManager.sendTextMessage(
                            destination, null, parts.get(0), sentIntents.get(0), null);
                } else {
                    smsManager.sendMultipartTextMessage(
                            destination, null, parts, sentIntents, null);
                }
            } catch (RuntimeException error) {
                Log.e(TAG, "Unable to submit SMS: " + error.getClass().getSimpleName());
            }
        }
    }

    private void registerSmsResultReceiver() {
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                    context,
                    smsSentReceiver,
                    new IntentFilter(ACTION_SMS_SENT),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
        mainHandler.removeCallbacks(unregisterSmsReceiver);
        mainHandler.postDelayed(unregisterSmsReceiver, SMS_RESULT_RECEIVER_TIMEOUT_MS);
    }

    private void unregisterSmsResultReceiver() {
        if (!receiverRegistered) {
            return;
        }
        try {
            context.unregisterReceiver(smsSentReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver was already removed by the framework.
        }
        receiverRegistered = false;
    }

    private ArrayList<PendingIntent> createSentIntents(int totalParts) {
        ArrayList<PendingIntent> intents = new ArrayList<>(totalParts);
        for (int part = 0; part < totalParts; part++) {
            Intent resultIntent = new Intent(ACTION_SMS_SENT)
                    .setPackage(context.getPackageName())
                    .putExtra("part", part)
                    .putExtra("total", totalParts);
            intents.add(PendingIntent.getBroadcast(
                    context,
                    nextSmsRequestCode.getAndIncrement(),
                    resultIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        return intents;
    }

    private void vibrateActivation() {
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(
                    1_000, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(1_000);
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }
}
