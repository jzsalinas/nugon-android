package py.com.nugon;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.text.DateFormat;
import java.util.Date;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = "nugon_prefs";
    private static final String DISPLAY_NAME_KEY = "display_name";
    private static final String ACTIVE_PAIRING_CODE_KEY = "active_pairing_code";
    private static final String ACTIVE_PAIRING_EXPIRES_AT_KEY = "active_pairing_expires_at";
    private static final int REQUEST_SMS = 100;
    private static final int REQUEST_FOREGROUND_LOCATION = 101;
    private static final int REQUEST_BACKGROUND_LOCATION = 102;

    private EditText contactsEditText;
    private EditText displayNameEditText;
    private EditText messageEditText;
    private TextView backendStatusText;
    private TextView linkedFamilyStatusText;
    private TextView pairingCodeText;
    private TextView pairingExpiryText;
    private View pairingActions;
    private Button createPairingButton;
    private Button revokeLinksButton;
    private SharedPreferences prefs;
    private EmergencyDispatcher testDispatcher;
    private DeviceCredentials deviceCredentials;
    private final Handler pairingExpiryHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        contactsEditText = findViewById(R.id.contactsEditText);
        displayNameEditText = findViewById(R.id.displayNameEditText);
        messageEditText = findViewById(R.id.messageEditText);
        backendStatusText = findViewById(R.id.backendStatusText);
        linkedFamilyStatusText = findViewById(R.id.linkedFamilyStatusText);
        pairingCodeText = findViewById(R.id.pairingCodeText);
        pairingExpiryText = findViewById(R.id.pairingExpiryText);
        pairingActions = findViewById(R.id.pairingActions);
        createPairingButton = findViewById(R.id.createPairingButton);
        revokeLinksButton = findViewById(R.id.revokeLinksButton);
        Button saveButton = findViewById(R.id.saveButton);
        Button permissionsButton = findViewById(R.id.permissionsButton);
        Button accessibilityButton = findViewById(R.id.accessibilityButton);
        Button testButton = findViewById(R.id.testButton);
        Button copyPairingCodeButton = findViewById(R.id.copyPairingCodeButton);
        Button sharePairingCodeButton = findViewById(R.id.sharePairingCodeButton);

        contactsEditText.setText(prefs.getString("contacts", ""));
        displayNameEditText.setText(prefs.getString(DISPLAY_NAME_KEY, ""));
        messageEditText.setText(prefs.getString(
                "emergency_message", getString(R.string.message_default)));
        deviceCredentials = new DeviceCredentials(this);

        saveButton.setOnClickListener(view -> saveConfigurationAndSyncDisplayName());
        createPairingButton.setOnClickListener(view -> createPairing());
        revokeLinksButton.setOnClickListener(view -> confirmRevokeLinks());
        permissionsButton.setOnClickListener(view -> requestNextAlertPermission());
        accessibilityButton.setOnClickListener(view -> showAccessibilityDisclosure());
        testButton.setOnClickListener(view -> testAlert());
        copyPairingCodeButton.setOnClickListener(view -> copyActivePairingCode());
        sharePairingCodeButton.setOnClickListener(view -> shareActivePairingCode());

        // Runtime permissions are intentionally never requested from onCreate().
    }

    @Override
    protected void onResume() {
        super.onResume();
        restoreActivePairing();
        updateStatus();
    }

    @Override
    protected void onDestroy() {
        pairingExpiryHandler.removeCallbacksAndMessages(null);
        if (testDispatcher != null) {
            testDispatcher.shutdown();
            testDispatcher = null;
        }
        super.onDestroy();
    }

    private boolean saveConfiguration() {
        String rawDisplayName = displayNameEditText.getText().toString();
        String displayName = trimWhitespace(rawDisplayName);
        if ((!rawDisplayName.isEmpty() && displayName.isEmpty())
                || containsControlCharacter(displayName)) {
            displayNameEditText.setError(getString(R.string.display_name_invalid_error));
            Toast.makeText(this, R.string.display_name_invalid_error, Toast.LENGTH_LONG).show();
            return false;
        }
        displayNameEditText.setError(null);
        displayNameEditText.setText(displayName);
        prefs.edit()
                .putString("contacts", contactsEditText.getText().toString())
                .putString("emergency_message", messageEditText.getText().toString())
                .putString(DISPLAY_NAME_KEY, displayName)
                .remove("sender_id")
                .remove("backend_url")
                .apply();
        Toast.makeText(this, R.string.config_saved, Toast.LENGTH_SHORT).show();
        return true;
    }

    private static String trimWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) break;
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            int codePoint = value.codePointBefore(end);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) break;
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private static boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            if (Character.getType(codePoint) == Character.CONTROL) return true;
            index += Character.charCount(codePoint);
        }
        return false;
    }

    private void saveConfigurationAndSyncDisplayName() {
        if (!saveConfiguration()) return;
        String backendUrl = BuildConfig.NUGON_BACKEND_URL;
        if (!isInternetNotificationsReady()) {
            updateBackendStatus();
            return;
        }
        NetworkClient.updateDisplayName(
                this,
                backendUrl,
                currentDisplayName(),
                new NetworkClient.ResultCallback<Void>() {
                    @Override
                    public void onSuccess(Void ignored) {
                        updateBackendStatus();
                    }

                    @Override
                    public void onError(@NonNull String errorCode) {
                        Toast.makeText(MainActivity.this,
                                R.string.display_name_sync_failed, Toast.LENGTH_LONG).show();
                    }
                });
    }

    @Nullable
    private String currentDisplayName() {
        String displayName = prefs.getString(DISPLAY_NAME_KEY, "");
        return displayName == null || displayName.isEmpty() ? null : displayName;
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
        if (isInternetNotificationsReady()) {
            backendStatusText.setText(R.string.backend_status_registered);
            linkedFamilyStatusText.setText(R.string.family_status_loading);
            refreshLinkStatus();
        } else if (isBackendUrlValid()) {
            backendStatusText.setText(R.string.backend_status_not_initialized);
            linkedFamilyStatusText.setText(R.string.no_family_links);
            createPairingButton.setText(R.string.create_pairing_button);
            revokeLinksButton.setEnabled(false);
        } else {
            backendStatusText.setText(R.string.backend_status_invalid_url);
            linkedFamilyStatusText.setText(R.string.family_status_unavailable);
            revokeLinksButton.setEnabled(false);
        }
    }

    private void refreshLinkStatus() {
        NetworkClient.getLinkStatus(
                this,
                BuildConfig.NUGON_BACKEND_URL,
                new NetworkClient.ResultCallback<NetworkClient.LinkStatus>() {
                    @Override
                    public void onSuccess(NetworkClient.LinkStatus status) {
                        showLinkedFamilyCount(status.linkedCount);
                        long expiresAt = prefs.getLong(ACTIVE_PAIRING_EXPIRES_AT_KEY, 0L);
                        if (expiresAt > 0L && status.isPairingUnavailable(expiresAt)) {
                            clearActivePairing();
                        }
                    }

                    @Override
                    public void onError(@NonNull String errorCode) {
                        linkedFamilyStatusText.setText(R.string.family_status_unavailable);
                        revokeLinksButton.setEnabled(true);
                    }
                });
    }

    private void showLinkedFamilyCount(int count) {
        if (count == 0) {
            linkedFamilyStatusText.setText(R.string.no_family_links);
            createPairingButton.setText(R.string.create_pairing_button);
            revokeLinksButton.setEnabled(false);
            return;
        }
        linkedFamilyStatusText.setText(getResources().getQuantityString(
                R.plurals.linked_family_count, count, count));
        createPairingButton.setText(R.string.link_another_family_button);
        revokeLinksButton.setEnabled(true);
    }

    private void restoreActivePairing() {
        pairingExpiryHandler.removeCallbacksAndMessages(null);
        String code = prefs.getString(ACTIVE_PAIRING_CODE_KEY, null);
        long expiresAt = prefs.getLong(ACTIVE_PAIRING_EXPIRES_AT_KEY, 0L);
        long remaining = expiresAt - System.currentTimeMillis();
        if (code == null || code.isEmpty() || remaining <= 0L) {
            clearActivePairing();
            return;
        }
        pairingCodeText.setText(code);
        String expiry = DateFormat.getTimeInstance(DateFormat.SHORT)
                .format(new Date(expiresAt));
        pairingExpiryText.setText(getString(R.string.pairing_expires, expiry));
        setPairingActionsVisible(true);
        pairingExpiryHandler.postDelayed(this::restoreActivePairing, remaining);
    }

    private void persistActivePairing(NetworkClient.Pairing pairing) {
        prefs.edit()
                .putString(ACTIVE_PAIRING_CODE_KEY, pairing.code)
                .putLong(ACTIVE_PAIRING_EXPIRES_AT_KEY, pairing.expiresAt)
                .apply();
        restoreActivePairing();
    }

    private void clearActivePairing() {
        pairingExpiryHandler.removeCallbacksAndMessages(null);
        prefs.edit()
                .remove(ACTIVE_PAIRING_CODE_KEY)
                .remove(ACTIVE_PAIRING_EXPIRES_AT_KEY)
                .apply();
        pairingCodeText.setText(R.string.pairing_none);
        pairingExpiryText.setText("");
        setPairingActionsVisible(false);
    }

    @Nullable
    private String activePairingCode() {
        String code = prefs.getString(ACTIVE_PAIRING_CODE_KEY, null);
        long expiresAt = prefs.getLong(ACTIVE_PAIRING_EXPIRES_AT_KEY, 0L);
        if (code == null || code.isEmpty() || expiresAt <= System.currentTimeMillis()) {
            clearActivePairing();
            return null;
        }
        return code;
    }

    private void setPairingActionsVisible(boolean visible) {
        pairingActions.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void copyActivePairingCode() {
        String code = activePairingCode();
        if (code == null) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(getString(R.string.pairing_clip_label), code);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, R.string.pairing_code_copied, Toast.LENGTH_SHORT).show();
    }

    private void shareActivePairingCode() {
        String code = activePairingCode();
        if (code == null) return;
        String webUrl;
        try {
            webUrl = NetworkClient.publicWebUrl(BuildConfig.NUGON_BACKEND_URL);
        } catch (IllegalArgumentException error) {
            Toast.makeText(this, R.string.backend_operation_failed, Toast.LENGTH_LONG).show();
            return;
        }
        Intent shareIntent = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, getString(
                        R.string.pairing_share_text, webUrl, code));
        startActivity(Intent.createChooser(
                shareIntent, getString(R.string.pairing_share_chooser_title)));
    }

    private boolean isInternetNotificationsReady() {
        try {
            String canonicalUrl = NetworkClient.canonicalBackendUrl(BuildConfig.NUGON_BACKEND_URL);
            return deviceCredentials.isRegisteredFor(canonicalUrl);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private boolean isBackendUrlValid() {
        try {
            NetworkClient.canonicalBackendUrl(BuildConfig.NUGON_BACKEND_URL);
            return true;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private void createPairing() {
        if (!saveConfiguration()) return;
        String backendUrl = BuildConfig.NUGON_BACKEND_URL;
        setBackendButtonsEnabled(false);
        backendStatusText.setText(R.string.backend_status_preparing);
        pairingCodeText.setText(R.string.pairing_generating);
        pairingExpiryText.setText("");
        setPairingActionsVisible(false);
        NetworkClient.updateDisplayName(this, backendUrl, currentDisplayName(),
                new NetworkClient.ResultCallback<Void>() {
                    @Override
                    public void onSuccess(Void ignored) {
                        requestPairing(backendUrl);
                    }

                    @Override
                    public void onError(@NonNull String errorCode) {
                        pairingRequestFailed();
                    }
                });
    }

    private void requestPairing(String backendUrl) {
        NetworkClient.createPairing(this, backendUrl,
                new NetworkClient.ResultCallback<NetworkClient.Pairing>() {
                    @Override
                    public void onSuccess(NetworkClient.Pairing pairing) {
                        setBackendButtonsEnabled(true);
                        backendStatusText.setText(R.string.backend_status_registered);
                        persistActivePairing(pairing);
                        refreshLinkStatus();
                    }

                    @Override
                    public void onError(@NonNull String errorCode) {
                        pairingRequestFailed();
                    }
                });
    }

    private void pairingRequestFailed() {
        setBackendButtonsEnabled(true);
        backendStatusText.setText(R.string.backend_status_error);
        restoreActivePairing();
        Toast.makeText(MainActivity.this,
                R.string.backend_operation_failed, Toast.LENGTH_LONG).show();
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
        if (!isInternetNotificationsReady()) {
            showLinkedFamilyCount(0);
            return;
        }
        saveConfiguration();
        String backendUrl = BuildConfig.NUGON_BACKEND_URL;
        setBackendButtonsEnabled(false);
        NetworkClient.revokeAllLinks(this, backendUrl,
                new NetworkClient.ResultCallback<Integer>() {
                    @Override
                    public void onSuccess(Integer removed) {
                        setBackendButtonsEnabled(true);
                        showLinkedFamilyCount(0);
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
        createPairingButton.setEnabled(enabled);
        revokeLinksButton.setEnabled(enabled && isInternetNotificationsReady());
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
