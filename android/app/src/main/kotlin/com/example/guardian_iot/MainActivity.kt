package com.example.guardian_iot

import android.content.Intent
import android.os.Build
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
                        putExtra(GuardianMonitorService.EXTRA_NAME, call.argument<String>("name") ?: "")
                        putExtra(GuardianMonitorService.EXTRA_PHONE, call.argument<String>("phone") ?: "")
                        putExtra(GuardianMonitorService.EXTRA_AUTOMATIC, call.argument<Boolean>("automatic") ?: false)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
                    result.success(true)
                }
                "stopMonitoring" -> {
                    startService(Intent(this, GuardianMonitorService::class.java).setAction(GuardianMonitorService.ACTION_STOP))
                    result.success(true)
                }
                "isMonitoring" -> result.success(GuardianMonitorService.isRunning)
                else -> result.notImplemented()
            }
        }
    }
}
