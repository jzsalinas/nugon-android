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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = "nugon_prefs";
    private static final int REQUEST_SMS = 100;
    private static final int REQUEST_FOREGROUND_LOCATION = 101;
    private static final int REQUEST_BACKGROUND_LOCATION = 102;

    private EditText contactsEditText;
    private EditText backendUrlEditText;
    private EditText messageEditText;
    private EditText senderIdEditText;
    private SharedPreferences prefs;
    private EmergencyDispatcher testDispatcher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        contactsEditText = findViewById(R.id.contactsEditText);
        backendUrlEditText = findViewById(R.id.backendUrlEditText);
        messageEditText = findViewById(R.id.messageEditText);
        senderIdEditText = findViewById(R.id.senderIdEditText);
        Button saveButton = findViewById(R.id.saveButton);
        Button permissionsButton = findViewById(R.id.permissionsButton);
        Button accessibilityButton = findViewById(R.id.accessibilityButton);
        Button testButton = findViewById(R.id.testButton);

        contactsEditText.setText(prefs.getString("contacts", ""));
        backendUrlEditText.setText(prefs.getString("backend_url", ""));
        messageEditText.setText(prefs.getString(
                "emergency_message", getString(R.string.message_default)));
        senderIdEditText.setText(prefs.getString("sender_id", ""));

        saveButton.setOnClickListener(view -> saveConfiguration());
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
                .putString("backend_url", backendUrlEditText.getText().toString())
                .putString("emergency_message", messageEditText.getText().toString())
                .putString("sender_id", senderIdEditText.getText().toString())
                .apply();
        Toast.makeText(this, R.string.config_saved, Toast.LENGTH_SHORT).show();
    }

    private void testAlert() {
        saveConfiguration();
        if (!areAllAlertPermissionsGranted()) {
            Toast.makeText(this, R.string.permissions_before_test, Toast.LENGTH_LONG).show();
            requestNextAlertPermission();
            return;
        }
        if (testDispatcher == null) {
            testDispatcher = new EmergencyDispatcher(this);
        }
        testDispatcher.dispatch();
    }

    private void updateStatus() {
        Button permissionsButton = findViewById(R.id.permissionsButton);
        permissionsButton.setText(getMissingPermissionMessage());
        permissionsButton.setBackgroundColor(ContextCompat.getColor(
                this,
                areAllAlertPermissionsGranted()
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

    private boolean areAllAlertPermissionsGranted() {
        return hasSmsPermission()
                && hasForegroundLocationPermission()
                && hasBackgroundLocationPermission();
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

        boolean granted;
        if (requestCode == REQUEST_SMS) {
            granted = hasSmsPermission();
        } else if (requestCode == REQUEST_FOREGROUND_LOCATION) {
            // Approximate location is a valid foreground grant on Android 12+.
            granted = hasForegroundLocationPermission();
        } else {
            granted = hasBackgroundLocationPermission();
        }
        if (!granted) {
            Toast.makeText(this, R.string.permissions_required, Toast.LENGTH_LONG).show();
        }
        updateStatus();
    }
}
