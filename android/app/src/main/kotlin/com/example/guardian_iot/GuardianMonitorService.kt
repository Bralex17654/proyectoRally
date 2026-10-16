package com.example.guardian_iot

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
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
import android.telephony.PhoneNumberUtils
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
    private var smsCallbacksExpected = 0
    private var smsCallbacksReceived = 0
    private var smsDispatchFinished = false
    private var smsAnyFailed = false
    private var smsProtocolFinished = false
    private var fallbackCallStarted = false

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopMonitoring(); return START_NOT_STICKY }
            ACTION_CANCEL_ALERT -> { cancelAlert(); return START_STICKY }
            ACTION_SMS_RESULT -> {
                handleSmsResult(intent.getBooleanExtra(EXTRA_SMS_SUCCESS, false))
                return START_STICKY
            }
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
            updateMonitorNotification("Posible caída · revisa TzindaApp")
            return
        }
        val location = getLastLocationText()
        smsCallbacksExpected = 0
        smsCallbacksReceived = 0
        smsDispatchFinished = false
        smsAnyFailed = false
        smsProtocolFinished = false
        fallbackCallStarted = false
        var requested = 0
        var failed = 0
        contacts.forEach { contact ->
            var expectedPartsForContact = 0
            try {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                    failed++
                } else {
                    val sms = getSmsManager()
                    val greeting = if (contact.name.isBlank()) "Hola" else "Hola ${contact.name}"
                    val body = "$greeting, TzindaApp detectó un patrón compatible con una posible caída. Verifica cómo estoy. $location"
                    val parts = sms.divideMessage(body)
                    val callbacks = ArrayList<PendingIntent>(parts.size)
                    expectedPartsForContact = parts.size
                    parts.forEachIndexed { index, _ ->
                        val callbackId = smsRequestCode.incrementAndGet()
                        val callback = Intent(this, SmsStatusReceiver::class.java).apply {
                            action = ACTION_SMS_SENT
                            putExtra(EXTRA_SMS_NAME, contact.name)
                            putExtra(EXTRA_SMS_PHONE, contact.phone)
                            putExtra(EXTRA_SMS_PART, index + 1)
                            putExtra(EXTRA_SMS_PARTS, parts.size)
                            putExtra(EXTRA_SMS_NOTIFICATION_ID, SMS_STATUS_NOTIFICATION_ID + callbackId)
                        }
                        callbacks.add(PendingIntent.getBroadcast(
                            this,
                            callbackId,
                            callback,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ))
                    }
                    if (parts.size == 1) {
                        sms.sendTextMessage(contact.phone, null, body, callbacks.first(), null)
                    } else {
                        sms.sendMultipartTextMessage(contact.phone, null, parts, callbacks, null)
                    }
                    smsCallbacksExpected += expectedPartsForContact
                    requested++
                }
            } catch (error: Exception) {
                failed++
                smsAnyFailed = true
                android.util.Log.e("GuardianIoT", "No se pudo solicitar SMS a ${contact.phone}", error)
            }
        }
        smsDispatchFinished = true
        if (requested == 0) smsAnyFailed = true
        val smsStatus = when {
            requested == 0 -> "SMS no solicitados · revisa permiso/SIM"
            failed > 0 -> "SMS en proceso: $requested · errores: $failed"
            else -> "SMS en proceso para ${contacts.size} contactos"
        }
        updateMonitorNotification(smsStatus)
        postSmsStatusNotification(smsStatus, failed > 0)
        if (smsCallbacksReceived < smsCallbacksExpected) {
            handler.removeCallbacks(smsResultTimeout)
            handler.postDelayed(smsResultTimeout, SMS_RESULT_TIMEOUT_MS)
        }
        finishSmsProtocolIfReady()
    }

    private fun handleSmsResult(success: Boolean) {
        smsCallbacksReceived++
        if (!success) smsAnyFailed = true
        finishSmsProtocolIfReady()
    }

    private fun finishSmsProtocolIfReady() {
        if (!smsDispatchFinished || smsCallbacksReceived < smsCallbacksExpected || smsProtocolFinished) return
        smsProtocolFinished = true
        handler.removeCallbacks(smsResultTimeout)
        if (smsAnyFailed) {
            startFallbackCall()
        } else if (smsCallbacksExpected > 0) {
            startCallSequence()
        } else {
            startFallbackCall()
        }
    }

    private val smsResultTimeout = Runnable {
        if (!smsProtocolFinished) {
            smsAnyFailed = true
            smsProtocolFinished = true
            startFallbackCall()
        }
    }

    private fun startFallbackCall() {
        if (fallbackCallStarted) return
        fallbackCallStarted = true
        val firstContact = contacts.firstOrNull()
        if (firstContact == null) {
            updateMonitorNotification("No se pudo enviar el SMS y no hay contacto para llamar")
            postAlertNotification("No se pudo enviar el SMS · agrega un contacto", showCancel = false)
            return
        }
        callTargets = listOf(firstContact)
        callIndex = 1
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            updateMonitorNotification("SMS no enviado · falta permiso para llamar")
            postAlertNotification("SMS no enviado · toca para llamar al primer contacto", showCancel = false, offerCalls = true)
            return
        }
        try {
            val telecom = getSystemService(TELECOM_SERVICE) as TelecomManager
            telecom.placeCall(Uri.fromParts("tel", firstContact.phone, null), null)
            updateMonitorNotification("SMS no enviado · llamando al primer contacto")
            postAlertNotification("No se pudo enviar el SMS · llamando a ${firstContact.name.ifBlank { firstContact.phone }}", showCancel = false)
        } catch (error: Exception) {
            android.util.Log.e("GuardianIoT", "No se pudo llamar al primer contacto", error)
            updateMonitorNotification("SMS y llamada no disponibles · revisa permisos/SIM")
            postAlertNotification("No se pudo enviar el SMS · toca para llamar al primer contacto", showCancel = false, offerCalls = true)
        }
    }

    private fun getSmsManager(): SmsManager {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val defaultSubscriptionId = android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId()
            if (defaultSubscriptionId != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                return getSystemService(SmsManager::class.java).createForSubscriptionId(defaultSubscriptionId)
            }
        }
        @Suppress("DEPRECATION")
        return SmsManager.getDefault()
    }

    private fun postSmsStatusNotification(message: String, error: Boolean) {
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(if (error) "TzindaApp · revisar SMS" else "TzindaApp · alerta enviada")
            .setContentText(message)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(SMS_STATUS_NOTIFICATION_ID, notification)
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
        .setContentTitle("TzindaApp · monitoreo")
        .setContentText(text)
        .setContentIntent(appPendingIntent())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun postAlertNotification(text: String, showCancel: Boolean, offerCalls: Boolean = false) {
        val builder = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(if (showCancel) "TzindaApp · ¿estás bien?" else "TzindaApp · alerta")
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
            val monitor = NotificationChannel(MONITOR_CHANNEL_ID, "Monitoreo TzindaApp", NotificationManager.IMPORTANCE_LOW).apply {
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
            val rawPhone = item.optString("phone").trim()
            val phone = PhoneNumberUtils.stripSeparators(rawPhone)
            val digitCount = phone.count { it.isDigit() }
            if (phone.isBlank() || digitCount !in 7..15 || phone.drop(1).contains('+')) null
            else Contact(item.optString("name").trim(), phone)
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
        const val SMS_STATUS_NOTIFICATION_ID = 2003
        const val ACTION_SMS_SENT = "com.example.guardian_iot.SMS_SENT"
        const val EXTRA_SMS_NAME = "sms_name"
        const val EXTRA_SMS_PHONE = "sms_phone"
        const val EXTRA_SMS_PART = "sms_part"
        const val EXTRA_SMS_PARTS = "sms_parts"
        const val EXTRA_SMS_NOTIFICATION_ID = "sms_notification_id"
        const val ACTION_SMS_RESULT = "com.example.guardian_iot.SMS_RESULT"
        const val EXTRA_SMS_SUCCESS = "sms_success"
        private const val COOLDOWN_MS = 45_000L
        private const val SMS_RESULT_TIMEOUT_MS = 30_000L
        private val smsRequestCode = java.util.concurrent.atomic.AtomicInteger(10_000)
    }
}

class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GuardianMonitorService.ACTION_SMS_SENT) return
        val name = intent.getStringExtra(GuardianMonitorService.EXTRA_SMS_NAME).orEmpty()
        val phone = intent.getStringExtra(GuardianMonitorService.EXTRA_SMS_PHONE).orEmpty()
        val part = intent.getIntExtra(GuardianMonitorService.EXTRA_SMS_PART, 1)
        val parts = intent.getIntExtra(GuardianMonitorService.EXTRA_SMS_PARTS, 1)
        val destination = name.ifBlank { phone }
        val (message, failed) = when (resultCode) {
            android.app.Activity.RESULT_OK -> "SMS aceptado por Android para $destination" to false
            SmsManager.RESULT_ERROR_NO_SERVICE -> "Sin servicio móvil: no se envió a $destination" to true
            SmsManager.RESULT_ERROR_RADIO_OFF -> "Radio/SIM apagada: no se envió a $destination" to true
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "Límite de SMS excedido para $destination" to true
            SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> "La SIM bloqueó el número de $destination" to true
            SmsManager.RESULT_ERROR_NULL_PDU -> "Error al preparar SMS para $destination" to true
            else -> "Android rechazó el SMS para $destination (código $resultCode)" to true
        }
        android.util.Log.i("GuardianIoT", "$message · parte $part/$parts")
        val notification = NotificationCompat.Builder(context, "guardian_fall_alarm")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(if (failed) "TzindaApp · SMS no enviado" else "TzindaApp · SMS procesado")
            .setContentText(if (parts > 1) "$message · parte $part/$parts" else message)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        val notificationId = intent.getIntExtra(
            GuardianMonitorService.EXTRA_SMS_NOTIFICATION_ID,
            GuardianMonitorService.SMS_STATUS_NOTIFICATION_ID,
        )
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(notificationId, notification)
        val resultIntent = Intent(context, GuardianMonitorService::class.java).apply {
            action = GuardianMonitorService.ACTION_SMS_RESULT
            putExtra(GuardianMonitorService.EXTRA_SMS_SUCCESS, !failed)
        }
        try {
            context.startService(resultIntent)
        } catch (error: Exception) {
            android.util.Log.e("GuardianIoT", "No se pudo actualizar el resultado de SMS", error)
        }
    }
}
