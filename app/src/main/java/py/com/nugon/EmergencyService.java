package py.com.nugon;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.location.Location;
import android.media.AudioManager;
/* [WORKAROUND] Uncomment imports for manual builds to enable screen-off bypass
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
*/
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
/* [WORKAROUND] Uncomment for manual builds
import androidx.media.VolumeProviderCompat;
*/

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.OnSuccessListener;

public class EmergencyService extends Service {
    private static final String TAG = "EmergencyService";
    private static final String CHANNEL_ID = "emergency_channel";
    
    public static final String ACTION_START_MONITOR = "py.com.nugon.START_MONITOR";
    public static final String ACTION_TRIGGER_EMERGENCY = "py.com.nugon.TRIGGER_EMERGENCY";
    private static final String ACTION_SMS_SENT = "py.com.nugon.SMS_SENT";
    
    private static final long LONG_PRESS_THRESHOLD = 1500; // 1.5 seconds
    
    private FusedLocationProviderClient fusedLocationClient;
    private PowerManager.WakeLock wakeLock;
    private SettingsContentObserver volumeObserver;
    private boolean isTriggered = false;

    /* [WORKAROUND] Uncomment variables for manual builds
    private static final long LONG_PRESS_THRESHOLD = 1500; // 1.5 seconds
    private long firstPressTime = 0;
    private int lastDirection = 0;
    private final Handler detectionHandler = new Handler(Looper.getMainLooper());
    private MediaSessionCompat mediaSession;
    private AudioTrack silentAudioTrack;
    private AudioFocusRequest focusRequest;
    
    private final AudioManager.OnAudioFocusChangeListener focusChangeListener = focusChange -> {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || 
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            disableScreenOffBypass();
        }
    };
    */

    private final BroadcastReceiver smsSentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (getResultCode() == android.app.Activity.RESULT_OK) {
                Log.i(TAG, "SMS SENT Success ✅");
            } else {
                Log.e(TAG, "SMS SENT Fail: " + getResultCode());
            }
        }
    };

    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            /* [WORKAROUND] Uncomment for manual builds to enable dynamic bypass
            if (intent.getAction() == null) return;
            if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                enableScreenOffBypass();
            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                disableScreenOffBypass();
            }
            */
            Log.d(TAG, "Screen state: " + (intent != null ? intent.getAction() : "null"));
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        createNotificationChannel();
        
        IntentFilter smsFilter = new IntentFilter(ACTION_SMS_SENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsSentReceiver, smsFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(smsSentReceiver, smsFilter);
        }

        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenStateReceiver, screenFilter);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nugon:EmergencyWakeLock");
        }

        volumeObserver = new SettingsContentObserver(this, new Handler(Looper.getMainLooper()));
        getContentResolver().registerContentObserver(android.provider.Settings.System.CONTENT_URI, true, volumeObserver);
        
        /* [WORKAROUND] Uncomment for manual builds
        if (pm != null && !pm.isInteractive()) {
            enableScreenOffBypass();
        }
        */
    }

    /* [WORKAROUND] Uncomment methods for manual builds
    private void enableScreenOffBypass() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null && am.isMusicActive()) return;

        setupMediaSession();
        startSilentAudio();
    }

    private void disableScreenOffBypass() {
        if (silentAudioTrack != null) {
            try { silentAudioTrack.stop(); silentAudioTrack.release(); } catch (Exception ignored) {}
            silentAudioTrack = null;
        }
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                am.abandonAudioFocusRequest(focusRequest);
            } else {
                am.abandonAudioFocus(focusChangeListener);
            }
        }
        updateNotification();
    }

    private void setupMediaSession() {
        if (mediaSession != null) return;
        mediaSession = new MediaSessionCompat(this, "NugonEmergencySession");
        
        MediaMetadataCompat metadata = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Protección Nugon SOS")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Activo")
                .build();
        mediaSession.setMetadata(metadata);

        VolumeProviderCompat volumeProvider = new VolumeProviderCompat(VolumeProviderCompat.VOLUME_CONTROL_RELATIVE, 100, 50) {
            @Override
            public void onAdjustVolume(int direction) {
                handleVolumeKey(direction);
            }
        };

        mediaSession.setPlaybackToRemote(volumeProvider);
        mediaSession.setActive(true);
        
        PlaybackStateCompat state = new PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1.0f)
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE)
                .build();
        mediaSession.setPlaybackState(state);
    }

    private synchronized void handleVolumeKey(int direction) {
        // Logic for manual volume adjustment when bypass is active...
        // Refer to Version 1.5 git history for details.
        checkLongPress(direction);
    }
    */

    /* [WORKAROUND] This logic is part of the manual build bypass
    private void checkLongPress(int direction) {
        long currentTime = System.currentTimeMillis();
        if (firstPressTime == 0 || direction != lastDirection) {
            firstPressTime = currentTime;
            lastDirection = direction;
        } else {
            if (currentTime - firstPressTime >= LONG_PRESS_THRESHOLD) {
                triggerEmergencyInternal();
                firstPressTime = 0;
            }
        }

        detectionHandler.removeCallbacksAndMessages(null);
        detectionHandler.postDelayed(() -> {
            if (!isTriggered) firstPressTime = 0;
        }, 500);
    }
    */

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundWithTypes();

        String action = intent != null ? intent.getAction() : null;
        if (ACTION_TRIGGER_EMERGENCY.equals(action)) {
            triggerEmergencyInternal();
        } else {
            /* [WORKAROUND] Uncomment for manual builds
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                enableScreenOffBypass();
            }
            */
        }
        
        return START_STICKY;
    }

    private void startForegroundWithTypes() {
        Notification notification = getNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                /* [WORKAROUND] Uncomment for manual builds
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                */
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(1, notification);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            /* [WORKAROUND] Uncomment for manual builds
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            */
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(1, notification);
        }
    }

    private void triggerEmergencyInternal() {
        if (isTriggered) return;
        isTriggered = true;
        Log.i(TAG, "Emergency Triggered!");
        
        new Handler(Looper.getMainLooper()).postDelayed(this::vibrateEmergency, 500);
        triggerEmergency();
    }

    /* [WORKAROUND] Uncomment for manual builds
    private void startSilentAudio() {
        // ... (AudioTrack logic)
    }
    */

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(smsSentReceiver);
            unregisterReceiver(screenStateReceiver);
            getContentResolver().unregisterContentObserver(volumeObserver);
        } catch (Exception ignored) {}
        
        /* [WORKAROUND] Uncomment for manual builds
        disableScreenOffBypass();
        */

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    private void triggerEmergency() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(15000); 
        }

        try {
            fusedLocationClient.getLastLocation().addOnSuccessListener(new OnSuccessListener<Location>() {
                @Override
                public void onSuccess(Location location) {
                    if (location != null) sendAlerts(location.getLatitude(), location.getLongitude());
                    else requestFreshLocation();
                }
            });
        } catch (SecurityException e) { sendAlerts(0, 0); }
    }

    private void vibrateEmergency() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null) return;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            v.vibrate(1000);
        }
    }

    private void requestFreshLocation() {
        try {
            LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).setMaxUpdates(1).build();
            fusedLocationClient.requestLocationUpdates(request, new com.google.android.gms.location.LocationCallback() {
                @Override
                public void onLocationResult(@NonNull com.google.android.gms.location.LocationResult res) {
                    Location loc = res.getLastLocation();
                    sendAlerts(loc != null ? loc.getLatitude() : 0, loc != null ? loc.getLongitude() : 0);
                }
            }, Looper.getMainLooper());
        } catch (SecurityException e) { sendAlerts(0, 0); }
    }

    private void sendAlerts(double lat, double lon) {
        SharedPreferences prefs = getSharedPreferences("nugon_prefs", MODE_PRIVATE);
        String contacts = prefs.getString("contacts", "");
        String backendUrl = prefs.getString("backend_url", "");
        String customMessage = prefs.getString("emergency_message", getString(R.string.message_default));
        String senderId = prefs.getString("sender_id", "Anónimo");
        
        String message = customMessage + " https://maps.google.com/?q=" + lat + "," + lon;

        if (!contacts.isEmpty()) {
            SmsManager sms = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ? getSystemService(SmsManager.class) : SmsManager.getDefault();
            if (sms != null) {
                for (String contact : contacts.split(",")) {
                    try {
                        java.util.ArrayList<String> parts = sms.divideMessage(message);
                        java.util.ArrayList<PendingIntent> intents = new java.util.ArrayList<>();
                        PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_SMS_SENT), PendingIntent.FLAG_IMMUTABLE);
                        for (int i=0; i<parts.size(); i++) intents.add(pi);
                        sms.sendMultipartTextMessage(contact.trim(), null, parts, intents, null);
                    } catch (Exception e) { Log.e(TAG, "SMS fail", e); }
                }
            }
        }
        NetworkClient.sendAlert(backendUrl, senderId, message, lat, lon);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            isTriggered = false;
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        }, 10000);
    }

    /* [WORKAROUND] Uncomment for manual builds if needed
    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(1, getNotification());
    }
    */

    private Notification getNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.emergency_notification_title))
                .setContentText(getString(R.string.emergency_notification_content))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Emergency Service Channel", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    private class SettingsContentObserver extends ContentObserver {
        private final AudioManager am;
        private int lastVol;
        private long lastTime = 0;
        private int cumulativeDelta = 0;
        private int lastDir = 0;

        public SettingsContentObserver(Context context, Handler handler) {
            super(handler);
            am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            lastVol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        }

        @Override
        public void onChange(boolean selfChange) {
            int currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            long now = System.currentTimeMillis();

            if (currentVol != lastVol) {
                int delta = Math.abs(currentVol - lastVol);
                int dir = (currentVol > lastVol) ? 1 : -1;
                
                if (dir != lastDir || (now - lastTime > 400)) cumulativeDelta = delta;
                else cumulativeDelta += delta;

                if (cumulativeDelta >= 5 || ((currentVol == 0 || currentVol == maxVol) && delta >= 2)) {
                    triggerEmergencyInternal();
                    cumulativeDelta = 0;
                }
                lastVol = currentVol;
                lastTime = now;
                lastDir = dir;
            }
        }
    }
}
