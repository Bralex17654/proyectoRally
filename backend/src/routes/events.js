import { Router } from 'express';

import { assertDeviceAccess, getDevice, requireAuth } from '../auth.js';
import { DEVICE_EVENT_TYPES, deviceStats, logEvent } from '../domain.js';
import { badRequest } from '../errors.js';
import * as v from '../validation.js';

const MAX_BATCH = 100;
const MAX_DATA_BYTES = 16 * 1024;
const MAX_SAMPLES = 2000;

/**
 * API 4: eventos del teléfono y estadísticas para calibrar el algoritmo.
 *
 * `data.samples` (opcional, con consentimiento) es una lista de magnitudes de
 * aceleración alrededor de un impacto: sirve para ajustar los umbrales
 * 25 m/s² y 2.2 m/s² que hoy están fijos en la app.
 */
export function eventsRouter(ctx) {
  const router = Router();
  const { store } = ctx;
  const anyone = requireAuth(store, 'device', 'caregiver', 'community');

  router.post('/devices/:id/events', requireAuth(store, 'device'), (req, res) => {
    const device = getDevice(store, req.params.id);
    assertDeviceAccess(req.who, device, ['device']);
    const raw = Array.isArray(req.body?.events) ? req.body.events : [req.body ?? {}];
    if (raw.length === 0 || raw.length > MAX_BATCH) throw badRequest(`Envía entre 1 y ${MAX_BATCH} eventos`);

    const saved = raw.map((e, i) => {
      const type = v.oneOf(e?.type, DEVICE_EVENT_TYPES, { name: `events[${i}].type` });
      const data = e?.data ?? {};
      if (typeof data !== 'object' || Array.isArray(data)) throw badRequest(`events[${i}].data debe ser un objeto`);
      if (data.samples !== undefined) {
        if (!Array.isArray(data.samples) || data.samples.length > MAX_SAMPLES || data.samples.some((s) => !Number.isFinite(s))) {
          throw badRequest(`events[${i}].data.samples debe ser una lista de hasta ${MAX_SAMPLES} números`);
        }
      }
      if (JSON.stringify(data).length > MAX_DATA_BYTES) throw badRequest(`events[${i}].data es demasiado grande`);
      const at = v.timestamp(e?.at, { name: `events[${i}].at`, now: ctx.now() });
      return logEvent(ctx, device.id, type, data, { at, source: 'device' });
    });
    res.status(201).json({ saved: saved.length, ids: saved.map((e) => e.id) });
  });

  router.get('/devices/:id/events', anyone, (req, res) => {
    const device = getDevice(store, req.params.id);
    assertDeviceAccess(req.who, device);
    const limit = v.num(req.query.limit ?? 50, { name: 'limit', min: 1, max: 1000, integer: true });
    const type = req.query.type ? v.str(req.query.type, { name: 'type', max: 40 }) : null;
    const events = store.data.events
      .filter((e) => e.deviceId === device.id && (!type || e.type === type))
      .sort((a, b) => b.at.localeCompare(a.at))
      .slice(0, limit)
      .map(({ data, ...rest }) => ({ ...rest, data: data?.samples ? { ...data, samples: `${data.samples.length} lecturas` } : data }));
    res.json({ events });
  });

  router.get('/devices/:id/stats', anyone, (req, res) => {
    const device = getDevice(store, req.params.id);
    assertDeviceAccess(req.who, device);
    const days = v.num(req.query.days ?? 30, { name: 'days', min: 1, max: 365, integer: true });
    res.json(deviceStats(ctx, device.id, days));
  });

  return router;
}
