package com.example.guardian_iot

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val channelName = "guardian_iot/monitor"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName).setMethodCallHandler { call, result ->
            when (call.method) {
                "startMonitoring" -> {
                    val intent = Intent(this, GuardianMonitorService::class.java).apply {
                        action = GuardianMonitorService.ACTION_START
                        putExtra(GuardianMonitorService.EXTRA_CONTACTS, call.argument<String>("contactsJson") ?: "[]")
                        putExtra(GuardianMonitorService.EXTRA_AUTOMATIC, call.argument<Boolean>("automatic") ?: false)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
                    result.success(true)
                }
                "stopMonitoring" -> {
                    startService(Intent(this, GuardianMonitorService::class.java).setAction(GuardianMonitorService.ACTION_STOP))
                    result.success(true)
                }
                "cancelAlert" -> {
                    startService(Intent(this, GuardianMonitorService::class.java).setAction(GuardianMonitorService.ACTION_CANCEL_ALERT))
                    result.success(true)
                }
                "ensureFullScreenIntent" -> {
                    if (Build.VERSION.SDK_INT >= 34) {
                        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                        if (!manager.canUseFullScreenIntent()) {
                            try {
                                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                            } catch (_: Exception) { }
                            result.success(false)
                        } else result.success(true)
                    } else result.success(true)
                }
                "isMonitoring" -> result.success(GuardianMonitorService.isRunning)
                "alertPermissionStatus" -> result.success(mapOf(
                    "sms" to (ContextCompat.checkSelfPermission(this, android.Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED),
                    "call" to (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED),
                    "phoneState" to (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED),
                ))
                else -> result.notImplemented()
            }
        }
    }
}
