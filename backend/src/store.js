import fs from 'node:fs';
import path from 'node:path';

const empty = () => ({
  devices: {},
  caregivers: {},
  communities: {},
  alerts: {},
  invites: {},
  events: [],
  /** hash del token → { kind, id } */
  tokens: {},
});

const MAX_EVENTS = 20000;

/**
 * Almacenamiento en memoria con respaldo en un archivo JSON.
 * Suficiente para el prototipo; para producción se reemplaza por
 * PostgreSQL o Firestore manteniendo la misma forma de los datos.
 */
export class Store {
  constructor({ file = null } = {}) {
    this.file = file;
    this.data = empty();
    this._timer = null;
    if (file && fs.existsSync(file)) {
      try {
        this.data = { ...empty(), ...JSON.parse(fs.readFileSync(file, 'utf8')) };
      } catch (error) {
        console.error(`No se pudo leer ${file}; se inicia vacío.`, error.message);
      }
    }
  }

  /** Guarda en disco poco después del último cambio. */
  save() {
    if (!this.file) return;
    clearTimeout(this._timer);
    this._timer = setTimeout(() => this.flush(), 200);
    this._timer.unref?.();
  }

  flush() {
    if (!this.file) return;
    clearTimeout(this._timer);
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    const tmp = `${this.file}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(this.data, null, 2));
    fs.renameSync(tmp, this.file);
  }

  addEvent(event) {
    this.data.events.push(event);
    if (this.data.events.length > MAX_EVENTS) {
      this.data.events.splice(0, this.data.events.length - MAX_EVENTS);
    }
    this.save();
    return event;
  }
}
