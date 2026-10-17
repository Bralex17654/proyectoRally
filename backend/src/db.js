import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);

/**
 * Pool de conexiones a PostgreSQL (Neon). `pg` se carga solo si hay
 * DATABASE_URL, así el servidor sigue funcionando sin base de datos.
 */
export function createPool(databaseUrl, { max = 5 } = {}) {
  const { Pool } = require('pg');
  // `sslmode=require` ya se comporta como `verify-full` en pg 8; se escribe
  // explícito para no imprimir la advertencia en cada arranque.
  const connectionString = databaseUrl.replace(/sslmode=require/, 'sslmode=verify-full');
  const pool = new Pool({ connectionString, max, idleTimeoutMillis: 30000 });
  pool.on('error', (error) => console.error('[db] conexión cerrada:', error.message));
  return pool;
}

/** Nombre SQL seguro (esquema.tabla) para usar fuera de parámetros. */
export function sqlName(name) {
  if (!/^[a-z_][a-z0-9_]*(\.[a-z_][a-z0-9_]*)?$/i.test(name)) {
    throw new Error(`Nombre de tabla no válido: ${name}`);
  }
  return name
    .split('.')
    .map((part) => `"${part}"`)
    .join('.');
}
