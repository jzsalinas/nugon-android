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
import android.database.ContentObserver;
import android.location.Location;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
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
import androidx.media.VolumeProviderCompat;

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
    private MediaSessionCompat mediaSession;
    private AudioTrack silentAudioTrack;
    private AudioFocusRequest focusRequest;
    private SettingsContentObserver volumeObserver;
    
    private long firstPressTime = 0;
    private int lastDirection = 0;
    private long lastAdjustmentTime = 0;
    private boolean isSelfAdjusting = false;
    private final Handler detectionHandler = new Handler(Looper.getMainLooper());
    private boolean isTriggered = false;

    private final AudioManager.OnAudioFocusChangeListener focusChangeListener = focusChange -> {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || 
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            disableScreenOffBypass();
        }
    };

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
            if (intent.getAction() == null) return;
            if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                enableScreenOffBypass();
            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                disableScreenOffBypass();
            }
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
        
        if (pm != null && !pm.isInteractive()) {
            enableScreenOffBypass();
        }
    }

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
        if (isSelfAdjusting || isTriggered) return;

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean isScreenOn = pm != null && pm.isInteractive();

        if (isScreenOn) {
            checkLongPress(direction);
            return;
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastAdjustmentTime < 100) return;

        isSelfAdjusting = true;
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
            }
            lastAdjustmentTime = System.currentTimeMillis();
        } finally {
            isSelfAdjusting = false;
        }

        checkLongPress(direction);
    }

    private void checkLongPress(int direction) {
        long currentTime = System.currentTimeMillis();
        if (firstPressTime == 0 || direction != lastDirection) {
            if (mediaSession != null && mediaSession.isActive()) {
                vibrate(50); 
            }
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

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, getNotification());

        String action = intent != null ? intent.getAction() : null;
        if (ACTION_TRIGGER_EMERGENCY.equals(action)) {
            triggerEmergencyInternal();
        } else {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                enableScreenOffBypass();
            }
        }
        return START_STICKY;
    }

    private void triggerEmergencyInternal() {
        if (isTriggered) return;
        isTriggered = true;
        
        // Use a delay for vibration to settle volume events
        new Handler(Looper.getMainLooper()).postDelayed(this::vibrateEmergency, 500);
        
        triggerEmergency();
    }

    private void startSilentAudio() {
        if (silentAudioTrack != null && silentAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) return;
        try {
            int sampleRate = 44100;
            int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            silentAudioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(minBufferSize)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            silentAudioTrack.write(new byte[minBufferSize], 0, minBufferSize);
            silentAudioTrack.setLoopPoints(0, minBufferSize / 2, -1);
            
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                            .setOnAudioFocusChangeListener(focusChangeListener).build();
                    am.requestAudioFocus(focusRequest);
                } else {
                    am.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
                }
            }
            silentAudioTrack.play();
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(smsSentReceiver);
            unregisterReceiver(screenStateReceiver);
            getContentResolver().unregisterContentObserver(volumeObserver);
        } catch (Exception ignored) {}
        disableScreenOffBypass();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    private void triggerEmergency() {
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(15000); 

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

    private void vibrate(long duration) {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
            } else { v.vibrate(duration); }
        }
    }

    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(1, getNotification());
    }

    private Notification getNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.emergency_notification_title))
                .setContentText(getString(R.string.emergency_notification_content))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true);

        if (mediaSession != null && mediaSession.isActive()) {
            builder.setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(0));
        }
        return builder.build();
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
