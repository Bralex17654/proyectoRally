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
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import kotlin.math.sqrt

class GuardianMonitorService : Service(), SensorEventListener {
    private data class Contact(val name: String, val phone: String)

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var sensorManager: SensorManager
    private var contacts: List<Contact> = emptyList()
    private var automatic = false
    private var impactAt = 0L
    private var lastTrigger = 0L
    private var peak = 0.0
    private var secondsLeft = 20
    private var alertPending = false
    private var callTargets: List<Contact> = emptyList()
    private var callIndex = 0
    private var callInProgress = false
    private var observedCallState = false
    private var phoneListener: PhoneStateListener? = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopMonitoring(); return START_NOT_STICKY }
            ACTION_CANCEL_ALERT -> { cancelAlert(); return START_STICKY }
        }
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (intent?.hasExtra(EXTRA_CONTACTS) == true) {
            contacts = parseContacts(intent.getStringExtra(EXTRA_CONTACTS).orEmpty())
            automatic = intent.getBooleanExtra(EXTRA_AUTOMATIC, false)
            prefs.edit().putString(EXTRA_CONTACTS, toJson(contacts)).putBoolean(EXTRA_AUTOMATIC, automatic)
                .putBoolean(PREF_MONITOR_ENABLED, true).apply()
        } else {
            contacts = parseContacts(prefs.getString(EXTRA_CONTACTS, "[]").orEmpty())
            automatic = prefs.getBoolean(EXTRA_AUTOMATIC, false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, monitorNotification("Acelerómetro activo"), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, monitorNotification("Acelerómetro activo"))
        }
        isRunning = true
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        if (getSharedPreferences(FLUTTER_PREFS, MODE_PRIVATE).getBoolean(FLUTTER_PENDING_KEY, false)) beginCountdown()
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
        getSharedPreferences(FLUTTER_PREFS, MODE_PRIVATE).edit().putBoolean(FLUTTER_PENDING_KEY, true).apply()
        postAlertNotification("Posible caída · ¿estás bien? $secondsLeft s", showCancel = true)
        updateMonitorNotification("Posible caída · revisión de 20 segundos")
        handler.removeCallbacks(countdownTick)
        handler.post(countdownTick)
    }

    private val countdownTick = object : Runnable {
        override fun run() {
            if (!alertPending) return
            secondsLeft--
            if (secondsLeft <= 0) {
                alertPending = false
                getSharedPreferences(FLUTTER_PREFS, MODE_PRIVATE).edit().putBoolean(FLUTTER_PENDING_KEY, false).apply()
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ALERT_NOTIFICATION_ID)
                sendAlert()
            } else {
                postAlertNotification("Posible caída · ¿estás bien? $secondsLeft s", showCancel = true)
                handler.postDelayed(this, 1_000L)
            }
        }
    }

    private fun cancelAlert() {
        if (!alertPending) return
        alertPending = false
        handler.removeCallbacks(countdownTick)
        getSharedPreferences(FLUTTER_PREFS, MODE_PRIVATE).edit().putBoolean(FLUTTER_PENDING_KEY, false).apply()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ALERT_NOTIFICATION_ID)
        updateMonitorNotification("Monitoreo activo · alerta cancelada")
    }

    private fun sendAlert() {
        if (!automatic || contacts.isEmpty()) {
            updateMonitorNotification("Posible caída · revisa Guardian IoT")
            return
        }
        val location = getLastLocationText()
        var sent = 0
        var failed = 0
        contacts.forEach { contact ->
            try {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                    failed++
                } else {
                    @Suppress("DEPRECATION")
                    val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
                    val greeting = if (contact.name.isBlank()) "Hola" else "Hola ${contact.name}"
                    val body = "$greeting, Guardian IoT detectó un patrón compatible con una posible caída. Verifica cómo estoy. $location"
                    sms.sendMultipartTextMessage(contact.phone, null, sms.divideMessage(body), null, null)
                    sent++
                }
            } catch (_: Exception) { failed++ }
        }
        updateMonitorNotification("SMS solicitados: $sent · errores: $failed")
        startCallSequence()
    }

    private fun startCallSequence() {
        callTargets = contacts.take(2)
        callIndex = 0
        callInProgress = false
        observedCallState = false
        if (callTargets.isEmpty()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            val telephony = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            phoneListener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    if (state != TelephonyManager.CALL_STATE_IDLE) {
                        observedCallState = true
                        callInProgress = true
                    } else if (callInProgress && observedCallState) {
                        callInProgress = false
                        observedCallState = false
                        handler.removeCallbacks(callStateTimeout)
                        handler.postDelayed({ placeNextCall() }, 2_000L)
                    }
                }
            }
            @Suppress("DEPRECATION")
            telephony.listen(phoneListener, PhoneStateListener.LISTEN_CALL_STATE)
        }
        placeNextCall()
    }

    private val callStateTimeout = object : Runnable {
        override fun run() {
            if (callIndex >= callTargets.size || !callInProgress) return
            val canReadState = ContextCompat.checkSelfPermission(this@GuardianMonitorService, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
            val telephony = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            if (!canReadState) {
                stopPhoneStateListener()
                postAlertNotification("Se intentó una llamada · toca para llamar a los contactos", showCancel = false, offerCalls = true)
            } else if (telephony.callState == TelephonyManager.CALL_STATE_IDLE) {
                callInProgress = false
                observedCallState = false
                placeNextCall()
            } else {
                handler.postDelayed(this, 30_000L)
            }
        }
    }

    private fun placeNextCall() {
        if (callIndex >= callTargets.size) {
            stopPhoneStateListener()
            postAlertNotification("Avisos enviados · llamadas intentadas", showCancel = false, offerCalls = true)
            return
        }
        val contact = callTargets[callIndex++]
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                stopPhoneStateListener()
                postAlertNotification("SMS enviados · falta permiso para llamar", showCancel = false, offerCalls = true)
                return
            }
            callInProgress = true
            observedCallState = false
            val telecom = getSystemService(TELECOM_SERVICE) as TelecomManager
            telecom.placeCall(Uri.fromParts("tel", contact.phone, null), null)
            updateMonitorNotification("Llamando a ${contact.name.ifBlank { contact.phone }}")
            handler.removeCallbacks(callStateTimeout)
            handler.postDelayed(callStateTimeout, 90_000L)
        } catch (_: Exception) {
            callInProgress = false
            if (callIndex < callTargets.size) handler.postDelayed({ placeNextCall() }, 3_000L)
            else {
                stopPhoneStateListener()
                postAlertNotification("SMS enviados · no se pudo iniciar la llamada", showCancel = false, offerCalls = true)
            }
        }
    }

    private fun stopPhoneStateListener() {
        handler.removeCallbacks(callStateTimeout)
        if (phoneListener != null && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            @Suppress("DEPRECATION")
            (getSystemService(TELEPHONY_SERVICE) as TelephonyManager).listen(phoneListener, PhoneStateListener.LISTEN_NONE)
        }
        phoneListener = null
        callInProgress = false
    }

    private fun getLastLocationText(): String {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return "Ubicación no disponible."
        return try {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            val location = manager.getProviders(true).asSequence().mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
            if (location == null) "Ubicación no disponible." else "Ubicación aproximada: https://maps.google.com/?q=${location.latitude},${location.longitude}"
        } catch (_: Exception) { "Ubicación no disponible." }
    }

    private fun monitorNotification(text: String): Notification = NotificationCompat.Builder(this, MONITOR_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle("Guardian IoT · monitoreo")
        .setContentText(text)
        .setContentIntent(appPendingIntent())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun postAlertNotification(text: String, showCancel: Boolean, offerCalls: Boolean = false) {
        val builder = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(if (showCancel) "Guardian IoT · ¿estás bien?" else "Guardian IoT · alerta")
            .setContentText(text)
            .setContentIntent(appPendingIntent(markFallPending = showCancel))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(!showCancel)
            .setOngoing(showCancel)
            .setOnlyAlertOnce(true)
        appPendingIntent(markFallPending = showCancel)?.let { builder.setFullScreenIntent(it, true) }
        if (showCancel) {
            val cancelIntent = Intent(this, GuardianMonitorService::class.java).setAction(ACTION_CANCEL_ALERT)
            val cancelPending = PendingIntent.getService(this, 2, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0, "Estoy bien", cancelPending)
        }
        if (offerCalls) {
            callTargets.take(2).forEachIndexed { index, contact ->
                val callIntent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", contact.phone, null))
                val callPending = PendingIntent.getActivity(this, 30 + index, callIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(0, "Llamar ${index + 1}", callPending)
            }
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ALERT_NOTIFICATION_ID, builder.build())
    }

    private fun appPendingIntent(markFallPending: Boolean = false): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (markFallPending) launchIntent.putExtra(EXTRA_FALL_ALERT, true)
        return PendingIntent.getActivity(this, if (markFallPending) 4 else 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun updateMonitorNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, monitorNotification(text))
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val monitor = NotificationChannel(MONITOR_CHANNEL_ID, "Monitoreo Guardian IoT", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Indica que el acelerómetro está monitoreando en segundo plano"
            }
            val alert = NotificationChannel(ALERT_CHANNEL_ID, "Alertas de posible caída", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alarma sonora y vibración para confirmar si la persona está bien"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 700, 250, 700, 250, 1000)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            }
            manager.createNotificationChannel(monitor)
            manager.createNotificationChannel(alert)
        }
    }

    private fun parseContacts(json: String): List<Contact> = try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val phone = item.optString("phone").trim()
            if (phone.isBlank()) null else Contact(item.optString("name").trim(), phone)
        }.take(5)
    } catch (_: Exception) { emptyList() }

    private fun toJson(list: List<Contact>): String = JSONArray().apply {
        list.forEach { contact -> put(org.json.JSONObject().put("name", contact.name).put("phone", contact.phone)) }
    }.toString()

    private fun stopMonitoring() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_MONITOR_ENABLED, false).apply()
        handler.removeCallbacksAndMessages(null)
        getSharedPreferences(FLUTTER_PREFS, MODE_PRIVATE).edit().putBoolean(FLUTTER_PENDING_KEY, false).apply()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ALERT_NOTIFICATION_ID)
        stopPhoneStateListener()
        sensorManager.unregisterListener(this)
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        stopPhoneStateListener()
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
        const val EXTRA_CONTACTS = "contactsJson"
        const val EXTRA_AUTOMATIC = "automatic"
        const val EXTRA_FALL_ALERT = "fall_alert"
        const val PREFS_NAME = "guardian_monitor_settings"
        const val PREF_MONITOR_ENABLED = "monitor_enabled"
        private const val FLUTTER_PREFS = "FlutterSharedPreferences"
        private const val FLUTTER_PENDING_KEY = "flutter.fall_pending"
        private const val MONITOR_CHANNEL_ID = "guardian_monitor_v2"
        private const val ALERT_CHANNEL_ID = "guardian_fall_alarm"
        private const val NOTIFICATION_ID = 2001
        private const val ALERT_NOTIFICATION_ID = 2002
        private const val COOLDOWN_MS = 45_000L
    }
}
