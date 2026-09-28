package py.com.nugon;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.text.DateFormat;
import java.util.Date;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = "nugon_prefs";
    private static final int REQUEST_SMS = 100;
    private static final int REQUEST_FOREGROUND_LOCATION = 101;
    private static final int REQUEST_BACKGROUND_LOCATION = 102;

    private EditText contactsEditText;
    private EditText messageEditText;
    private TextView backendStatusText;
    private TextView pairingCodeText;
    private TextView pairingExpiryText;
    private Button registerDeviceButton;
    private Button createPairingButton;
    private Button revokeLinksButton;
    private SharedPreferences prefs;
    private EmergencyDispatcher testDispatcher;
    private DeviceCredentials deviceCredentials;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        contactsEditText = findViewById(R.id.contactsEditText);
        messageEditText = findViewById(R.id.messageEditText);
        backendStatusText = findViewById(R.id.backendStatusText);
        pairingCodeText = findViewById(R.id.pairingCodeText);
        pairingExpiryText = findViewById(R.id.pairingExpiryText);
        registerDeviceButton = findViewById(R.id.registerDeviceButton);
        createPairingButton = findViewById(R.id.createPairingButton);
        revokeLinksButton = findViewById(R.id.revokeLinksButton);
        Button saveButton = findViewById(R.id.saveButton);
        Button permissionsButton = findViewById(R.id.permissionsButton);
        Button accessibilityButton = findViewById(R.id.accessibilityButton);
        Button testButton = findViewById(R.id.testButton);

        contactsEditText.setText(prefs.getString("contacts", ""));
        messageEditText.setText(prefs.getString(
                "emergency_message", getString(R.string.message_default)));
        deviceCredentials = new DeviceCredentials(this);

        saveButton.setOnClickListener(view -> saveConfiguration());
        registerDeviceButton.setOnClickListener(view -> registerDevice());
        createPairingButton.setOnClickListener(view -> createPairing());
        revokeLinksButton.setOnClickListener(view -> confirmRevokeLinks());
        permissionsButton.setOnClickListener(view -> requestNextAlertPermission());
        accessibilityButton.setOnClickListener(view -> showAccessibilityDisclosure());
        testButton.setOnClickListener(view -> testAlert());

        // Runtime permissions are intentionally never requested from onCreate().
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    @Override
    protected void onDestroy() {
        if (testDispatcher != null) {
            testDispatcher.shutdown();
            testDispatcher = null;
        }
        super.onDestroy();
    }

    private void saveConfiguration() {
        prefs.edit()
                .putString("contacts", contactsEditText.getText().toString())
                .putString("emergency_message", messageEditText.getText().toString())
                .remove("sender_id")
                .remove("backend_url")
                .apply();
        Toast.makeText(this, R.string.config_saved, Toast.LENGTH_SHORT).show();
    }

    private void testAlert() {
        saveConfiguration();
        if (!hasSmsPermission()) {
            Toast.makeText(this, R.string.sms_permission_required, Toast.LENGTH_LONG).show();
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.SEND_SMS}, REQUEST_SMS);
            return;
        }
        if (!hasForegroundLocationPermission()) {
            Toast.makeText(this, R.string.test_without_location, Toast.LENGTH_LONG).show();
        }
        if (testDispatcher == null) {
            testDispatcher = new EmergencyDispatcher(this);
        }
        testDispatcher.dispatchFromForeground();
    }

    private void updateStatus() {
        Button permissionsButton = findViewById(R.id.permissionsButton);
        permissionsButton.setText(getMissingPermissionMessage());
        permissionsButton.setBackgroundColor(ContextCompat.getColor(
                this,
                hasSmsPermission() && hasForegroundLocationPermission()
                        && hasBackgroundLocationPermission()
                        ? android.R.color.holo_green_dark
                        : android.R.color.holo_orange_dark));
        permissionsButton.setTextColor(ContextCompat.getColor(this, android.R.color.white));

        Button accessibilityButton = findViewById(R.id.accessibilityButton);
        boolean enabled = isAccessibilityServiceEnabled();
        if (enabled && NugonAccessibilityService.isRunning) {
            accessibilityButton.setText(R.string.accessibility_status_active);
            accessibilityButton.setBackgroundColor(ContextCompat.getColor(
                    this, android.R.color.holo_green_dark));
        } else if (enabled) {
            accessibilityButton.setText(R.string.accessibility_status_enabled);
            accessibilityButton.setBackgroundColor(ContextCompat.getColor(
                    this, android.R.color.holo_orange_dark));
        } else {
            accessibilityButton.setText(R.string.accessibility_status_disabled);
            accessibilityButton.setBackgroundColor(ContextCompat.getColor(
                    this, android.R.color.holo_orange_dark));
        }
        accessibilityButton.setTextColor(ContextCompat.getColor(this, android.R.color.white));
        updateBackendStatus();
    }

    private void updateBackendStatus() {
        try {
            String canonicalUrl = NetworkClient.canonicalBackendUrl(BuildConfig.NUGON_BACKEND_URL);
            if (deviceCredentials.isRegisteredFor(canonicalUrl)) {
                backendStatusText.setText(R.string.backend_status_registered);
            } else if (deviceCredentials.hasCredentials()) {
                backendStatusText.setText(R.string.backend_status_pending);
            } else {
                backendStatusText.setText(R.string.backend_status_not_initialized);
            }
        } catch (IllegalArgumentException error) {
            backendStatusText.setText(R.string.backend_status_invalid_url);
        }
    }

    private void registerDevice() {
        saveConfiguration();
        String backendUrl = BuildConfig.NUGON_BACKEND_URL;
        setBackendButtonsEnabled(false);
        backendStatusText.setText(R.string.backend_status_registering);
        NetworkClient.registerDevice(this, backendUrl, new NetworkClient.ResultCallback<Void>() {
            @Override
            public void onSuccess(Void ignored) {
                setBackendButtonsEnabled(true);
                backendStatusText.setText(R.string.backend_status_registered);
                Toast.makeText(MainActivity.this,
                        R.string.backend_registered, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(@NonNull String errorCode) {
                setBackendButtonsEnabled(true);
                backendStatusText.setText(R.string.backend_status_error);
                Toast.makeText(MainActivity.this,
                        R.string.backend_operation_failed, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void createPairing() {
        saveConfiguration();
        String backendUrl = BuildConfig.NUGON_BACKEND_URL;
        setBackendButtonsEnabled(false);
        pairingCodeText.setText(R.string.pairing_generating);
        pairingExpiryText.setText("");
        NetworkClient.createPairing(this, backendUrl,
                new NetworkClient.ResultCallback<NetworkClient.Pairing>() {
                    @Override
                    public void onSuccess(NetworkClient.Pairing pairing) {
                        setBackendButtonsEnabled(true);
                        backendStatusText.setText(R.string.backend_status_registered);
                        pairingCodeText.setText(pairing.code);
                        String expiry = DateFormat.getTimeInstance(DateFormat.SHORT)
                                .format(new Date(pairing.expiresAt));
                        pairingExpiryText.setText(getString(R.string.pairing_expires, expiry));
                    }

                    @Override
                    public void onError(@NonNull String errorCode) {
                        setBackendButtonsEnabled(true);
                        pairingCodeText.setText(R.string.pairing_failed);
                        pairingExpiryText.setText("");
                        Toast.makeText(MainActivity.this,
                                R.string.backend_operation_failed, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void confirmRevokeLinks() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.revoke_links_title)
                .setMessage(R.string.revoke_links_message)
                .setPositiveButton(R.string.revoke_links_confirm, (dialog, which) -> revokeLinks())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void revokeLinks() {
        saveConfiguration();
        String backendUrl = BuildConfig.NUGON_BACKEND_URL;
        setBackendButtonsEnabled(false);
        NetworkClient.revokeAllLinks(this, backendUrl,
                new NetworkClient.ResultCallback<Integer>() {
                    @Override
                    public void onSuccess(Integer removed) {
                        setBackendButtonsEnabled(true);
                        pairingCodeText.setText(R.string.pairing_none);
                        pairingExpiryText.setText("");
                        Toast.makeText(MainActivity.this,
                                getString(R.string.links_revoked, removed), Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onError(@NonNull String errorCode) {
                        setBackendButtonsEnabled(true);
                        Toast.makeText(MainActivity.this,
                                R.string.backend_operation_failed, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void setBackendButtonsEnabled(boolean enabled) {
        registerDeviceButton.setEnabled(enabled);
        createPairingButton.setEnabled(enabled);
        revokeLinksButton.setEnabled(enabled);
    }

    private boolean isAccessibilityServiceEnabled() {
        String canonicalName = NugonAccessibilityService.class.getCanonicalName();
        String shortName = getPackageName() + "/.NugonAccessibilityService";
        try {
            int accessibilityEnabled = Settings.Secure.getInt(
                    getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED);
            if (accessibilityEnabled != 1) {
                return false;
            }
            String enabledServices = Settings.Secure.getString(
                    getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabledServices != null
                    && (enabledServices.contains(getPackageName() + "/" + canonicalName)
                    || enabledServices.contains(shortName));
        } catch (Settings.SettingNotFoundException error) {
            Log.e(TAG, "Unable to read accessibility status", error);
            return false;
        }
    }

    private String getMissingPermissionMessage() {
        if (!hasSmsPermission()) {
            return getString(R.string.permission_sms_action);
        }
        if (!hasForegroundLocationPermission()) {
            return getString(R.string.permission_location_action);
        }
        if (!hasBackgroundLocationPermission()) {
            return getString(R.string.permission_background_location_action);
        }
        return getString(R.string.permissions_granted);
    }

    private boolean hasSmsPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasForegroundLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBackgroundLocationPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNextAlertPermission() {
        if (!hasSmsPermission()) {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.SEND_SMS}, REQUEST_SMS);
            return;
        }
        if (!hasForegroundLocationPermission()) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    REQUEST_FOREGROUND_LOCATION);
            return;
        }
        if (!hasBackgroundLocationPermission()) {
            showBackgroundLocationDisclosure();
            return;
        }
        Toast.makeText(this, R.string.permissions_already_granted, Toast.LENGTH_SHORT).show();
    }

    private void showBackgroundLocationDisclosure() {
        String settingsLabel = getString(R.string.background_location_settings_label_fallback);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            settingsLabel = getPackageManager().getBackgroundPermissionOptionLabel().toString();
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.background_location_disclosure_title)
                .setMessage(getString(
                        R.string.background_location_disclosure_message, settingsLabel))
                .setPositiveButton(R.string.continue_to_settings, (ignored, which) -> {
                    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                        ActivityCompat.requestPermissions(
                                this,
                                new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                                REQUEST_BACKGROUND_LOCATION);
                    } else {
                        Intent intent = new Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    }
                })
                .setNegativeButton(R.string.cancel, (ignored, which) -> ignored.dismiss())
                .setCancelable(false)
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    private void showAccessibilityDisclosure() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.accessibility_disclosure_title)
                .setMessage(R.string.accessibility_disclosure_message)
                .setPositiveButton(R.string.continue_to_accessibility_settings, (ignored, which) -> {
                    Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    startActivity(intent);
                })
                .setNegativeButton(R.string.cancel, (ignored, which) -> ignored.dismiss())
                .setCancelable(false)
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_SMS
                && requestCode != REQUEST_FOREGROUND_LOCATION
                && requestCode != REQUEST_BACKGROUND_LOCATION) {
            return;
        }

        if (requestCode == REQUEST_SMS) {
            if (!hasSmsPermission()) {
                Toast.makeText(this, R.string.sms_permission_denied, Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQUEST_FOREGROUND_LOCATION) {
            // Approximate location is a valid foreground grant on Android 12+.
            if (!hasForegroundLocationPermission()) {
                Toast.makeText(this, R.string.location_permission_denied, Toast.LENGTH_LONG).show();
            }
        } else {
            if (!hasBackgroundLocationPermission()) {
                Toast.makeText(this, R.string.location_permission_denied, Toast.LENGTH_LONG).show();
            }
        }
        updateStatus();
    }
}
