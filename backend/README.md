# Guardian IoT · servidor

API del prototipo Guardian IoT. Documentación de cada endpoint: [`../docs/API.md`](../docs/API.md).

## Requisitos

Node.js 20 o superior.

## Arrancar

```bash
cd backend
npm install
npm start
```

Al arrancar muestra las direcciones disponibles:

```
Guardian IoT API lista
  Local:      http://localhost:8080
  En tu red:  http://192.168.1.50:8080   ← pon esta URL en la app del teléfono
```

En la app: **Ajustes → Servidor Guardian (opcional)** → escribe esa URL y el nombre de la persona → **Conectar**. La laptop y el teléfono deben estar en la misma red Wi-Fi. Si no conecta, revisa que el Firewall de Windows permita Node.js en redes privadas.

Configuración opcional: copia `.env.example` como `.env` (ver comentarios dentro).

## Probar

```bash
npm test
```

Las 16 pruebas recorren las 5 APIs con un reloj falso: latido y teléfono sin reportarse, batería baja, alerta → ubicación → "Voy en camino" → cierre, escalada sin respuesta, configuración remota, códigos vencidos, estadísticas, comunidad y permisos entre usuarios.

## Demo en 2 minutos

1. `npm start` y conecta el teléfono desde Ajustes.
2. En la app toca **Código para familiar**. Desde otra terminal registra a un familiar y vincúlalo:

   ```bash
   B=http://localhost:8080/api/v1
   curl -s -X POST $B/caregivers -H 'content-type: application/json' -d '{"name":"Ana"}'
   curl -s -X POST $B/caregivers/me/devices -H "authorization: Bearer <caregiverToken>" \
     -H 'content-type: application/json' -d '{"code":"<código>"}'
   ```

3. Provoca una posible caída (o espera la cuenta regresiva). El SMS llega con el link.
4. Abre el link en cualquier celular y toca **Voy en camino**: el teléfono de la persona muestra "Ana va en camino" y deja de llamar.

## Telegram (opcional, gratis)

1. Crea un bot con [@BotFather](https://t.me/BotFather) y pon su token en `TELEGRAM_BOT_TOKEN`.
2. Cada familiar escribe al bot y obtiene su chat id (por ejemplo con [@userinfobot](https://t.me/userinfobot)).
3. Guarda ese id con `PATCH /api/v1/caregivers/me { "telegramChatId": "123456" }`.

## Desplegar

Funciona en cualquier hosting de Node (Render, Railway, una VM). Define `PUBLIC_URL` con la URL `https://` para que los links del SMS funcionen fuera de casa. Los datos se guardan en `DATA_FILE` (JSON); para muchos usuarios cambia `src/store.js` por PostgreSQL o Firestore manteniendo la misma forma de los datos.

## Estructura

```
src/
  server.js          arranque, vigilante cada minuto
  app.js             Express, rutas y manejo de errores
  routes/            devices · alerts · caregivers · events · communities
  watchdog.js        teléfonos sin reportarse, escalada, limpieza
  notifier.js        avisos (consola + Telegram)
  messages.js        textos de los avisos
  domain.js          vistas, estadísticas, utilidades
  auth.js            tokens (se guarda solo el hash) y permisos
  validation.js      validación de entradas
  store.js           almacenamiento JSON
public/              página del link (/a/:id) y panel comunitario (/c/:id)
test/                pruebas con node:test
```
