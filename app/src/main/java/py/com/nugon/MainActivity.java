package py.com.nugon;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 100;

    private EditText contactsEditText;
    private EditText backendUrlEditText;
    private EditText messageEditText;
    private EditText senderIdEditText;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("nugon_prefs", MODE_PRIVATE);

        contactsEditText = findViewById(R.id.contactsEditText);
        backendUrlEditText = findViewById(R.id.backendUrlEditText);
        messageEditText = findViewById(R.id.messageEditText);
        senderIdEditText = findViewById(R.id.senderIdEditText);
        Button saveButton = findViewById(R.id.saveButton);
        Button permissionsButton = findViewById(R.id.permissionsButton);
        Button accessibilityButton = findViewById(R.id.accessibilityButton);
        Button batteryButton = findViewById(R.id.batteryButton);
        Button testButton = findViewById(R.id.testButton);

        contactsEditText.setText(prefs.getString("contacts", ""));
        backendUrlEditText.setText(prefs.getString("backend_url", ""));
        messageEditText.setText(prefs.getString("emergency_message", getString(R.string.message_default)));
        senderIdEditText.setText(prefs.getString("sender_id", ""));

        saveButton.setOnClickListener(v -> {
            prefs.edit()
                    .putString("contacts", contactsEditText.getText().toString())
                    .putString("backend_url", backendUrlEditText.getText().toString())
                    .putString("emergency_message", messageEditText.getText().toString())
                    .putString("sender_id", senderIdEditText.getText().toString())
                    .apply();
            Toast.makeText(this, R.string.config_saved, Toast.LENGTH_SHORT).show();
            startMonitor();
        });

        permissionsButton.setOnClickListener(v -> requestPermissions());

        accessibilityButton.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        batteryButton.setOnClickListener(v -> {
            Intent intent = new Intent();
            if (isIgnoringBatteryOptimizations()) {
                intent.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            } else {
                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
            }
            startActivity(intent);
        });

        testButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, EmergencyService.class);
            intent.setAction(EmergencyService.ACTION_TRIGGER_EMERGENCY);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        });

        requestPermissions();
        startMonitor();
    }

    private void startMonitor() {
        Intent intent = new Intent(this, EmergencyService.class);
        intent.setAction(EmergencyService.ACTION_START_MONITOR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatus();
    }

    private void updateServiceStatus() {
        Button permissionsButton = findViewById(R.id.permissionsButton);
        if (areBasicPermissionsGranted()) {
            permissionsButton.setText("Permisos: Concedidos ✅");
            permissionsButton.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
            permissionsButton.setTextColor(ContextCompat.getColor(this, android.R.color.white));
        } else {
            permissionsButton.setText("Conceder Permisos Básicos ⚠️");
            permissionsButton.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark));
            permissionsButton.setTextColor(ContextCompat.getColor(this, android.R.color.white));
        }

        Button accessibilityButton = findViewById(R.id.accessibilityButton);
        if (isAccessibilityServiceEnabled()) {
            accessibilityButton.setText("Servicio Activo ✅");
            accessibilityButton.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
            accessibilityButton.setTextColor(ContextCompat.getColor(this, android.R.color.white));
        } else {
            accessibilityButton.setText("Activar Servicio Accesibilidad ⚠️");
            accessibilityButton.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark));
            accessibilityButton.setTextColor(ContextCompat.getColor(this, android.R.color.white));
        }

        Button batteryButton = findViewById(R.id.batteryButton);
        if (isIgnoringBatteryOptimizations()) {
            batteryButton.setText("Batería: Sin restricciones ✅");
            batteryButton.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
            batteryButton.setTextColor(ContextCompat.getColor(this, android.R.color.white));
        } else {
            batteryButton.setText("Batería: Optimizada (Cambiar) ⚠️");
            batteryButton.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark));
            batteryButton.setTextColor(ContextCompat.getColor(this, android.R.color.white));
        }
    }

    private boolean isIgnoringBatteryOptimizations() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            return pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        return false;
    }

    private boolean isAccessibilityServiceEnabled() {
        String service = getPackageName() + "/" + NugonAccessibilityService.class.getCanonicalName();
        int accessibilityEnabled = 0;
        try {
            accessibilityEnabled = Settings.Secure.getInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException e) {
            Log.e("MainActivity", "Error finding accessibility setting", e);
        }

        if (accessibilityEnabled == 1) {
            String settingValue = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue != null) {
                return settingValue.contains(service);
            }
        }
        return false;
    }

    private boolean areBasicPermissionsGranted() {
        String[] permissions = {
                Manifest.permission.SEND_SMS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        };

        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }

        return true;
    }

    private void requestPermissions() {
        String[] permissions = {
                Manifest.permission.SEND_SMS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        };

        // On Android 10+ (Q), we need to request background location separately
        // On Android 13+ (Tiramisu), we add POST_NOTIFICATIONS
        
        boolean needsForeground = false;
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needsForeground = true;
                break;
            }
        }

        if (needsForeground) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
        } else {
            // Foreground granted, check background
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    // Show a message to the user explaining why we need background location
                    Toast.makeText(this, "Por favor, elige 'Permitir todo el tiempo' para la ubicación", Toast.LENGTH_LONG).show();
                    ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, PERMISSION_REQUEST_CODE);
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_REQUEST_CODE);
                    }
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, R.string.permissions_required, Toast.LENGTH_LONG).show();
                    break;
                }
            }
        }
    }
}