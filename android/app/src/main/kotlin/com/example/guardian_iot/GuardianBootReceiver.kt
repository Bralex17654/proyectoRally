package com.example.guardian_iot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

class GuardianBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED && intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = context.getSharedPreferences(GuardianMonitorService.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(GuardianMonitorService.PREF_MONITOR_ENABLED, false)) return
        val serviceIntent = Intent(context, GuardianMonitorService::class.java).setAction(GuardianMonitorService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(context, serviceIntent)
        else context.startService(serviceIntent)
    }
}
