package com.example.guardian_iot

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente de la API de TzindaApp (carpeta backend/ del repositorio).
 *
 * Es opcional: si no hay servidor configurado, la app funciona igual que antes
 * (SMS y llamadas desde el chip). Todas las llamadas son bloqueantes, así que
 * se usan desde un hilo de fondo.
 */
class GuardianApi(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val baseUrl: String get() = prefs.getString(KEY_BASE_URL, "").orEmpty()
    val deviceId: String get() = prefs.getString(KEY_DEVICE_ID, "").orEmpty()
    private val token: String get() = prefs.getString(KEY_TOKEN, "").orEmpty()
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && deviceId.isNotBlank() && token.isNotBlank()

    var configVersion: Int
        get() = prefs.getInt(KEY_CONFIG_VERSION, 0)
        set(value) = prefs.edit().putInt(KEY_CONFIG_VERSION, value).apply()

    var heartbeatEveryMin: Int
        get() = prefs.getInt(KEY_HEARTBEAT_MIN, 15)
        private set(value) = prefs.edit().putInt(KEY_HEARTBEAT_MIN, value.coerceIn(1, 120)).apply()

    fun status(): Map<String, Any?> = mapOf(
        "configured" to isConfigured,
        "baseUrl" to baseUrl,
        "deviceId" to deviceId,
        "lastOkAt" to prefs.getLong(KEY_LAST_OK, 0L).takeIf { it > 0 },
        "lastError" to prefs.getString(KEY_LAST_ERROR, null),
    )

    /** Registra el teléfono en el servidor y guarda su token. */
    fun register(url: String, personName: String, model: String, androidVersion: String, contacts: JSONArray): JSONObject {
        val clean = url.trim().trimEnd('/')
        require(clean.startsWith("http://") || clean.startsWith("https://")) { "La URL debe empezar con http:// o https://" }
        val body = JSONObject()
            .put("personName", personName)
            .put("model", model)
            .put("androidVersion", androidVersion)
            .put("appVersion", APP_VERSION)
            .put("config", JSONObject().put("contacts", validContacts(contacts)))
        val res = request("POST", "$clean/api/v1/devices", body, auth = false)
            ?: throw IOException(prefs.getString(KEY_LAST_ERROR, null) ?: "Sin respuesta del servidor")
        prefs.edit()
            .putString(KEY_BASE_URL, clean)
            .putString(KEY_DEVICE_ID, res.getString("deviceId"))
            .putString(KEY_TOKEN, res.getString("deviceToken"))
            .putInt(KEY_CONFIG_VERSION, res.optInt("configVersion", 1))
            .apply()
        heartbeatEveryMin = res.optInt("heartbeatEveryMin", 15)
        return res
    }

    fun disconnect() = prefs.edit().clear().apply()

    /** Latido: devuelve la configuración del servidor (puede traer cambios de un familiar). */
    fun heartbeat(battery: Int?, charging: Boolean?, monitoring: Boolean, location: Location?): JSONObject? {
        val body = JSONObject()
            .put("monitoring", monitoring)
            .put("appVersion", APP_VERSION)
        battery?.let { body.put("battery", it) }
        charging?.let { body.put("charging", it) }
        location?.let { body.put("location", it.toJson()) }
        val res = request("POST", "/api/v1/devices/$deviceId/heartbeat", body) ?: return null
        heartbeatEveryMin = res.optInt("heartbeatEveryMin", heartbeatEveryMin)
        return res
    }

    /** Sube los contactos locales para que los familiares los vean en el servidor. */
    fun pushConfig(contacts: JSONArray, autoActions: Boolean, countdownSeconds: Int): Int? {
        val body = JSONObject()
            .put("contacts", validContacts(contacts))
            .put("autoActions", autoActions)
            .put("countdownSeconds", countdownSeconds)
        val res = request("PUT", "/api/v1/devices/$deviceId/config", body) ?: return null
        return res.optInt("configVersion").also { configVersion = it }
    }

    /** Crea la alerta y devuelve { alertId, url } para incluir el link en el SMS. */
    fun createAlert(type: String, peak: Double?, battery: Int?, location: Location?): JSONObject? {
        val body = JSONObject().put("type", type)
        peak?.let { body.put("peak", it) }
        battery?.let { body.put("battery", it) }
        location?.let { body.put("location", it.toJson()) }
        return request("POST", "/api/v1/alerts", body, connectTimeoutMs = 4000, readTimeoutMs = 5000)
    }

    /** Envía la ubicación nueva; la respuesta trae quién va en camino. */
    fun updateAlertLocation(alertId: String, location: Location): JSONObject? =
        request("POST", "/api/v1/alerts/$alertId/location", location.toJson())

    fun getAlert(alertId: String): JSONObject? = request("GET", "/api/v1/alerts/$alertId", null)

    fun resolveAlert(alertId: String, reason: String): JSONObject? =
        request("POST", "/api/v1/alerts/$alertId/resolve", JSONObject().put("reason", reason))

    fun postEvent(type: String, data: JSONObject = JSONObject()): JSONObject? =
        request("POST", "/api/v1/devices/$deviceId/events", JSONObject().put("type", type).put("data", data))

    fun createInvite(): JSONObject? = request("POST", "/api/v1/devices/$deviceId/invite", JSONObject())

    /** Órdenes pendientes del servidor, p. ej. "la pulsera marcó una caída". */
    fun fetchCommands(): JSONArray? =
        request("GET", "/api/v1/devices/$deviceId/commands", null, connectTimeoutMs = 3000, readTimeoutMs = 4000)
            ?.optJSONArray("commands")

    private fun request(
        method: String,
        pathOrUrl: String,
        body: JSONObject?,
        auth: Boolean = true,
        connectTimeoutMs: Int = 6000,
        readTimeoutMs: Int = 10000,
    ): JSONObject? {
        val url = if (pathOrUrl.startsWith("http")) pathOrUrl else "$baseUrl$pathOrUrl"
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", "application/json")
                if (auth) setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = if (text.isBlank()) JSONObject() else JSONObject(text)
            if (code in 200..299) {
                prefs.edit().putLong(KEY_LAST_OK, System.currentTimeMillis()).remove(KEY_LAST_ERROR).apply()
                json
            } else {
                prefs.edit().putString(KEY_LAST_ERROR, json.optString("message", "Error $code")).apply()
                null
            }
        } catch (e: Exception) {
            prefs.edit().putString(KEY_LAST_ERROR, e.message ?: e.javaClass.simpleName).apply()
            null
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        const val APP_VERSION = "1.1.0"
        private const val PREFS_NAME = "guardian_api"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_TOKEN = "device_token"
        private const val KEY_CONFIG_VERSION = "config_version"
        private const val KEY_HEARTBEAT_MIN = "heartbeat_min"
        private const val KEY_LAST_OK = "last_ok_at"
        private const val KEY_LAST_ERROR = "last_error"

        fun Location.toJson(): JSONObject = JSONObject()
            .put("lat", latitude)
            .put("lng", longitude)
            .put("accuracy", accuracy.toDouble())
            .put("at", time)

        /** El servidor exige al menos 8 dígitos por teléfono. */
        fun validContacts(contacts: JSONArray): JSONArray {
            val out = JSONArray()
            for (i in 0 until contacts.length()) {
                val item = contacts.optJSONObject(i) ?: continue
                val phone = item.optString("phone").trim()
                if (phone.count { it.isDigit() } >= 8) {
                    out.put(JSONObject().put("name", item.optString("name").trim()).put("phone", phone))
                }
            }
            return out
        }
    }
}
