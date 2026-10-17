import crypto from 'node:crypto';

import { Router } from 'express';

import { canAccessDevice, hashToken, identify, sameSecret } from '../auth.js';
import { alertSummary, iso, publicBase } from '../domain.js';
import { HttpError, forbidden, unauthorized } from '../errors.js';
import * as v from '../validation.js';

/**
 * API 6: la pulsera física. Lee en tiempo real la tabla de lecturas de la base
 * (pulso, oxigenación, acelerómetro y caídas marcadas por la pulsera).
 *
 * Son datos de salud, así que piden la ADMIN_KEY o el token de un cuidador o
 * una comunidad vinculados a la pulsera. La transmisión en vivo acepta la
 * clave como `?key=` porque EventSource no puede mandar encabezados.
 */
export function sensorRouter(ctx) {
  const router = Router();
  const { store } = ctx;

  const sensor = () => {
    if (!ctx.sensor) throw new HttpError(503, 'sensor_unavailable', 'No hay una pulsera conectada (falta DATABASE_URL)');
    return ctx.sensor;
  };
  const sensorDevice = () => store.data.devices[sensor().deviceId];

  const access = (req, _res, next) => {
    sensor();
    const key = req.get('x-admin-key') ?? (typeof req.query.key === 'string' ? req.query.key : null);
    if (key && sameSecret(key, ctx.config.adminKey)) {
      req.who = { kind: 'admin' };
      return next();
    }
    const who = identify(store, req) ?? (key ? store.data.tokens[hashToken(key)] : null);
    if (!who) throw unauthorized('Usa la ADMIN_KEY o el token de un cuidador vinculado a la pulsera');
    if (!canAccessDevice(who, sensorDevice())) throw forbidden('Ese token no está vinculado a la pulsera');
    req.who = who;
    next();
  };

  const status = async () => {
    const { hub } = sensor();
    const last = (await hub.latest()) ?? null;
    const seconds = last ? Math.round((ctx.now() - Date.parse(last.hora)) / 1000) : null;
    const device = sensorDevice();
    return {
      deviceId: device.id,
      personName: device.personName,
      tabla: hub.table,
      enLinea: seconds != null && seconds <= ctx.config.sensorOnlineSeconds,
      segundosDesdeUltima: seconds,
      ultimaLectura: last,
      totalLecturas: await hub.count(),
    };
  };

  router.get('/sensor', access, async (_req, res) => res.json(await status()));

  router.get('/sensor/lecturas', access, async (req, res) => {
    const limit = v.num(req.query.limit ?? 60, { name: 'limit', min: 1, max: 1000, integer: true });
    const afterId = v.num(req.query.desde, { name: 'desde', min: 0, integer: true, optional: true }) ?? null;
    res.json({ lecturas: await sensor().hub.readings({ limit, afterId }) });
  });

  router.get('/sensor/resumen', access, async (req, res) => {
    const minutes = v.num(req.query.minutos ?? 60, { name: 'minutos', min: 1, max: 10080, integer: true });
    res.json(await sensor().hub.summary(minutes));
  });

  router.get('/sensor/alertas', access, (req, res) => {
    const device = sensorDevice();
    const base = publicBase(ctx, req);
    const alerts = Object.values(store.data.alerts)
      .filter((a) => a.deviceId === device.id)
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
      .slice(0, 20)
      .map((a) => ({ ...alertSummary(a, base), sensor: a.sensor ?? null, resolution: a.resolution }));
    res.json({ alertas: alerts });
  });

  // Código para que un familiar o una comunidad se vincule con la pulsera.
  router.post('/sensor/invite', access, (req, res) => {
    if (req.who.kind !== 'admin') throw forbidden('Solo con la ADMIN_KEY');
    let code;
    do {
      code = String(crypto.randomInt(100000, 1000000));
    } while (store.data.invites[code]);
    const expiresAt = iso(ctx.now() + ctx.config.inviteTtlMs);
    store.data.invites[code] = { code, deviceId: sensorDevice().id, expiresAt };
    store.save();
    res.status(201).json({ code, expiresAt, deviceId: sensorDevice().id });
  });

  // Transmisión en vivo: un evento "lectura" por cada fila nueva y "alerta" si la pulsera marca una caída.
  router.get('/sensor/stream', access, (req, res) => {
    const { hub } = sensor();
    const device = sensorDevice();
    res.set({
      'content-type': 'text/event-stream; charset=utf-8',
      'cache-control': 'no-cache, no-transform',
      connection: 'keep-alive',
      'x-accel-buffering': 'no',
    });
    res.flushHeaders();
    const send = (event, data) => res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);

    if (hub.lastReading) send('lectura', hub.lastReading);
    const unsubscribe = hub.subscribe((readings) => {
      for (const r of readings) send('lectura', r);
    });
    const onAlert = (alert, url) => {
      if (alert.deviceId === device.id) send('alerta', { id: alert.id, type: alert.type, createdAt: alert.createdAt, url });
    };
    ctx.bus.on('alert', onAlert);
    const ping = setInterval(() => res.write(': ping\n\n'), 15000);
    req.on('close', () => {
      unsubscribe();
      ctx.bus.off('alert', onAlert);
      clearInterval(ping);
    });
  });

  return router;
}
