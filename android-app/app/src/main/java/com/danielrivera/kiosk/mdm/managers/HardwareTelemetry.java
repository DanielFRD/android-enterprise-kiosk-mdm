package com.danielrivera.kiosk.mdm.managers;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.danielrivera.kiosk.mdm.KioskDeviceAdminReceiver;

import java.lang.reflect.Method;

/**
 * Extracts low-level hardware identifiers (MAC Address and Serial Number)
 * across varied OEM tablet brands and Android API levels.
 */
public class HardwareTelemetry {
    private static final String TAG = "HardwareTelemetry";

    /**
     * Reads Wi-Fi MAC Address directly using Device Policy Manager (requires Device Owner)
     * avoiding modern Android privacy randomization blocks.
     */
    public static String getMacAddress(Context context) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = KioskDeviceAdminReceiver.getComponentName(context);
            if (dpm != null && dpm.isDeviceOwnerApp(context.getPackageName())) {
                String mac = dpm.getWifiMacAddress(admin);
                if (mac != null && !mac.isEmpty()) {
                    return mac.toUpperCase();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to read MAC from DevicePolicyManager: " + e.getMessage());
        }
        return "02:00:00:00:00:00";
    }

    /**
     * Resolves the hardware serial number using multiple reflection fallbacks
     * across Mediatek, Qualcomm, Allwinner, and Rockchip OEM implementations.
     */
    public static String getSerialNumber() {
        String serialNumber = "";

        // 1. Direct modern Build.getSerial() if permitted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                serialNumber = Build.getSerial();
                if (isValidSerial(serialNumber)) return serialNumber;
            } catch (SecurityException ignored) {}
        }

        // 2. Fallback to reflection on hidden android.os.SystemProperties keys
        String[] propertyKeys = {
                "gsm.sn1",
                "ril.serialnumber",
                "ro.serialno",
                "sys.serialnumber",
                "ro.boot.serialno",
                "ro.kernel.androidboot.serialno"
        };

        try {
            Class<?> systemPropertiesClass = Class.forName("android.os.SystemProperties");
            Method getMethod = systemPropertiesClass.getMethod("get", String.class);

            for (String key : propertyKeys) {
                String val = (String) getMethod.invoke(systemPropertiesClass, key);
                if (isValidSerial(val)) {
                    return val;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Reflection for serial number failed: " + e.getMessage());
        }

        // 3. Fallback to legacy Build.SERIAL
        if (isValidSerial(Build.SERIAL)) {
            return Build.SERIAL;
        }

        return "UNKNOWN_SERIAL";
    }

    private static boolean isValidSerial(String s) {
        return s != null && !s.trim().isEmpty() && !"unknown".equalsIgnoreCase(s.trim());
    }
}
