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
import android.location.Location;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.VolumeProvider;
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
    
    private long firstPressTime = 0;
    private int lastDirection = 0;
    private final Handler detectionHandler = new Handler(Looper.getMainLooper());
    private boolean isTriggered = false;

    private final BroadcastReceiver smsSentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (getResultCode()) {
                case android.app.Activity.RESULT_OK:
                    Log.i(TAG, "SMS SENT STATUS: Success ✅");
                    break;
                case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
                    Log.e(TAG, "SMS SENT STATUS: Generic Failure (Check balance/format) ❌");
                    break;
                case SmsManager.RESULT_ERROR_NO_SERVICE:
                    Log.e(TAG, "SMS SENT STATUS: No Service ❌");
                    break;
                case SmsManager.RESULT_ERROR_RADIO_OFF:
                    Log.e(TAG, "SMS SENT STATUS: Radio Off (Airplane mode?) ❌");
                    break;
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        createNotificationChannel();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsSentReceiver, new IntentFilter(ACTION_SMS_SENT), Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(smsSentReceiver, new IntentFilter(ACTION_SMS_SENT));
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nugon:EmergencyWakeLock");
        }
        
        setupMediaSession();
        startSilentAudio();
    }

    private void startSilentAudio() {
        try {
            int sampleRate = 44100;
            int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, 
                    AudioFormat.CHANNEL_OUT_MONO, 
                    AudioFormat.ENCODING_PCM_16BIT);

            silentAudioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(minBufferSize)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();

            byte[] silence = new byte[minBufferSize];
            silentAudioTrack.write(silence, 0, silence.length);
            silentAudioTrack.setLoopPoints(0, silence.length / 2, -1);
            
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    AudioFocusRequest request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .build();
                    am.requestAudioFocus(request);
                } else {
                    am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
                }
            }

            silentAudioTrack.play();
            Log.i(TAG, "Silent audio loop started to keep volume buttons active.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start silent audio", e);
        }
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
        Log.i(TAG, "MediaSession setup complete and active.");
    }

    private synchronized void handleVolumeKey(int direction) {
        long currentTime = System.currentTimeMillis();
        
        if (isTriggered) return;

        if (firstPressTime == 0 || direction != lastDirection) {
            vibrate(50); // Diagnostic vibration only on first press
            firstPressTime = currentTime;
            lastDirection = direction;
            Log.d(TAG, "Volume press start, direction: " + direction);
        } else {
            if (currentTime - firstPressTime >= LONG_PRESS_THRESHOLD) {
                Log.i(TAG, "Emergency threshold met via MediaSession!");
                isTriggered = true;
                triggerEmergency();
                firstPressTime = 0;
            }
        }
        
        detectionHandler.removeCallbacksAndMessages(null);
        detectionHandler.postDelayed(() -> {
            if (!isTriggered) {
                firstPressTime = 0;
            }
        }, 500);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, getNotification());

        String action = intent != null ? intent.getAction() : null;
        Log.i(TAG, "onStartCommand with action: " + action);

        if (ACTION_TRIGGER_EMERGENCY.equals(action)) {
            if (!isTriggered) {
                isTriggered = true;
                triggerEmergency();
            }
        } else {
            setupMediaSession();
            if (silentAudioTrack == null || silentAudioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                startSilentAudio();
            }
        }
        
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(smsSentReceiver);
        } catch (Exception ignored) {}
        
        if (silentAudioTrack != null) {
            try {
                silentAudioTrack.stop();
                silentAudioTrack.release();
            } catch (Exception ignored) {}
        }

        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    private void triggerEmergency() {
        vibrate(1000); // Restored to original 1 second for better emergency feedback
        
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(15000); 
        }

        try {
            fusedLocationClient.getLastLocation().addOnSuccessListener(new OnSuccessListener<Location>() {
                @Override
                public void onSuccess(Location location) {
                    if (location != null) {
                        sendAlerts(location.getLatitude(), location.getLongitude());
                    } else {
                        requestFreshLocation();
                    }
                }
            });
        } catch (SecurityException e) {
            sendAlerts(0, 0);
        }
    }

    private void requestFreshLocation() {
        try {
            LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                    .setMaxUpdates(1)
                    .build();
            
            fusedLocationClient.requestLocationUpdates(request, new com.google.android.gms.location.LocationCallback() {
                @Override
                public void onLocationResult(@NonNull com.google.android.gms.location.LocationResult locationResult) {
                    Location loc = locationResult.getLastLocation();
                    if (loc != null) {
                        sendAlerts(loc.getLatitude(), loc.getLongitude());
                    } else {
                        sendAlerts(0, 0);
                    }
                }
            }, null);
        } catch (SecurityException e) {
            sendAlerts(0, 0);
        }
    }

    private void sendAlerts(double lat, double lon) {
        SharedPreferences prefs = getSharedPreferences("nugon_prefs", MODE_PRIVATE);
        String contacts = prefs.getString("contacts", "");
        String backendUrl = prefs.getString("backend_url", "");
        String customMessage = prefs.getString("emergency_message", getString(R.string.message_default));
        String senderId = prefs.getString("sender_id", "Anónimo");
        
        String mapsUrl = String.format("https://maps.google.com/?q=%f,%f", lat, lon);
        String message = customMessage + " " + mapsUrl;

        Log.i(TAG, "Dispatching alerts. Sender: " + senderId + ", Location: " + lat + "," + lon);

        // Send SMS
        if (!contacts.isEmpty()) {
            String[] contactList = contacts.split(",");
            SmsManager smsManager;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                smsManager = getSystemService(SmsManager.class);
            } else {
                smsManager = SmsManager.getDefault();
            }

            if (smsManager != null) {
                for (String contact : contactList) {
                    try {
                        String destination = contact.trim();
                        java.util.ArrayList<String> parts = smsManager.divideMessage(message);
                        
                        java.util.ArrayList<PendingIntent> sentIntents = new java.util.ArrayList<>();
                        Intent sentIntent = new Intent(ACTION_SMS_SENT);
                        PendingIntent pi = PendingIntent.getBroadcast(this, 0, sentIntent, PendingIntent.FLAG_IMMUTABLE);
                        for (int k = 0; k < parts.size(); k++) sentIntents.add(pi);

                        smsManager.sendMultipartTextMessage(destination, null, parts, sentIntents, null);
                        Log.i(TAG, "SMS queued for " + destination);
                    } catch (Exception e) {
                        Log.e(TAG, "SMS dispatch failed", e);
                    }
                }
            }
        }

        NetworkClient.sendAlert(backendUrl, senderId, message, lat, lon);
        
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            isTriggered = false;
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            Log.i(TAG, "Ready for next emergency.");
        }, 10000);
    }

    private void vibrate(long duration) {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(duration);
            }
        }
    }

    private Notification getNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.emergency_notification_title))
                .setContentText(getString(R.string.emergency_notification_content))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true);

        if (mediaSession != null) {
            builder.setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(0));
        }

        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Emergency Service Channel",
                    NotificationManager.IMPORTANCE_HIGH
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}