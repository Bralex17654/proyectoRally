import assert from 'node:assert/strict';
import { after, before, beforeEach, describe, test } from 'node:test';

import { createApp } from '../src/app.js';
import { loadConfig } from '../src/config.js';
import { createNotifier } from '../src/notifier.js';
import { Store } from '../src/store.js';
import { createWatchdog } from '../src/watchdog.js';

const MIN = 60 * 1000;

let server;
let base;
let ctx;
let clock;
let notifier;
let watchdog;

/** Cliente mínimo: devuelve { status, body }. */
async function call(method, path, { token, body, headers = {} } = {}) {
  const res = await fetch(`${base}${path}`, {
    method,
    headers: {
      'content-type': 'application/json',
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null };
}

before(async () => {
  clock = Date.parse('2026-10-16T12:00:00Z');
  notifier = createNotifier({ log: () => {} });
  const config = loadConfig({ ADMIN_KEY: 'clave-admin', PUBLIC_URL: 'https://guardian.test' });
  // Reloj falso compartido por la app y el vigilante.
  const created = createApp({ store: new Store(), notifier, config, now: () => clock });
  const { app } = created;
  ctx = created.ctx;
  watchdog = createWatchdog(ctx);
  await new Promise((resolve) => {
    server = app.listen(0, '127.0.0.1', resolve);
  });
  base = `http://127.0.0.1:${server.address().port}`;
});

after(() => server.close());

beforeEach(() => {
  notifier.sent.length = 0;
});

async function registerDevice(personName = 'Doña María') {
  const res = await call('POST', '/api/v1/devices', {
    body: { personName, model: 'Moto G5', androidVersion: '8.1', appVersion: '1.0.0' },
  });
  assert.equal(res.status, 201);
  return res.body;
}

async function linkCaregiver(device, name = 'Ana') {
  const cg = await call('POST', '/api/v1/caregivers', { body: { name, phone: '55 1234 5678', telegramChatId: '123' } });
  assert.equal(cg.status, 201);
  const invite = await call('POST', `/api/v1/devices/${device.deviceId}/invite`, { token: device.deviceToken });
  assert.equal(invite.status, 201);
  assert.match(invite.body.code, /^\d{6}$/);
  const link = await call('POST', '/api/v1/caregivers/me/devices', {
    token: cg.body.caregiverToken,
    body: { code: invite.body.code },
  });
  assert.equal(link.status, 201);
  return cg.body;
}

describe('API 1 · dispositivos y latido', () => {
  test('registra un teléfono y recibe su latido', async () => {
    const device = await registerDevice();
    assert.ok(device.deviceToken.length > 20);
    assert.equal(device.config.countdownSeconds, 20);

    const hb = await call('POST', `/api/v1/devices/${device.deviceId}/heartbeat`, {
      token: device.deviceToken,
      body: { battery: 80, charging: false, monitoring: true, location: { lat: 19.43, lng: -99.13, accuracy: 15 } },
    });
    assert.equal(hb.status, 200);
    assert.equal(hb.body.heartbeatEveryMin, 15);

    const view = await call('GET', `/api/v1/devices/${device.deviceId}`, { token: device.deviceToken });
    assert.equal(view.body.status, 'online');
    assert.equal(view.body.battery, 80);
    assert.equal(view.body.lastLocation.lat, 19.43);
  });

  test('rechaza tokens inválidos y el acceso a otros teléfonos', async () => {
    const a = await registerDevice('A');
    const b = await registerDevice('B');
    const noToken = await call('POST', `/api/v1/devices/${a.deviceId}/heartbeat`, { body: {} });
    assert.equal(noToken.status, 401);
    const wrong = await call('POST', `/api/v1/devices/${a.deviceId}/heartbeat`, { token: 'falso', body: {} });
    assert.equal(wrong.status, 401);
    const other = await call('GET', `/api/v1/devices/${a.deviceId}`, { token: b.deviceToken });
    assert.equal(other.status, 403);
  });

  test('valida los datos del latido', async () => {
    const device = await registerDevice();
    const bad = await call('POST', `/api/v1/devices/${device.deviceId}/heartbeat`, {
      token: device.deviceToken,
      body: { battery: 140, location: { lat: 200, lng: 0 } },
    });
    assert.equal(bad.status, 400);
    assert.match(bad.body.message, /battery/);
  });

  test('avisa una sola vez por batería baja y se rearma al cargar', async () => {
    const device = await registerDevice();
    await linkCaregiver(device);
    notifier.sent.length = 0;
    const beat = (battery, charging = false) =>
      call('POST', `/api/v1/devices/${device.deviceId}/heartbeat`, { token: device.deviceToken, body: { battery, charging } });

    await beat(14);
    await beat(12);
    assert.equal(notifier.sent.filter((n) => n.kind === 'low_battery').length, 1);
    await beat(30, true);
    await beat(13);
    assert.equal(notifier.sent.filter((n) => n.kind === 'low_battery').length, 2);
  });

  test('el vigilante avisa si el teléfono deja de reportarse y cuando vuelve', async () => {
    const device = await registerDevice('Don Luis');
    await linkCaregiver(device);
    await call('POST', `/api/v1/devices/${device.deviceId}/heartbeat`, { token: device.deviceToken, body: { battery: 50 } });
    notifier.sent.length = 0;

    clock += 30 * MIN;
    await watchdog.run();
    assert.equal(notifier.sent.filter((n) => n.kind === 'offline' && n.deviceId === device.deviceId).length, 0);

    clock += 20 * MIN;
    await watchdog.run();
    await watchdog.run();
    const mine = (kind) => notifier.sent.filter((n) => n.kind === kind && n.deviceId === device.deviceId);
    const offline = mine('offline');
    assert.equal(offline.length, 1, 'un solo aviso aunque el vigilante corra varias veces');
    assert.match(offline[0].text, /Don Luis dejó de reportarse hace 50 min/);
    assert.equal(offline[0].telegramDelivered, 0, 'sin token de Telegram solo se registra');

    await call('POST', `/api/v1/devices/${device.deviceId}/heartbeat`, { token: device.deviceToken, body: { battery: 49 } });
    assert.equal(mine('back_online').length, 1);
  });
});

describe('API 2 · alertas', () => {
  test('flujo completo: alerta → ubicación → "Voy en camino" → cierre', async () => {
    const device = await registerDevice('Doña María');
    await linkCaregiver(device, 'Ana');
    notifier.sent.length = 0;

    const created = await call('POST', '/api/v1/alerts', {
      token: device.deviceToken,
      body: { type: 'fall', peak: 31.4, battery: 64, sms: { sent: 3, failed: 1 }, location: { lat: 19.4326, lng: -99.1332, accuracy: 12 } },
    });
    assert.equal(created.status, 201);
    assert.equal(created.body.url, `https://guardian.test/a/${created.body.alertId}`);
    const alertNotice = notifier.sent.find((n) => n.kind === 'alert');
    assert.match(alertNotice.text, /posible caída de Doña María/);
    assert.match(alertNotice.text, new RegExp(created.body.alertId));

    const alertId = created.body.alertId;
    const moved = await call('PATCH', `/api/v1/alerts/${alertId}/location`, {
      token: device.deviceToken,
      body: { lat: 19.4327, lng: -99.1333, accuracy: 8 },
    });
    assert.equal(moved.status, 200);

    // La página pública no necesita token.
    const pub = await call('GET', `/api/v1/alerts/${alertId}`);
    assert.equal(pub.status, 200);
    assert.equal(pub.body.status, 'active');
    assert.equal(pub.body.latestLocation.accuracy, 8);
    assert.equal(pub.body.locations.length, 2);

    clock += 2 * MIN;
    const ack = await call('POST', `/api/v1/alerts/${alertId}/ack`, { body: { name: 'Ana', etaMinutes: 15 } });
    assert.equal(ack.status, 200);
    assert.equal(ack.body.status, 'acknowledged');
    assert.equal(ack.body.acks[0].etaMinutes, 15);
    assert.match(notifier.sent.find((n) => n.kind === 'ack').text, /Ana va en camino con Doña María/);

    // El teléfono consulta el estado para mostrar "Ana va en camino".
    const polled = await call('PATCH', `/api/v1/alerts/${alertId}/location`, {
      token: device.deviceToken,
      body: { lat: 19.4327, lng: -99.1333 },
    });
    assert.equal(polled.body.acks[0].name, 'Ana');

    const resolved = await call('POST', `/api/v1/alerts/${alertId}/resolve`, {
      body: { reason: 'real_fall', by: 'Ana', note: 'Se resbaló en el baño, está bien' },
    });
    assert.equal(resolved.status, 200);
    assert.equal(resolved.body.status, 'resolved');

    const again = await call('POST', `/api/v1/alerts/${alertId}/ack`, { body: { name: 'Luis' } });
    assert.equal(again.status, 409);

    const list = await call('GET', `/api/v1/devices/${device.deviceId}/alerts`, { token: device.deviceToken });
    assert.equal(list.body.alerts.length, 1);
    assert.equal(list.body.alerts[0].resolution.reason, 'real_fall');
  });

  test('una alerta sin respuesta se escala cada 3 minutos hasta 3 veces', async () => {
    const device = await registerDevice('Don Pepe');
    await linkCaregiver(device);
    const created = await call('POST', '/api/v1/alerts', { token: device.deviceToken, body: { type: 'sos' } });
    assert.match(notifier.sent.find((n) => n.kind === 'alert').text, /Don Pepe pidió ayuda con el botón SOS/);
    notifier.sent.length = 0;

    for (let i = 0; i < 5; i++) {
      clock += 3 * MIN;
      await watchdog.run();
    }
    const escalations = notifier.sent.filter((n) => n.kind === 'escalation');
    assert.equal(escalations.length, 3);
    assert.match(escalations[0].text, /SIN RESPUESTA \(1\)/);

    const view = await call('GET', `/api/v1/alerts/${created.body.alertId}`);
    assert.equal(view.body.escalations, 3);
  });

  test('solo el teléfono dueño puede crear alertas o mover su ubicación', async () => {
    const owner = await registerDevice();
    const intruder = await registerDevice();
    const noAuth = await call('POST', '/api/v1/alerts', { body: { type: 'fall' } });
    assert.equal(noAuth.status, 401);
    const created = await call('POST', '/api/v1/alerts', { token: owner.deviceToken, body: { type: 'fall' } });
    const hijack = await call('PATCH', `/api/v1/alerts/${created.body.alertId}/location`, {
      token: intruder.deviceToken,
      body: { lat: 0, lng: 0 },
    });
    assert.equal(hijack.status, 404);
    const badType = await call('POST', '/api/v1/alerts', { token: owner.deviceToken, body: { type: 'terremoto' } });
    assert.equal(badType.status, 400);
  });
});

describe('API 3 · cuidadores y configuración remota', () => {
  test('el cuidador cambia los contactos y el teléfono los recibe en el latido', async () => {
    const device = await registerDevice();
    const caregiver = await linkCaregiver(device);

    const mine = await call('GET', '/api/v1/caregivers/me/devices', { token: caregiver.caregiverToken });
    assert.equal(mine.body.devices.length, 1);

    const put = await call('PUT', `/api/v1/devices/${device.deviceId}/config`, {
      token: caregiver.caregiverToken,
      body: { contacts: [{ name: 'Ana', phone: '+52 55 1111 2222' }, { name: 'Luis', phone: '55-3333-4444' }], countdownSeconds: 30 },
    });
    assert.equal(put.status, 200);
    assert.equal(put.body.configVersion, 2);
    assert.equal(put.body.config.contacts[0].phone, '+525511112222');

    const hb = await call('POST', `/api/v1/devices/${device.deviceId}/heartbeat`, { token: device.deviceToken, body: {} });
    assert.equal(hb.body.configVersion, 2);
    assert.equal(hb.body.config.countdownSeconds, 30);
    assert.equal(hb.body.config.contacts.length, 2);
  });

  test('los códigos de invitación vencen y no se reutilizan', async () => {
    const device = await registerDevice();
    const cg = await call('POST', '/api/v1/caregivers', { body: { name: 'Rosa' } });
    const invite = await call('POST', `/api/v1/devices/${device.deviceId}/invite`, { token: device.deviceToken });

    clock += 31 * MIN;
    const late = await call('POST', '/api/v1/caregivers/me/devices', { token: cg.body.caregiverToken, body: { code: invite.body.code } });
    assert.equal(late.status, 400);

    const fresh = await call('POST', `/api/v1/devices/${device.deviceId}/invite`, { token: device.deviceToken });
    const ok = await call('POST', '/api/v1/caregivers/me/devices', { token: cg.body.caregiverToken, body: { code: fresh.body.code } });
    assert.equal(ok.status, 201);
    const reuse = await call('POST', '/api/v1/caregivers/me/devices', { token: cg.body.caregiverToken, body: { code: fresh.body.code } });
    assert.equal(reuse.status, 400);
  });

  test('un cuidador no vinculado no ve el teléfono', async () => {
    const device = await registerDevice();
    const stranger = await call('POST', '/api/v1/caregivers', { body: { name: 'Extraño' } });
    const res = await call('GET', `/api/v1/devices/${device.deviceId}`, { token: stranger.body.caregiverToken });
    assert.equal(res.status, 403);
    const put = await call('PUT', `/api/v1/devices/${device.deviceId}/config`, {
      token: stranger.body.caregiverToken,
      body: { contacts: [] },
    });
    assert.equal(put.status, 403);
  });
});

describe('API 4 · eventos y estadísticas', () => {
  test('registra eventos en lote y calcula falsas alarmas y tiempo de respuesta', async () => {
    const device = await registerDevice();
    const events = await call('POST', `/api/v1/devices/${device.deviceId}/events`, {
      token: device.deviceToken,
      body: {
        events: [
          { type: 'cancelled_by_user', data: { peak: 27.1 } },
          { type: 'cancelled_by_user', data: { peak: 26.0 } },
          { type: 'impact_discarded', data: { peak: 25.5, samples: [9.8, 30.1, 2.0, 1.1] } },
        ],
      },
    });
    assert.equal(events.status, 201);
    assert.equal(events.body.saved, 3);

    const alert = await call('POST', '/api/v1/alerts', { token: device.deviceToken, body: { type: 'fall' } });
    clock += 90 * 1000;
    await call('POST', `/api/v1/alerts/${alert.body.alertId}/ack`, { body: { name: 'Ana' } });
    await call('POST', `/api/v1/alerts/${alert.body.alertId}/resolve`, { body: { reason: 'false_alarm' } });

    const stats = await call('GET', `/api/v1/devices/${device.deviceId}/stats`, { token: device.deviceToken });
    assert.equal(stats.status, 200);
    assert.equal(stats.body.cancelledByUser, 2);
    assert.equal(stats.body.impactsDiscarded, 1);
    assert.equal(stats.body.alerts.falseAlarm, 1);
    assert.equal(stats.body.falseAlarmRate, 1);
    assert.equal(stats.body.medianAckSeconds, 90);

    const list = await call('GET', `/api/v1/devices/${device.deviceId}/events?type=impact_discarded`, { token: device.deviceToken });
    assert.equal(list.body.events[0].data.samples, '4 lecturas');
  });

  test('rechaza tipos de evento desconocidos', async () => {
    const device = await registerDevice();
    const res = await call('POST', `/api/v1/devices/${device.deviceId}/events`, {
      token: device.deviceToken,
      body: { type: 'hackeo' },
    });
    assert.equal(res.status, 400);
  });
});

describe('API 5 · comunidades', () => {
  test('una comunidad agrupa teléfonos y ve su estado', async () => {
    const denied = await call('POST', '/api/v1/communities', { body: { name: 'Casa de día' }, headers: { 'x-admin-key': 'mal' } });
    assert.equal(denied.status, 401);

    const community = await call('POST', '/api/v1/communities', {
      body: { name: 'Casa de día San Juan' },
      headers: { 'x-admin-key': 'clave-admin' },
    });
    assert.equal(community.status, 201);
    assert.match(community.body.dashboardUrl, /\/c\/com_/);
    const { communityId, communityToken } = community.body;

    const device = await registerDevice('Doña Carmen');
    await call('POST', `/api/v1/devices/${device.deviceId}/heartbeat`, { token: device.deviceToken, body: { battery: 10 } });
    const invite = await call('POST', `/api/v1/devices/${device.deviceId}/invite`, { token: device.deviceToken });
    const join = await call('POST', `/api/v1/communities/${communityId}/devices`, {
      token: communityToken,
      body: { code: invite.body.code },
    });
    assert.equal(join.status, 201);
    await call('POST', '/api/v1/alerts', { token: device.deviceToken, body: { type: 'fall' } });

    const dash = await call('GET', `/api/v1/communities/${communityId}/dashboard`, { token: communityToken });
    assert.equal(dash.status, 200);
    assert.equal(dash.body.summary.devices, 1);
    assert.equal(dash.body.summary.lowBattery, 1);
    assert.equal(dash.body.summary.openAlerts, 1);
    assert.equal(dash.body.devices[0].personName, 'Doña Carmen');

    const other = await call('POST', '/api/v1/communities', { body: { name: 'Otra' }, headers: { 'x-admin-key': 'clave-admin' } });
    const peek = await call('GET', `/api/v1/communities/${communityId}/dashboard`, { token: other.body.communityToken });
    assert.equal(peek.status, 403);
  });
});

describe('general', () => {
  test('responde JSON claro ante errores', async () => {
    const res = await fetch(`${base}/api/v1/devices`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{malo' });
    assert.equal(res.status, 400);
    assert.match((await res.json()).message, /JSON/);
    const missing = await call('GET', '/api/v1/nada');
    assert.equal(missing.status, 404);
    const health = await call('GET', '/health');
    assert.equal(health.body.ok, true);
  });

  test('sirve la página del link de la alerta', async () => {
    const res = await fetch(`${base}/a/al_cualquiera`);
    assert.equal(res.status, 200);
    assert.match(await res.text(), /Voy en camino/);
  });
});
