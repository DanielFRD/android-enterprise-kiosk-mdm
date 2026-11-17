package com.danielrivera.kiosk.mdm.managers;

import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Handles Over-The-Air (OTA) APK download and silent background installation
 * leveraging Android Device Owner privileges via PackageInstaller Session.
 */
public class SilentUpdateReceiver extends BroadcastReceiver {
    private static final String TAG = "SilentUpdateReceiver";
    public static final String ACTION_INSTALL_COMPLETE = "com.danielrivera.kiosk.mdm.INSTALL_COMPLETE";

    private final Context context;
    private DownloadManager downloadManager;
    private long downloadId = -1;
    private final IntentFilter intentFilter;
    private boolean isRegistered = false;

    public SilentUpdateReceiver(Context context) {
        this.context = context;
        this.intentFilter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
    }

    public synchronized void register() {
        if (!isRegistered) {
            context.registerReceiver(this, intentFilter);
            isRegistered = true;
        }
    }

    public synchronized void unregister() {
        if (isRegistered) {
            try {
                context.unregisterReceiver(this);
            } catch (IllegalArgumentException ignored) {}
            isRegistered = false;
        }
    }

    /**
     * Enqueues an APK download from the designated OTA repository endpoint.
     */
    public void startDownload(String apkDownloadUrl) {
        try {
            register();
            downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);

            Uri uri = Uri.parse(apkDownloadUrl);
            DownloadManager.Request request = new DownloadManager.Request(uri);
            String extension = MimeTypeMap.getFileExtensionFromUrl(apkDownloadUrl);
            String filename = URLUtil.guessFileName(apkDownloadUrl, null, extension);

            request.setTitle("Actualización Kiosko");
            request.setDescription("Descargando nueva versión del sistema...");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);

            downloadId = downloadManager.enqueue(request);
            Log.i(TAG, "Enqueued APK download: " + apkDownloadUrl + " (ID: " + downloadId + ")");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start APK download", e);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {
            long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (completedId != -1 && completedId == downloadId) {
                Log.i(TAG, "Download completed for ID: " + completedId + ". Commencing silent installation...");
                installApkSilently(completedId);
            }
        }
    }

    /**
     * Silently commits the APK to the system PackageInstaller without prompting the user.
     * Requires the application to be running as Device Owner.
     */
    private void installApkSilently(long id) {
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(id);

        try (Cursor cursor = downloadManager.query(query)) {
            if (cursor != null && cursor.moveToFirst()) {
                int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                if (statusIndex != -1 && cursor.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL) {
                    int uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
                    if (uriIndex != -1) {
                        String fileUri = cursor.getString(uriIndex);
                        File apkFile = new File(Uri.parse(fileUri).getPath());

                        if (apkFile.exists()) {
                            performPackageInstallerCommit(apkFile);
                        } else {
                            Log.e(TAG, "Downloaded file not found on disk: " + fileUri);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Silent install transaction failed", e);
        }
    }

    private void performPackageInstallerCommit(File apkFile) {
        try {
            PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(context.getPackageName());

            int sessionId = packageInstaller.createSession(params);
            try (PackageInstaller.Session session = packageInstaller.openSession(sessionId);
                 InputStream in = new FileInputStream(apkFile);
                 OutputStream out = session.openWrite("COSU_OTA", 0, -1)) {

                byte[] buffer = new byte[65536];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                session.fsync(out);

                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    flags |= PendingIntent.FLAG_MUTABLE;
                }

                Intent broadcastIntent = new Intent(ACTION_INSTALL_COMPLETE);
                PendingIntent pendingIntent = PendingIntent.getBroadcast(context, sessionId, broadcastIntent, flags);
                IntentSender intentSender = pendingIntent.getIntentSender();

                // Commit session: under Device Owner, this completes silently in background
                session.commit(intentSender);
                Log.i(TAG, "PackageInstaller session " + sessionId + " committed successfully.");
            }
        } catch (Exception e) {
            Log.e(TAG, "PackageInstaller session commit error", e);
        }
    }
}
