package com.danielrivera.kiosk.mdm.managers;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.util.List;

/**
 * Manages Wi-Fi state and forces reconnection to the authorized corporate SSID.
 */
public class WifiEnforcerManager {
    private static final String TAG = "WifiEnforcer";

    private final Context context;
    private final WifiManager wifiManager;
    private final ConnectivityManager connectivityManager;
    private String targetSSID = "ENTERPRISE_KIOSK_WIFI";

    public WifiEnforcerManager(Context context, String defaultSSID) {
        this.context = context.getApplicationContext();
        this.wifiManager = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
        this.connectivityManager = (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (defaultSSID != null && !defaultSSID.trim().isEmpty()) {
            this.targetSSID = defaultSSID;
        }
    }

    public void setTargetSSID(String ssid) {
        this.targetSSID = ssid;
    }

    public String getTargetSSID() {
        return this.targetSSID;
    }

    public boolean isInternetAvailable() {
        if (connectivityManager == null) return false;
        NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnected();
    }

    public boolean isWifiConnected() {
        if (connectivityManager == null) return false;
        NetworkInfo wifiInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        return wifiInfo != null && wifiInfo.isConnected();
    }

    public String getConnectedSSID() {
        if (wifiManager == null || !isWifiConnected()) {
            return "- Sin Conexión Wi-Fi -";
        }
        WifiInfo info = wifiManager.getConnectionInfo();
        String ssid = info != null ? info.getSSID() : "";
        if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }
        return (ssid == null || ssid.isEmpty() || "<unknown ssid>".equals(ssid)) ? "- Red Desconocida -" : ssid;
    }

    /**
     * Programmatically forces the device Wi-Fi radio ON and connects to the target SSID.
     */
    public boolean enforceConnection() {
        try {
            if (wifiManager == null) return false;

            if (!wifiManager.isWifiEnabled()) {
                Log.i(TAG, "Enabling Wi-Fi adapter...");
                wifiManager.setWifiEnabled(true);
            }

            WifiConfiguration conf = new WifiConfiguration();
            conf.SSID = "\"" + targetSSID + "\"";
            conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);

            int netId = wifiManager.addNetwork(conf);
            if (netId == -1) {
                // Network already configured, locate its ID
                List<WifiConfiguration> list = wifiManager.getConfiguredNetworks();
                if (list != null) {
                    for (WifiConfiguration i : list) {
                        if (i.SSID != null && i.SSID.equals("\"" + targetSSID + "\"")) {
                            netId = i.networkId;
                            break;
                        }
                    }
                }
            }

            if (netId != -1) {
                wifiManager.disconnect();
                wifiManager.enableNetwork(netId, true);
                wifiManager.reconnect();
                Log.i(TAG, "Connecting to SSID: " + targetSSID + " (NetID: " + netId + ")");
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error enforcing Wi-Fi connection", e);
        }
        return false;
    }
}
