package com.example.webview;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private WebView mywebView;
    private ValueCallback<Uri[]> filePathCallback;
    private ActivityResultLauncher<Intent> fileChooserLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize file chooser launcher
        initializeFileChooser();

        mywebView = findViewById(R.id.webview);
        setupWebView();

        // Load your URL
        mywebView.loadUrl("https://beta.dia.walm.tech");
    }

    private void initializeFileChooser() {
        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                new ActivityResultCallback<ActivityResult>() {
                    @Override
                    public void onActivityResult(ActivityResult result) {
                        if (filePathCallback == null) return;

                        Uri[] results = null;

                        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                            String dataString = result.getData().getDataString();
                            if (dataString != null) {
                                results = new Uri[]{Uri.parse(dataString)};
                            }
                        }

                        filePathCallback.onReceiveValue(results);
                        filePathCallback = null;
                    }
                }
        );
    }

    private void setupWebView() {
        WebSettings webSettings = mywebView.getSettings();

        // Enable JavaScript (essential for Google login)
        webSettings.setJavaScriptEnabled(true);

        // Enable DOM storage (required for modern web apps)
        webSettings.setDomStorageEnabled(true);

        // Enable database storage
        webSettings.setDatabaseEnabled(true);

        // App cache is deprecated and not needed for modern WebView

        // Set cache mode
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // Enable file access
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);

        // Set custom user agent (Google often blocks default WebView user agent)
        String customUserAgent = "Mozilla/5.0 (Linux; Android " +
                Build.VERSION.RELEASE + "; " + Build.MODEL + ") AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/91.0.4472.124 Mobile Safari/537.36";
        webSettings.setUserAgentString(customUserAgent);

        // Enable zoom controls
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);

        // Support multiple windows
        webSettings.setSupportMultipleWindows(true);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);

        // Enable mixed content (if your site uses both HTTP and HTTPS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        // Set WebViewClient to handle page navigation
        mywebView.setWebViewClient(new MyWebViewClient());

        // Set WebChromeClient for better JavaScript support and pop-ups
        mywebView.setWebChromeClient(new MyWebChromeClient());
    }

    private class MyWebViewClient extends WebViewClient {

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            // Show loading indicator if needed
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            // Hide loading indicator if needed
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();

            // Handle special URLs (like OAuth callbacks) if needed
            if (url.startsWith("your-app-scheme://")) {
                // Handle custom scheme URLs
                return true;
            }

            // Load URL in WebView
            view.loadUrl(url);
            return true;
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            super.onReceivedError(view, errorCode, description, failingUrl);
            Toast.makeText(MainActivity.this, "Error loading page: " + description, Toast.LENGTH_SHORT).show();
        }
    }

    private class MyWebChromeClient extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            super.onProgressChanged(view, newProgress);
            // Update progress bar if you have one
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                      android.os.Message resultMsg) {
            // Handle pop-up windows (important for some OAuth flows)
            WebView newWebView = new WebView(MainActivity.this);
            newWebView.setWebViewClient(new MyWebViewClient());
            newWebView.setWebChromeClient(new MyWebChromeClient());

            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(newWebView);
            resultMsg.sendToTarget();

            return true;
        }

        // Handle file upload for Android 5.0+
        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                         FileChooserParams fileChooserParams) {

            // Store the callback for later use
            MainActivity.this.filePathCallback = filePathCallback;

            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);

            // Check if the web page specifies accepted file types
            String[] acceptTypes = fileChooserParams.getAcceptTypes();
            if (acceptTypes.length > 0 && !acceptTypes[0].isEmpty()) {
                intent.setType(acceptTypes[0]);
            } else {
                intent.setType("*/*"); // Allow all file types
            }

            // Handle multiple file selection
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