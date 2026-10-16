# API de Guardian IoT

Servidor opcional que agrega a la app lo que un teléfono solo no puede hacer: saber si el teléfono se apagó, mandar un link con mapa en vivo, dejar que la familia responda "Voy en camino", configurar a distancia y llevar estadísticas.

El SMS desde el chip **sigue siendo el canal principal** (funciona sin internet). Si el servidor no responde en ~9 segundos, la app manda el SMS como siempre, sin link.

- Código: [`backend/`](../backend)
- Base: `http://<servidor>:8080/api/v1`
- Formato: JSON en UTF-8. Errores: `{ "error": "bad_request", "message": "..." }`

## Autenticación

| Quién | Cómo obtiene su token | Header |
|---|---|---|
| Teléfono | `POST /devices` | `Authorization: Bearer <deviceToken>` |
| Familiar / cuidador | `POST /caregivers` | `Authorization: Bearer <caregiverToken>` |
| Comunidad | `POST /communities` (requiere `X-Admin-Key`) | `Authorization: Bearer <communityToken>` |
| Quien tenga el link de una alerta | — (el id es aleatorio, no adivinable) | ninguno |

El servidor guarda solo el hash SHA-256 de cada token.

---

## 1. Dispositivos y latido

### `POST /devices` — registrar el teléfono

```json
{ "personName": "Doña María", "model": "Moto G5", "androidVersion": "8.1",
  "config": { "contacts": [{ "name": "Ana", "phone": "5511112222" }] } }
```

`201` → `{ "deviceId": "dev_x1", "deviceToken": "…", "heartbeatEveryMin": 15, "config": {…}, "configVersion": 1 }`

### `POST /devices/:id/heartbeat` — latido (teléfono, cada 15 min)

```json
{ "battery": 64, "charging": false, "monitoring": true,
  "location": { "lat": 19.4326, "lng": -99.1332, "accuracy": 15 } }
```

`200` → `{ "ok": true, "configVersion": 2, "config": {…}, "heartbeatEveryMin": 15 }`

Si `configVersion` subió, un familiar cambió algo y la app lo aplica.

Además, el servidor:
- avisa **una vez** si la batería baja de 15 % sin cargar;
- con el vigilante (cada minuto) avisa si el teléfono pasa **45 min sin reportarse**, y avisa de nuevo cuando vuelve.

### `GET /devices/:id` — estado (teléfono, cuidador vinculado o comunidad)

`status`: `online` | `offline` | `never`, más batería, última ubicación y alertas abiertas.

---

## 2. Alertas

### `POST /alerts` — crear (teléfono, al terminar la cuenta regresiva)

```json
{ "type": "fall", "peak": 31.4, "battery": 64,
  "location": { "lat": 19.4326, "lng": -99.1332, "accuracy": 12 } }
```

`201` → `{ "alertId": "al_Xy…", "url": "https://servidor/a/al_Xy…", "status": "active" }`

La app pone ese `url` en el SMS. `type`: `fall` | `sos`.

### `POST` (o `PATCH`) `/alerts/:id/location` — ubicación en vivo (teléfono, cada minuto)

```json
{ "lat": 19.4327, "lng": -99.1333, "accuracy": 8 }
```

`200` → `{ "status": "acknowledged", "acks": [{ "name": "Ana", "etaMinutes": 15, … }] }`. Con eso el teléfono muestra *"Ana va en camino"* y deja de llamar.

### `GET /alerts/:id` — ver la alerta (público con el link)

Persona, tipo, estado, últimas 30 ubicaciones, confirmaciones, escaladas y cierre.

### `POST /alerts/:id/ack` — "Voy en camino" (público con el link, o con token de cuidador)

```json
{ "name": "Ana", "etaMinutes": 15, "message": "Salgo del trabajo" }
```

El estado pasa a `acknowledged` y se avisa a los demás cuidadores.

**Escalada:** si nadie responde en 3 minutos, el servidor vuelve a avisar a todos (*"SIN RESPUESTA (1)"*), hasta 3 veces.

### `POST /alerts/:id/resolve` — cerrar

```json
{ "reason": "real_fall", "by": "Ana", "note": "Se resbaló en el baño" }
```

`reason`: `false_alarm` | `real_fall` | `attended` | `other`. El teléfono lo usa con el botón "Ya estoy bien".

### `GET /devices/:id/alerts?status=open&limit=50` — historial

### Página web `GET /a/:id`

Página para el familiar: mapa en vivo (OpenStreetMap), batería, botón **Voy en camino** con tiempo de llegada, botón **Abrir en Google Maps** y cierre de la alerta. Funciona en Android y iPhone sin instalar nada.

---

## 3. Cuidadores y configuración remota

| Método | Ruta | Quién | Para qué |
|---|---|---|---|
| `POST` | `/caregivers` | cualquiera | `{ name, phone?, telegramChatId? }` → `caregiverToken` |
| `GET` / `PATCH` | `/caregivers/me` | cuidador | ver o editar sus datos (p. ej. su chat de Telegram) |
| `POST` | `/devices/:id/invite` | teléfono | código de 6 dígitos que vence en 30 min |
| `POST` | `/caregivers/me/devices` | cuidador | `{ "code": "482913" }` → queda vinculado |
| `GET` | `/caregivers/me/devices` | cuidador | sus teléfonos con estado |
| `DELETE` | `/caregivers/me/devices/:deviceId` | cuidador | desvincularse |
| `GET` | `/devices/:id/config` | teléfono, cuidador, comunidad | configuración actual |
| `PUT` | `/devices/:id/config` | teléfono o cuidador | cambiar configuración |

Configuración:

```json
{ "contacts": [{ "name": "Ana", "phone": "+525511112222" }],
  "countdownSeconds": 30, "autoActions": true, "callFirst": 2 }
```

Hasta 5 contactos; `countdownSeconds` entre 10 y 120. El teléfono la recibe en su siguiente latido.

---

## 4. Eventos y estadísticas

### `POST /devices/:id/events` — uno o en lote (teléfono)

```json
{ "events": [
  { "type": "cancelled_by_user", "data": { "peak": 27.1 } },
  { "type": "impact_discarded", "data": { "peak": 25.5, "samples": [9.8, 30.1, 2.0] } }
] }
```

Tipos: `monitor_on`, `monitor_off`, `impact_discarded`, `countdown_started`, `cancelled_by_user`, `sms_result`, `call_result`, `other`.

`data.samples` es opcional (con consentimiento): hasta 2000 lecturas de aceleración alrededor de un impacto, para recalibrar los umbrales que hoy están fijos en la app (25 m/s² y 2.2 m/s²).

El servidor también registra sus propios eventos: `alert_created`, `alert_acknowledged`, `alert_resolved`, `device_offline`, `device_back_online`, `low_battery`, `escalation`, `config_updated`, `caregiver_linked`.

### `GET /devices/:id/events?type=&limit=`

### `GET /devices/:id/stats?days=30`

```json
{ "alerts": { "total": 4, "fall": 3, "sos": 1, "falseAlarm": 1, "realFall": 1, "open": 0 },
  "cancelledByUser": 6, "impactsDiscarded": 12, "falseAlarmRate": 0.78,
  "medianAckSeconds": 95, "escalations": 1, "offlineEpisodes": 0, "lowBatteryEpisodes": 2 }
```

`falseAlarmRate` = (canceladas por la persona + cerradas como falsa alarma) / posibles caídas. Es el dato para la presentación y para ajustar el algoritmo.

---

## 5. Comunidades

Para un centro de día, asilo o comité vecinal que acompaña a varias personas.

| Método | Ruta | Quién |
|---|---|---|
| `POST` | `/communities` con header `X-Admin-Key` | quien administra el servidor → `communityToken` y `dashboardUrl` |
| `POST` | `/communities/:id/devices` `{ "code": "482913" }` | comunidad |
| `GET` | `/communities/:id/dashboard` | comunidad |

El panel devuelve un resumen (teléfonos, en línea, sin reportarse, batería baja, alertas abiertas) y cada teléfono con sus alertas de la semana. Página web: `GET /c/:id` (pide el token).

---

## Avisos a familiares

| Evento | Texto |
|---|---|
| Alerta | 🚨 posible caída de Doña María. Mapa y "Voy en camino": *link* |
| SOS | 🆘 Doña María pidió ayuda con el botón SOS… |
| Alguien responde | ✅ Ana va en camino con Doña María |
| Nadie responde | ⚠️ SIN RESPUESTA (1): nadie ha confirmado la alerta… |
| Teléfono apagado | 📵 El teléfono de Doña María dejó de reportarse hace 45 min |
| Batería baja | 🔋 El teléfono de Doña María tiene 14 % de batería |

Canales: consola del servidor (siempre) y **Telegram** si defines `TELEGRAM_BOT_TOKEN` y el cuidador registra su `telegramChatId`. Más canales (push con Firebase, WhatsApp Business, correo) se agregan en `backend/src/notifier.js` sin tocar las rutas.

## Prueba rápida con curl

```bash
B=http://localhost:8080/api/v1
curl -s -X POST $B/devices -H 'content-type: application/json' -d '{"personName":"Maria"}'
# copia deviceId y deviceToken
curl -s -X POST $B/alerts -H "authorization: Bearer TOKEN" -H 'content-type: application/json' \
  -d '{"type":"fall","location":{"lat":19.4326,"lng":-99.1332,"accuracy":12}}'
# abre el "url" de la respuesta en el navegador
```
