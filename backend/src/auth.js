import crypto from 'node:crypto';

import { forbidden, notFound, unauthorized } from './errors.js';

export const newId = (prefix, bytes = 6) => `${prefix}_${crypto.randomBytes(bytes).toString('base64url')}`;
export const newToken = () => crypto.randomBytes(24).toString('base64url');
export const hashToken = (token) => crypto.createHash('sha256').update(token).digest('hex');

/** Compara secretos en tiempo constante. */
export const sameSecret = (a, b) => {
  const x = Buffer.from(String(a ?? ''));
  const y = Buffer.from(String(b ?? ''));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
};

/** Registra un token (solo se guarda su hash) para un dispositivo, cuidador o comunidad. */
export function issueToken(store, kind, id) {
  const token = newToken();
  store.data.tokens[hashToken(token)] = { kind, id };
  return { token, tokenHash: hashToken(token) };
}

export function identify(store, req) {
  const header = req.get('authorization') ?? '';
  const match = /^Bearer\s+(\S+)$/i.exec(header);
  if (!match) return null;
  return store.data.tokens[hashToken(match[1])] ?? null;
}

/** Middleware: exige un token de alguno de los tipos indicados. */
export const requireAuth = (store, ...kinds) => (req, _res, next) => {
  const who = identify(store, req);
  if (!who || !kinds.includes(who.kind)) throw unauthorized();
  req.who = who;
  next();
};

/** Autenticación opcional (rutas públicas que también aceptan token). */
export const optionalAuth = (store) => (req, _res, next) => {
  req.who = identify(store, req);
  next();
};

export function getDevice(store, id) {
  const device = store.data.devices[id];
  if (!device) throw notFound('Dispositivo no encontrado');
  return device;
}

/** ¿Quién puede ver un dispositivo? Él mismo, sus cuidadores vinculados y sus comunidades. */
export function canAccessDevice(who, device) {
  if (!who) return false;
  if (who.kind === 'device') return who.id === device.id;
  if (who.kind === 'caregiver') return device.caregiverIds.includes(who.id);
  if (who.kind === 'community') return device.communityIds.includes(who.id);
  return false;
}

export function assertDeviceAccess(who, device, kinds = ['device', 'caregiver', 'community']) {
  if (!who || !kinds.includes(who.kind) || !canAccessDevice(who, device)) throw forbidden();
}
