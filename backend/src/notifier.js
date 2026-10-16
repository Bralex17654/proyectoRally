/**
 * Avisos a los cuidadores vinculados a un dispositivo.
 *
 * Canales:
 *  - consola (siempre, útil en la demo)
 *  - Telegram, si hay TELEGRAM_BOT_TOKEN y el cuidador registró su chat id
 *    (gratis y funciona en Android y iPhone)
 *
 * El SMS lo sigue enviando el teléfono con su chip: funciona sin internet.
 * Este servidor agrega el link con mapa, el "Voy en camino" y la escalada.
 */
export function createNotifier({ telegramToken = null, log = console.log, fetchImpl = globalThis.fetch } = {}) {
  const sent = [];

  async function sendTelegram(chatId, text) {
    if (!telegramToken || !chatId) return false;
    try {
      const res = await fetchImpl(`https://api.telegram.org/bot${telegramToken}/sendMessage`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ chat_id: chatId, text }),
      });
      return res.ok;
    } catch (error) {
      log(`[telegram] error: ${error.message}`);
      return false;
    }
  }

  /** Envía [text] a todos los cuidadores del dispositivo. */
  async function toCaregivers(store, deviceId, kind, text) {
    const device = store.data.devices[deviceId];
    const caregivers = (device?.caregiverIds ?? []).map((id) => store.data.caregivers[id]).filter(Boolean);
    log(`[aviso:${kind}] ${text}`);
    const results = await Promise.all(caregivers.map((c) => sendTelegram(c.telegramChatId, text)));
    const entry = {
      deviceId,
      kind,
      text,
      caregivers: caregivers.length,
      telegramDelivered: results.filter(Boolean).length,
    };
    sent.push(entry);
    return entry;
  }

  return { toCaregivers, sent };
}
