package com.chaimate.dia;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.media.AudioAttributes;
import android.net.Uri;

import androidx.core.app.NotificationCompat;

public class NotificationHelper {
    private static final String CHANNEL_ID = "dia_notification_channel";
    private static final String CHANNEL_NAME = "DIA Notifications";
    private static final String CHANNEL_DESCRIPTION = "Notifications for DIA app with heads-up display";
    private static final int NOTIFICATION_ID = 1001; // Fixed ID for updates
    private static final String TAG = "NotificationHelper";

    private final Context context;
    private final NotificationManager notificationManager;
    private final Handler handler = new Handler();

    public NotificationHelper(Context context) {
        this.context = context;
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            NotificationChannel existingChannel = notificationManager.getNotificationChannel(CHANNEL_ID);

            if (existingChannel == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_HIGH // Required for heads-up
                );
                channel.setDescription(CHANNEL_DESCRIPTION);
                channel.enableVibration(true);
                channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                channel.enableLights(true);
                channel.setLightColor(android.graphics.Color.BLUE);

                // Custom sound (optional)
                Uri soundUri = android.provider.Settings.System.DEFAULT_NOTIFICATION_URI;
                AudioAttributes audioAttributes = new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build();
                channel.setSound(soundUri, audioAttributes);

                notificationManager.createNotificationChannel(channel);
                Log.d(TAG, "Notification channel created: " + CHANNEL_ID);
            } else {
                Log.d(TAG, "Channel already exists: " + CHANNEL_ID + " | Importance: " + existingChannel.getImportance());
            }
        }
    }

    /**
     * Shows a heads-up notification for ~2 seconds, then re-posts it quietly.
     */
    public void showHeadsUpNotification(String title, String message, Class<?> activityClass) {
        PendingIntent pendingIntent = getContentIntent(activityClass);

        // Step 1: Show heads-up notification
        NotificationCompat.Builder builder = getBaseBuilder(title, message, pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_MAX) // For pre-Oreo
                .setCategory(NotificationCompat.CATEGORY_MESSAGE);

        notificationManager.notify(NOTIFICATION_ID, builder.build());

        // Step 2: After 2 seconds, re-post quietly so it stays in shade
        handler.postDelayed(() -> {
            NotificationCompat.Builder quietBuilder = getBaseBuilder(title, message, pendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT) // Lower priority
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // For Oreo+, you can't lower importance at runtime — so this just refreshes it quietly
                notificationManager.notify(NOTIFICATION_ID, quietBuilder.build());
            } else {
                notificationManager.notify(NOTIFICATION_ID, quietBuilder.build());
            }

            Log.d(TAG, "Heads-up collapsed to shade");
        }, 2000);
    }

    private PendingIntent getContentIntent(Class<?> activityClass) {
        Intent intent;
        if (activityClass != null) {
            intent = new Intent(context, activityClass);
        } else {
            intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        }

        if (intent != null) {
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        } else {
            intent = new Intent(); // Fallback empty intent
        }

        return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private NotificationCompat.Builder getBaseBuilder(String title, String message, PendingIntent pendingIntent) {
        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info) // Replace with your app icon
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setDefaults(NotificationCompat.DEFAULT_ALL);
    }

    public void cancelNotification() {
        if (notificationManager != null) {
            notificationManager.cancel(NOTIFICATION_ID);
        }
    }
}
