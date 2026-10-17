import assert from 'node:assert/strict';
import { after, before, describe, test } from 'node:test';

import { createApp } from '../src/app.js';
import { loadConfig } from '../src/config.js';
import { createNotifier } from '../src/notifier.js';
import { detectIncidents, formatReading } from '../src/sensor.js';
import { ensureSensorDevice, linkSensorToAlerts } from '../src/sensor-link.js';
import { Store } from '../src/store.js';

/** Fila como la escribe la pulsera en public.lecturas. */
const row = (id, over = {}) => ({
  id: String(id),
  hora: new Date(Date.parse('2026-10-17T01:00:00Z') + id * 1000),
  dedo: 1,
  bpm: 81,
  spo2: 96,
  ir: '52000',
  rojo: '48000',
  saturado: 0,
  max_ok: 1,
  max_err: 0,
  mpu_ok: 1,
  mpu_err: 0,
  ax_mg: -42,
  ay_mg: -20,
  az_mg: 953,
  mag_mg: 954,
  caida: 0,
  caidas: 0,
  ax_g: '-0.042',
  ay_g: '-0.020',
  az_g: '0.953',
  mag_g: '0.954',
  ...over,
});

describe('lecturas de la pulsera', () => {
  test('formatea la fila y solo muestra pulso con dedo en el sensor', () => {
    const withFinger = formatReading(row(1));
    assert.equal(withFinger.id, 1);
    assert.equal(withFinger.pulso, 81);
    assert.equal(withFinger.spo2, 96);
    assert.equal(withFinger.acelerometro.magnitud, 0.954);
    assert.equal(withFinger.sensores.acelerometro, true);

    assert.equal(withFinger.acelerometro.pico, null, 'la v6 no manda pico');
    const v7 = formatReading(row(3, { mag_max_g: '3.420', mag_min_g: '0.380' }));
    assert.equal(v7.acelerometro.pico, 3.42);
    assert.equal(v7.acelerometro.minimo, 0.38);

    const noFinger = formatReading(row(2, { dedo: 0, bpm: 0, spo2: 0 }));
    assert.equal(noFinger.pulso, null);
    assert.equal(noFinger.spo2, null);
  });

  test('detecta caídas por cambio de bandera o de contador, no por reinicio', () => {
    const series = [
      row(1, { caidas: 1 }),
      row(2, { caida: 1, caidas: 2 }), // nueva caída
      row(3, { caida: 1, caidas: 2 }), // la misma, sigue marcada
      row(4, { caida: 1, caidas: 4 }), // el contador sube: otra caída
      row(5, { caida: 0, caidas: 4 }),
      row(6, { caida: 0, caidas: 0 }), // reinicio de la pulsera
    ].map(formatReading);
    const { incidents, last } = detectIncidents(series.at(0), series.slice(1));
    assert.deepEqual(incidents.map((r) => r.id), [2, 4]);
    assert.equal(last.id, 6);
  });
});

/** Pulsera simulada con la misma interfaz que createSensorHub. */
function fakeHub(initial) {
  const listeners = new Set();
  const all = [...initial];
  let lastReading = all.at(-1) ?? null;
  return {
    table: 'public.lecturas',
    subscribe(fn) {
      listeners.add(fn);
      return () => listeners.delete(fn);
    },
    get lastReading() {
      return lastReading;
    },
    async push(...readings) {
      const previous = lastReading;
      all.push(...readings);
      lastReading = readings.at(-1);
      for (const fn of listeners) await fn(readings, previous);
    },
    async latest() {
      return lastReading;
    },
    async count() {
      return all.length;
    },
    async readings({ limit = 60, afterId = null } = {}) {
      const list = afterId == null ? all.slice(-limit) : all.filter((r) => r.id > afterId).slice(0, limit);
      return list;
    },
    async summary(minutes) {
      return { minutos: minutes, lecturas: all.length, caidas: detectIncidents(all.at(0), all.slice(1)).incidents.length };
    },
  };
}

describe('API de la pulsera (API 6)', () => {
  let server;
  let base;
  let ctx;
  let hub;
  let notifier;
  let clock = Date.parse('2026-10-17T01:00:10Z');

  const get = async (path, headers = {}) => {
    const res = await fetch(`${base}${path}`, { headers });
    return { status: res.status, body: await res.json() };
  };

  before(async () => {
    notifier = createNotifier({ log: () => {} });
    const config = loadConfig({ ADMIN_KEY: 'clave-admin', PUBLIC_URL: 'https://tzinda.test' });
    hub = fakeHub([formatReading(row(1)), formatReading(row(2))]);
    const created = createApp({ store: new Store(), notifier, config, now: () => clock, sensor: { hub, deviceId: null } });
    ctx = created.ctx;
    const device = ensureSensorDevice(ctx, { personName: 'Doña Lupe', table: 'public.lecturas' });
    ctx.sensor.deviceId = device.id;
    linkSensorToAlerts(ctx, hub, device, { cooldownMs: 120_000 });
    await new Promise((resolve) => {
      server = created.app.listen(0, '127.0.0.1', resolve);
    });
    base = `http://127.0.0.1:${server.address().port}`;
  });

  after(() => server.close());

  test('pide la ADMIN_KEY o un token vinculado', async () => {
    assert.equal((await get('/api/v1/sensor')).status, 401);
    assert.equal((await get('/api/v1/sensor', { 'x-admin-key': 'mal' })).status, 401);
    const ok = await get('/api/v1/sensor', { 'x-admin-key': 'clave-admin' });
    assert.equal(ok.status, 200);
    assert.equal(ok.body.personName, 'Doña Lupe');
    assert.equal(ok.body.enLinea, true);
    assert.equal(ok.body.totalLecturas, 2);
  });

  test('un familiar vinculado con el código de la pulsera puede ver las lecturas', async () => {
    const invite = await fetch(`${base}/api/v1/sensor/invite`, { method: 'POST', headers: { 'x-admin-key': 'clave-admin' } });
    const { code } = await invite.json();
    const cg = await (await fetch(`${base}/api/v1/caregivers`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ name: 'Ana' }),
    })).json();
    const stranger = await get('/api/v1/sensor/lecturas', { authorization: `Bearer ${cg.caregiverToken}` });
    assert.equal(stranger.status, 403);
    await fetch(`${base}/api/v1/caregivers/me/devices`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${cg.caregiverToken}` },
      body: JSON.stringify({ code }),
    });
    const readings = await get(`/api/v1/sensor/lecturas?limit=10&key=${cg.caregiverToken}`);
    assert.equal(readings.status, 200);
    assert.equal(readings.body.lecturas.length, 2);
  });

  test('una caída marcada por la pulsera crea una sola alerta con link', async () => {
    notifier.sent.length = 0;
    await hub.push(formatReading(row(3, { caida: 1, caidas: 1, mag_g: '4.643' })), formatReading(row(4, { caida: 1, caidas: 1 })));
    const alerts = Object.values(ctx.store.data.alerts);
    assert.equal(alerts.length, 1);
    assert.equal(alerts[0].source, 'sensor');
    assert.equal(alerts[0].peak, 4.643);
    assert.match(notifier.sent.find((n) => n.kind === 'alert').text, /la pulsera detectó una posible caída de Doña Lupe/);

    // Otra caída dentro del tiempo de espera solo se registra como evento.
    clock += 30_000;
    await hub.push(formatReading(row(5, { caida: 0, caidas: 1 })), formatReading(row(6, { caida: 1, caidas: 2 })));
    assert.equal(Object.values(ctx.store.data.alerts).length, 1);
    assert.ok(ctx.store.data.events.some((e) => e.type === 'sensor_fall_repeat'));

    const list = await get('/api/v1/sensor/alertas', { 'x-admin-key': 'clave-admin' });
    assert.equal(list.body.alertas.length, 1);
    assert.match(list.body.alertas[0].url, /^https:\/\/tzinda\.test\/a\/al_/);
  });

  test('el teléfono de la persona recibe la orden de hacer sonar su alarma', async () => {
    // Un teléfono registrado en el servidor.
    const reg = await (await fetch(`${base}/api/v1/devices`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ personName: 'Doña Lupe', model: 'samsung SM-A256E' }),
    })).json();
    const poll = async () => {
      const res = await fetch(`${base}/api/v1/devices/${reg.deviceId}/commands`, { headers: { authorization: `Bearer ${reg.deviceToken}` } });
      return (await res.json()).commands;
    };
    assert.deepEqual(await poll(), []);

    clock += 200_000; // pasa el tiempo de espera entre alertas
    await hub.push(formatReading(row(20, { caida: 0, caidas: 2 })), formatReading(row(21, { caida: 1, caidas: 3 })));
    const commands = await poll();
    assert.equal(commands.length, 1);
    assert.equal(commands[0].type, 'fall_alarm');
    assert.equal(typeof commands[0].peakG, 'number', 'el teléfono muestra el golpe en g');
    const alert = ctx.store.data.alerts[commands[0].alertId];
    assert.equal(alert.source, 'sensor');
    assert.equal(commands[0].url, `https://tzinda.test/a/${alert.id}`);
    assert.deepEqual(await poll(), [], 'la orden se entrega una sola vez');

    // Una orden vieja (el teléfono estuvo apagado) ya no hace sonar la alarma.
    clock += 200_000;
    await hub.push(formatReading(row(22, { caida: 1, caidas: 4 })));
    clock += 130_000;
    assert.deepEqual(await poll(), []);

    const st = await get('/api/v1/sensor', { 'x-admin-key': 'clave-admin' });
    assert.ok(st.body.telefonos.some((t) => t.id === reg.deviceId));

    // El teléfono acompañante ve la pulsera (pico v7 incluido), pero no puede simular caídas.
    const phoneAuth = { authorization: `Bearer ${reg.deviceToken}` };
    const own = await get('/api/v1/sensor', phoneAuth);
    assert.equal(own.status, 200);
    assert.ok('pico' in own.body.ultimaLectura.acelerometro);
    const sim = await fetch(`${base}/api/v1/sensor/simular-caida`, { method: 'POST', headers: phoneAuth });
    assert.equal(sim.status, 403);
  });

  test('simular una caída hace sonar la alarma del teléfono sin tocar las lecturas', async () => {
    const denied = await fetch(`${base}/api/v1/sensor/simular-caida`, { method: 'POST' });
    assert.equal(denied.status, 401);
    const before = (await hub.readings({ limit: 1000 })).length;
    const res = await fetch(`${base}/api/v1/sensor/simular-caida`, { method: 'POST', headers: { 'x-admin-key': 'clave-admin' } });
    assert.equal(res.status, 201);
    const body = await res.json();
    assert.ok(body.telefonos.length >= 1);
    assert.equal(ctx.store.data.alerts[body.alertId].sensor.simulada, true);
    const phone = ctx.store.data.devices[body.telefonos.at(0)];
    assert.ok(phone.commands.some((c) => c.alertId === body.alertId));
    assert.equal((await hub.readings({ limit: 1000 })).length, before);
  });

  test('avisa una vez si el acelerómetro de la pulsera falla', async () => {
    notifier.sent.length = 0;
    await hub.push(formatReading(row(30, { mpu_ok: 0, mpu_err: 1, mag_g: '0.000' })), formatReading(row(31, { mpu_ok: 0, mpu_err: 1 })));
    await hub.push(formatReading(row(32, { mpu_ok: 0, mpu_err: 1 })));
    assert.equal(notifier.sent.filter((n) => n.kind === 'sensor_error').length, 1);
    assert.match(notifier.sent.find((n) => n.kind === 'sensor_error').text, /acelerómetro de la pulsera de Doña Lupe dejó de funcionar/);
    await hub.push(formatReading(row(33)));
    assert.ok(ctx.store.data.events.some((e) => e.type === 'sensor_accel_ok'));
  });

  test('las lecturas cuentan como latido del dispositivo', async () => {
    const device = ctx.store.data.devices[ctx.sensor.deviceId];
    assert.equal(device.lastHeartbeat.source, 'sensor');
    assert.equal(device.kind, 'sensor');
  });

  test('transmite lecturas en vivo por Server-Sent Events', async () => {
    const controller = new AbortController();
    const res = await fetch(`${base}/api/v1/sensor/stream?key=clave-admin`, { signal: controller.signal });
    assert.equal(res.status, 200);
    assert.match(res.headers.get('content-type'), /text\/event-stream/);
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let text = decoder.decode((await reader.read()).value);
    await hub.push(formatReading(row(7, { caidas: 2 })));
    while (!text.includes('"id":7')) text += decoder.decode((await reader.read()).value);
    assert.match(text, /event: lectura/);
    controller.abort();
  });

  test('sin pulsera conectada responde 503', async () => {
    const { app } = createApp({ store: new Store(), notifier, config: loadConfig({ ADMIN_KEY: 'x' }) });
    const s = await new Promise((resolve) => {
      const srv = app.listen(0, '127.0.0.1', () => resolve(srv));
    });
    const res = await fetch(`http://127.0.0.1:${s.address().port}/api/v1/sensor`, { headers: { 'x-admin-key': 'x' } });
    assert.equal(res.status, 503);
    s.close();
  });
});

describe('links públicos', () => {
  test('un request desde localhost no reemplaza la dirección de red del teléfono', async () => {
    const { publicBase } = await import('../src/domain.js');
    const ctx = { config: { publicUrl: '' }, lastBase: null };
    const req = (host) => ({ protocol: 'http', get: () => host });
    assert.equal(publicBase(ctx, req('192.168.120.204:8080')), 'http://192.168.120.204:8080');
    assert.equal(publicBase(ctx, req('localhost:8080')), 'http://192.168.120.204:8080');
    assert.equal(publicBase(ctx, req('127.0.0.1:8080')), 'http://192.168.120.204:8080');
    assert.equal(publicBase(ctx), 'http://192.168.120.204:8080');
    assert.equal(publicBase({ config: { publicUrl: 'https://tzinda.app' } }, req('localhost')), 'https://tzinda.app');
  });
});
