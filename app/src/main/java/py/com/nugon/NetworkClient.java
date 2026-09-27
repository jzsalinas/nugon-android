package py.com.nugon;

import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class NetworkClient {
    private static final String TAG = "NetworkClient";
    private static final OkHttpClient client = new OkHttpClient();
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public static void sendAlert(String url, String senderId, String message, double lat, double lon) {
        if (url == null || url.isEmpty()) {
            Log.w(TAG, "Backend URL not configured");
            return;
        }

        String jsonBody;
        try {
            JSONObject json = new JSONObject();
            json.put("sender_id", senderId);
            json.put("message", message);
            json.put("latitude", lat);
            json.put("longitude", lon);
            jsonBody = json.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error building network alert payload: "
                    + e.getClass().getSimpleName());
            return;
        }
        
        RequestBody body = RequestBody.create(jsonBody, JSON);
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();

        Log.i(TAG, "Network alert request started");
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Network alert request failed: "
                        + e.getClass().getSimpleName());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Log.e(TAG, "Unsuccessful response: " + response.code());
                } else {
                    Log.i(TAG, "Network alert sent successfully");
                }
                response.close();
            }
        });
    }
}
