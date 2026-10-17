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
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
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
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
    private var countdownTotal = DEFAULT_COUNTDOWN_S
    private var secondsLeft = DEFAULT_COUNTDOWN_S
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

    // Servidor TzindaApp (opcional, ver backend/).
    private lateinit var api: GuardianApi
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private var activeAlertId: String? = null
    private var trackingUntil = 0L
    private var knownAcks = 0
    private var ackedBy: String? = null
    private var bestFix: Location? = null
    private var locationListener: LocationListener? = null

    /** Alerta que el servidor ya creó por una caída de la pulsera: (id, link). */
    private var sensorAlert: Pair<String, String>? = null
    private var countdownLabel = "Posible caída"

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        api = GuardianApi(this)
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopMonitoring(); return START_NOT_STICKY }
            ACTION_CANCEL_ALERT -> { cancelAlert(); return START_STICKY }
            ACTION_RESOLVE_ALERT -> { resolveActiveAlert(); return START_STICKY }
            ACTION_SMS_RESULT -> {
                handleSmsResult(intent.getBooleanExtra(EXTRA_SMS_SUCCESS, false))
                return START_STICKY
            }
        }
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val contactsChanged = intent?.hasExtra(EXTRA_CONTACTS) == true
        if (contactsChanged) {
            contacts = parseContacts(intent!!.getStringExtra(EXTRA_CONTACTS).orEmpty())
            automatic = intent.getBooleanExtra(EXTRA_AUTOMATIC, false)
            prefs.edit().putString(EXTRA_CONTACTS, toJson(contacts)).putBoolean(EXTRA_AUTOMATIC, automatic)
                .putBoolean(PREF_MONITOR_ENABLED, true).apply()
        } else {
            contacts = parseContacts(prefs.getString(EXTRA_CONTACTS, "[]").orEmpty())
            automatic = prefs.getBoolean(EXTRA_AUTOMATIC, false)
        }
        countdownTotal = prefs.getInt(PREF_COUNTDOWN, DEFAULT_COUNTDOWN_S)
        startInForeground()
        isRunning = true
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        if (getSharedPreferences(FLUTTER_PREFS, MODE_PRIVATE).getBoolean(FLUTTER_PENDING_KEY, false)) beginCountdown()

        // Los contactos editados en el teléfono se reflejan en el servidor.
        if (contactsChanged && api.isConfigured) {
            val snapshot = JSONArray(toJson(contacts))
            io.execute { api.pushConfig(snapshot, automatic, countdownTotal) }
        }
        handler.removeCallbacks(heartbeatTick)
        handler.post(heartbeatTick)
        handler.removeCallbacks(commandsTick)
        handler.post(commandsTick)
        return START_STICKY
    }

    /** Tipo "location" además de "specialUse" para poder actualizar el GPS durante una alerta. */
    private fun startInForeground() {
        val notification = monitorNotification("Acelerómetro activo")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val withLocation = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    if (hasLocationPermission()) withLocation else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } catch (_: SecurityException) {
                // Iniciado en segundo plano sin permiso de ubicación: se vigila igual, sin GPS en vivo.
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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
        if (elapsed > 9_000L) {
            impactAt = 0L
            reportEvent("impact_discarded", JSONObject().put("peak", peak))
            return
        }
        if (elapsed > 3_000L && magnitude < 2.2) {
            lastTrigger = now
            impactAt = 0L
            beginCountdown()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun beginCountdown(fromSensor: Boolean = false) {
        if (alertPending) return
        alertPending = true
        secondsLeft = countdownTotal
        countdownLabel = if (fromSensor) "La pulsera detectó una posible caída" else "Posible caída"
        getSharedPreferences(FLUTTER_PREFS, MODE_PRIVATE).edit().putBoolean(FLUTTER_PENDING_KEY, true).apply()
        postAlertNotification("$countdownLabel · ¿estás bien? $secondsLeft s", showCancel = true)
        updateMonitorNotification("$countdownLabel · revisión de $countdownTotal segundos")
        // Se empieza a buscar GPS desde ya para tener una ubicación fresca si hay que avisar.
        startLocationUpdates()
        reportEvent("countdown_started", JSONObject().put("peak", peak))
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
                postAlertNotification("$countdownLabel · ¿estás bien? $secondsLeft s", showCancel = true)
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
        stopLocationUpdates()
        reportEvent("cancelled_by_user", JSONObject().put("peak", peak).put("secondsLeft", secondsLeft))
        sensorAlert?.let { (alertId, _) ->
            sensorAlert = null
            io.execute { api.resolveAlert(alertId, "false_alarm") }
        }
    }

    /** Primero se intenta crear la alerta en el servidor (máx. ~9 s) para incluir el link en el SMS. */
    private fun sendAlert() {
        val alertPeak = peak
        sensorAlert?.let { (alertId, url) ->
            sensorAlert = null
            activeAlertId = alertId
            knownAcks = 0
            ackedBy = null
            dispatchSms(link = url)
            return
        }
        if (!api.isConfigured) {
            dispatchSms(link = null)
            return
        }
        val fix = bestFix ?: lastKnownLocation()
        updateMonitorNotification("Posible caída · avisando a tu familia…")
        io.execute {
            val created = api.createAlert("fall", alertPeak, batteryInfo().first, fix)
            handler.post {
                activeAlertId = created?.optString("alertId")?.takeIf { it.isNotBlank() }
                knownAcks = 0
                ackedBy = null
                dispatchSms(link = created?.optString("url")?.takeIf { it.isNotBlank() })
            }
        }
    }

    private fun dispatchSms(link: String?) {
        startAlertTracking()
        if (!automatic || contacts.isEmpty()) {
            updateMonitorNotification(
                if (activeAlertId != null) "Posible caída · familia avisada por el servidor" else "Posible caída · revisa TzindaApp",
            )
            return
        }
        val location = link?.let { "Mapa en vivo y \"Voy en camino\": $it" } ?: getLastLocationText()
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
        reportEvent(
            "sms_result",
            JSONObject()
                .put("expected", smsCallbacksExpected)
                .put("received", smsCallbacksReceived)
                .put("anyFailed", smsAnyFailed)
                .put("alertId", activeAlertId ?: JSONObject.NULL),
        )
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
        ackedBy?.let { name ->
            postAlertNotification("$name va en camino · no hace falta llamar", showCancel = false, offerCalls = true)
            return
        }
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
        // Si un familiar ya respondió "Voy en camino" en el link, no se sigue llamando.
        ackedBy?.let { name ->
            stopPhoneStateListener()
            postAlertNotification("$name va en camino · no se harán más llamadas", showCancel = false, offerCalls = true)
            return
        }
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

    // ------------------------------------------------------------ servidor TzindaApp

    /** Latido periódico: batería, estado y ubicación; trae cambios de configuración de un familiar. */
    private val heartbeatTick = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (api.isConfigured) {
                val (battery, charging) = batteryInfo()
                val fix = lastKnownLocation()
                io.execute {
                    val res = api.heartbeat(battery, charging, monitoring = true, location = fix)
                    if (res != null) handler.post { applyRemoteConfig(res) }
                }
            }
            handler.postDelayed(this, api.heartbeatEveryMin * 60_000L)
        }
    }

    /** Cada 5 s: ¿la pulsera marcó una caída? Entonces suena la misma alarma que con el sensor del teléfono. */
    private val commandsTick = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (api.isConfigured) {
                io.execute {
                    val commands = api.fetchCommands()
                    if (commands != null && commands.length() > 0) handler.post { handleCommands(commands) }
                }
            }
            handler.postDelayed(this, COMMANDS_EVERY_MS)
        }
    }

    private fun handleCommands(commands: JSONArray) {
        for (i in 0 until commands.length()) {
            val command = commands.optJSONObject(i) ?: continue
            if (command.optString("type") != "fall_alarm") continue
            // Ya hay una cuenta regresiva o una alerta en curso: no se encima otra.
            if (alertPending || activeAlertId != null) continue
            val alertId = command.optString("alertId")
            if (alertId.isBlank()) continue
            sensorAlert = alertId to command.optString("url")
            peak = 0.0
            beginCountdown(fromSensor = true)
        }
    }

    private fun applyRemoteConfig(res: JSONObject) {
        val version = res.optInt("configVersion", 0)
        if (version <= api.configVersion) return
        api.configVersion = version
        val config = res.optJSONObject("config") ?: return
        val remoteContacts = parseContacts(config.optJSONArray("contacts")?.toString() ?: "[]")
        if (remoteContacts.isNotEmpty()) contacts = remoteContacts
        automatic = config.optBoolean("autoActions", automatic)
        countdownTotal = config.optInt("countdownSeconds", countdownTotal).coerceIn(10, 120)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(EXTRA_CONTACTS, toJson(contacts))
            .putBoolean(EXTRA_AUTOMATIC, automatic)
            .putInt(PREF_COUNTDOWN, countdownTotal)
            .apply()
        // La interfaz de Flutter lee los contactos de sus propias preferencias.
        val flutterPrefs = getSharedPreferences(FLUTTER_PREFS, MODE_PRIVATE).edit()
            .putString("flutter.trusted_contacts", toJson(contacts))
            .putBoolean("flutter.automatic_actions", automatic)
        contacts.firstOrNull()?.let {
            flutterPrefs.putString("flutter.contact_name", it.name).putString("flutter.contact_phone", it.phone)
        }
        flutterPrefs.apply()
        updateMonitorNotification("Configuración actualizada por un familiar")
    }

    /** Durante 15 min tras la alerta: envía la ubicación y revisa si alguien va en camino. */
    private fun startAlertTracking() {
        if (activeAlertId == null) {
            handler.postDelayed({ if (activeAlertId == null) stopLocationUpdates() }, 120_000L)
            return
        }
        trackingUntil = System.currentTimeMillis() + ALERT_TRACKING_MS
        handler.removeCallbacks(trackingTick)
        handler.postDelayed(trackingTick, 20_000L)
    }

    private val trackingTick = object : Runnable {
        override fun run() {
            val alertId = activeAlertId
            if (alertId == null || System.currentTimeMillis() > trackingUntil) {
                stopLocationUpdates()
                return
            }
            val fix = bestFix
            io.execute {
                // Si falla el envío de ubicación (p. ej. 409: ya se cerró desde el link), se consulta el estado.
                val res = fix?.let { api.updateAlertLocation(alertId, it) } ?: api.getAlert(alertId)
                if (res != null) handler.post { handleAlertStatus(res) }
            }
            handler.postDelayed(this, 60_000L)
        }
    }

    private fun handleAlertStatus(res: JSONObject) {
        val acks = res.optJSONArray("acks") ?: JSONArray()
        if (acks.length() > knownAcks) {
            knownAcks = acks.length()
            val last = acks.getJSONObject(acks.length() - 1)
            val name = last.optString("name").ifBlank { "Un familiar" }
            val eta = last.optInt("etaMinutes", 0)
            ackedBy = name
            val text = if (eta > 0) "$name va en camino · llega en ~$eta min" else "$name va en camino"
            showInfoNotification("✅ $text", "Quédate tranquilo, ya te van a ayudar.")
            updateMonitorNotification(text)
        }
        if (res.optString("status") == "resolved") {
            activeAlertId = null
            handler.removeCallbacks(trackingTick)
            stopLocationUpdates()
            updateMonitorNotification("Alerta cerrada · monitoreo activo")
        }
    }

    /** Botón "Ya estoy bien" de la notificación después de enviar la alerta. */
    private fun resolveActiveAlert() {
        val alertId = activeAlertId ?: return
        activeAlertId = null
        handler.removeCallbacks(trackingTick)
        stopLocationUpdates()
        stopPhoneStateListener()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ALERT_NOTIFICATION_ID)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ACK_NOTIFICATION_ID)
        updateMonitorNotification("Avisaste que estás bien · monitoreo activo")
        val reason = if (ackedBy != null) "attended" else "false_alarm"
        io.execute { api.resolveAlert(alertId, reason) }
    }

    private fun resolvePendingIntent(): PendingIntent {
        val intent = Intent(this, GuardianMonitorService::class.java).setAction(ACTION_RESOLVE_ALERT)
        return PendingIntent.getService(this, 3, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun showInfoNotification(title: String, text: String) {
        val builder = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(appPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
        if (activeAlertId != null) builder.addAction(0, "Ya estoy bien", resolvePendingIntent())
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ACK_NOTIFICATION_ID, builder.build())
    }

    private fun reportEvent(type: String, data: JSONObject) {
        if (api.isConfigured) io.execute { api.postEvent(type, data) }
    }

    private fun batteryInfo(): Pair<Int?, Boolean?> {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null to null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return percent to charging
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun lastKnownLocation(): Location? {
        if (!hasLocationPermission()) return null
        return try {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            manager.getProviders(true).asSequence().mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (_: Exception) { null }
    }

    private fun startLocationUpdates() {
        if (!hasLocationPermission() || locationListener != null) return
        bestFix = lastKnownLocation()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val current = bestFix
                if (current == null || location.time - current.time > 30_000L || location.accuracy <= current.accuracy) {
                    bestFix = location
                }
            }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Requerido en Android < 10")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        locationListener = listener
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (manager.isProviderEnabled(provider)) {
                    manager.requestLocationUpdates(provider, 15_000L, 0f, listener, Looper.getMainLooper())
                }
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    private fun stopLocationUpdates() {
        locationListener?.let { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(it) }
        locationListener = null
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
        } else if (activeAlertId != null) {
            builder.addAction(0, "Ya estoy bien", resolvePendingIntent())
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
        stopLocationUpdates()
        sensorManager.unregisterListener(this)
        isRunning = false
        // Último latido para que el servidor sepa que se pausó a propósito.
        if (api.isConfigured) {
            val (battery, charging) = batteryInfo()
            io.execute { api.heartbeat(battery, charging, monitoring = false, location = null) }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        stopPhoneStateListener()
        stopLocationUpdates()
        handler.removeCallbacksAndMessages(null)
        io.shutdown()
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
        const val ACTION_RESOLVE_ALERT = "com.example.guardian_iot.RESOLVE_ALERT"
        const val EXTRA_CONTACTS = "contactsJson"
        const val EXTRA_AUTOMATIC = "automatic"
        const val EXTRA_FALL_ALERT = "fall_alert"
        const val PREFS_NAME = "guardian_monitor_settings"
        const val PREF_MONITOR_ENABLED = "monitor_enabled"
        private const val PREF_COUNTDOWN = "countdown_seconds"
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
        private const val ACK_NOTIFICATION_ID = 2004
        private const val COOLDOWN_MS = 45_000L
        private const val DEFAULT_COUNTDOWN_S = 20
        private const val ALERT_TRACKING_MS = 15 * 60_000L
        private const val COMMANDS_EVERY_MS = 5_000L
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
