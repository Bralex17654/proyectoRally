/**
 * Prueba contra la base real (DATABASE_URL de .env): `npm run test:db`.
 *
 * Trabaja en un esquema temporal que se borra al final. De `public.lecturas`
 * solo copia la estructura (CREATE TABLE ... LIKE); no escribe en ella.
 */
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { after, before, test } from 'node:test';

import { createApp } from '../src/app.js';
import { loadConfig } from '../src/config.js';
import { createPool } from '../src/db.js';
import { createNotifier } from '../src/notifier.js';
import { PgStore } from '../src/pg-store.js';
import { createSensorHub } from '../src/sensor.js';
import { ensureSensorDevice, linkSensorToAlerts } from '../src/sensor-link.js';

const url = process.env.DATABASE_URL;
const schema = `tzinda_prueba_${crypto.randomBytes(4).toString('hex')}`;
let pool;

before(async () => {
  assert.ok(url, 'Falta DATABASE_URL en .env');
  pool = createPool(url, { max: 3 });
  await pool.query(`create schema ${schema}`);
  await pool.query(`create table ${schema}.lecturas (like public.lecturas)`);
});

after(async () => {
  await pool.query(`drop schema if exists ${schema} cascade`);
  await pool.end();
});

const insertReading = (id, over = {}) =>
  pool.query(
    `insert into ${schema}.lecturas (id, hora, dedo, bpm, spo2, ir, rojo, saturado, max_ok, max_err, mpu_ok, mpu_err,
       ax_mg, ay_mg, az_mg, mag_mg, caida, caidas, ax_g, ay_g, az_g, mag_g)
     values ($1, now(), 1, 80, 97, 50000, 47000, 0, 1, 0, 1, 0, -40, -20, 955, 956, $2, $3, -0.040, -0.020, 0.955, $4)`,
    [id, over.caida ?? 0, over.caidas ?? 0, over.mag ?? 0.956],
  );

test('guarda y vuelve a cargar los datos de las APIs en PostgreSQL', async () => {
  const store = await PgStore.open({ pool, schema, debounceMs: 10 });
  store.data.devices.dev_a = { id: 'dev_a', personName: 'Doña Mary', caregiverIds: [], communityIds: [] };
  store.data.events.push({ id: 'ev_1', deviceId: 'dev_a', type: 'monitor_on', at: new Date().toISOString(), data: {} });
  store.data.invites['123456'] = { code: '123456', deviceId: 'dev_a', expiresAt: new Date().toISOString() };
  await store.flush();

  delete store.data.invites['123456'];
  store.data.devices.dev_a.personName = 'Doña María';
  await store.flush();

  const reloaded = await PgStore.open({ pool, schema });
  assert.equal(reloaded.data.devices.dev_a.personName, 'Doña María');
  assert.equal(reloaded.data.events.length, 1);
  assert.deepEqual(reloaded.data.invites, {});

  const view = await pool.query(`select persona from ${schema}.dispositivos where id = 'dev_a'`);
  assert.equal(view.rows[0].persona, 'Doña María');
});

test('lee lecturas nuevas en tiempo real y una caída crea una alerta guardada en la base', async () => {
  await insertReading(1);
  const store = await PgStore.open({ pool, schema, debounceMs: 10 });
  const hub = createSensorHub({ pool, table: `${schema}.lecturas`, intervalMs: 100000, log: () => {} });
  await hub.start();
  assert.equal(hub.lastReading.id, 1);

  const notifier = createNotifier({ log: () => {} });
  const { ctx } = createApp({ store, notifier, config: loadConfig({ ADMIN_KEY: 'x' }), sensor: { hub, deviceId: null } });
  const device = ensureSensorDevice(ctx, { personName: 'Don Beto', table: `${schema}.lecturas` });
  linkSensorToAlerts(ctx, hub, device);

  const seen = [];
  hub.subscribe((readings) => seen.push(...readings.map((r) => r.id)));
  await insertReading(2);
  await insertReading(3, { caida: 1, caidas: 1, mag: 4.2 });
  await hub.poll();
  assert.deepEqual(seen, [2, 3]);

  const alerts = Object.values(store.data.alerts);
  assert.equal(alerts.length, 1);
  assert.equal(alerts[0].source, 'sensor');
  assert.equal(alerts[0].peak, 4.2);

  const summary = await hub.summary(60);
  assert.equal(summary.lecturas, 3);
  assert.equal(summary.caidas, 1);
  assert.equal(summary.pulso.promedio, 80);

  await store.flush();
  const saved = await pool.query(`select origen, estado from ${schema}.alertas`);
  assert.deepEqual(saved.rows, [{ origen: 'sensor', estado: 'active' }]);
  hub.stop();
});
