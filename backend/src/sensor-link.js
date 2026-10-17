import { raiseAlert } from './alert-service.js';
import { issueToken, newId } from './auth.js';
import { defaultConfig, iso, logEvent, publicBase } from './domain.js';
import { messages } from './messages.js';
import { detectIncidents } from './sensor.js';

const HEARTBEAT_EVERY_MS = 30_000;

/**
 * La pulsera física se representa como un dispositivo más: así recibe
 * cuidadores (con código de invitación), aparece en el panel comunitario y
 * sus caídas usan el mismo protocolo de alertas que el teléfono.
 */
export function ensureSensorDevice(ctx, { personName, table, rename = false }) {
  const { store } = ctx;
  const current = store.data.settings.sensor;
  const existing = current?.deviceId ? store.data.devices[current.deviceId] : null;
  if (existing) {
    // SENSOR_PERSON_NAME en .env cambia el nombre que ven los familiares.
    if (rename && existing.personName !== personName) {
      existing.personName = personName;
      store.save();
    }
    return existing;
  }

  const id = newId('dev', 6);
  issueToken(store, 'device', id);
  store.data.devices[id] = {
    id,
    kind: 'sensor',
    personName,
    model: 'Pulsera física (MAX30102 + MPU6050)',
    androidVersion: '',
    appVersion: '',
    createdAt: iso(ctx.now()),
    caregiverIds: [],
    communityIds: [],
    config: defaultConfig(),
    configVersion: 1,
    lastHeartbeat: null,
    flags: { offlineNotified: false, lowBatteryNotified: false },
  };
  store.data.settings.sensor = { deviceId: id, table, createdAt: iso(ctx.now()) };
  logEvent(ctx, id, 'device_registered', { model: 'Pulsera física', table });
  store.save();
  return store.data.devices[id];
}

/** Cada lectura nueva cuenta como latido; cada caída marcada por la pulsera crea una alerta. */
export function linkSensorToAlerts(ctx, hub, device, { cooldownMs = 120_000 } = {}) {
  let lastAlertAt = 0;
  let lastBeatAt = 0;
  const window = [];

  return hub.subscribe(async (readings, previous) => {
    const now = ctx.now();
    for (const r of readings) {
      window.push(r);
      if (window.length > 10) window.shift();
    }

    if (now - lastBeatAt >= HEARTBEAT_EVERY_MS) {
      lastBeatAt = now;
      const last = readings.at(-1);
      device.lastHeartbeat = {
        at: last.hora,
        battery: null,
        charging: null,
        monitoring: last.sensores.acelerometro,
        location: null,
        appVersion: null,
        source: 'sensor',
      };
      if (device.flags.offlineNotified) {
        device.flags.offlineNotified = false;
        logEvent(ctx, device.id, 'device_back_online', { source: 'sensor' });
        await ctx.notifier.toCaregivers(ctx.store, device.id, 'back_online', messages.backOnline(device));
      }
      ctx.store.save();
    }

    const { incidents } = detectIncidents(previous, readings);
    for (const r of incidents) {
      const sensor = { lecturaId: r.id, caidas: r.caidas, pulso: r.pulso, spo2: r.spo2, magnitud: r.acelerometro.magnitud };
      if (now - lastAlertAt < cooldownMs) {
        logEvent(ctx, device.id, 'sensor_fall_repeat', sensor, { source: 'device' });
        continue;
      }
      lastAlertAt = now;
      const peak = Math.max(...window.map((w) => w.acelerometro.magnitud ?? 0));
      await raiseAlert(
        ctx,
        device,
        { type: 'fall', source: 'sensor', detectedAt: r.hora, peak, sensor },
        publicBase(ctx),
      );
    }
  });
}
