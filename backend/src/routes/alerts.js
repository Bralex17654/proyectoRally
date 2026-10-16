import { Router } from 'express';

import { assertDeviceAccess, getDevice, newId, optionalAuth, requireAuth } from '../auth.js';
import {
  ALERT_TYPES,
  RESOLUTION_REASONS,
  alertSummary,
  alertUrl,
  alertView,
  iso,
  logEvent,
  publicBase,
} from '../domain.js';
import { conflict, notFound } from '../errors.js';
import { messages } from '../messages.js';
import * as v from '../validation.js';

const MAX_LOCATIONS = 200;
const MAX_ACKS = 20;

/**
 * API 2: ciclo de vida de una alerta.
 *
 * El id de la alerta es aleatorio y no adivinable: quien recibe el link
 * por SMS puede ver el mapa y responder sin crear cuenta.
 */
export function alertsRouter(ctx) {
  const router = Router();
  const { store } = ctx;
  const deviceOnly = requireAuth(store, 'device');

  const getAlert = (id) => {
    const alert = store.data.alerts[id];
    if (!alert) throw notFound('Alerta no encontrada');
    return alert;
  };

  // El teléfono crea la alerta al terminar la cuenta regresiva (o al presionar SOS).
  router.post('/alerts', deviceOnly, async (req, res) => {
    const device = getDevice(store, req.who.id);
    const body = req.body ?? {};
    const now = ctx.now();
    const loc = v.location(body.location, { now });
    const alert = {
      id: newId('al', 8),
      deviceId: device.id,
      personName: device.personName,
      type: v.oneOf(body.type ?? 'fall', ALERT_TYPES, { name: 'type' }),
      status: 'active',
      createdAt: iso(now),
      detectedAt: v.timestamp(body.detectedAt, { name: 'detectedAt', now }),
      peak: v.num(body.peak, { name: 'peak', min: 0, max: 1000, optional: true }) ?? null,
      battery: v.num(body.battery, { name: 'battery', min: 0, max: 100, integer: true, optional: true }) ?? null,
      sms: {
        sent: v.num(body.sms?.sent, { name: 'sms.sent', min: 0, max: 50, integer: true, optional: true }) ?? null,
        failed: v.num(body.sms?.failed, { name: 'sms.failed', min: 0, max: 50, integer: true, optional: true }) ?? null,
      },
      locations: loc ? [loc] : [],
      acks: [],
      escalations: 0,
      lastEscalationAt: null,
      resolution: null,
    };
    store.data.alerts[alert.id] = alert;
    const base = publicBase(ctx, req);
    const url = alertUrl(base, alert.id);
    logEvent(ctx, device.id, 'alert_created', { alertId: alert.id, type: alert.type, peak: alert.peak });
    store.save();
    await ctx.notifier.toCaregivers(store, device.id, 'alert', messages.alert(alert, url));
    res.status(201).json({ alertId: alert.id, url, status: alert.status });
  });

  // Ubicación actualizada mientras la alerta siga abierta. La respuesta trae
  // las confirmaciones para que el teléfono muestre "Ana va en camino".
  const updateLocation = (req, res) => {
    const alert = getAlert(req.params.id);
    if (alert.deviceId !== req.who.id) throw notFound('Alerta no encontrada');
    if (alert.status === 'resolved') throw conflict('La alerta ya está cerrada');
    alert.locations.push(v.location(req.body, { optional: false, now: ctx.now() }));
    if (alert.locations.length > MAX_LOCATIONS) alert.locations.splice(0, alert.locations.length - MAX_LOCATIONS);
    store.save();
    res.json({ ok: true, status: alert.status, acks: alert.acks });
  };
  router.patch('/alerts/:id/location', deviceOnly, updateLocation);
  // Alias POST: HttpURLConnection de Android no soporta PATCH.
  router.post('/alerts/:id/location', deviceOnly, updateLocation);

  // Vista pública (la usa la página del link y el teléfono para saber quién va en camino).
  router.get('/alerts/:id', (req, res) => {
    res.json(alertView(ctx, getAlert(req.params.id), publicBase(ctx, req)));
  });

  // "Voy en camino".
  router.post('/alerts/:id/ack', optionalAuth(store), async (req, res) => {
    const alert = getAlert(req.params.id);
    if (alert.status === 'resolved') throw conflict('La alerta ya está cerrada');
    if (alert.acks.length >= MAX_ACKS) throw conflict('La alerta ya tiene demasiadas respuestas');
    const caregiver = req.who?.kind === 'caregiver' ? store.data.caregivers[req.who.id] : null;
    const name = v.str(req.body?.name, { name: 'name', max: 60, optional: Boolean(caregiver) }) ?? caregiver.name;
    const ack = {
      name,
      message: v.str(req.body?.message, { name: 'message', max: 200, optional: true }) ?? null,
      etaMinutes: v.num(req.body?.etaMinutes, { name: 'etaMinutes', min: 0, max: 600, integer: true, optional: true }) ?? null,
      caregiverId: caregiver?.id ?? null,
      at: iso(ctx.now()),
    };
    alert.acks.push(ack);
    alert.status = 'acknowledged';
    logEvent(ctx, alert.deviceId, 'alert_acknowledged', { alertId: alert.id, name });
    store.save();
    await ctx.notifier.toCaregivers(store, alert.deviceId, 'ack', messages.ack(alert, name, alertUrl(publicBase(ctx, req), alert.id)));
    res.json(alertView(ctx, alert, publicBase(ctx, req)));
  });

  // Cierre: "Estoy bien" desde el teléfono, o "ya lo atendimos" desde el link.
  router.post('/alerts/:id/resolve', optionalAuth(store), async (req, res) => {
    const alert = getAlert(req.params.id);
    if (alert.status === 'resolved') throw conflict('La alerta ya está cerrada');
    const reason = v.oneOf(req.body?.reason ?? 'attended', RESOLUTION_REASONS, { name: 'reason' });
    const fromDevice = req.who?.kind === 'device' && req.who.id === alert.deviceId;
    const by = v.str(req.body?.by, { name: 'by', max: 60, optional: true }) ?? (fromDevice ? alert.personName || 'la persona' : null);
    alert.status = 'resolved';
    alert.resolution = {
      reason,
      by,
      note: v.str(req.body?.note, { name: 'note', max: 300, optional: true }) ?? null,
      at: iso(ctx.now()),
    };
    logEvent(ctx, alert.deviceId, 'alert_resolved', { alertId: alert.id, reason });
    store.save();
    await ctx.notifier.toCaregivers(store, alert.deviceId, 'resolved', messages.resolved(alert, reason, by));
    res.json(alertView(ctx, alert, publicBase(ctx, req)));
  });

  // Historial de alertas de un dispositivo.
  router.get('/devices/:id/alerts', requireAuth(store, 'device', 'caregiver', 'community'), (req, res) => {
    const device = getDevice(store, req.params.id);
    assertDeviceAccess(req.who, device);
    const status = req.query.status ? v.oneOf(req.query.status, ['active', 'acknowledged', 'resolved', 'open'], { name: 'status' }) : null;
    const limit = v.num(req.query.limit ?? 50, { name: 'limit', min: 1, max: 500, integer: true });
    const base = publicBase(ctx, req);
    const items = Object.values(store.data.alerts)
      .filter((a) => a.deviceId === device.id)
      .filter((a) => !status || (status === 'open' ? a.status !== 'resolved' : a.status === status))
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
      .slice(0, limit)
      .map((a) => ({ ...alertSummary(a, base), resolution: a.resolution }));
    res.json({ alerts: items });
  });

  return router;
}
