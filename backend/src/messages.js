import { reasonLabel } from './domain.js';

const who = (name) => (name?.trim() ? name.trim() : 'la persona protegida');

/** Textos de los avisos a cuidadores. */
export const messages = {
  alert: (alert, url) => {
    if (alert.type === 'sos') {
      return `🆘 TzindaApp: ${who(alert.personName)} pidió ayuda con el botón SOS. Mapa y "Voy en camino": ${url}`;
    }
    const by = alert.source === 'sensor' ? 'la pulsera detectó una ' : '';
    return `🚨 TzindaApp: ${by}posible caída de ${who(alert.personName)}. Detalles y "Voy en camino": ${url}`;
  },
  ack: (alert, name, url) => `✅ ${name} va en camino con ${who(alert.personName)}. ${url}`,
  resolved: (alert, reason, by) =>
    `💚 Se cerró la alerta de ${who(alert.personName)}: ${reasonLabel[reason] ?? reason}${by ? ` (${by})` : ''}.`,
  escalation: (alert, minutes, n, url) =>
    `⚠️ SIN RESPUESTA (${n}): nadie ha confirmado la alerta de ${who(alert.personName)} desde hace ${minutes} min. ${url}`,
  offline: (device, minutes) =>
    `📵 El teléfono de ${who(device.personName)} dejó de reportarse hace ${minutes} min` +
    `${device.lastHeartbeat?.battery != null ? ` (última batería ${device.lastHeartbeat.battery}%)` : ''}. ` +
    'Revisa que esté encendido y con Guardian activo.',
  backOnline: (device) => `📶 El teléfono de ${who(device.personName)} volvió a reportarse.`,
  lowBattery: (device, battery) => `🔋 El teléfono de ${who(device.personName)} tiene ${battery}% de batería. Ponlo a cargar.`,
  sensorAccelError: (device) =>
    `🔧 El acelerómetro de la pulsera de ${who(device.personName)} dejó de funcionar: mientras tanto no puede detectar caídas. Revisa sus cables.`,
  linked: (device, caregiverName) => `🔗 ${caregiverName} ahora recibe las alertas de ${who(device.personName)}.`,
};
