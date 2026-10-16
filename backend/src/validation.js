import { badRequest } from './errors.js';

export function str(value, { name, max = 200, optional = false } = {}) {
  if (value === undefined || value === null || value === '') {
    if (optional) return undefined;
    throw badRequest(`Falta "${name}"`);
  }
  if (typeof value !== 'string') throw badRequest(`"${name}" debe ser texto`);
  const trimmed = value.trim();
  if (trimmed.length > max) throw badRequest(`"${name}" admite máximo ${max} caracteres`);
  return trimmed;
}

export function num(value, { name, min = -Infinity, max = Infinity, optional = false, integer = false } = {}) {
  if (value === undefined || value === null) {
    if (optional) return undefined;
    throw badRequest(`Falta "${name}"`);
  }
  const n = Number(value);
  if (!Number.isFinite(n) || n < min || n > max || (integer && !Number.isInteger(n))) {
    throw badRequest(`"${name}" debe ser un número${integer ? ' entero' : ''} entre ${min} y ${max}`);
  }
  return n;
}

export function bool(value, { name, optional = false } = {}) {
  if (value === undefined || value === null) {
    if (optional) return undefined;
    throw badRequest(`Falta "${name}"`);
  }
  if (typeof value !== 'boolean') throw badRequest(`"${name}" debe ser true o false`);
  return value;
}

export function oneOf(value, allowed, { name, optional = false } = {}) {
  if (value === undefined || value === null) {
    if (optional) return undefined;
    throw badRequest(`Falta "${name}"`);
  }
  if (!allowed.includes(value)) throw badRequest(`"${name}" debe ser uno de: ${allowed.join(', ')}`);
  return value;
}

/** { lat, lng, accuracy?, at? } → ubicación normalizada. */
export function location(value, { name = 'location', optional = true, now = Date.now() } = {}) {
  if (value === undefined || value === null) {
    if (optional) return null;
    throw badRequest(`Falta "${name}"`);
  }
  if (typeof value !== 'object') throw badRequest(`"${name}" debe ser un objeto { lat, lng }`);
  return {
    lat: num(value.lat, { name: `${name}.lat`, min: -90, max: 90 }),
    lng: num(value.lng, { name: `${name}.lng`, min: -180, max: 180 }),
    accuracy: num(value.accuracy, { name: `${name}.accuracy`, min: 0, max: 100000, optional: true }) ?? null,
    at: timestamp(value.at, { name: `${name}.at`, now }),
  };
}

/** Acepta ISO 8601 o milisegundos; devuelve ISO. */
export function timestamp(value, { name, now = Date.now() } = {}) {
  if (value === undefined || value === null) return new Date(now).toISOString();
  const date = typeof value === 'number' ? new Date(value) : new Date(String(value));
  if (Number.isNaN(date.getTime())) throw badRequest(`"${name}" debe ser una fecha ISO o milisegundos`);
  return date.toISOString();
}

export function phone(value, { name = 'phone', optional = false } = {}) {
  const raw = str(value, { name, max: 30, optional });
  if (raw === undefined) return undefined;
  const normalized = raw.startsWith('+') ? `+${raw.replace(/\D/g, '')}` : raw.replace(/\D/g, '');
  if (normalized.replace('+', '').length < 8) throw badRequest(`"${name}" debe tener al menos 8 dígitos`);
  return normalized;
}
