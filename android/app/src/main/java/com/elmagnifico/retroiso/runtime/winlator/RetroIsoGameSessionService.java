package com.elmagnifico.retroiso.runtime.winlator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.elmagnifico.retroiso.MainActivity;

/**
 * Foreground notification while the user is playing an emulated Windows game.
 * This makes the game process less likely to be reclaimed when the user briefly
 * switches Android apps. It cannot guarantee survival during OOM or force-stop.
 *
 * START_NOT_STICKY deliberately avoids starting an empty Wine session when the
 * Android system has already terminated the game process.
 */
public final class RetroIsoGameSessionService extends Service {
    private static final String TAG = "RetroIsoSession";
    private static final String CHANNEL_ID = "retroiso_game_session";
    private static final int NOTIFICATION_ID = 1842;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager manager =
            (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Partie Retro ISO", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Conserver une partie ouverte lors d'un changement d'application");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent backToApp = PendingIntent.getActivity(this, 0, open, pendingFlags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Notification notification = builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Retro ISO — partie en cours")
            .setContentText("Jeu conservé actif pendant un changement d'application")
            .setContentIntent(backToApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build();
        try {
            startForeground(NOTIFICATION_ID, notification);
        }
        catch (RuntimeException ex) {
            Log.w(TAG, "Foreground session not available; game remains playable", ex);
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
