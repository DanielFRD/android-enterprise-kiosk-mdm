package com.danielrivera.kiosk.mdm.managers;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;

import com.danielrivera.kiosk.mdm.KioskDeviceAdminReceiver;

/**
 * Manages Android Enterprise Lockdown Policies, LockTask (COSU),
 * Keyguard suppression and User Restrictions.
 */
public class KioskPolicyManager {
    private static final String TAG = "KioskPolicyManager";

    private final Activity activity;
    private final DevicePolicyManager dpm;
    private final ComponentName adminComponent;
    private final String packageName;

    public KioskPolicyManager(Activity activity) {
        this.activity = activity;
        this.dpm = (DevicePolicyManager) activity.getSystemService(Context.DEVICE_POLICY_SERVICE);
        this.adminComponent = KioskDeviceAdminReceiver.getComponentName(activity);
        this.packageName = activity.getPackageName();
    }

    public boolean isDeviceOwner() {
        return dpm != null && dpm.isDeviceOwnerApp(packageName);
    }

    /**
     * Applies complete COSU (Corporate-Owned Single-Use) Kiosk lockdown.
     */
    public void applyKioskLockdown(boolean enable) {
        setImmersiveMode(enable);

        if (isDeviceOwner()) {
            setLockTaskPackages(enable);
            setKeyguardAndStatusBar(enable);
            setAsDefaultHomeLauncher(enable);
            enableStayOnWhilePluggedIn(enable);
            setUserHardwareRestrictions(enable);
        } else {
            Log.w(TAG, "Application is not provisioned as Device Owner. Running with limited lockdown.");
        }
    }

    /**
     * Fullscreen Sticky Immersive Mode (Hides Navigation Bar and Status Bar)
     */
    public void setImmersiveMode(boolean enable) {
        View decorView = activity.getWindow().getDecorView();
        if (enable) {
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_IMMERSIVE);
        } else {
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    /**
     * LockTask Mode (Pins the app to screen, disables Home/Recents)
     */
    public void setLockTaskPackages(boolean enable) {
        try {
            if (enable) {
                dpm.setLockTaskPackages(adminComponent, new String[]{packageName});
                activity.startLockTask();
            } else {
                activity.stopLockTask();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error toggling LockTask mode", e);
        }
    }

    /**
     * Disables lockscreen, notification shade pulldown and keyguard.
     */
    public void setKeyguardAndStatusBar(boolean disable) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dpm.setKeyguardDisabled(adminComponent, disable);
                dpm.setStatusBarDisabled(adminComponent, disable);
            }
            dpm.setKeyguardDisabledFeatures(adminComponent,
                    DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_ALL);
        } catch (Exception e) {
            Log.e(TAG, "Error configuring keyguard and status bar", e);
        }
    }

    /**
     * Sets this application as the persistent, unprompted Home Launcher.
     */
    public void setAsDefaultHomeLauncher(boolean enable) {
        try {
            if (enable) {
                IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
                filter.addCategory(Intent.CATEGORY_HOME);
                filter.addCategory(Intent.CATEGORY_DEFAULT);
                ComponentName activityComponent = new ComponentName(packageName, activity.getClass().getName());
                dpm.addPersistentPreferredActivity(adminComponent, filter, activityComponent);
            } else {
                dpm.clearPackagePersistentPreferredActivities(adminComponent, packageName);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error configuring default home launcher", e);
        }
    }

    /**
     * Prevents tablets in commercial kiosks from sleeping when connected to power.
     */
    public void enableStayOnWhilePluggedIn(boolean active) {
        try {
            int plugModes = BatteryManager.BATTERY_PLUGGED_AC
                    | BatteryManager.BATTERY_PLUGGED_USB
                    | BatteryManager.BATTERY_PLUGGED_WIRELESS;

            dpm.setGlobalSetting(adminComponent,
                    Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                    active ? Integer.toString(plugModes) : "0");
        } catch (Exception e) {
            Log.e(TAG, "Error setting stay-on policy", e);
        }
    }

    /**
     * Enforces strict tamper-proofing restrictions on hardware features.
     */
    public void setUserHardwareRestrictions(boolean disallow) {
        try {
            setRestriction(UserManager.DISALLOW_SAFE_BOOT, disallow);
            setRestriction(UserManager.DISALLOW_FACTORY_RESET, disallow);
            setRestriction(UserManager.DISALLOW_ADD_USER, disallow);
            setRestriction(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA, disallow);
            setRestriction(UserManager.DISALLOW_ADJUST_VOLUME, disallow);
            setRestriction(UserManager.DISALLOW_CONFIG_WIFI, disallow);
            setRestriction(UserManager.DISALLOW_APPS_CONTROL, disallow);
            setRestriction(UserManager.DISALLOW_CONFIG_BLUETOOTH, disallow);
            setRestriction(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS, disallow);
            setRestriction(UserManager.DISALLOW_OUTGOING_CALLS, disallow);
            setRestriction(UserManager.DISALLOW_SMS, disallow);
            setRestriction(UserManager.DISALLOW_UNINSTALL_APPS, disallow);
            // Allow installing updates silently without blocking unknown sources
            setRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, false);
        } catch (Exception e) {
            Log.e(TAG, "Error setting user restrictions", e);
        }
    }

    private void setRestriction(String restriction, boolean disallow) {
        if (disallow) {
            dpm.addUserRestriction(adminComponent, restriction);
        } else {
            dpm.clearUserRestriction(adminComponent, restriction);
        }
    }
}
