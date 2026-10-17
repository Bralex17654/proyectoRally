"""Guarda las lecturas en PostgreSQL (Neon). Si no hay red, las acumula y las envia al volver."""
import os
import threading
import time
from collections import deque
from datetime import datetime, timezone
from pathlib import Path

import psycopg

CONF_PATH = Path(__file__).parent / "db.conf"
MAX_PENDIENTES = 5000          # ~1,4 h de lecturas a 1 por segundo
REINTENTO_S = 10               # espera entre reintentos de conexion

CAMPOS = [
    "dedo", "bpm", "spo2", "ir", "rojo", "saturado", "max_ok", "max_err",
    "mpu_ok", "mpu_err", "ax_mg", "ay_mg", "az_mg", "mag_mg", "mag_max_mg", "mag_min_mg",
    "caida", "caidas",
]

SQL_TABLA = """
CREATE TABLE IF NOT EXISTS lecturas (
    id        BIGSERIAL PRIMARY KEY,
    hora      TIMESTAMPTZ NOT NULL,
    dedo      SMALLINT, bpm INTEGER, spo2 INTEGER,
    ir        BIGINT,   rojo BIGINT, saturado SMALLINT,
    max_ok    SMALLINT, max_err SMALLINT,
    mpu_ok    SMALLINT, mpu_err SMALLINT,
    ax_mg     INTEGER,  ay_mg INTEGER, az_mg INTEGER, mag_mg INTEGER,
    caida     SMALLINT, caidas INTEGER
);
CREATE INDEX IF NOT EXISTS lecturas_hora_idx ON lecturas (hora);
-- Aceleracion en g, calculada por la propia base (el sensor entrega milli-g)
ALTER TABLE lecturas
    ADD COLUMN IF NOT EXISTS ax_g NUMERIC(7,3) GENERATED ALWAYS AS (ax_mg / 1000.0) STORED,
    ADD COLUMN IF NOT EXISTS ay_g NUMERIC(7,3) GENERATED ALWAYS AS (ay_mg / 1000.0) STORED,
    ADD COLUMN IF NOT EXISTS az_g NUMERIC(7,3) GENERATED ALWAYS AS (az_mg / 1000.0) STORED,
    ADD COLUMN IF NOT EXISTS mag_g NUMERIC(7,3) GENERATED ALWAYS AS (mag_mg / 1000.0) STORED;
-- v7: |a| maximo y minimo entre lecturas (el golpe dura centesimas de segundo)
ALTER TABLE lecturas
    ADD COLUMN IF NOT EXISTS mag_max_mg INTEGER,
    ADD COLUMN IF NOT EXISTS mag_min_mg INTEGER;
ALTER TABLE lecturas
    ADD COLUMN IF NOT EXISTS mag_max_g NUMERIC(7,3) GENERATED ALWAYS AS (mag_max_mg / 1000.0) STORED,
    ADD COLUMN IF NOT EXISTS mag_min_g NUMERIC(7,3) GENERATED ALWAYS AS (mag_min_mg / 1000.0) STORED;
"""

SQL_INSERT = (
    "INSERT INTO lecturas (hora, " + ", ".join(CAMPOS) + ") VALUES (%s"
    + ", %s" * len(CAMPOS) + ")"
)


def _url():
    url = os.environ.get("DATABASE_URL", "").strip()
    if not url and CONF_PATH.exists():
        url = CONF_PATH.read_text().strip()
    return url


class Almacen:
    """Las lecturas se escriben desde un hilo aparte: la red lenta o caida
    no retrasa el muestreo, y no se pierde ninguna hasta MAX_PENDIENTES."""

    def __init__(self):
        self.url = _url()
        self.con = None
        self.pendientes = deque(maxlen=MAX_PENDIENTES)
        self.lock = threading.Lock()
        self.ultimo_error = None
        if not self.url:
            print("BD desactivada: falta python/db.conf o DATABASE_URL")
            return
        threading.Thread(target=self._hilo, daemon=True).start()

    def guardar(self, d):
        """Encola la lectura con su hora real. No bloquea ni lanza excepciones."""
        fila = (datetime.now(timezone.utc),) + tuple(d.get(c) for c in CAMPOS)
        with self.lock:
            if len(self.pendientes) == MAX_PENDIENTES:
                print("BD: cola llena, se descarta la lectura mas antigua")
            self.pendientes.append(fila)

    def _hilo(self):
        while True:
            try:
                if self.con is None or self.con.closed:
                    self.con = psycopg.connect(self.url, connect_timeout=10, autocommit=True)
                    self.con.execute(SQL_TABLA)
                    print("Base de datos conectada")
                    self.ultimo_error = None
                with self.lock:
                    lote = list(self.pendientes)
                if lote:
                    with self.con.transaction(), self.con.cursor() as cur:   # todo el lote o nada
                        cur.executemany(SQL_INSERT, lote)
                    with self.lock:                 # solo se descarta lo ya confirmado
                        for _ in lote:
                            self.pendientes.popleft()
                time.sleep(1)
            except Exception as e:
                self._cerrar()
                self._avisar(e)
                time.sleep(REINTENTO_S)

    def _cerrar(self):
        try:
            if self.con is not None:
                self.con.close()
        except Exception:
            pass
        self.con = None

    def _avisar(self, e):
        # Solo avisa cuando cambia el error, para no llenar el monitor
        msg = f"{type(e).__name__}: {e}".splitlines()[0]
        if msg != self.ultimo_error:
            self.ultimo_error = msg
            print(f"BD no disponible, guardando en memoria ({len(self.pendientes)} pendientes): {msg}")
