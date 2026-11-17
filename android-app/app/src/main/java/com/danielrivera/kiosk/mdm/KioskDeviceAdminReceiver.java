package com.danielrivera.kiosk.mdm;

import android.app.admin.DeviceAdminReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;

/**
 * Enterprise Device Admin Receiver
 * Entry point for Android Device Owner (MDM) privileges.
 */
public class KioskDeviceAdminReceiver extends DeviceAdminReceiver {
    private static final String TAG = "KioskDeviceAdmin";

    public static ComponentName getComponentName(Context context) {
        return new ComponentName(context.getApplicationContext(), KioskDeviceAdminReceiver.class);
    }

    @Override
    public void onEnabled(@NonNull Context context, @NonNull Intent intent) {
        super.onEnabled(context, intent);
        Log.i(TAG, "Device Owner privileges granted to Kiosk Agent.");
    }

    @Override
    public void onDisabled(@NonNull Context context, @NonNull Intent intent) {
        super.onDisabled(context, intent);
        Log.w(TAG, "Device Owner privileges revoked.");
    }

    @Override
    public void onLockTaskModeEntering(@NonNull Context context, @NonNull Intent intent, @NonNull String pkg) {
        super.onLockTaskModeEntering(context, intent, pkg);
        Log.i(TAG, "LockTask Mode Entered (Kiosk Mode Active) for package: " + pkg);
    }

    @Override
    public void onLockTaskModeExiting(@NonNull Context context, @NonNull Intent intent) {
        super.onLockTaskModeExiting(context, intent);
        Log.w(TAG, "LockTask Mode Exited.");
    }
}
