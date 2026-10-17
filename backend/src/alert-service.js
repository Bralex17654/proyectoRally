import { newId } from './auth.js';
import { alertUrl, iso, logEvent } from './domain.js';
import { messages } from './messages.js';

/**
 * Crea una alerta y avisa a los cuidadores. La usan el teléfono (POST /alerts)
 * y la pulsera física (lecturas de la base de datos).
 *
 * `source`: 'app' (teléfono) o 'sensor' (pulsera física).
 */
export async function raiseAlert(ctx, device, input, base) {
  const now = ctx.now();
  const alert = {
    id: newId('al', 8),
    deviceId: device.id,
    personName: device.personName,
    type: input.type ?? 'fall',
    source: input.source ?? 'app',
    status: 'active',
    createdAt: iso(now),
    detectedAt: input.detectedAt ?? iso(now),
    peak: input.peak ?? null,
    battery: input.battery ?? null,
    sms: input.sms ?? { sent: null, failed: null },
    locations: input.location ? [input.location] : [],
    acks: [],
    escalations: 0,
    lastEscalationAt: null,
    resolution: null,
    sensor: input.sensor ?? null,
  };
  ctx.store.data.alerts[alert.id] = alert;
  const url = alertUrl(base, alert.id);
  logEvent(ctx, device.id, 'alert_created', {
    alertId: alert.id,
    type: alert.type,
    peak: alert.peak,
    source: alert.source,
  });
  ctx.store.save();
  ctx.bus?.emit('alert', alert, url);
  await ctx.notifier.toCaregivers(ctx.store, device.id, 'alert', messages.alert(alert, url));
  return { alert, url };
}
