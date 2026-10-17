import { sqlName } from './db.js';
import { Store, empty } from './store.js';

const KEYED = ['devices', 'caregivers', 'communities', 'alerts', 'invites', 'tokens', 'settings'];
const SEP = '\u0000';

/**
 * Almacenamiento en PostgreSQL con la misma interfaz que Store: las rutas leen
 * y escriben `store.data` en memoria y `save()` guarda en la base, poco después,
 * solo los registros que cambiaron (y borra los que ya no existen).
 *
 * Cada registro es una fila de `<esquema>.registros` (colección, id, datos jsonb).
 * Las vistas `alertas` y `dispositivos` permiten revisarlos con SQL normal.
 */
export class PgStore extends Store {
  constructor({ pool, schema = 'tzinda', debounceMs = 250 }) {
    super({ file: null });
    this.pool = pool;
    this.schema = sqlName(schema);
    this.debounceMs = debounceMs;
    this._saved = new Map();
    this._timer = null;
    this._flushing = null;
    this._dirty = false;
  }

  static async open(options) {
    const store = new PgStore(options);
    await store.migrate();
    await store.load();
    return store;
  }

  async migrate() {
    const s = this.schema;
    await this.pool.query(`
      create schema if not exists ${s};
      create table if not exists ${s}.registros (
        coleccion text not null,
        id text not null,
        datos jsonb not null,
        actualizado timestamptz not null default now(),
        primary key (coleccion, id)
      );
      create index if not exists registros_alertas_dispositivo
        on ${s}.registros ((datos->>'deviceId')) where coleccion in ('alerts', 'events');
      create or replace view ${s}.dispositivos as
        select id,
               datos->>'personName' as persona,
               datos->>'model' as modelo,
               (datos->'lastHeartbeat'->>'at')::timestamptz as ultimo_latido,
               (datos->'lastHeartbeat'->>'battery')::int as bateria,
               jsonb_array_length(datos->'caregiverIds') as cuidadores,
               actualizado
          from ${s}.registros where coleccion = 'devices';
      create or replace view ${s}.alertas as
        select id,
               datos->>'deviceId' as dispositivo,
               datos->>'personName' as persona,
               datos->>'type' as tipo,
               coalesce(datos->>'source', 'app') as origen,
               datos->>'status' as estado,
               (datos->>'createdAt')::timestamptz as creada,
               jsonb_array_length(datos->'acks') as confirmaciones,
               datos->'resolution'->>'reason' as cierre
          from ${s}.registros where coleccion = 'alerts';
    `);
  }

  async load() {
    const data = empty();
    const { rows } = await this.pool.query(`select coleccion, id, datos from ${this.schema}.registros`);
    for (const row of rows) {
      if (row.coleccion === 'events') data.events.push(row.datos);
      else if (KEYED.includes(row.coleccion)) data[row.coleccion][row.id] = row.datos;
      this._saved.set(`${row.coleccion}${SEP}${row.id}`, JSON.stringify(row.datos));
    }
    data.events.sort((a, b) => a.at.localeCompare(b.at));
    this.data = data;
  }

  /** Foto actual: clave → JSON. */
  _snapshot() {
    const snap = new Map();
    for (const collection of KEYED) {
      for (const [id, value] of Object.entries(this.data[collection] ?? {})) {
        snap.set(`${collection}${SEP}${id}`, JSON.stringify(value));
      }
    }
    for (const event of this.data.events) snap.set(`events${SEP}${event.id}`, JSON.stringify(event));
    return snap;
  }

  save() {
    this._dirty = true;
    clearTimeout(this._timer);
    this._timer = setTimeout(() => {
      this.flush().catch((error) => console.error('[db] no se pudo guardar:', error.message));
    }, this.debounceMs);
    this._timer.unref?.();
  }

  /**
   * Guarda los cambios pendientes. Espera a que termine cualquier guardado en
   * curso (puede haber varios esperando) y luego escribe solo las diferencias,
   * así que llamarlo de más no cuesta nada.
   */
  async flush() {
    clearTimeout(this._timer);
    while (this._flushing) await this._flushing;
    this._dirty = false;
    this._flushing = this._write().finally(() => {
      this._flushing = null;
    });
    await this._flushing;
  }

  async _write() {
    const snap = this._snapshot();
    const upsert = { colecciones: [], ids: [], datos: [] };
    for (const [key, json] of snap) {
      if (this._saved.get(key) === json) continue;
      const [coleccion, id] = key.split(SEP);
      upsert.colecciones.push(coleccion);
      upsert.ids.push(id);
      upsert.datos.push(json);
    }
    const removed = { colecciones: [], ids: [] };
    for (const key of this._saved.keys()) {
      if (snap.has(key)) continue;
      const [coleccion, id] = key.split(SEP);
      removed.colecciones.push(coleccion);
      removed.ids.push(id);
    }
    if (!upsert.ids.length && !removed.ids.length) return;

    const client = await this.pool.connect();
    try {
      await client.query('begin');
      if (upsert.ids.length) {
        await client.query(
          `insert into ${this.schema}.registros (coleccion, id, datos, actualizado)
           select c, i, d::jsonb, now() from unnest($1::text[], $2::text[], $3::text[]) as t(c, i, d)
           on conflict (coleccion, id) do update set datos = excluded.datos, actualizado = now()`,
          [upsert.colecciones, upsert.ids, upsert.datos],
        );
      }
      if (removed.ids.length) {
        await client.query(
          `delete from ${this.schema}.registros r
            using unnest($1::text[], $2::text[]) as t(c, i)
            where r.coleccion = t.c and r.id = t.i`,
          [removed.colecciones, removed.ids],
        );
      }
      await client.query('commit');
    } catch (error) {
      await client.query('rollback').catch(() => {});
      this._dirty = true;
      throw error;
    } finally {
      client.release();
    }
    for (let i = 0; i < upsert.ids.length; i++) {
      this._saved.set(`${upsert.colecciones[i]}${SEP}${upsert.ids[i]}`, upsert.datos[i]);
    }
    for (let i = 0; i < removed.ids.length; i++) {
      this._saved.delete(`${removed.colecciones[i]}${SEP}${removed.ids[i]}`);
    }
  }
}
