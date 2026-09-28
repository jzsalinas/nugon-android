package py.com.nugon;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class NetworkClient {
    private static final String TAG = "NetworkClient";
    private static final OkHttpClient CLIENT = new OkHttpClient();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private NetworkClient() {
    }

    public static void registerDevice(
            @NonNull Context context,
            @NonNull String backendUrl,
            @NonNull ResultCallback<Void> callback) {
        prepareAuthenticatedDevice(
                context, backendUrl, true, callback, session -> callback.onSuccess(null));
    }

    public static void createPairing(
            @NonNull Context context,
            @NonNull String backendUrl,
            @NonNull ResultCallback<Pairing> callback) {
        prepareAuthenticatedDevice(context, backendUrl, false, callback, session -> {
            JSONObject body = new JSONObject();
            Request request = authenticatedRequest(
                    session,
                    session.apiBase + "/devices/" + session.credentials.deviceId + "/pairings")
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
            execute(request, new ResponseCallback() {
                @Override
                public void onResponse(int statusCode, @Nullable String responseBody) {
                    if (statusCode != 201 || responseBody == null) {
                        callback.onError("PAIRING_REQUEST_FAILED");
                        return;
                    }
                    try {
                        JSONObject json = new JSONObject(responseBody);
                        callback.onSuccess(new Pairing(
                                json.getString("code"),
                                json.getLong("expiresAt")));
                    } catch (Exception error) {
                        callback.onError("INVALID_SERVER_RESPONSE");
                    }
                }

                @Override
                public void onFailure() {
                    callback.onError("NETWORK_ERROR");
                }
            });
        });
    }

    public static void revokeAllLinks(
            @NonNull Context context,
            @NonNull String backendUrl,
            @NonNull ResultCallback<Integer> callback) {
        prepareAuthenticatedDevice(context, backendUrl, false, callback, session -> {
            Request request = authenticatedRequest(
                    session,
                    session.apiBase + "/devices/" + session.credentials.deviceId + "/links")
                    .delete()
                    .build();
            execute(request, new ResponseCallback() {
                @Override
                public void onResponse(int statusCode, @Nullable String responseBody) {
                    if (statusCode != 200 || responseBody == null) {
                        callback.onError("REVOCATION_FAILED");
                        return;
                    }
                    try {
                        callback.onSuccess(new JSONObject(responseBody).getInt("removed"));
                    } catch (Exception error) {
                        callback.onError("INVALID_SERVER_RESPONSE");
                    }
                }

                @Override
                public void onFailure() {
                    callback.onError("NETWORK_ERROR");
                }
            });
        });
    }

    public static void sendAlert(
            @NonNull Context context,
            @Nullable String backendUrl,
            @NonNull String message,
            @Nullable Double latitude,
            @Nullable Double longitude) {
        if (backendUrl == null || backendUrl.trim().isEmpty()) {
            Log.i(TAG, "Backend channel skipped because no URL is configured");
            return;
        }
        prepareAuthenticatedDevice(
                context,
                backendUrl,
                false,
                new ResultCallback<Void>() {
                    @Override
                    public void onSuccess(Void ignored) {
                    }

                    @Override
                    public void onError(@NonNull String errorCode) {
                        Log.e(TAG, "Backend registration failed: " + errorCode);
                    }
                },
                session -> {
                    try {
                        JSONObject json = new JSONObject();
                        json.put("message", message);
                        if (latitude != null && longitude != null) {
                            json.put("latitude", latitude);
                            json.put("longitude", longitude);
                        }
                        Request request = authenticatedRequest(
                                session,
                                session.apiBase + "/devices/" + session.credentials.deviceId
                                        + "/alerts")
                                .post(RequestBody.create(json.toString(), JSON))
                                .build();
                        Log.i(TAG, "Authenticated network alert request started");
                        execute(request, new ResponseCallback() {
                            @Override
                            public void onResponse(int statusCode, @Nullable String responseBody) {
                                if (statusCode >= 200 && statusCode < 300) {
                                    Log.i(TAG, "Network alert processed successfully");
                                } else {
                                    Log.e(TAG, "Network alert response code=" + statusCode);
                                }
                            }

                            @Override
                            public void onFailure() {
                                Log.e(TAG, "Network alert request failed");
                            }
                        });
                    } catch (Exception error) {
                        Log.e(TAG, "Unable to build network alert request: "
                                + error.getClass().getSimpleName());
                    }
                });
    }

    public static String canonicalBackendUrl(@NonNull String backendUrl) {
        HttpUrl parsed = HttpUrl.parse(backendUrl.trim());
        if (parsed == null || parsed.username().length() > 0 || parsed.password().length() > 0
                || parsed.query() != null || parsed.fragment() != null) {
            throw new IllegalArgumentException("INVALID_BACKEND_URL");
        }
        if (!"https".equals(parsed.scheme())) {
            throw new IllegalArgumentException("HTTPS_REQUIRED");
        }
        String normalized = parsed.toString();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static <T> void prepareAuthenticatedDevice(
            Context context,
            String backendUrl,
            boolean forceRegistration,
            ResultCallback<T> errorCallback,
            SessionCallback successCallback) {
        final String normalizedBackend;
        final DeviceCredentials credentialsStore = new DeviceCredentials(context);
        final DeviceCredentials.Credentials credentials;
        try {
            normalizedBackend = canonicalBackendUrl(backendUrl);
            credentials = credentialsStore.getOrCreate();
        } catch (IllegalArgumentException error) {
            errorCallback.onError(error.getMessage() == null ? "INVALID_BACKEND_URL" : error.getMessage());
            return;
        } catch (DeviceCredentials.CredentialException error) {
            errorCallback.onError("DEVICE_CREDENTIAL_ERROR");
            return;
        }

        String apiBase = normalizedBackend.endsWith("/api/v1")
                ? normalizedBackend
                : normalizedBackend + "/api/v1";
        if (!forceRegistration && credentialsStore.isRegisteredFor(normalizedBackend)) {
            successCallback.onReady(new AuthenticatedSession(apiBase, credentials));
            return;
        }
        JSONObject json = new JSONObject();
        try {
            json.put("deviceId", credentials.deviceId);
            json.put("deviceSecret", credentials.deviceSecret);
        } catch (Exception error) {
            errorCallback.onError("DEVICE_CREDENTIAL_ERROR");
            return;
        }
        Request request = new Request.Builder()
                .url(apiBase + "/devices")
                .post(RequestBody.create(json.toString(), JSON))
                .build();
        execute(request, new ResponseCallback() {
            @Override
            public void onResponse(int statusCode, @Nullable String responseBody) {
                if (statusCode == 200 || statusCode == 201) {
                    credentialsStore.markRegistered(normalizedBackend);
                    successCallback.onReady(new AuthenticatedSession(
                            apiBase, credentials));
                } else {
                    errorCallback.onError(statusCode == 409
                            ? "DEVICE_ID_CONFLICT"
                            : "REGISTRATION_FAILED");
                }
            }

            @Override
            public void onFailure() {
                errorCallback.onError("NETWORK_ERROR");
            }
        });
    }

    private static Request.Builder authenticatedRequest(
            AuthenticatedSession session,
            String url) {
        return new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + session.credentials.deviceSecret);
    }

    private static void execute(Request request, ResponseCallback callback) {
        CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException error) {
                Log.e(TAG, "Network request failed: " + error.getClass().getSimpleName());
                MAIN_HANDLER.post(callback::onFailure);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                int statusCode = response.code();
                String responseBody = null;
                try {
                    if (response.body() != null) responseBody = response.body().string();
                } catch (IOException error) {
                    Log.e(TAG, "Unable to read network response");
                } finally {
                    response.close();
                }
                String finalResponseBody = responseBody;
                MAIN_HANDLER.post(() -> callback.onResponse(statusCode, finalResponseBody));
            }
        });
    }

    public interface ResultCallback<T> {
        void onSuccess(T result);

        void onError(@NonNull String errorCode);
    }

    public static final class Pairing {
        public final String code;
        public final long expiresAt;

        Pairing(String code, long expiresAt) {
            this.code = code;
            this.expiresAt = expiresAt;
        }
    }

    private interface SessionCallback {
        void onReady(AuthenticatedSession session);
    }

    private interface ResponseCallback {
        void onResponse(int statusCode, @Nullable String responseBody);

        void onFailure();
    }

    private static final class AuthenticatedSession {
        final String apiBase;
        final DeviceCredentials.Credentials credentials;

        AuthenticatedSession(String apiBase, DeviceCredentials.Credentials credentials) {
            this.apiBase = apiBase;
            this.credentials = credentials;
        }
    }
}
