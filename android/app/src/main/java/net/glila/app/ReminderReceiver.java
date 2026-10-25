package net.glila.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;

import java.util.Calendar;

/**
 * The daily reminder. The page chooses the time and the text for each weekday
 * (see MainActivity's setReminder); this shows it and sets the alarm for the next day.
 * It also sets the alarm again after the phone restarts or the app is updated.
 */
public class ReminderReceiver extends BroadcastReceiver {
    static final String PREFS = "reminder";
    private static final String ACTION_REMIND = "net.glila.app.REMIND";
    private static final String CHANNEL = "daily";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_REMIND.equals(intent.getAction())) show(context);
        schedule(context);
    }

    static void schedule(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, ReminderReceiver.class).setAction(ACTION_REMIND);
        PendingIntent pending = PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarms.cancel(pending);
        if (!p.getBoolean("on", false)) return;

        Calendar at = Calendar.getInstance();
        at.set(Calendar.HOUR_OF_DAY, p.getInt("hour", 20));
        at.set(Calendar.MINUTE, p.getInt("minute", 0));
        at.set(Calendar.SECOND, 0);
        at.set(Calendar.MILLISECOND, 0);
        if (!at.after(Calendar.getInstance())) at.add(Calendar.DAY_OF_YEAR, 1);
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.getTimeInMillis(), pending);
    }

    private static void show(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean("on", false)) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                p.getString("channel", "Daily reminder"), NotificationManager.IMPORTANCE_DEFAULT));
        }
        // Calendar's Sunday is 1; the page's is 0
        int day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1;
        String text = "";
        try {
            text = new JSONArray(p.getString("lines", "[]")).optString(day, "");
        } catch (Exception e) {
            // keep the title alone
        }
        Intent open = new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(context, 1, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(context, CHANNEL)
            : new Notification.Builder(context);
        b.setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(p.getString("title", "Glila"))
            .setContentText(text)
            .setContentIntent(tap)
            .setAutoCancel(true);
        nm.notify(1, b.build());
    }
}
