package com.example.guardian_iot

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.SmsManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

class GuardianMonitorService : Service(), SensorEventListener {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var sensorManager: SensorManager
    private var impactAt = 0L
    private var lastTrigger = 0L
    private var peak = 0.0
    private var secondsLeft = 20
    private var alertPending = false
    private var contactName = ""
    private var contactPhone = ""
    private var automatic = false

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_STOP -> { stopMonitoring(); return START_NOT_STICKY }
            ACTION_CANCEL_ALERT -> { cancelAlert(); return START_STICKY }
            ACTION_START -> {
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                if (intent != null) {
                    contactName = intent.getStringExtra(EXTRA_NAME).orEmpty()
                    contactPhone = intent.getStringExtra(EXTRA_PHONE).orEmpty()
                    automatic = intent.getBooleanExtra(EXTRA_AUTOMATIC, false)
                    prefs.edit().putString(EXTRA_NAME, contactName).putString(EXTRA_PHONE, contactPhone)
                        .putBoolean(EXTRA_AUTOMATIC, automatic).apply()
                } else {
                    contactName = prefs.getString(EXTRA_NAME, "").orEmpty()
                    contactPhone = prefs.getString(EXTRA_PHONE, "").orEmpty()
                    automatic = prefs.getBoolean(EXTRA_AUTOMATIC, false)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification("Acelerómetro activo", false), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(NOTIFICATION_ID, notification("Acelerómetro activo", false))
                }
                isRunning = true
                val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
                    ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
                return START_STICKY
            }
        }
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent) {
        var magnitude = sqrt((event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2]).toDouble())
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) magnitude = kotlin.math.abs(magnitude - SensorManager.GRAVITY_EARTH)
        val now = System.currentTimeMillis()
        if (impactAt == 0L && magnitude > 25.0 && now - lastTrigger > COOLDOWN_MS) {
            impactAt = now
            peak = magnitude
            return
        }
        if (impactAt == 0L) return
        if (magnitude > peak) peak = magnitude
        val elapsed = now - impactAt
        if (elapsed > 9_000L) { impactAt = 0L; return }
        if (elapsed > 3_000L && magnitude < 2.2) {
            lastTrigger = now
            impactAt = 0L
            beginCountdown()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun beginCountdown() {
        if (alertPending) return
        alertPending = true
        secondsLeft = 20
        updateNotification("Posible caída · confirma en $secondsLeft s", true)
        handler.post(countdownTick)
    }

    private val countdownTick = object : Runnable {
        override fun run() {
            if (!alertPending) return
            secondsLeft--
            if (secondsLeft <= 0) {
                alertPending = false
                sendAlert()
            } else {
                updateNotification("Posible caída · confirma en $secondsLeft s", true)
                handler.postDelayed(this, 1_000L)
            }
        }
    }

    private fun cancelAlert() {
        if (!alertPending) return
        alertPending = false
        handler.removeCallbacks(countdownTick)
        updateNotification("Monitoreo activo · alerta cancelada", false)
    }

    private fun sendAlert() {
        if (!automatic || contactPhone.isBlank()) {
            updateNotification("Posible caída · abre Guardian IoT", false)
            return
        }
        val location = getLastLocationText()
        val greeting = if (contactName.isBlank()) "Hola" else "Hola $contactName"
        val body = "$greeting, Guardian IoT detectó un patrón compatible con una posible caída. Verifica cómo estoy. $location"
        var smsSent = false
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
                val parts = sms.divideMessage(body)
                sms.sendMultipartTextMessage(contactPhone, null, parts, null, null)
                smsSent = true
            }
        } catch (_: Exception) { }
        updateNotification(if (smsSent) "SMS solicitado · intentando llamada" else "No se pudo enviar SMS · intentando llamada", false)
        handler.postDelayed({ placeCall() }, 1_500L)
    }

    private fun placeCall() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                updateNotification("SMS solicitado · toca para abrir la llamada", false, true)
                return
            }
            val call = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", contactPhone, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(call)
            updateNotification("SMS solicitado · intento de llamada realizado", false, true)
        } catch (_: Exception) {
            updateNotification("SMS solicitado · Android requiere tocar para llamar", false, true)
        }
    }

    private fun getLastLocationText(): String {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return "Ubicación no disponible."
        return try {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            val location = manager.getProviders(true).asSequence().mapNotNull { manager.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
            if (location == null) "Ubicación no disponible." else "Ubicación aproximada: https://maps.google.com/?q=${location.latitude},${location.longitude}"
        } catch (_: Exception) { "Ubicación no disponible." }
    }

    private fun notification(text: String, cancelable: Boolean, offerCall: Boolean = false): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPending = openIntent?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Guardian IoT · monitoreo")
            .setContentText(text)
            .setContentIntent(contentPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (cancelable) {
            val cancelIntent = Intent(this, GuardianMonitorService::class.java).setAction(ACTION_CANCEL_ALERT)
            val pending = PendingIntent.getService(this, 2, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0, "Estoy bien", pending)
        }
        if (offerCall && contactPhone.isNotBlank()) {
            val callIntent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", contactPhone, null))
            val callPending = PendingIntent.getActivity(this, 3, callIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0, "Llamar", callPending)
        }
        return builder.build()
    }

    private fun updateNotification(text: String, cancelable: Boolean, offerCall: Boolean = false) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(text, cancelable, offerCall))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Monitoreo Guardian IoT", NotificationManager.IMPORTANCE_HIGH)
            channel.description = "Indica que el acelerómetro está monitoreando en segundo plano"
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun stopMonitoring() {
        handler.removeCallbacksAndMessages(null)
        sensorManager.unregisterListener(this)
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile var isRunning = false
            private set
        const val ACTION_START = "com.example.guardian_iot.START"
        const val ACTION_STOP = "com.example.guardian_iot.STOP"
        const val ACTION_CANCEL_ALERT = "com.example.guardian_iot.CANCEL_ALERT"
        const val EXTRA_NAME = "contactName"
        const val EXTRA_PHONE = "contactPhone"
        const val EXTRA_AUTOMATIC = "automatic"
        private const val CHANNEL_ID = "guardian_monitor"
        private const val PREFS_NAME = "guardian_monitor_settings"
        private const val NOTIFICATION_ID = 2001
        private const val COOLDOWN_MS = 45_000L
    }
}
