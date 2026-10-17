import os from 'node:os';

import { createApp } from './app.js';
import { loadConfig } from './config.js';
import { createPool } from './db.js';
import { createNotifier } from './notifier.js';
import { PgStore } from './pg-store.js';
import { createSensorHub } from './sensor.js';
import { ensureSensorDevice, linkSensorToAlerts } from './sensor-link.js';
import { Store } from './store.js';
import { createWatchdog } from './watchdog.js';

// Variables de .env, si existe (Node 20.12+).
try {
  process.loadEnvFile('.env');
} catch {
  // Sin .env: se usan las variables del sistema o los valores por defecto.
}

const config = loadConfig();
const notifier = createNotifier({ telegramToken: config.telegramToken });

// Almacenamiento: PostgreSQL (Neon) si hay DATABASE_URL; si no, archivo JSON.
const pool = config.databaseUrl ? createPool(config.databaseUrl) : null;
const store = pool ? await PgStore.open({ pool, schema: config.dbSchema }) : new Store({ file: config.dataFile });

// Pulsera física: lecturas en tiempo real desde la misma base.
let sensor = null;
if (pool && config.sensorEnabled) {
  try {
    const hub = createSensorHub({ pool, table: config.sensorTable, intervalMs: config.sensorPollMs });
    await hub.start();
    sensor = { hub, deviceId: null };
  } catch (error) {
    console.error(`[sensor] no se pudo leer ${config.sensorTable}: ${error.message}`);
  }
}

const { app, ctx } = createApp({ store, notifier, config, sensor });
if (sensor) {
  const device = ensureSensorDevice(ctx, {
    personName: config.sensorPersonName,
    table: config.sensorTable,
    rename: config.sensorPersonNameSet,
  });
  sensor.deviceId = device.id;
  linkSensorToAlerts(ctx, sensor.hub, device, { cooldownMs: config.sensorAlertCooldownMs });
}
const watchdog = createWatchdog(ctx);

setInterval(() => {
  watchdog.run().catch((error) => console.error('[vigilante]', error));
}, config.watchdogIntervalMs).unref();

app.listen(config.port, '0.0.0.0', () => {
  const lan = Object.values(os.networkInterfaces())
    .flat()
    .filter((i) => i && i.family === 'IPv4' && !i.internal)
    .map((i) => `http://${i.address}:${config.port}`);
  console.log('TzindaApp API lista');
  console.log(`  Local:      http://localhost:${config.port}`);
  for (const url of lan) console.log(`  En tu red:  ${url}   ← pon esta URL en la app del teléfono`);
  if (config.publicUrl) console.log(`  Pública:    ${config.publicUrl}`);
  console.log(`  Datos:      ${pool ? `PostgreSQL, esquema "${config.dbSchema}"` : `archivo ${config.dataFile}`}`);
  console.log(
    `  Pulsera:    ${sensor ? `leyendo ${config.sensorTable} cada ${config.sensorPollMs} ms · monitor en /sensor` : 'sin conectar'}`,
  );
  console.log(`  Telegram:   ${config.telegramToken ? 'activado' : 'desactivado (define TELEGRAM_BOT_TOKEN)'}`);
  if (config.generatedAdminKey) {
    console.log(`  ADMIN_KEY temporal para crear comunidades y ver el monitor: ${config.adminKey}`);
  }
});

let closing = false;
const shutdown = async () => {
  if (closing) return;
  closing = true;
  sensor?.hub.stop();
  try {
    await store.flush();
  } finally {
    await pool?.end().catch(() => {});
    process.exit(0);
  }
};
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
