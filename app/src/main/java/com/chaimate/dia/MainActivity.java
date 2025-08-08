package com.chaimate.dia;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.WebChromeClient;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.util.Log;
import android.widget.Toast;
import android.content.Intent;
import android.provider.MediaStore;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.firebase.messaging.FirebaseMessaging;

import java.io.IOException;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private NotificationHelper notificationHelper;
    private static final int PERMISSION_CODE = 1001;
    private static final int FILE_CHOOSER_CODE = 1002;
    private static final String TAG = "MainActivity";
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri photoURI;
    private ActivityResultLauncher<Intent> fileChooserLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d(TAG, "onCreate started");

        // Initialize file chooser launcher
        fileChooserLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && filePathCallback != null) {
                    Uri[] results = null;
                    if (result.getData() != null) {
                        String dataString = result.getData().getDataString();
                        if (dataString != null) {
                            results = new Uri[]{Uri.parse(dataString)};
                        }
                    }
                    filePathCallback.onReceiveValue(results);
                    filePathCallback = null;
                } else if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                }
            }
        );

        try {
            // Request all necessary permissions
            requestPermissions();
            
            // Setup WebView with JavaScript bridge
            setupWebView();
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate", e);
            Toast.makeText(this, "Error initializing app", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestPermissions() {
        // Request camera and microphone permissions separately for better handling
        String[] cameraPermissions = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        };
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Add Android 13+ media permissions
            String[] mediaPermissions = {
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            };
            String[] allPermissions = new String[cameraPermissions.length + mediaPermissions.length];
            System.arraycopy(cameraPermissions, 0, allPermissions, 0, cameraPermissions.length);
            System.arraycopy(mediaPermissions, 0, allPermissions, cameraPermissions.length, mediaPermissions.length);
            cameraPermissions = allPermissions;
        }

        // Check and request permissions
        boolean allPermissionsGranted = true;
        for (String permission : cameraPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                allPermissionsGranted = false;
                break;
            }
        }

        if (!allPermissionsGranted) {
            Log.d(TAG, "Requesting camera and microphone permissions");
            ActivityCompat.requestPermissions(this, cameraPermissions, 1003);
        } else {
            Log.d(TAG, "All permissions already granted");
        }

        // Always generate FCM token regardless of notification permission
        generateAndSendFcmToken();
    }

    private void generateAndSendFcmToken() {
        try {
            Log.d(TAG, "Starting FCM token generation...");
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                Log.d(TAG, "FCM token generation completed, success: " + task.isSuccessful());
                if (!isFinishing() && !isDestroyed()) {
                    if (!task.isSuccessful()) {
                        Log.w(TAG, "Fetching FCM registration token failed", task.getException());
                        return;
                    }
                    String fcmToken = task.getResult();
                    Log.d(TAG, "FCM token generated: " + fcmToken);
                    if (fcmToken != null) {
                        // Get access_token cookie from your domain
                        String cookie = CookieManager.getInstance().getCookie("https://dia.chaimate.ai");
                        Log.d(TAG, "Cookie retrieved: " + (cookie != null ? "exists" : "null"));
                        String accessToken = null;
                        if (cookie != null) {
                            for (String c : cookie.split("; ")) { // <-- note the space after ;
                                if (c.trim().startsWith("access_token=")) {
                                    accessToken = c.trim().substring("access_token=".length());
                                    Log.d(TAG, "Access token found in cookie");
                                    break;
                                }
                            }
                        }
                        if (accessToken != null) {
                            Log.d(TAG, "Sending FCM token to backend with access token");
                            sendFcmTokenToBackend(fcmToken, accessToken);
                        } else {
                            Log.w(TAG, "access_token cookie not found");
                        }
                    } else {
                        Log.w(TAG, "FCM token is null");
                    }
                } else {
                    Log.w(TAG, "Activity is finishing or destroyed, skipping FCM backend call");
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error in generateAndSendFcmToken", e);
        }
    }

    private void setupWebView() {
        try {
            webView = findViewById(R.id.webview);
            WebSettings webSettings = webView.getSettings();
            webSettings.setJavaScriptEnabled(true);
            webSettings.setDomStorageEnabled(true);
            webSettings.setAllowFileAccess(true);
            webSettings.setAllowContentAccess(true);
            webSettings.setMediaPlaybackRequiresUserGesture(false);
            webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            
            // Enable camera and microphone
            webSettings.setMediaPlaybackRequiresUserGesture(false);
            
            // Add JavaScript interface
            webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
            
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    Log.d(TAG, "WebView page loaded: " + url);
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    view.loadUrl(request.getUrl().toString());
                    return true;
                }
            });
            
            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                               FileChooserParams fileChooserParams) {
                    MainActivity.this.filePathCallback = filePathCallback;
                    
                    Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
                        File photoFile = null;
                        try {
                            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                            String imageFileName = "JPEG_" + timeStamp + "_";
                            File storageDir = getExternalFilesDir(null);
                            photoFile = File.createTempFile(imageFileName, ".jpg", storageDir);
                        } catch (IOException ex) {
                            Log.e(TAG, "Error creating image file", ex);
                        }
                        
                        if (photoFile != null) {
                            photoURI = FileProvider.getUriForFile(MainActivity.this,
                                    getApplicationContext().getPackageName() + ".provider", photoFile);
                            takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);
                        } else {
                            takePictureIntent = null;
                        }
                    }
                    
                    Intent chooserIntent = new Intent(Intent.ACTION_GET_CONTENT);
                    chooserIntent.addCategory(Intent.CATEGORY_OPENABLE);
                    chooserIntent.setType("*/*");
                    
                    Intent[] intentArray;
                    if (takePictureIntent != null) {
                        intentArray = new Intent[]{takePictureIntent};
                    } else {
                        intentArray = new Intent[]{};
                    }
                    
                    Intent chooser = new Intent(Intent.ACTION_CHOOSER);
                    chooser.putExtra(Intent.EXTRA_INTENT, chooserIntent);
                    chooser.putExtra(Intent.EXTRA_TITLE, "Select File");
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, intentArray);
                    
                    fileChooserLauncher.launch(chooser);
                    return true;
                }

                @Override
                public void onPermissionRequest(android.webkit.PermissionRequest request) {
                    Log.d(TAG, "Permission request: " + request.getResources());
                    
                    // Check if microphone permission is needed
                    if (request.getResources().length > 0) {
                        boolean hasMicrophonePermission = ContextCompat.checkSelfPermission(
                            MainActivity.this, 
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED;
                        
                        if (!hasMicrophonePermission) {
                            Log.d(TAG, "Microphone permission not granted, requesting it");
                            ActivityCompat.requestPermissions(
                                MainActivity.this, 
                                new String[]{Manifest.permission.RECORD_AUDIO}, 
                                1004
                            );
                            // Don't grant immediately, wait for user response
                            return;
                        }
                    }
                    
                    // Grant all requested permissions
                    request.grant(request.getResources());
                }

                @Override
                public void onPermissionRequestCanceled(android.webkit.PermissionRequest request) {
                    Log.d(TAG, "Permission request canceled: " + request.getResources());
                }
            });
            
            // Load your URL
            String url = "https://dia.chaimate.ai?source=android";
            Log.d(TAG, "Loading URL in WebView: " + url);
            webView.loadUrl(url);
        } catch (Exception e) {
            Log.e(TAG, "Error setting up WebView", e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Log.d(TAG, "Permission result received: requestCode=" + requestCode);
        
        if (requestCode == 1003) {
            // Camera and microphone permissions
            boolean allGranted = true;
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    Log.w(TAG, "Permission denied: " + permissions[i]);
                } else {
                    Log.d(TAG, "Permission granted: " + permissions[i]);
                }
            }
            
            if (allGranted) {
                Log.d(TAG, "All camera and microphone permissions granted");
                Toast.makeText(this, "Camera and microphone access granted", Toast.LENGTH_SHORT).show();
            } else {
                Log.w(TAG, "Some camera/microphone permissions denied");
                Toast.makeText(this, "Camera and microphone access is needed for full functionality", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == 1004) {
            // Microphone permission specifically
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Microphone permission granted");
                Toast.makeText(this, "Microphone access granted", Toast.LENGTH_SHORT).show();
            } else {
                Log.w(TAG, "Microphone permission denied");
                Toast.makeText(this, "Microphone access is needed for voice features", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        
        Log.d(TAG, "App resumed - checking if FCM token update is needed");
        
        // Only check FCM token if activity is not finishing
        if (!isFinishing() && !isDestroyed()) {
            Log.d(TAG, "Activity is active, proceeding with FCM token check");
            checkAndUpdateFcmToken();
        } else {
            Log.d(TAG, "Activity is finishing or destroyed, skipping FCM token check");
        }
    }
    
    private void checkAndUpdateFcmToken() {
        try {
            // Get access_token cookie from your domain
            String cookie = CookieManager.getInstance().getCookie("https://dia.chaimate.ai");
            final String accessToken;
            if (cookie != null) {
                String tempToken = null;
                for (String c : cookie.split("; ")) { // <-- note the space after ;
                    if (c.trim().startsWith("access_token=")) {
                        tempToken = c.trim().substring("access_token=".length());
                        break;
                    }
                }
                accessToken = tempToken;
            } else {
                accessToken = null;
            }
            
            if (accessToken != null && !isFinishing()) {
                // Get FCM token and send to backend
                FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                    if (!isFinishing() && !isDestroyed()) {
                        if (!task.isSuccessful()) {
                            Log.w(TAG, "Fetching FCM registration token failed", task.getException());
                            return;
                        }
                        String fcmToken = task.getResult();
                        Log.d(TAG, "FCM token generated (onResume): " + fcmToken);
                        if (fcmToken != null) {
                            sendFcmTokenToBackend(fcmToken, accessToken);
                        }
                    }
                });
            } else {
                Log.d(TAG, "No access_token found on app resume");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in checkAndUpdateFcmToken", e);
        }
    }

    // JavaScript interface for web page communication
    public class AndroidBridge {
        @JavascriptInterface
        public void triggerGoogleLogin() {
            // This method is called from JavaScript: window.AndroidBridge.triggerGoogleLogin()
            Log.d(TAG, "triggerGoogleLogin called from JavaScript");
            runOnUiThread(() -> {
                // Get FCM token and access_token cookie, then send to backend
                FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                    if (!isFinishing() && !isDestroyed()) {
                        if (!task.isSuccessful()) {
                            Log.w(TAG, "Fetching FCM registration token failed", task.getException());
                            return;
                        }
                        String fcmToken = task.getResult();
                        Log.d(TAG, "FCM token generated (from JS): " + fcmToken);
                        if (fcmToken != null) {
                            // Get access_token cookie from your domain
                            String cookie = CookieManager.getInstance().getCookie("https://dia.chaimate.ai");
                            Log.d(TAG, "Cookie retrieved (from JS): " + (cookie != null ? "exists" : "null"));
                            String accessToken = null;
                            if (cookie != null) {
                                for (String c : cookie.split("; ")) { // <-- note the space after ;
                                    if (c.trim().startsWith("access_token=")) {
                                        accessToken = c.trim().substring("access_token=".length());
                                        Log.d(TAG, "Access token found in cookie (from JS)");
                                        break;
                                    }
                                }
                            }
                            if (accessToken != null) {
                                Log.d(TAG, "Sending FCM token to backend with access token (from JS)");
                                sendFcmTokenToBackend(fcmToken, accessToken);
                            } else {
                                Log.w(TAG, "access_token cookie not found (from JS)");
                            }
                        }
                    }
                });
            });
        }
    }

    private void sendFcmTokenToBackend(String fcmToken, String accessToken) {
        if (fcmToken == null || accessToken == null) {
            Log.w(TAG, "FCM token or access token is null");
            return;
        }
        Log.d(TAG, "Sending FCM token to backend: " + fcmToken);
        new Thread(() -> {
            try {
                OkHttpClient client = new OkHttpClient();
                MediaType JSON = MediaType.parse("application/json; charset=utf-8");
                String json = "{\"fcm_token\":\"" + fcmToken + "\"}";
                RequestBody body = RequestBody.create(json, JSON);
                Request request = new Request.Builder()
                        .url("https://api.chaimate.ai/notification/update/fcm/token")
                        .addHeader("Authorization", "Bearer " + accessToken)
                        .post(body)
                        .build();
                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        Log.d(TAG, "FCM token sent to backend successfully");
                    } else {
                        Log.w(TAG, "Failed to send FCM token to backend: " + response.code());
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error sending FCM token to backend", e);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in sendFcmTokenToBackend", e);
            }
        }).start();
    }
}