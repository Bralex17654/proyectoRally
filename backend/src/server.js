import os from 'node:os';

import { createApp } from './app.js';
import { loadConfig } from './config.js';
import { createNotifier } from './notifier.js';
import { Store } from './store.js';
import { createWatchdog } from './watchdog.js';

// Variables de .env, si existe (Node 20.12+).
try {
  process.loadEnvFile('.env');
} catch {
  // Sin .env: se usan las variables del sistema o los valores por defecto.
}

const config = loadConfig();
const store = new Store({ file: config.dataFile });
const notifier = createNotifier({ telegramToken: config.telegramToken });
const { app, ctx } = createApp({ store, notifier, config });
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
  console.log(`  Telegram:   ${config.telegramToken ? 'activado' : 'desactivado (define TELEGRAM_BOT_TOKEN)'}`);
  if (config.generatedAdminKey) {
    console.log(`  ADMIN_KEY temporal para crear comunidades: ${config.adminKey}`);
  }
});

const shutdown = () => {
  store.flush();
  process.exit(0);
};
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
