package dev.notepin.poc;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;

/** Persistent intent is separate from service/process lifetime. */
public final class Automation {
  private Automation() {}
  public static boolean enabled(Context c) {
    return c.getSharedPreferences("automation", 0).getBoolean("enabled", false);
  }
  public static void setEnabled(Context c, boolean value) {
    if (!c.getSharedPreferences("automation", 0).edit().putBoolean("enabled", value).commit())
      throw new IllegalStateException("Could not persist automation preference");
  }
  private static void retireLegacyTransfers(Context c) {
    SharedPreferences prefs = c.getSharedPreferences("automation", 0);
    if (prefs.getBoolean("localAndStreamOnly", false)) return;
    JobScheduler jobs = c.getSystemService(JobScheduler.class);
    if (jobs != null) for (JobInfo job : jobs.getAllPendingJobs()) {
      ComponentName service = job.getService();
      if (service.getPackageName().equals(c.getPackageName())
          && service.getClassName().equals("androidx.work.impl.background.systemjob.SystemJobService"))
        jobs.cancel(job.getId());
    }
    if (!c.getSharedPreferences("uploads", 0).edit().clear().commit()
        || !c.getSharedPreferences("status", 0).edit().remove("upload").commit()
        || !prefs.edit().remove("wifiOnly").putBoolean("localAndStreamOnly", true).commit())
      throw new IllegalStateException("Could not retire legacy transfers");
  }
  public static void recover(Context c) {
    retireLegacyTransfers(c);
    if (!new java.io.File(c.getFilesDir(), "bootstrap.vault").exists()) return;
    if (!enabled(c)) return;
    if (c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        || c.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
      return;
    try {
      c.startForegroundService(new Intent(c, CollectorService.class));
    } catch (RuntimeException e) {
      c.getSharedPreferences("status", 0).edit().putString("text",
          "自動再開をOSが制限しました。アプリを開いて再開してください").apply();
    }
  }
}
