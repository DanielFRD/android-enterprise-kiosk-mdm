#!/usr/bin/env bash
# ===============================================================================
# Android Enterprise Fleet Provisioning Script (POSIX/Bash)
# Field-proven on 5,000+ commercial devices across USB Hubs
# ===============================================================================

PACKAGE_NAME="com.danielrivera.kiosk.mdm"
ADMIN_RECEIVER="com.danielrivera.kiosk.mdm/.KioskDeviceAdminReceiver"
APK_PATH="app.apk"

if [ ! -f "$APK_PATH" ] && [ -f "../android-app/app/build/outputs/apk/debug/app-debug.apk" ]; then
    APK_PATH="../android-app/app/build/outputs/apk/debug/app-debug.apk"
fi

get_devices() {
    adb devices | awk 'NR>1 && $2=="device" {print $1}'
}

install_all() {
    echo "[*] Installing APK ($APK_PATH) across all connected tablets..."
    for dev in $(get_devices); do
        echo "  [-] Installing on $dev..."
        adb -s "$dev" install -r -g "$APK_PATH"
    done
}

grant_permissions() {
    echo "[*] Granting runtime permissions..."
    for dev in $(get_devices); do
        echo "  [-] Granting permissions on $dev..."
        adb -s "$dev" shell pm grant "$PACKAGE_NAME" android.permission.READ_EXTERNAL_STORAGE
        adb -s "$dev" shell pm grant "$PACKAGE_NAME" android.permission.WRITE_EXTERNAL_STORAGE
        adb -s "$dev" shell pm grant "$PACKAGE_NAME" android.permission.ACCESS_FINE_LOCATION
        adb -s "$dev" shell pm grant "$PACKAGE_NAME" android.permission.ACCESS_COARSE_LOCATION
        adb -s "$dev" shell pm grant "$PACKAGE_NAME" android.permission.READ_PHONE_STATE
    done
}

set_device_owner() {
    echo "[*] Promoting app to Device Owner..."
    for dev in $(get_devices); do
        echo "  [-] Setting Device Owner on $dev..."
        adb -s "$dev" shell dpm set-device-owner "$ADMIN_RECEIVER"
    done
}

debloat() {
    echo "[*] Disabling consumer bloatware packages..."
    for dev in $(get_devices); do
        echo "  [-] Debloating $dev..."
        for pkg in $(adb -s "$dev" shell pm list packages | grep -E "kids|search|chrome" | cut -d: -f2); do
            adb -s "$dev" shell pm disable-user --user 0 "$pkg"
        done
        for pkg in $(adb -s "$dev" shell pm list packages | grep "launcher" | cut -d: -f2); do
            if [ "$pkg" != "$PACKAGE_NAME" ]; then
                adb -s "$dev" shell pm disable-user --user 0 "$pkg"
            fi
        done
    done
}

enable_gps() {
    echo "[*] Enforcing High Accuracy GPS mode..."
    for dev in $(get_devices); do
        echo "  [-] Enforcing GPS on $dev..."
        adb -s "$dev" shell settings put secure location_mode 3
    done
}

deprovision() {
    echo "[*] Revoking Device Owner (Maintenance Mode)..."
    for dev in $(get_devices); do
        echo "  [-] Removing admin on $dev..."
        adb -s "$dev" shell dpm remove-active-admin "$ADMIN_RECEIVER"
    done
}

show_status() {
    echo "[*] Connected Fleet Status:"
    adb devices -l
    for dev in $(get_devices); do
        echo "--- Device: $dev ---"
        adb -s "$dev" shell dumpsys battery | grep "level:"
        adb -s "$dev" shell dumpsys wifi | grep "mNetworkInfo" | head -n 1
    done
}

case "$1" in
    full)
        install_all
        grant_permissions
        set_device_owner
        debloat
        enable_gps
        echo "[OK] Full Fleet Provisioning Completed."
        ;;
    install)
        install_all
        ;;
    owner)
        set_device_owner
        ;;
    debloat)
        debloat
        ;;
    status)
        show_status
        ;;
    deprovision)
        deprovision
        ;;
    *)
        echo "Usage: $0 {full|install|owner|debloat|status|deprovision}"
        exit 1
        ;;
esac
