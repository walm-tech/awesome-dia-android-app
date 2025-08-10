package com.chaimate.dia;

import android.Manifest;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
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
import android.content.SharedPreferences;
import androidx.core.app.NotificationCompat;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.common.api.ApiException;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.AuthResult;
import android.os.Handler;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;

import android.app.Notification;
import android.app.NotificationChannel;

import java.io.IOException;

public class MainActivity extends AppCompatActivity {
    private NotificationHelper notificationHelper;
    private static final String TAG = "MainActivity";
    private static final String BASE_URL = "https://dia.chaimate.ai?source=android";
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private ActivityResultLauncher<Intent> fileChooserLauncher;

    private FirebaseAuth mAuth;
    private GoogleSignInClient mGoogleSignInClient;
    private ActivityResultLauncher<Intent> googleSignInLauncher;
    
    // WebView state preservation
    private static final String KEY_WEBVIEW_STATE = "webview_state";
    private static final String KEY_WEBVIEW_URL = "webview_url";
    private static final String PREF_NAME = "WebViewState";
    private static final String PREF_LAST_URL = "last_url";
    private static final String PREF_LAST_SCROLL_Y = "last_scroll_y";
    private boolean isWebViewStateRestored = false;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d(TAG, "onCreate started");

        // Request notification permission for Android 13+ (only if needed)
        requestNotificationPermission();

        // Initialize Google Sign-In launcher for Firebase
        googleSignInLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                Log.d(TAG, "Google Sign-In result received - ResultCode: " + result.getResultCode());
                
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    // Handle the Google Sign-In result and authenticate with Firebase
                    try {
                        Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(result.getData());
                        task.addOnCompleteListener(this, completedTask -> {
                            if (completedTask.isSuccessful()) {
                                GoogleSignInAccount account = completedTask.getResult();
                                Log.d(TAG, "Google Sign-In successful: " + account.getEmail());
                                
                                // Authenticate with Firebase using the Google account
                                firebaseAuthWithGoogle(account);
                                                    } else {
                            Log.e(TAG, "Google Sign-In failed: " + completedTask.getException());
                        }
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing Google Sign-In result", e);
                    }
                } else if (result.getResultCode() == RESULT_CANCELED) {
                    Log.d(TAG, "Google Sign-In was canceled by user");
                } else {
                    Log.e(TAG, "Google Sign-In failed with result code: " + result.getResultCode());
                }
            });

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
            // Initialize Firebase Auth
            mAuth = FirebaseAuth.getInstance();
            
            // Initialize Google Sign-In for Firebase
            GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken(getString(R.string.default_web_client_id))
                    .requestEmail()
                    .build();
            
            mGoogleSignInClient = GoogleSignIn.getClient(this, gso);
            
            // Set up Firebase Auth state listener
            mAuth.addAuthStateListener(new FirebaseAuth.AuthStateListener() {
                @Override
                public void onAuthStateChanged(@NonNull FirebaseAuth firebaseAuth) {
                    FirebaseUser user = firebaseAuth.getCurrentUser();
                    if (user != null) {
                        Log.d(TAG, "User signed in: " + user.getEmail());
                        // User is signed in, handle successful authentication
                        handleSuccessfulSignIn(user);
                    } else {
                        Log.d(TAG, "User signed out");
                    }
                }
            });
            
            // Setup WebView with JavaScript bridge
            setupWebView();
            
            // If we have saved state, try to restore it
            if (savedInstanceState != null) {
                Log.d(TAG, "Found saved instance state, attempting to restore WebView");
                restoreWebViewState(savedInstanceState);
            } else {
                // Check if we have persistent state saved in SharedPreferences
                Log.d(TAG, "No saved instance state, checking for persistent state");
                restoreWebViewStateFromPreferences();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate", e);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        
        // Save WebView state
        if (webView != null) {
            Log.d(TAG, "Saving WebView state");
            webView.saveState(outState);
            
            // Also save the current URL
            String currentUrl = webView.getUrl();
            if (currentUrl != null) {
                outState.putString(KEY_WEBVIEW_URL, currentUrl);
                Log.d(TAG, "Saved WebView URL: " + currentUrl);
            }
        }
        
        // Save state to SharedPreferences for persistent storage
        saveWebViewStateToPreferences();
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        
        // Restore WebView state
        if (webView != null && !isWebViewStateRestored) {
            Log.d(TAG, "Restoring WebView state");
            try {
                webView.restoreState(savedInstanceState);
                isWebViewStateRestored = true;
                
                // Check if we have a saved URL
                String savedUrl = savedInstanceState.getString(KEY_WEBVIEW_URL);
                if (savedUrl != null) {
                    Log.d(TAG, "Restored WebView URL: " + savedUrl);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error restoring WebView state", e);
                // Fallback: load the default URL if state restoration fails
                isWebViewStateRestored = false;
                String url = BASE_URL;
                Log.d(TAG, "State restoration failed, loading default URL: " + url);
                webView.loadUrl(url);
            }
        }
    }

    private void saveWebViewStateToPreferences() {
        if (webView != null) {
            SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            
            // Save current URL
            String currentUrl = webView.getUrl();
            if (currentUrl != null && !currentUrl.isEmpty()) {
                editor.putString(PREF_LAST_URL, currentUrl);
                Log.d(TAG, "Saved URL to preferences: " + currentUrl);
            }
            
            // Save scroll position if possible
            try {
                // Use JavaScript to get scroll position
                webView.evaluateJavascript(
                    "(function() { return window.pageYOffset || document.documentElement.scrollTop; })();",
                    value -> {
                        if (value != null && !value.equals("null")) {
                            try {
                                int scrollY = Integer.parseInt(value);
                                editor.putInt(PREF_LAST_SCROLL_Y, scrollY);
                                Log.d(TAG, "Saved scroll position to preferences: " + scrollY);
                            } catch (NumberFormatException e) {
                                Log.w(TAG, "Could not parse scroll position: " + value);
                            }
                        }
                        editor.apply();
                    }
                );
            } catch (Exception e) {
                Log.w(TAG, "Could not save scroll position", e);
                editor.apply();
            }
        }
    }

    private void restoreWebViewStateFromPreferences() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String savedUrl = prefs.getString(PREF_LAST_URL, null);
        
        if (savedUrl != null && !savedUrl.isEmpty()) {
            Log.d(TAG, "Restoring URL from preferences: " + savedUrl);
            if (webView != null) {
                webView.loadUrl(savedUrl);
                
                // Restore scroll position after page loads
                int savedScrollY = prefs.getInt(PREF_LAST_SCROLL_Y, 0);
                if (savedScrollY > 0) {
                    // Try to restore scroll position multiple times with increasing delays
                    restoreScrollPosition(savedScrollY, 1);
                }
            }
        } else {
            Log.d(TAG, "No saved URL found in preferences, loading default");
            if (webView != null) {
                webView.loadUrl(BASE_URL);
            }
        }
    }

    private void restoreScrollPosition(int scrollY, int attempt) {
        if (attempt > 3) {
            Log.w(TAG, "Failed to restore scroll position after 3 attempts");
            return;
        }
        
        webView.postDelayed(() -> {
            try {
                String scrollScript = "window.scrollTo(0, " + scrollY + ");";
                webView.evaluateJavascript(scrollScript, value -> {
                    // Check if scroll was successful
                    webView.evaluateJavascript(
                        "(function() { return window.pageYOffset || document.documentElement.scrollTop; })();",
                        currentScroll -> {
                            try {
                                int currentScrollY = Integer.parseInt(currentScroll);
                                if (Math.abs(currentScrollY - scrollY) > 50) {
                                    // Scroll position not restored, try again
                                    Log.d(TAG, "Scroll position not restored, retrying... (attempt " + (attempt + 1) + ")");
                                    restoreScrollPosition(scrollY, attempt + 1);
                                } else {
                                    Log.d(TAG, "Scroll position restored successfully: " + scrollY);
                                }
                            } catch (NumberFormatException e) {
                                Log.w(TAG, "Could not parse current scroll position");
                            }
                        }
                    );
                });
            } catch (Exception e) {
                Log.w(TAG, "Could not restore scroll position on attempt " + attempt, e);
                if (attempt < 3) {
                    restoreScrollPosition(scrollY, attempt + 1);
                }
            }
        }, 1000 * attempt); // Increase delay with each attempt
    }

    private void clearSavedWebViewState() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.clear();
        editor.apply();
        Log.d(TAG, "Cleared saved WebView state");
    }

    private void restoreWebViewState(Bundle savedInstanceState) {
        if (webView != null && savedInstanceState != null) {
            try {
                // Try to restore the WebView state
                webView.restoreState(savedInstanceState);
                isWebViewStateRestored = true;
                Log.d(TAG, "WebView state restored successfully");
                
                // Check if the WebView has content
                String currentUrl = webView.getUrl();
                if (currentUrl != null && !currentUrl.isEmpty()) {
                    Log.d(TAG, "WebView restored with URL: " + currentUrl);
                } else {
                    Log.d(TAG, "WebView restored but no URL found, loading default");
                    webView.loadUrl(BASE_URL);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to restore WebView state", e);
                // Load the default URL as fallback
                webView.loadUrl(BASE_URL);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause - WebView state will be preserved");
        
        // Sync cookies before saving state
        syncCookies();
        
        // Save WebView state to persistent storage
        saveWebViewStateToPreferences();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop - Saving WebView state for app termination");
        
        // Sync cookies before saving state
        syncCookies();
        
        // Save WebView state to persistent storage when app is stopped
        saveWebViewStateToPreferences();
    }

//    @Override
//    protected void onResume() {
//        super.onResume();
//
//        Log.d(TAG, "App resumed - checking if FCM token update is needed");
//
//        // Only check FCM token if activity is not finishing
//        if (!isFinishing() && !isDestroyed()) {
//            Log.d(TAG, "Activity is active, proceeding with FCM token check");
//            checkAndUpdateFcmToken();
//        } else {
//            Log.d(TAG, "Activity is finishing or destroyed, skipping FCM token check");
//        }
//    }

    @Override
    public void onBackPressed() {
        // Check if WebView can go back
        if (webView != null && webView.canGoBack()) {
            Log.d(TAG, "WebView can go back, navigating back");
            webView.goBack();
        } else {
            Log.d(TAG, "WebView cannot go back, calling super.onBackPressed");
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        // Save WebView state before destroying
        if (webView != null) {
            Log.d(TAG, "onDestroy - Saving WebView state before destruction");
            
            // Sync cookies before saving state
            syncCookies();
            
            saveWebViewStateToPreferences();
        }
        
        // Don't destroy the WebView completely to preserve state
        if (webView != null) {
            Log.d(TAG, "onDestroy - Preserving WebView state");
            // Remove the WebView from its parent but don't destroy it
            if (webView.getParent() != null) {
                ((android.view.ViewGroup) webView.getParent()).removeView(webView);
            }
        }
        super.onDestroy();
    }



    private void requestNotificationPermission() {
        // Only request notification permission on Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Requesting notification permission");
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1004);
            } else {
                Log.d(TAG, "Notification permission already granted");
            }
        } else {
            Log.d(TAG, "Notification permission not required on this Android version");
        }
    }

    private void generateAndSendFcmToken() {
        try {
            Log.d(TAG, "Generating FCM token in background...");
            
            // Get FCM token asynchronously
            FirebaseMessaging.getInstance().getToken()
                    .addOnCompleteListener(task -> {
                        if (!task.isSuccessful()) {
                            Log.w(TAG, "Fetching FCM registration token failed", task.getException());
                            return;
                        }

                        // Get new FCM registration token
                        String token = task.getResult();
                        Log.d(TAG, "FCM Token: " + token);

                        if (token != null) {
                            // Send token to backend in background
                            sendFcmTokenToBackend(token);
                        }
                    });
                    
        } catch (Exception e) {
            Log.e(TAG, "Error generating FCM token", e);
        }
    }
    
    // Send FCM token to backend in background
    private void sendFcmTokenToBackend(String fcmToken) {
        new Thread(() -> {
            try {
                Log.d(TAG, "Sending FCM token to backend in background...");
                
                // Get access token from cookies
                String accessToken = getAccessTokenFromCookies();
                
                if (accessToken != null && !accessToken.isEmpty()) {
                    // Send to backend
                    sendFcmTokenWithAccessToken(fcmToken, accessToken);
                } else {
                    Log.w(TAG, "Access token not available, skipping backend update");
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error sending FCM token to backend", e);
            }
        }).start();
    }

    private void setupWebView() {
        try {
            webView = findViewById(R.id.webview);
            WebSettings webSettings = webView.getSettings();
            
            // Basic WebView settings
            webSettings.setJavaScriptEnabled(true);
            webSettings.setDomStorageEnabled(true);
            webSettings.setAllowFileAccess(true);
            webSettings.setAllowContentAccess(true);
            webSettings.setMediaPlaybackRequiresUserGesture(false);
            webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            
            // Enable caching and state retention
            webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
            webSettings.setDatabaseEnabled(true);
            
            // Configure CookieManager to accept all cookies
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.setAcceptCookie(true);
            cookieManager.setAcceptThirdPartyCookies(webView, true);
            
            // Additional settings for better cookie and storage persistence
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cookieManager.setAcceptThirdPartyCookies(webView, true);
            }
            
            // Enable microphone and camera access
            webSettings.setAllowFileAccessFromFileURLs(true);
            webSettings.setAllowUniversalAccessFromFileURLs(true);
            
            // Enable hardware acceleration
            webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null);
            
            // Add JavaScript interface
            webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
            
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    Log.d(TAG, "WebView page loaded: " + url);
                    
                    // Log cookies for debugging
                    logCookies(url);
                    
                    // Sync cookies to ensure they are persisted
                    syncCookies();
                    
                    // Save the current state after page loads
                    saveWebViewStateToPreferences();
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
                    
                    // Only allow gallery access - no camera
                    Intent chooserIntent = new Intent(Intent.ACTION_GET_CONTENT);
                    chooserIntent.addCategory(Intent.CATEGORY_OPENABLE);
                    chooserIntent.setType("image/*"); // Only images from gallery
                    
                    Intent chooser = new Intent(Intent.ACTION_CHOOSER);
                    chooser.putExtra(Intent.EXTRA_INTENT, chooserIntent);
                    chooser.putExtra(Intent.EXTRA_TITLE, "Select Image from Gallery");
                    
                    fileChooserLauncher.launch(chooser);
                    return true;
                }

            });
            
            // Load your URL only if WebView state is not being restored
            if (!isWebViewStateRestored) {
                // Check if we have persistent state first
                SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
                String savedUrl = prefs.getString(PREF_LAST_URL, null);
                
                if (savedUrl != null && !savedUrl.isEmpty()) {
                    Log.d(TAG, "Loading saved URL from preferences: " + savedUrl);
                    webView.loadUrl(savedUrl);
                    isWebViewStateRestored = true;
                } else {
                    String url = BASE_URL;
                    Log.d(TAG, "Loading default URL in WebView: " + url);
                    webView.loadUrl(url);
                }
            } else {
                Log.d(TAG, "WebView state restored, skipping URL load");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting up WebView", e);
        }
    }
    
    private void handleSuccessfulSignIn(FirebaseUser user) {
        Log.d(TAG, "User signed in successfully: " + user.getEmail());
        
        // Get the Firebase ID token
        user.getIdToken(true)
                .addOnCompleteListener(tokenTask -> {
                    if (tokenTask.isSuccessful()) {
                        String firebaseIdToken = tokenTask.getResult().getToken();
                        Log.d(TAG, "Firebase ID token: " + firebaseIdToken);
                        
                        // Redirect to the callback URL with the Firebase ID token
                        String callbackUrl = "https://dia.chaimate.ai/auth/callback?source=android#id_token=" + firebaseIdToken;
                        Log.d(TAG, "Redirecting to callback URL: " + callbackUrl);
                        
                        if (webView != null) {
                            webView.loadUrl(callbackUrl);
                        }
                        
                        // Show success message
                        Toast.makeText(MainActivity.this, "Sign in successful!", Toast.LENGTH_SHORT).show();
                        
                        // Successfully signed in
                    } else {
                        Log.e(TAG, "Failed to get Firebase ID token", tokenTask.getException());
                    }
                });
    }

    private void firebaseAuthWithGoogle(GoogleSignInAccount acct) {
        Log.d(TAG, "firebaseAuthWithGoogle:" + acct.getId());
        
        // IMMEDIATE FEEDBACK: Show user that authentication is in progress
        showLoadingState("Signing you in...");
        
        AuthCredential credential = GoogleAuthProvider.getCredential(acct.getIdToken(), null);
        mAuth.signInWithCredential(credential)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        // Sign in success, update UI with the signed-in user's information
                        Log.d(TAG, "signInWithCredential:success");
                        FirebaseUser user = mAuth.getCurrentUser();
                        
                        // IMMEDIATE SUCCESS FEEDBACK
                        showSuccessState("Welcome back!");
                        
                        // IMMEDIATE REDIRECT: Don't wait for backend response
                        redirectToWebApp();
                        
                    } else {
                        // If sign in fails, display a message to the user.
                        Log.w(TAG, "signInWithCredential:failure", task.getException());
                        showErrorState("Authentication failed. Please try again.");
                    }
                });
    }
    
    // Show loading state with immediate feedback
    private void showLoadingState(String message) {
        runOnUiThread(() -> {
            // You can implement a loading UI here
            Log.d(TAG, "Loading: " + message);
        });
    }
    
    // Show success state
    private void showSuccessState(String message) {
        runOnUiThread(() -> {
            Log.d(TAG, "Success: " + message);
        });
    }
    
    // Show error state
    private void showErrorState(String message) {
        runOnUiThread(() -> {
            Log.e(TAG, "Error: " + message);
        });
    }
    
    // Immediate redirect to web app
    private void redirectToWebApp() {
        runOnUiThread(() -> {
            // Load your web app immediately
            // The FCM token will be generated and sent in the background
            Log.d(TAG, "Redirecting to web app immediately");
            
            // You can add a small delay here if you want to show the success message
            new Handler().postDelayed(() -> {
                // Load your web content here
                // webView.loadUrl("your_web_app_url");
                Log.d(TAG, "Web app should be loaded now");
            }, 500); // Just 500ms delay to show success message
        });
    }

    private void checkAndUpdateFcmToken() {
        try {
            // Get access_token cookie from your domain
            String cookie = CookieManager.getInstance().getCookie(BASE_URL);
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

    // Get access token from cookies more efficiently
    private String getAccessTokenFromCookies() {
        try {
            CookieManager cookieManager = CookieManager.getInstance();
            String cookies = cookieManager.getCookie("your_domain.com"); // Replace with your actual domain
            
            if (cookies != null && cookies.contains("access_token=")) {
                // Extract access token from cookies
                String[] cookiePairs = cookies.split(";");
                for (String pair : cookiePairs) {
                    if (pair.trim().startsWith("access_token=")) {
                        return pair.trim().substring("access_token=".length());
                    }
                }
            }
            
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error getting access token from cookies", e);
            return null;
        }
    }
    
    // Optimized method to send FCM token with access token
    private void sendFcmTokenWithAccessToken(String fcmToken, String accessToken) {
        try {
            // Create HTTP client
            OkHttpClient client = new OkHttpClient();
            
            // Build request body
            RequestBody body = new FormBody.Builder()
                    .add("fcm_token", fcmToken)
                    .add("access_token", accessToken)
                    .build();
            
            // Build request
            Request request = new Request.Builder()
                    .url("your_backend_url/update_fcm_token") // Replace with your actual endpoint
                    .post(body)
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .build();
            
            // Execute request asynchronously
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Log.e(TAG, "Failed to send FCM token to backend", e);
                }
                
                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (response.isSuccessful()) {
                        Log.d(TAG, "FCM token successfully sent to backend");
                    } else {
                        Log.w(TAG, "Backend returned error: " + response.code());
                    }
                    response.close();
                }
            });
            
        } catch (Exception e) {
            Log.e(TAG, "Error sending FCM token to backend", e);
        }
    }

    // JavaScript interface for web page communication
    public class AndroidBridge {
        @JavascriptInterface
        public void triggerGoogleLogin() {
            Log.d(TAG, "triggerGoogleLogin called from JavaScript");
            runOnUiThread(() -> {
                performNativeGoogleLogin();
            });
        }
        
        @JavascriptInterface
        public void triggerLogout() {
            Log.d(TAG, "triggerLogout called from JavaScript");
            runOnUiThread(() -> {
                performNativeGoogleLogout();
            });
        }
        
        @JavascriptInterface
        public void logAccessToken(String accessToken) {
            Log.i(TAG, "=== ACCESS TOKEN FROM WEB ===");
            Log.i(TAG, "Access Token: " + accessToken);
            Log.i(TAG, "Token Length: " + accessToken.length());
            Log.i(TAG, "=============================");
        }
        
        @JavascriptInterface
        public void updateFcmToken() {
            Log.d(TAG, "updateFcmToken called from JavaScript");
            runOnUiThread(() -> {
                checkAndUpdateFcmToken();
            });
        }
        
        private void performNativeGoogleLogin() {
            Log.d(TAG, "Starting Firebase Google authentication");
            
            try {
                // Check if Firebase Auth is initialized
                if (mAuth == null) {
                    Log.e(TAG, "Firebase Auth is not initialized");
                    return;
                }
                
                // Check if user is already signed in
                FirebaseUser currentUser = mAuth.getCurrentUser();
                if (currentUser != null) {
                    Log.d(TAG, "User already signed in: " + currentUser.getEmail());
                    // User is already signed in, get the ID token
                    currentUser.getIdToken(true)
                            .addOnCompleteListener(tokenTask -> {
                                if (tokenTask.isSuccessful()) {
                                    String firebaseIdToken = tokenTask.getResult().getToken();
                                    Log.d(TAG, "Firebase ID token for existing user: " + firebaseIdToken);
                                    
                                    // Redirect to the callback URL with the Firebase ID token
                                    String callbackUrl = BASE_URL + "/auth/callback#id_token=" + firebaseIdToken;
                                    Log.d(TAG, "Redirecting to callback URL: " + callbackUrl);
                                    
                                    if (webView != null) {
                                        webView.loadUrl(callbackUrl);
                                    }
                                } else {
                                    Log.e(TAG, "Failed to get Firebase ID token for existing user", tokenTask.getException());
                                    // Continue with sign-in process
                                    startFirebaseGoogleSignIn();
                                }
                            });
                    return;
                }
                
                // Start Firebase Google Sign-In process
                startFirebaseGoogleSignIn();
                
            } catch (Exception e) {
                Log.e(TAG, "Error starting Firebase Google authentication", e);
            }
        }
        
        private void startFirebaseGoogleSignIn() {
            Log.d(TAG, "Starting Firebase Google Sign-In");
            
            // Create Google Sign-In intent
            Intent signInIntent = mGoogleSignInClient.getSignInIntent();
            googleSignInLauncher.launch(signInIntent);
        }
        
        private void performNativeGoogleLogout() {
            Log.d(TAG, "Starting Firebase Google authentication logout");
            
            try {
                // Check if Firebase Auth is initialized
                if (mAuth == null) {
                    Log.e(TAG, "Firebase Auth is not initialized");
                    Log.e(TAG, "Firebase Auth not configured");
                    return;
                }
                
                // Check if user is currently signed in
                FirebaseUser currentUser = mAuth.getCurrentUser();
                if (currentUser == null) {
                    Log.d(TAG, "No user currently signed in");
                    return;
                }
                
                Log.d(TAG, "User signed in: " + currentUser.getEmail() + ", proceeding with logout");
                
                // Sign out from Firebase Auth
                mAuth.signOut();
                Log.d(TAG, "Firebase Auth Sign-Out successful");
                
                // Sign out from Google Sign-In to clear the account picker state
                if (mGoogleSignInClient != null) {
                    mGoogleSignInClient.signOut().addOnCompleteListener(MainActivity.this, task -> {
                        if (task.isSuccessful()) {
                            Log.d(TAG, "Google Sign-In Sign-Out successful");
                            
                            // Also revoke access to completely clear the sign-in state
                            mGoogleSignInClient.revokeAccess().addOnCompleteListener(MainActivity.this, revokeTask -> {
                                if (revokeTask.isSuccessful()) {
                                    Log.d(TAG, "Google Sign-In access revoked successfully");
                                } else {
                                    Log.e(TAG, "Google Sign-In access revocation failed", revokeTask.getException());
                                }
                            });
                        } else {
                            Log.e(TAG, "Google Sign-In Sign-Out failed", task.getException());
                        }
                    });
                }
                
                Log.d(TAG, "Successfully signed out");
                
            } catch (Exception e) {
                Log.e(TAG, "Error during Firebase logout", e);
            }
        }
        


        @JavascriptInterface
        public void testMicrophoneAccess() {
            Log.d(TAG, "testMicrophoneAccess called from JavaScript");
            runOnUiThread(() -> {
                // Inject JavaScript to test microphone access with detailed error reporting
                String js = "console.log('Testing microphone access...'); " +
                           "console.log('navigator.mediaDevices:', navigator.mediaDevices); " +
                           "console.log('getUserMedia available:', !!navigator.mediaDevices.getUserMedia); " +
                           "navigator.mediaDevices.getUserMedia({ audio: true })" +
                           ".then(stream => { " +
                           "  console.log('Microphone access successful!'); " +
                           "  console.log('Stream tracks:', stream.getTracks().length); " +
                           "  if (window.AndroidBridge) { window.AndroidBridge.logMessage('Microphone access successful!'); }" +
                           "  stream.getTracks().forEach(track => track.stop()); " +
                           "})" +
                           ".catch(error => { " +
                           "  console.error('Microphone access failed:', error); " +
                           "  console.error('Error name:', error.name); " +
                           "  console.error('Error message:', error.message); " +
                           "  console.error('Error code:', error.code); " +
                           "  if (window.AndroidBridge) { window.AndroidBridge.logMessage('Microphone access failed: ' + error.name + ' - ' + error.message); } " +
                           "});";
                
                if (webView != null) {
                    webView.evaluateJavascript(js, null);
                }
            });
        }

        @JavascriptInterface
        public void debugMicrophonePermissions() {
            Log.d(TAG, "debugMicrophonePermissions called from JavaScript");
            runOnUiThread(() -> {
                // Inject JavaScript to debug microphone permissions
                String js = "console.log('=== Microphone Permission Debug ==='); " +
                           "console.log('navigator.mediaDevices:', navigator.mediaDevices); " +
                           "console.log('getUserMedia available:', !!navigator.mediaDevices.getUserMedia); " +
                           "console.log('Permissions API available:', !!navigator.permissions); " +
                           "if (navigator.permissions) { " +
                           "  navigator.permissions.query({name: 'microphone'}).then(result => { " +
                           "    console.log('Microphone permission state:', result.state); " +
                           "    console.log('Permission result:', result); " +
                           "  }).catch(err => { " +
                           "    console.error('Permission query failed:', err); " +
                           "    console.error('Permission error name:', err.name); " +
                           "    console.error('Permission error message:', err.message); " +
                           "  }); " +
                           "} else { " +
                           "  console.log('Permissions API not available'); " +
                           "}";
                
                if (webView != null) {
                    webView.evaluateJavascript(js, null);
                }
            });
        }

        @JavascriptInterface
        public void logMessage(String message) {
            Log.d(TAG, "JS Log: " + message);
        }
        
        @JavascriptInterface
        public void clearSavedState() {
            Log.d(TAG, "Clearing saved WebView state from JavaScript");
            runOnUiThread(() -> {
                clearSavedWebViewState();
                            Log.d(TAG, "Saved state cleared");
        });
    }
    
    @JavascriptInterface
    public void testNotification() {
        Log.d(TAG, "testNotification called from JavaScript");
        runOnUiThread(() -> {
            
            // Create a simple test notification using the direct approach
            showSimpleTestNotification();
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

    private void logCookies(String url) {
        Log.d(TAG, "Cookies for URL: " + url);
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null) {
            Log.d(TAG, "Cookies: " + cookies);
        } else {
            Log.d(TAG, "No cookies found for URL: " + url);
        }
    }

    private void syncCookies() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().flush();
            } else {
                // For older Android versions
                try {
                    CookieSyncManager.getInstance().sync();
                } catch (Exception e) {
                    Log.w(TAG, "Could not sync cookies for older Android version", e);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not sync cookies", e);
        }
    }
    
    // Test method to verify notifications work
    private void showTestNotification() {
        try {
            Log.d(TAG, "=== SHOWING TEST NOTIFICATION ===");
            
            NotificationManager notificationManager = 
                (NotificationManager) getSystemService(android.content.Context.NOTIFICATION_SERVICE);

            if (notificationManager == null) {
                Log.e(TAG, "NotificationManager is null!");
                return;
            }

            // Create intent for when notification is tapped
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE
            );

            // Build the notification with ALL possible heads-up settings
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "dia_notification_channel")
                .setContentTitle("Test Heads-Up Notification")
                .setContentText("This is a test notification to verify heads-up display and sound")
                .setPriority(NotificationCompat.PRIORITY_MAX)  // MAX priority for heads-up
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)  // Sound, vibration, lights
                .setVibrate(new long[]{0, 500, 200, 500})
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(pendingIntent, true)  // CRITICAL: Force heads-up display
                .setStyle(new androidx.core.app.NotificationCompat.BigTextStyle().bigText("This is a test notification to verify heads-up display and sound"));

            // Add sound explicitly
            builder.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI);

            // Build the final notification
            android.app.Notification notification = builder.build();
            
            // Add critical flags for heads-up display
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                notification.flags |= android.app.Notification.FLAG_INSISTENT;
                notification.flags |= android.app.Notification.FLAG_HIGH_PRIORITY;
            }

            // Show the notification
            notificationManager.notify(999, notification);
            
            Log.d(TAG, "=== TEST NOTIFICATION DISPLAYED ===");
            Log.d(TAG, "Priority: MAX");
            Log.d(TAG, "Full Screen Intent: true");
            Log.d(TAG, "Flags: " + notification.flags);
            Log.d(TAG, "Sound: " + (notification.sound != null ? "Enabled" : "Disabled"));
            Log.d(TAG, "Vibration: " + (notification.vibrate != null ? "Enabled" : "Disabled"));
            
        } catch (Exception e) {
            Log.e(TAG, "Error showing test notification", e);
            e.printStackTrace();
        }
    }

    // WhatsApp-style test notification
    private void showSimpleTestNotification() {
        try {
            Log.d(TAG, "=== SHOWING WHATSAPP-STYLE TEST NOTIFICATION ===");
            
            // Use NotificationHelper for consistent behavior
            if (notificationHelper != null) {
                notificationHelper.showHeadsUpNotification(
                    "Test Heads-Up Notification",
                    "This should pop up as a banner, then become quiet in the shade!",
                    MainActivity.class
                );
            } else {
                Log.e(TAG, "NotificationHelper is null!");
            }
            
            Log.d(TAG, "WhatsApp-style test notification sent");
            
        } catch (Exception e) {
            Log.e(TAG, "Error showing WhatsApp-style test notification", e);
            e.printStackTrace();
        }
    }
}