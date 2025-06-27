package com.example.webview;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.*;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.auth.api.signin.*;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;

public class MainActivity extends AppCompatActivity {

    private WebView mywebView;
    private GoogleSignInClient mGoogleSignInClient;
    private static final int RC_SIGN_IN = 1002;

    private ValueCallback<Uri[]> filePathCallback;
    private ActivityResultLauncher<Intent> fileChooserLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mywebView = findViewById(R.id.webview);

        initializeFileChooser();
        setupGoogleSignIn();
        setupWebView();
        mywebView.loadUrl("https://beta.dia.walm.tech");
    }

    private void setupGoogleSignIn() {
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestIdToken("YOUR_WEB_CLIENT_ID_HERE") // 🔁 Replace with your actual Web Client ID
                .build();
        mGoogleSignInClient = GoogleSignIn.getClient(this, gso);
    }

    private void setupWebView() {
        WebSettings webSettings = mywebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);
        webSettings.setSupportMultipleWindows(true);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);

        // 🚨 Important: Prevent Google login from being blocked
        String customUserAgent = "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + "; " + Build.MODEL + ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Mobile Safari/537.36";
        webSettings.setUserAgentString(customUserAgent);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        mywebView.setWebViewClient(new MyWebViewClient());
        mywebView.setWebChromeClient(new MyWebChromeClient());

        mywebView.addJavascriptInterface(new JSBridge(), "AndroidBridge");
    }

    private void initializeFileChooser() {
        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (filePathCallback == null) return;

                    Uri[] results = null;
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri dataUri = result.getData().getData();
                        if (dataUri != null) {
                            results = new Uri[]{dataUri};
                        }
                    }

                    filePathCallback.onReceiveValue(results);
                    filePathCallback = null;
                }
        );
    }

    public class JSBridge {
        @JavascriptInterface
        public void triggerGoogleLogin() {
            runOnUiThread(() -> {
                Intent signInIntent = mGoogleSignInClient.getSignInIntent();
                startActivityForResult(signInIntent, RC_SIGN_IN);
            });
        }

        @JavascriptInterface
        public void triggerLogout() {
            runOnUiThread(() -> {
                mGoogleSignInClient.signOut().addOnCompleteListener(task -> {
                    mywebView.evaluateJavascript("window.onGoogleSignOut && window.onGoogleSignOut()", null);
                    Toast.makeText(MainActivity.this, "Logged out", Toast.LENGTH_SHORT).show();
                });
            });
        }
    }

    private class MyWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            view.loadUrl(request.getUrl().toString());
            return true;
        }
    }

    private class MyWebChromeClient extends WebChromeClient {

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                         FileChooserParams fileChooserParams) {
            MainActivity.this.filePathCallback = filePathCallback;

            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);

            String[] acceptTypes = fileChooserParams.getAcceptTypes();
            intent.setType((acceptTypes.length > 0 && !acceptTypes[0].isEmpty()) ? acceptTypes[0] : "*/*");

            if (fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }

            try {
                fileChooserLauncher.launch(Intent.createChooser(intent, "Select File"));
                return true;
            } catch (Exception e) {
                filePathCallback.onReceiveValue(null);
                MainActivity.this.filePathCallback = null;
                Toast.makeText(MainActivity.this, "Cannot open file chooser", Toast.LENGTH_SHORT).show();
                return false;
            }
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                      android.os.Message resultMsg) {
            WebView newWebView = new WebView(MainActivity.this);
            newWebView.setWebViewClient(new MyWebViewClient());
            newWebView.setWebChromeClient(this);

            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(newWebView);
            resultMsg.sendToTarget();

            return true;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == RC_SIGN_IN) {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            try {
                GoogleSignInAccount account = task.getResult(ApiException.class);
                if (account != null) {
                    String idToken = account.getIdToken();
                    String email = account.getEmail();
                    String name = account.getDisplayName();

                    idToken = idToken != null ? idToken.replace("'", "\\'") : "";
                    email = email != null ? email.replace("'", "\\'") : "";
                    name = name != null ? name.replace("'", "\\'") : "";

                    String js = "window.onGoogleSignInTokenReceived && window.onGoogleSignInTokenReceived('" +
                            idToken + "', '" + email + "', '" + name + "')";
                    mywebView.evaluateJavascript(js, null);
                }
            } catch (ApiException e) {
                Toast.makeText(this, "Google Sign-In failed: " + e.getStatusCode(), Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (mywebView.canGoBack()) {
            mywebView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (mywebView != null) {
            mywebView.destroy();
        }
        super.onDestroy();
    }
}