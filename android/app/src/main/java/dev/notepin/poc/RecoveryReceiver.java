package dev.notepin.poc;
import android.content.*;
public final class RecoveryReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context context, Intent intent) {
    if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
        || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))
      Automation.recover(context);
  }
}
