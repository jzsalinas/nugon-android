package py.com.nugon;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class DeviceCredentials {
    private static final String TAG = "DeviceCredentials";
    private static final String PREFS_NAME = "nugon_device_credentials";
    private static final String KEY_ALIAS = "nugon_device_secret_key_v1";
    private static final String DEVICE_ID = "device_id";
    private static final String ENCRYPTED_SECRET = "encrypted_secret";
    private static final String SECRET_IV = "secret_iv";
    private static final String REGISTERED_BACKEND = "registered_backend";
    private static final int DEVICE_ID_BYTES = 16;
    private static final int DEVICE_SECRET_BYTES = 32;

    private final Context context;
    private final SharedPreferences preferences;
    private final SecureRandom secureRandom = new SecureRandom();

    public DeviceCredentials(@NonNull Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public synchronized Credentials getOrCreate() throws CredentialException {
        String deviceId = preferences.getString(DEVICE_ID, null);
        String encryptedSecret = preferences.getString(ENCRYPTED_SECRET, null);
        String iv = preferences.getString(SECRET_IV, null);
        if (deviceId != null && encryptedSecret != null && iv != null) {
            try {
                return new Credentials(deviceId, decrypt(encryptedSecret, iv));
            } catch (Exception error) {
                Log.e(TAG, "Stored device credential could not be decrypted: "
                        + error.getClass().getSimpleName());
                resetLocalIdentity();
            }
        }
        return generate();
    }

    public boolean hasCredentials() {
        return preferences.contains(DEVICE_ID)
                && preferences.contains(ENCRYPTED_SECRET)
                && preferences.contains(SECRET_IV);
    }

    public boolean isRegisteredFor(String backendUrl) {
        String registeredBackend = preferences.getString(REGISTERED_BACKEND, null);
        return registeredBackend != null && registeredBackend.equals(backendUrl);
    }

    public void markRegistered(String backendUrl) {
        preferences.edit().putString(REGISTERED_BACKEND, backendUrl).apply();
    }

    private Credentials generate() throws CredentialException {
        try {
            byte[] idBytes = new byte[DEVICE_ID_BYTES];
            byte[] secretBytes = new byte[DEVICE_SECRET_BYTES];
            secureRandom.nextBytes(idBytes);
            secureRandom.nextBytes(secretBytes);
            String deviceId = encode(idBytes);
            String deviceSecret = encode(secretBytes);

            SecretKey encryptionKey = getOrCreateEncryptionKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey);
            byte[] ciphertext = cipher.doFinal(deviceSecret.getBytes(StandardCharsets.UTF_8));
            preferences.edit()
                    .putString(DEVICE_ID, deviceId)
                    .putString(ENCRYPTED_SECRET, encode(ciphertext))
                    .putString(SECRET_IV, encode(cipher.getIV()))
                    .remove(REGISTERED_BACKEND)
                    .apply();
            return new Credentials(deviceId, deviceSecret);
        } catch (Exception error) {
            throw new CredentialException("Unable to create device credentials", error);
        }
    }

    private String decrypt(String encryptedSecret, String iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateEncryptionKey(),
                new GCMParameterSpec(128, decode(iv)));
        byte[] plaintext = cipher.doFinal(decode(encryptedSecret));
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    private SecretKey getOrCreateEncryptionKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private void resetLocalIdentity() {
        preferences.edit().clear().apply();
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS);
        } catch (Exception error) {
            Log.e(TAG, "Unable to reset unusable Keystore entry: "
                    + error.getClass().getSimpleName());
        }
    }

    private static String encode(byte[] value) {
        return Base64.encodeToString(value, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static byte[] decode(String value) {
        return Base64.decode(value, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    public static final class Credentials {
        public final String deviceId;
        public final String deviceSecret;

        private Credentials(String deviceId, String deviceSecret) {
            this.deviceId = deviceId;
            this.deviceSecret = deviceSecret;
        }
    }

    public static final class CredentialException extends Exception {
        CredentialException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
