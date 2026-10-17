import { Router } from 'express';

import { issueToken, newId, requireAuth, sameSecret } from '../auth.js';
import { deviceStats, deviceView, iso, logEvent, publicBase } from '../domain.js';
import { forbidden, notFound, unauthorized } from '../errors.js';
import * as v from '../validation.js';
import { redeemInvite } from './caregivers.js';

/** API 5: centros de día, comités vecinales o asilos que supervisan varios teléfonos. */
export function communitiesRouter(ctx) {
  const router = Router();
  const { store } = ctx;
  const communityOnly = requireAuth(store, 'community');

  const ownCommunity = (req) => {
    const community = store.data.communities[req.params.id];
    if (!community) throw notFound('Comunidad no encontrada');
    if (req.who.id !== community.id) throw forbidden();
    return community;
  };

  // Solo la persona que administra el servidor crea comunidades (header X-Admin-Key).
  router.post('/communities', (req, res) => {
    if (!sameSecret(req.get('x-admin-key'), ctx.config.adminKey)) throw unauthorized('X-Admin-Key inválida');
    const id = newId('com', 5);
    const { token } = issueToken(store, 'community', id);
    store.data.communities[id] = {
      id,
      name: v.str(req.body?.name, { name: 'name', max: 80 }),
      createdAt: iso(ctx.now()),
    };
    store.save();
    res.status(201).json({ communityId: id, communityToken: token, dashboardUrl: `${publicBase(ctx, req)}/c/${id}` });
  });

  // Agregar un teléfono a la comunidad con el código que muestra la app.
  router.post('/communities/:id/devices', communityOnly, (req, res) => {
    const community = ownCommunity(req);
    const device = redeemInvite(ctx, req.body?.code);
    if (!device.communityIds.includes(community.id)) device.communityIds.push(community.id);
    logEvent(ctx, device.id, 'community_linked', { communityId: community.id, name: community.name });
    store.save();
    res.status(201).json(deviceView(ctx, device, publicBase(ctx, req)));
  });

  router.get('/communities/:id/dashboard', communityOnly, (req, res) => {
    const community = ownCommunity(req);
    const base = publicBase(ctx, req);
    const devices = Object.values(store.data.devices)
      .filter((d) => d.communityIds.includes(community.id))
      .map((d) => {
        const stats = deviceStats(ctx, d.id, 7);
        return {
          ...deviceView(ctx, d, base),
          week: { alerts: stats.alerts.total, falseAlarmRate: stats.falseAlarmRate, medianAckSeconds: stats.medianAckSeconds },
        };
      })
      .sort((a, b) => b.openAlerts.length - a.openAlerts.length || a.personName.localeCompare(b.personName));

    const lowLimit = ctx.config.lowBatteryPercent;
    res.json({
      community: { id: community.id, name: community.name },
      generatedAt: iso(ctx.now()),
      summary: {
        devices: devices.length,
        online: devices.filter((d) => d.status === 'online').length,
        offline: devices.filter((d) => d.status === 'offline').length,
        lowBattery: devices.filter((d) => d.battery != null && d.battery <= lowLimit && !d.charging).length,
        openAlerts: devices.reduce((sum, d) => sum + d.openAlerts.length, 0),
      },
      devices,
    });
  });

  return router;
}
