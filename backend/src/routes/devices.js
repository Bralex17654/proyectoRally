import crypto from 'node:crypto';

import { Router } from 'express';

import { assertDeviceAccess, getDevice, issueToken, newId, requireAuth } from '../auth.js';
import { defaultConfig, deviceView, iso, logEvent, publicBase } from '../domain.js';
import { badRequest } from '../errors.js';
import { messages } from '../messages.js';
import { COMMAND_TTL_MS } from '../sensor-link.js';
import * as v from '../validation.js';

/** API 1 (dispositivos y latido) + configuración remota e invitaciones (API 3). */
export function devicesRouter(ctx) {
  const router = Router();
  const { store } = ctx;
  const anyone = requireAuth(store, 'device', 'caregiver', 'community');
  const deviceOnly = requireAuth(store, 'device');

  // Registro de un teléfono reutilizado.
  router.post('/devices', (req, res) => {
    const body = req.body ?? {};
    const id = newId('dev', 6);
    const { token } = issueToken(store, 'device', id);
    const device = {
      id,
      personName: v.str(body.personName, { name: 'personName', max: 80, optional: true }) ?? '',
      model: v.str(body.model, { name: 'model', max: 80, optional: true }) ?? '',
      androidVersion: v.str(body.androidVersion, { name: 'androidVersion', max: 20, optional: true }) ?? '',
      appVersion: v.str(body.appVersion, { name: 'appVersion', max: 20, optional: true }) ?? '',
      createdAt: iso(ctx.now()),
      caregiverIds: [],
      communityIds: [],
      config: defaultConfig(),
      configVersion: 1,
      lastHeartbeat: null,
      flags: { offlineNotified: false, lowBatteryNotified: false },
    };
    if (body.config) {
      device.config = parseConfig(body.config, device.config);
    }
    store.data.devices[id] = device;
    logEvent(ctx, id, 'device_registered', { model: device.model });
    store.save();
    res.status(201).json({
      deviceId: id,
      deviceToken: token,
      heartbeatEveryMin: ctx.config.heartbeatEveryMin,
      config: device.config,
      configVersion: device.configVersion,
    });
  });

  // Latido periódico: batería, estado del monitoreo y ubicación.
  router.post('/devices/:id/heartbeat', deviceOnly, async (req, res) => {
    const device = getDevice(store, req.params.id);
    assertDeviceAccess(req.who, device, ['device']);
    const body = req.body ?? {};
    const heartbeat = {
      at: iso(ctx.now()),
      battery: v.num(body.battery, { name: 'battery', min: 0, max: 100, optional: true, integer: true }) ?? null,
      charging: v.bool(body.charging, { name: 'charging', optional: true }) ?? null,
      monitoring: v.bool(body.monitoring, { name: 'monitoring', optional: true }) ?? null,
      location: v.location(body.location, { now: ctx.now() }),
      appVersion: v.str(body.appVersion, { name: 'appVersion', max: 20, optional: true }) ?? null,
    };
    device.lastHeartbeat = heartbeat;
    if (heartbeat.appVersion) device.appVersion = heartbeat.appVersion;

    if (device.flags.offlineNotified) {
      device.flags.offlineNotified = false;
      logEvent(ctx, device.id, 'device_back_online');
      await ctx.notifier.toCaregivers(store, device.id, 'back_online', messages.backOnline(device));
    }

    const lowLimit = ctx.config.lowBatteryPercent;
    if (heartbeat.battery != null) {
      if (heartbeat.charging || heartbeat.battery > lowLimit + 5) {
        device.flags.lowBatteryNotified = false;
      } else if (heartbeat.battery <= lowLimit && !device.flags.lowBatteryNotified) {
        device.flags.lowBatteryNotified = true;
        logEvent(ctx, device.id, 'low_battery', { battery: heartbeat.battery });
        await ctx.notifier.toCaregivers(store, device.id, 'low_battery', messages.lowBattery(device, heartbeat.battery));
      }
    }

    store.save();
    res.json({
      ok: true,
      serverTime: iso(ctx.now()),
      heartbeatEveryMin: ctx.config.heartbeatEveryMin,
      configVersion: device.configVersion,
      config: device.config,
    });
  });

  router.get('/devices/:id', anyone, (req, res) => {
    const device = getDevice(store, req.params.id);
    assertDeviceAccess(req.who, device);
    res.json(deviceView(ctx, device, publicBase(ctx, req)));
  });

  router.get('/devices/:id/config', anyone, (req, res) => {
    const device = getDevice(store, req.params.id);
    assertDeviceAccess(req.who, device);
    res.json({ configVersion: device.configVersion, config: device.config });
  });

  // El cuidador cambia contactos y opciones a distancia; el teléfono la recibe en el siguiente latido.
  router.put('/devices/:id/config', requireAuth(store, 'device', 'caregiver'), (req, res) => {
    const device = getDevice(store, req.params.id);
    assertDeviceAccess(req.who, device, ['device', 'caregiver']);
    device.config = parseConfig(req.body ?? {}, device.config);
    device.configVersion += 1;
    logEvent(ctx, device.id, 'config_updated', { by: req.who.kind, configVersion: device.configVersion });
    store.save();
    res.json({ configVersion: device.configVersion, config: device.config });
  });

  // El teléfono pregunta cada pocos segundos si la pulsera marcó una caída.
  // Las órdenes se entregan una sola vez y caducan a los 2 minutos.
  router.get('/devices/:id/commands', deviceOnly, (req, res) => {
    const device = getDevice(store, req.params.id);
    assertDeviceAccess(req.who, device, ['device']);
    const now = ctx.now();
    publicBase(ctx, req);
    const pending = (device.commands ?? []).filter((c) => now - Date.parse(c.createdAt) < COMMAND_TTL_MS);
    device.lastCommandPollAt = iso(now);
    if (device.commands?.length) {
      device.commands = [];
      store.save();
    }
    res.json({ commands: pending, pollEverySeconds: 5 });
  });

  // Código de 6 dígitos para que un familiar o una comunidad se vincule.
  router.post('/devices/:id/invite', deviceOnly, (req, res) => {
    const device = getDevice(store, req.params.id);
    assertDeviceAccess(req.who, device, ['device']);
    let code;
    do {
      code = String(crypto.randomInt(100000, 1000000));
    } while (store.data.invites[code]);
    const expiresAt = iso(ctx.now() + ctx.config.inviteTtlMs);
    store.data.invites[code] = { code, deviceId: device.id, expiresAt };
    store.save();
    res.status(201).json({ code, expiresAt });
  });

  return router;
}

/** Valida una configuración parcial y la combina con la actual. */
export function parseConfig(body, current) {
  const next = { ...current };
  if (body.contacts !== undefined) {
    if (!Array.isArray(body.contacts) || body.contacts.length > 5) {
      throw badRequest('"contacts" debe ser una lista de hasta 5 contactos');
    }
    next.contacts = body.contacts.map((c, i) => ({
      name: v.str(c?.name, { name: `contacts[${i}].name`, max: 60, optional: true }) ?? '',
      phone: v.phone(c?.phone, { name: `contacts[${i}].phone` }),
    }));
  }
  const countdown = v.num(body.countdownSeconds, {
    name: 'countdownSeconds',
    min: 10,
    max: 120,
    integer: true,
    optional: true,
  });
  if (countdown !== undefined) next.countdownSeconds = countdown;
  const auto = v.bool(body.autoActions, { name: 'autoActions', optional: true });
  if (auto !== undefined) next.autoActions = auto;
  const callFirst = v.num(body.callFirst, { name: 'callFirst', min: 0, max: 5, integer: true, optional: true });
  if (callFirst !== undefined) next.callFirst = callFirst;
  return next;
}
