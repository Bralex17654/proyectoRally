import path from 'node:path';
import { fileURLToPath } from 'node:url';

import express from 'express';

import { HttpError } from './errors.js';
import { alertsRouter } from './routes/alerts.js';
import { caregiversRouter } from './routes/caregivers.js';
import { communitiesRouter } from './routes/communities.js';
import { devicesRouter } from './routes/devices.js';
import { eventsRouter } from './routes/events.js';

const publicDir = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'public');

/**
 * Crea la app con sus dependencias inyectadas (almacenamiento, avisos y reloj)
 * para poder probarla sin red ni esperas reales.
 */
export function createApp({ store, notifier, config, now = () => Date.now() }) {
  const ctx = { store, notifier, config, now, lastBase: null };
  const app = express();
  app.disable('x-powered-by');
  app.set('trust proxy', true);
  app.use(express.json({ limit: '256kb' }));

  // CORS abierto: la API la consumen el teléfono, la página del link y paneles externos.
  app.use((req, res, next) => {
    res.set('access-control-allow-origin', '*');
    res.set('access-control-allow-headers', 'authorization, content-type, x-admin-key');
    res.set('access-control-allow-methods', 'GET, POST, PUT, PATCH, DELETE, OPTIONS');
    if (req.method === 'OPTIONS') return res.status(204).end();
    next();
  });

  app.get('/health', (_req, res) => res.json({ ok: true, time: new Date(now()).toISOString() }));

  const api = express.Router();
  api.use(devicesRouter(ctx));
  api.use(alertsRouter(ctx));
  api.use(caregiversRouter(ctx));
  api.use(eventsRouter(ctx));
  api.use(communitiesRouter(ctx));
  api.use((_req, res) => res.status(404).json({ error: 'not_found', message: 'Ruta no encontrada' }));
  app.use('/api/v1', api);

  // Páginas web: alerta para familiares (link del SMS) y panel comunitario.
  app.get('/a/:id', (_req, res) => res.sendFile(path.join(publicDir, 'alert.html')));
  app.get('/c/:id', (_req, res) => res.sendFile(path.join(publicDir, 'dashboard.html')));
  app.use(express.static(publicDir));

  // eslint-disable-next-line no-unused-vars
  app.use((error, _req, res, _next) => {
    if (error instanceof HttpError) {
      return res.status(error.status).json({ error: error.code, message: error.message });
    }
    if (error?.type === 'entity.parse.failed') {
      return res.status(400).json({ error: 'bad_request', message: 'El cuerpo no es JSON válido' });
    }
    if (error?.type === 'entity.too.large') {
      return res.status(413).json({ error: 'too_large', message: 'El cuerpo es demasiado grande' });
    }
    console.error(error);
    res.status(500).json({ error: 'internal', message: 'Error interno del servidor' });
  });

  return { app, ctx };
}
