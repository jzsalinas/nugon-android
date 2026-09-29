package py.com.nugon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DeviceCredentialsPersistenceTest {
    private static final String PREFS_NAME = "nugon_device_credentials";

    @Test
    public void persistedCredentialsSurviveApplicationRecreationAndRegenerateOnlyWhenCorrupt()
            throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE);
        preferences.edit().clear().commit();

        DeviceCredentials.Credentials initial = new DeviceCredentials(context).getOrCreate();

        // A normal app update preserves SharedPreferences and Android Keystore. Recreating the
        // manager therefore exercises the same persisted-data path used after an update.
        DeviceCredentials.Credentials afterRecreation =
                new DeviceCredentials(context).getOrCreate();
        assertEquals(initial.deviceId, afterRecreation.deviceId);
        assertEquals(initial.deviceSecret, afterRecreation.deviceSecret);

        preferences.edit()
                .putString("encrypted_secret", "corrupt")
                .putString("secret_iv", "corrupt")
                .commit();
        DeviceCredentials.Credentials afterCorruption =
                new DeviceCredentials(context).getOrCreate();
        assertNotEquals(initial.deviceId, afterCorruption.deviceId);
        assertNotEquals(initial.deviceSecret, afterCorruption.deviceSecret);

        preferences.edit().clear().commit();
    }
}
