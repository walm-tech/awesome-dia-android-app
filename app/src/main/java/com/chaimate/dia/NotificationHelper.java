package com.chaimate.dia;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class NotificationHelper {
    private static final String CHANNEL_ID = "dia_notification_channel";
    private static final String CHANNEL_NAME = "DIA Notifications";
    private static final String CHANNEL_DESCRIPTION = "Notifications for DIA app";
    private static final int NOTIFICATION_ID = 1;
    private static final String TAG = "NotificationHelper";

    private Context context;
    private NotificationManager notificationManager;

    public NotificationHelper(Context context) {
        try {
            this.context = context;
            this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            createNotificationChannel();
        } catch (Exception e) {
            Log.e(TAG, "Error initializing NotificationHelper", e);
        }
    }

    private void createNotificationChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_DEFAULT
                );
                channel.setDescription(CHANNEL_DESCRIPTION);
                notificationManager.createNotificationChannel(channel);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creating notification channel", e);
        }
    }
}