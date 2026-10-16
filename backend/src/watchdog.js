import { alertUrl, deviceStatus, logEvent, publicBase } from './domain.js';
import { messages } from './messages.js';

/**
 * Revisión periódica (cada minuto):
 *  - teléfonos que dejaron de reportarse (apagados, sin batería, servicio detenido)
 *  - alertas sin "Voy en camino" → se vuelve a avisar (escalada)
 *  - invitaciones vencidas y datos más viejos que RETENTION_DAYS
 */
export function createWatchdog(ctx) {
  async function run() {
    const now = ctx.now();
    const { devices, alerts, invites, events } = ctx.store.data;
    const base = publicBase(ctx);

    for (const device of Object.values(devices)) {
      if (deviceStatus(ctx, device) === 'offline' && !device.flags.offlineNotified) {
        device.flags.offlineNotified = true;
        const minutes = Math.round((now - Date.parse(device.lastHeartbeat.at)) / 60000);
        logEvent(ctx, device.id, 'device_offline', { minutes });
        await ctx.notifier.toCaregivers(ctx.store, device.id, 'offline', messages.offline(device, minutes));
      }
    }

    for (const alert of Object.values(alerts)) {
      if (alert.status !== 'active' || alert.escalations >= ctx.config.maxEscalations) continue;
      const since = Date.parse(alert.lastEscalationAt ?? alert.createdAt);
      if (now - since < ctx.config.escalateAfterMs) continue;
      alert.escalations += 1;
      alert.lastEscalationAt = new Date(now).toISOString();
      const minutes = Math.round((now - Date.parse(alert.createdAt)) / 60000);
      logEvent(ctx, alert.deviceId, 'escalation', { alertId: alert.id, n: alert.escalations });
      await ctx.notifier.toCaregivers(
        ctx.store,
        alert.deviceId,
        'escalation',
        messages.escalation(alert, minutes, alert.escalations, alertUrl(base, alert.id)),
      );
    }

    for (const [code, invite] of Object.entries(invites)) {
      if (Date.parse(invite.expiresAt) < now) delete invites[code];
    }

    const cutoff = now - ctx.config.retentionDays * 24 * 60 * 60 * 1000;
    ctx.store.data.events = events.filter((e) => Date.parse(e.at) >= cutoff);
    for (const [id, alert] of Object.entries(alerts)) {
      if (alert.status === 'resolved' && Date.parse(alert.createdAt) < cutoff) delete alerts[id];
    }

    ctx.store.save();
  }

  return { run };
}
