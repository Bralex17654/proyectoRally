import { Router } from 'express';

import { getDevice, issueToken, newId, requireAuth } from '../auth.js';
import { deviceView, iso, logEvent, publicBase } from '../domain.js';
import { badRequest, notFound } from '../errors.js';
import { messages } from '../messages.js';
import * as v from '../validation.js';

/** Consume un código de invitación vigente y devuelve el dispositivo. */
export function redeemInvite(ctx, rawCode) {
  const code = v.str(rawCode, { name: 'code', max: 10 });
  const invite = ctx.store.data.invites[code];
  if (!invite || Date.parse(invite.expiresAt) < ctx.now()) throw badRequest('Código inválido o vencido');
  delete ctx.store.data.invites[code];
  return getDevice(ctx.store, invite.deviceId);
}

/** API 3: familiares y cuidadores. */
export function caregiversRouter(ctx) {
  const router = Router();
  const { store } = ctx;
  const caregiverOnly = requireAuth(store, 'caregiver');
  const me = (req) => {
    const caregiver = store.data.caregivers[req.who.id];
    if (!caregiver) throw notFound('Cuidador no encontrado');
    return caregiver;
  };
  const publicCaregiver = (c) => ({
    id: c.id,
    name: c.name,
    phone: c.phone,
    telegramChatId: c.telegramChatId,
    createdAt: c.createdAt,
  });

  router.post('/caregivers', (req, res) => {
    const body = req.body ?? {};
    const id = newId('cg', 6);
    const { token } = issueToken(store, 'caregiver', id);
    const caregiver = {
      id,
      name: v.str(body.name, { name: 'name', max: 60 }),
      phone: v.phone(body.phone, { optional: true }) ?? null,
      telegramChatId: v.str(body.telegramChatId, { name: 'telegramChatId', max: 40, optional: true }) ?? null,
      createdAt: iso(ctx.now()),
    };
    store.data.caregivers[id] = caregiver;
    store.save();
    res.status(201).json({ caregiverId: id, caregiverToken: token, caregiver: publicCaregiver(caregiver) });
  });

  router.get('/caregivers/me', caregiverOnly, (req, res) => res.json(publicCaregiver(me(req))));

  router.patch('/caregivers/me', caregiverOnly, (req, res) => {
    const caregiver = me(req);
    const body = req.body ?? {};
    const name = v.str(body.name, { name: 'name', max: 60, optional: true });
    if (name) caregiver.name = name;
    if (body.phone !== undefined) caregiver.phone = v.phone(body.phone, { optional: true }) ?? null;
    if (body.telegramChatId !== undefined) {
      caregiver.telegramChatId = v.str(body.telegramChatId, { name: 'telegramChatId', max: 40, optional: true }) ?? null;
    }
    store.save();
    res.json(publicCaregiver(caregiver));
  });

  // Vincularse con el código que muestra el teléfono de la persona.
  router.post('/caregivers/me/devices', caregiverOnly, async (req, res) => {
    const caregiver = me(req);
    const device = redeemInvite(ctx, req.body?.code);
    if (!device.caregiverIds.includes(caregiver.id)) device.caregiverIds.push(caregiver.id);
    logEvent(ctx, device.id, 'caregiver_linked', { caregiverId: caregiver.id, name: caregiver.name });
    store.save();
    await ctx.notifier.toCaregivers(store, device.id, 'linked', messages.linked(device, caregiver.name));
    res.status(201).json(deviceView(ctx, device, publicBase(ctx, req)));
  });

  router.get('/caregivers/me/devices', caregiverOnly, (req, res) => {
    const base = publicBase(ctx, req);
    const devices = Object.values(store.data.devices)
      .filter((d) => d.caregiverIds.includes(req.who.id))
      .map((d) => deviceView(ctx, d, base));
    res.json({ devices });
  });

  router.delete('/caregivers/me/devices/:deviceId', caregiverOnly, (req, res) => {
    const device = getDevice(store, req.params.deviceId);
    device.caregiverIds = device.caregiverIds.filter((id) => id !== req.who.id);
    logEvent(ctx, device.id, 'caregiver_unlinked', { caregiverId: req.who.id });
    store.save();
    res.status(204).end();
  });

  return router;
}
