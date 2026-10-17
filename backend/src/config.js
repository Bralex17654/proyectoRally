import crypto from 'node:crypto';

const minutes = (value) => Number(value) * 60 * 1000;

/** Configuración desde variables de entorno (ver .env.example). */
export function loadConfig(env = process.env) {
  const generatedAdminKey = !env.ADMIN_KEY;
  return {
    port: Number(env.PORT ?? 8080),
    /** URL pública para los links de los SMS. Si está vacía se usa la del request. */
    publicUrl: (env.PUBLIC_URL ?? '').replace(/\/+$/, ''),
    dataFile: env.DATA_FILE ?? 'data/guardian.json',
    adminKey: env.ADMIN_KEY || crypto.randomBytes(12).toString('base64url'),
    generatedAdminKey,
    telegramToken: env.TELEGRAM_BOT_TOKEN || null,
    heartbeatEveryMin: Number(env.HEARTBEAT_EVERY_MIN ?? 15),
    offlineAfterMs: minutes(env.OFFLINE_AFTER_MIN ?? 45),
    lowBatteryPercent: Number(env.LOW_BATTERY_PERCENT ?? 15),
    escalateAfterMs: minutes(env.ESCALATE_AFTER_MIN ?? 3),
    maxEscalations: Number(env.MAX_ESCALATIONS ?? 3),
    inviteTtlMs: minutes(env.INVITE_TTL_MIN ?? 30),
    retentionDays: Number(env.RETENTION_DAYS ?? 30),
    watchdogIntervalMs: Number(env.WATCHDOG_INTERVAL_MS ?? 60000),

    // PostgreSQL (Neon). Sin DATABASE_URL se usa el archivo JSON.
    databaseUrl: env.DATABASE_URL || null,
    dbSchema: env.DB_SCHEMA ?? 'tzinda',

    // Pulsera física: lee su tabla de lecturas en tiempo real.
    sensorEnabled: env.SENSOR_ENABLED !== 'false',
    sensorTable: env.SENSOR_TABLE ?? 'public.lecturas',
    sensorPollMs: Number(env.SENSOR_POLL_MS ?? 1000),
    sensorAlertCooldownMs: Number(env.SENSOR_ALERT_COOLDOWN_S ?? 120) * 1000,
    sensorOnlineSeconds: Number(env.SENSOR_ONLINE_SECONDS ?? 15),
    sensorPersonName: env.SENSOR_PERSON_NAME || 'Persona con pulsera',
    sensorPersonNameSet: Boolean(env.SENSOR_PERSON_NAME),
  };
}
