package de.wallkeys;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

/**
 * Selbstheilung: Stürzt ein AccessibilityService ab, markiert Android ihn als "crashed" und
 * bindet ihn nicht mehr neu – WallKeys wäre bis zur manuellen Reaktivierung tot.
 *
 * Dieser Job läuft alle 15 Minuten (auch nach einem Neustart) und aktiviert den Dienst wieder,
 * falls er nicht läuft. Benötigt einmalig per adb:
 *   adb shell pm grant de.wallkeys android.permission.WRITE_SECURE_SETTINGS
 */
public class Watchdog extends JobService {
    private static final int JOB_ID = 4711;
    static final String SERVICE = "de.wallkeys/de.wallkeys.KeyService";

    @Override
    public boolean onStartJob(JobParameters params) {
        check(this);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }

    static void schedule(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, Watchdog.class))
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build();
        js.schedule(job);
    }

    /** Aktiviert den Dienst neu, wenn er nicht läuft. */
    static void check(Context ctx) {
        if (KeyService.running) return;
        ContentResolver cr = ctx.getContentResolver();
        try {
            String list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            StringBuilder others = new StringBuilder();
            if (list != null) {
                for (String s : list.split(":")) {
                    if (s.isEmpty() || s.equals(SERVICE) || s.equals("de.wallkeys/.KeyService")) continue;
                    if (others.length() > 0) others.append(':');
                    others.append(s);
                }
            }
            // Erst entfernen, dann wieder hinzufügen – setzt den "crashed"-Zustand zurück
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.toString());
            String with = others.length() > 0 ? others + ":" + SERVICE : SERVICE;
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, with);
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            android.util.Log.w("WallKeys", "Watchdog: Dienst lief nicht und wurde neu aktiviert");
        } catch (SecurityException e) {
            android.util.Log.w("WallKeys", "Watchdog: WRITE_SECURE_SETTINGS fehlt – "
                    + "adb shell pm grant de.wallkeys android.permission.WRITE_SECURE_SETTINGS");
        }
    }
}
