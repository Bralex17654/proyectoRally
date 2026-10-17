import { sqlName } from './db.js';

const num = (v) => (v === null || v === undefined ? null : Number(v));

/**
 * Convierte una fila de `lecturas` (pulsera con MAX30102 + MPU6050) a JSON
 * legible. Pulso y oxigenación solo valen cuando hay dedo en el sensor.
 */
export function formatReading(row) {
  const finger = row.dedo === 1;
  return {
    id: Number(row.id),
    hora: new Date(row.hora).toISOString(),
    dedo: finger,
    pulso: finger && row.bpm > 0 ? row.bpm : null,
    spo2: finger && row.spo2 > 0 ? row.spo2 : null,
    ir: num(row.ir),
    rojo: num(row.rojo),
    saturado: row.saturado === 1,
    acelerometro: {
      x: num(row.ax_g),
      y: num(row.ay_g),
      z: num(row.az_g),
      magnitud: num(row.mag_g),
    },
    caida: row.caida === 1,
    caidas: row.caidas ?? 0,
    sensores: {
      pulso: row.max_ok === 1 && !row.max_err,
      acelerometro: row.mpu_ok === 1 && !row.mpu_err,
    },
  };
}

/**
 * Caídas nuevas en una serie de lecturas. La pulsera marca `caida = 1` durante
 * 2–3 lecturas y aumenta el contador `caidas`; el contador vuelve a 0 cuando
 * se reinicia, y eso no es una caída.
 */
export function detectIncidents(previous, readings) {
  const incidents = [];
  let prev = previous;
  for (const r of readings) {
    const started = r.caida && !(prev?.caida ?? false);
    const counted = prev != null && r.caidas > prev.caidas;
    if (started || counted) incidents.push(r);
    prev = r;
  }
  return { incidents, last: prev };
}

/**
 * Lee la tabla de lecturas cada `intervalMs` (por id creciente) y avisa a los
 * suscriptores. Se usa sondeo porque el pooler de Neon no admite LISTEN/NOTIFY
 * y así no hace falta modificar la tabla de la pulsera.
 */
export function createSensorHub({ pool, table = 'public.lecturas', intervalMs = 1000, log = console.log }) {
  const t = sqlName(table);
  const listeners = new Set();
  const recent = [];
  let lastId = null;
  let lastReading = null;
  let timer = null;
  let polling = false;
  let failures = 0;

  async function poll() {
    if (polling) return;
    polling = true;
    try {
      const { rows } = await pool.query(`select * from ${t} where id > $1 order by id asc limit 500`, [lastId ?? 0]);
      failures = 0;
      if (!rows.length) return;
      const readings = rows.map(formatReading);
      lastId = readings.at(-1).id;
      for (const r of readings) {
        recent.push(r);
        if (recent.length > 120) recent.shift();
      }
      const previous = lastReading;
      lastReading = readings.at(-1);
      for (const fn of listeners) {
        try {
          await fn(readings, previous);
        } catch (error) {
          log(`[sensor] error en suscriptor: ${error.message}`);
        }
      }
    } catch (error) {
      failures += 1;
      if (failures === 1 || failures % 30 === 0) log(`[sensor] no se pudo leer ${table}: ${error.message}`);
    } finally {
      polling = false;
    }
  }

  return {
    table,
    async start() {
      // Se arranca desde la última lectura: las caídas viejas no generan alertas.
      const { rows } = await pool.query(`select * from ${t} order by id desc limit 1`);
      if (rows.length) {
        lastReading = formatReading(rows[0]);
        lastId = lastReading.id;
      } else {
        lastId = 0;
      }
      timer = setInterval(poll, intervalMs);
      timer.unref?.();
    },
    stop() {
      clearInterval(timer);
    },
    poll,
    subscribe(fn) {
      listeners.add(fn);
      return () => listeners.delete(fn);
    },
    get lastReading() {
      return lastReading;
    },

    async latest() {
      const { rows } = await pool.query(`select * from ${t} order by id desc limit 1`);
      return rows.length ? formatReading(rows[0]) : null;
    },

    async count() {
      const { rows } = await pool.query(`select count(*)::int as n from ${t}`);
      return rows[0].n;
    },

    /** Lecturas en orden cronológico: las últimas `limit`, o las posteriores a `afterId`. */
    async readings({ limit = 60, afterId = null } = {}) {
      const { rows } =
        afterId == null
          ? await pool.query(`select * from (select * from ${t} order by id desc limit $1) x order by id asc`, [limit])
          : await pool.query(`select * from ${t} where id > $1 order by id asc limit $2`, [afterId, limit]);
      return rows.map(formatReading);
    },

    /** Resumen de los últimos `minutes` minutos. */
    async summary(minutes = 60) {
      const { rows } = await pool.query(
        `with w as (
           select *, lag(caida) over (order by id) as caida_prev, lag(caidas) over (order by id) as caidas_prev
             from ${t} where hora >= now() - make_interval(mins => $1)
         )
         select count(*)::int as lecturas,
                min(hora) as desde, max(hora) as hasta,
                round(avg(bpm) filter (where dedo = 1 and bpm > 0))::int as pulso_promedio,
                min(bpm) filter (where dedo = 1 and bpm > 0) as pulso_min,
                max(bpm) filter (where dedo = 1 and bpm > 0) as pulso_max,
                round(avg(spo2) filter (where dedo = 1 and spo2 > 0))::int as spo2_promedio,
                min(spo2) filter (where dedo = 1 and spo2 > 0) as spo2_min,
                count(*) filter (where dedo = 1)::int as lecturas_con_dedo,
                max(mag_g) as magnitud_max,
                count(*) filter (where (caida = 1 and coalesce(caida_prev, 0) = 0) or caidas > caidas_prev)::int as caidas,
                round(100.0 * avg(case when max_ok = 1 and coalesce(max_err, 0) = 0 then 1 else 0 end))::int as sensor_pulso_ok,
                round(100.0 * avg(case when mpu_ok = 1 and coalesce(mpu_err, 0) = 0 then 1 else 0 end))::int as sensor_acel_ok
           from w`,
        [minutes],
      );
      const r = rows[0];
      return {
        minutos: minutes,
        lecturas: r.lecturas,
        desde: r.desde ? new Date(r.desde).toISOString() : null,
        hasta: r.hasta ? new Date(r.hasta).toISOString() : null,
        pulso: { promedio: r.pulso_promedio, min: r.pulso_min, max: r.pulso_max },
        spo2: { promedio: r.spo2_promedio, min: r.spo2_min },
        lecturasConDedo: r.lecturas_con_dedo,
        magnitudMax: num(r.magnitud_max),
        caidas: r.caidas,
        sensoresOk: { pulso: r.sensor_pulso_ok, acelerometro: r.sensor_acel_ok },
      };
    },
  };
}
