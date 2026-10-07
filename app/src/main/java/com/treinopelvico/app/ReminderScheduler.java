package com.treinopelvico.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Calendar;

public final class ReminderScheduler {
    static final String PREFS = "treino_native";
    static final int REQ_SESSION_1 = 101;
    static final int REQ_SESSION_2 = 102;

    private ReminderScheduler() {}

    static void updateConfig(Context c, boolean enabled, String time1, String time2,
                             int perDay, boolean discrete, String startDate,
                             String progressDate, int progressCount) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit()
                .putBoolean("enabled", enabled)
                .putString("time1", safeTime(time1, "08:00"))
                .putString("time2", safeTime(time2, "18:00"))
                .putInt("perDay", Math.max(1, Math.min(perDay, 2)))
                .putBoolean("discrete", discrete)
                .putString("startDate", startDate == null ? "" : startDate)
                .putString("progressDate", progressDate == null ? "" : progressDate)
                .putInt("progressCount", Math.max(0, progressCount))
                .apply();
        reschedule(c);
    }

    static void reschedule(Context c) {
        cancel(c, REQ_SESSION_1);
        cancel(c, REQ_SESSION_2);

        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean("enabled", false)) return;

        int target = currentTarget(p);
        if (target >= 1) scheduleOne(c, 1, p.getString("time1", "08:00"));
        if (target >= 2) scheduleOne(c, 2, p.getString("time2", "18:00"));
    }

    private static void scheduleOne(Context c, int sessionNo, String hhmm) {
        try {
            String[] x = safeTime(hhmm, sessionNo == 1 ? "08:00" : "18:00").split(":");
            int h = Integer.parseInt(x[0]);
            int m = Integer.parseInt(x[1]);

            Calendar when = Calendar.getInstance();
            when.set(Calendar.HOUR_OF_DAY, h);
            when.set(Calendar.MINUTE, m);
            when.set(Calendar.SECOND, 0);
            when.set(Calendar.MILLISECOND, 0);
            if (when.getTimeInMillis() <= System.currentTimeMillis()) {
                when.add(Calendar.DAY_OF_YEAR, 1);
            }

            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;

            long window = 10L * 60L * 1000L;
            am.setWindow(
                    AlarmManager.RTC_WAKEUP,
                    when.getTimeInMillis(),
                    window,
                    alarmIntent(c, sessionNo)
            );
        } catch (Exception ignored) {
        }
    }

    static boolean shouldNotify(Context c, int sessionNo) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean("enabled", false)) return false;
        if (sessionNo > currentTarget(p)) return false;

        String today = LocalDate.now().toString();
        if (today.equals(p.getString("progressDate", "")) &&
                p.getInt("progressCount", 0) >= sessionNo) {
            return false;
        }
        return true;
    }

    static boolean isDiscrete(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("discrete", true);
    }

    private static int currentTarget(SharedPreferences p) {
        try {
            String s = p.getString("startDate", "");
            if (s == null || s.isEmpty()) return p.getInt("perDay", 2);

            LocalDate start = LocalDate.parse(s);
            LocalDate now = LocalDate.now();
            long days = ChronoUnit.DAYS.between(start, now);

            if (days < 0 || days >= 84) return 0;
            int week = (int) (days / 7) + 1;
            return week <= 8 ? 2 : 1;
        } catch (Exception e) {
            return p.getInt("perDay", 2);
        }
    }

    private static String safeTime(String s, String def) {
        return s != null && s.matches("(?:[01]\\d|2[0-3]):[0-5]\\d") ? s : def;
    }

    private static PendingIntent alarmIntent(Context c, int sessionNo) {
        Intent i = new Intent(c, ReminderReceiver.class)
                .putExtra("sessionNo", sessionNo);
        int req = sessionNo == 1 ? REQ_SESSION_1 : REQ_SESSION_2;
        return PendingIntent.getBroadcast(
                c, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static void cancel(Context c, int req) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent i = new Intent(c, ReminderReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(
                c, req, i,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
        );
        if (pi != null) {
            am.cancel(pi);
            pi.cancel();
        }
    }
}
