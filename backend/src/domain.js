import { newId } from './auth.js';

export const iso = (ms) => new Date(ms).toISOString();

export const ALERT_TYPES = ['fall', 'sos'];
export const RESOLUTION_REASONS = ['false_alarm', 'real_fall', 'attended', 'other'];
export const DEVICE_EVENT_TYPES = [
  'monitor_on',
  'monitor_off',
  'impact_discarded',
  'countdown_started',
  'cancelled_by_user',
  'sms_result',
  'call_result',
  'other',
];

export const reasonLabel = {
  false_alarm: 'falsa alarma',
  real_fall: 'caída real atendida',
  attended: 'ya fue atendida',
  other: 'otro motivo',
};

export const defaultConfig = () => ({
  contacts: [],
  countdownSeconds: 20,
  autoActions: true,
  callFirst: 2,
});

/**
 * URL base para links públicos: PUBLIC_URL o la del último request
 * (así el vigilante, que no tiene request, también arma links completos).
 */
export function publicBase(ctx, req) {
  if (ctx.config.publicUrl) return ctx.config.publicUrl;
  if (req) ctx.lastBase = `${req.protocol}://${req.get('host')}`;
  return ctx.lastBase ?? '';
}

export const alertUrl = (base, alertId) => `${base}/a/${alertId}`;

export function logEvent(ctx, deviceId, type, data = {}, { at, source = 'server' } = {}) {
  return ctx.store.addEvent({
    id: newId('ev', 6),
    deviceId,
    type,
    source,
    at: at ?? iso(ctx.now()),
    data,
  });
}

export function deviceStatus(ctx, device) {
  const hb = device.lastHeartbeat;
  if (!hb) return 'never';
  return ctx.now() - Date.parse(hb.at) > ctx.config.offlineAfterMs ? 'offline' : 'online';
}

export function openAlertsFor(ctx, deviceId) {
  return Object.values(ctx.store.data.alerts)
    .filter((a) => a.deviceId === deviceId && a.status !== 'resolved')
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
}

export function latestLocation(alert) {
  return alert.locations.length ? alert.locations[alert.locations.length - 1] : null;
}

export function alertSummary(alert, base) {
  return {
    id: alert.id,
    type: alert.type,
    status: alert.status,
    createdAt: alert.createdAt,
    url: alertUrl(base, alert.id),
    acknowledgedBy: alert.acks.map((a) => a.name),
  };
}

export function deviceView(ctx, device, base) {
  const hb = device.lastHeartbeat;
  return {
    id: device.id,
    personName: device.personName,
    model: device.model,
    androidVersion: device.androidVersion,
    appVersion: device.appVersion,
    createdAt: device.createdAt,
    status: deviceStatus(ctx, device),
    lastSeen: hb?.at ?? null,
    battery: hb?.battery ?? null,
    charging: hb?.charging ?? null,
    monitoring: hb?.monitoring ?? null,
    lastLocation: hb?.location ?? null,
    configVersion: device.configVersion,
    caregivers: device.caregiverIds.length,
    openAlerts: openAlertsFor(ctx, device.id).map((a) => alertSummary(a, base)),
  };
}

/** Vista pública de una alerta: la ve quien tenga el link (id no adivinable). */
export function alertView(ctx, alert, base) {
  const device = ctx.store.data.devices[alert.deviceId];
  return {
    id: alert.id,
    url: alertUrl(base, alert.id),
    personName: alert.personName,
    type: alert.type,
    source: alert.source ?? 'app',
    sensor: alert.sensor ?? null,
    status: alert.status,
    createdAt: alert.createdAt,
    detectedAt: alert.detectedAt,
    peak: alert.peak,
    battery: alert.battery,
    sms: alert.sms,
    latestLocation: latestLocation(alert),
    locations: alert.locations.slice(-30),
    acks: alert.acks,
    escalations: alert.escalations,
    resolution: alert.resolution,
    device: device ? { status: deviceStatus(ctx, device), lastSeen: device.lastHeartbeat?.at ?? null } : null,
  };
}

const median = (values) => {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
};

/** Indicadores para calibrar el algoritmo y para la presentación. */
export function deviceStats(ctx, deviceId, days = 30) {
  const since = ctx.now() - days * 24 * 60 * 60 * 1000;
  const inPeriod = (at) => Date.parse(at) >= since;
  const alerts = Object.values(ctx.store.data.alerts).filter((a) => a.deviceId === deviceId && inPeriod(a.createdAt));
  const events = ctx.store.data.events.filter((e) => e.deviceId === deviceId && inPeriod(e.at));
  const count = (type) => events.filter((e) => e.type === type).length;
  const resolved = (reason) => alerts.filter((a) => a.resolution?.reason === reason).length;

  const cancelledByUser = count('cancelled_by_user');
  const falseAlarms = resolved('false_alarm');
  const possibleFalls = cancelledByUser + alerts.filter((a) => a.type === 'fall').length;
  const ackSeconds = alerts
    .filter((a) => a.acks.length)
    .map((a) => (Date.parse(a.acks[0].at) - Date.parse(a.createdAt)) / 1000);

  return {
    periodDays: days,
    alerts: {
      total: alerts.length,
      fall: alerts.filter((a) => a.type === 'fall').length,
      sos: alerts.filter((a) => a.type === 'sos').length,
      open: alerts.filter((a) => a.status !== 'resolved').length,
      falseAlarm: falseAlarms,
      realFall: resolved('real_fall'),
      attended: resolved('attended'),
    },
    cancelledByUser,
    impactsDiscarded: count('impact_discarded'),
    /** Detecciones que terminaron siendo falsas (canceladas o marcadas así). */
    falseAlarmRate: possibleFalls ? Number(((cancelledByUser + falseAlarms) / possibleFalls).toFixed(2)) : null,
    medianAckSeconds: median(ackSeconds),
    escalations: alerts.reduce((sum, a) => sum + a.escalations, 0),
    offlineEpisodes: count('device_offline'),
    lowBatteryEpisodes: count('low_battery'),
  };
}
