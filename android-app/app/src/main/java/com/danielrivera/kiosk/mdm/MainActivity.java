package com.danielrivera.kiosk.mdm;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.danielrivera.kiosk.mdm.managers.HardwareTelemetry;
import com.danielrivera.kiosk.mdm.managers.KioskPolicyManager;
import com.danielrivera.kiosk.mdm.managers.SilentUpdateReceiver;
import com.danielrivera.kiosk.mdm.managers.WifiEnforcerManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Enterprise Kiosk Main Controller Activity
 * Manages Fullscreen Immersive LockTask, Enterprise WebView,
 * Wi-Fi State Enforcer, Hardware Telemetry, and Silent OTA Updates.
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 1001;

    // Configurable Remote Endpoints (Can be overridden via Server Handshake)
    private String serverConfigEndpoint = "http://10.0.2.2:5000/api/v1/device/handshake"; // Android emulator localhost default
    private String defaultSplashUrl = "http://10.0.2.2:5000/portal";
    private String defaultSsid = "ENTERPRISE_KIOSK_WIFI";

    // Managers
    private KioskPolicyManager policyManager;
    private WifiEnforcerManager wifiManager;
    private SilentUpdateReceiver silentUpdateReceiver;

    // UI & State
    private WebView webView;
    private Menu toolbarMenu;
    private MenuItem batteryMenuItem;
    private int currentVersionCode = 1;
    private String currentTargetUrl = defaultSplashUrl;

    private final String[] runtimePermissions = {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
    };

    // Real-time Battery Status Receiver
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateBatteryIndicator(intent);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Hardware Fullscreen configuration
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Resolve installed version
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            currentVersionCode = pInfo.versionCode;
        } catch (Exception ignored) {}

        // Initialize Specialized Managers
        policyManager = new KioskPolicyManager(this);
        wifiManager = new WifiEnforcerManager(this, defaultSsid);
        silentUpdateReceiver = new SilentUpdateReceiver(this);

        // Apply Device Owner / LockTask Kiosk Lockdown
        policyManager.applyKioskLockdown(true);

        // Enforce Corporate Wi-Fi connection
        wifiManager.enforceConnection();

        // Setup Enterprise WebView
        setupEnterpriseWebView();

        // Request runtime permissions if needed
        checkAndRequestPermissions();

        // Perform Server Handshake (Loads URL and checks for silent OTA updates)
        fetchRemoteConfiguration();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupEnterpriseWebView() {
        webView = findViewById(R.id.webview);
        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(false);
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true);
        }

        webView.setBackgroundColor(Color.WHITE);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Log.w(TAG, "WebView error encountered: " + description + ". Loading offline fallback.");
                view.loadUrl("file:///android_asset/html/error.html");
                super.onReceivedError(view, errorCode, description, failingUrl);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // Auto-grant WebRTC (Camera/Microphone) permissions inside enterprise kiosk
                request.grant(request.getResources());
            }
        });

        webView.loadUrl(currentTargetUrl);
    }

    /**
     * Handshake with remote server: submits device telemetry (MAC, serial, version)
     * and receives updated URL and OTA update manifest.
     */
    private void fetchRemoteConfiguration() {
        new Thread(() -> {
            try {
                String mac = HardwareTelemetry.getMacAddress(MainActivity.this);
                String serial = HardwareTelemetry.getSerialNumber();

                String endpoint = serverConfigEndpoint + "?mac=" + Uri.encode(mac)
                        + "&serial=" + Uri.encode(serial)
                        + "&version=" + currentVersionCode;

                URL url = new URL(endpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.connect();

                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    InputStream in = conn.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(in));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }

                    JSONObject json = new JSONObject(response.toString());
                    final String remoteUrl = json.optString("url", currentTargetUrl);
                    final int latestVersion = json.optInt("latestVersion", currentVersionCode);
                    final String apkDownloadUrl = json.optString("apkDownloadUrl", "");

                    runOnUiThread(() -> {
                        currentTargetUrl = remoteUrl;
                        webView.loadUrl(currentTargetUrl);

                        // If a higher version is published on the server, trigger silent background update
                        if (latestVersion > currentVersionCode && !apkDownloadUrl.isEmpty()) {
                            Log.i(TAG, "New OTA version available: v" + latestVersion + ". Initiating silent download...");
                            Toast.makeText(MainActivity.this, "Descargando actualización en segundo plano...", Toast.LENGTH_SHORT).show();
                            silentUpdateReceiver.startDownload(apkDownloadUrl);
                        }
                    });
                }
                conn.disconnect();
            } catch (Exception e) {
                Log.w(TAG, "Server handshake offline or unreachable: " + e.getMessage());
            }
        }).start();
    }

    // ==============================================================================
    // ACTION BAR & HARDWARE TELEMETRY CONTROLS
    // ==============================================================================

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        this.toolbarMenu = menu;
        this.batteryMenuItem = menu.findItem(R.id.nivelBateria);

        updateNetworkStatusUI();
        registerBatteryReceiver();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.recargarWebview) {
            webView.clearCache(true);
            webView.loadUrl(currentTargetUrl);
            fetchRemoteConfiguration();
            updateNetworkStatusUI();
            return true;
        }

        if (id == R.id.reconectarWifi) {
            wifiManager.enforceConnection();
            updateNetworkStatusUI();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void updateNetworkStatusUI() {
        if (toolbarMenu == null) return;

        MenuItem wifiItem = toolbarMenu.findItem(R.id.redWifi);
        MenuItem reconnectItem = toolbarMenu.findItem(R.id.reconectarWifi);

        boolean isConnected = wifiManager.isInternetAvailable();
        String ssid = wifiManager.getConnectedSSID();

        if (wifiItem != null) wifiItem.setTitle(" " + ssid);
        if (reconnectItem != null) reconnectItem.setVisible(!isConnected);
    }

    private void updateBatteryIndicator(Intent intent) {
        if (batteryMenuItem == null) return;

        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);

        boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;

        int pct = 100;
        if (level >= 0 && scale > 0) {
            pct = (int) ((level / (float) scale) * 100f);
        }

        if (isCharging) {
            batteryMenuItem.setIcon(R.drawable.battery_cargando);
        } else {
            if (pct > 75) batteryMenuItem.setIcon(R.drawable.batery100);
            else if (pct > 50) batteryMenuItem.setIcon(R.drawable.batery75);
            else if (pct > 25) batteryMenuItem.setIcon(R.drawable.batery50);
            else batteryMenuItem.setIcon(R.drawable.batery25);
        }

        batteryMenuItem.setTitle(" " + pct + "%");
    }

    private void registerBatteryReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(batteryReceiver, filter);
    }

    private void checkAndRequestPermissions() {
        boolean needed = false;
        for (String perm : runtimePermissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                needed = true;
                break;
            }
        }
        if (needed) {
            ActivityCompat.requestPermissions(this, runtimePermissions, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        policyManager.setImmersiveMode(true);
        silentUpdateReceiver.register();
    }

    @Override
    protected void onPause() {
        super.onPause();
        silentUpdateReceiver.unregister();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception ignored) {}
    }
}
