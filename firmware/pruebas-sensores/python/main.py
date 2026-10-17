import csv
import json
import time
from pathlib import Path

from arduino.app_utils import App, Bridge

from db import Almacen

CSV_PATH = Path(__file__).parent / "datos.csv"
CAMPOS = [
    "hora", "dedo", "bpm", "spo2", "ir", "rojo", "saturado", "max_ok", "max_err",
    "mpu_ok", "mpu_err", "ax_mg", "ay_mg", "az_mg", "mag_mg", "mag_max_mg", "mag_min_mg",
    "caida", "caidas", "descartes",
]
# Mismos codigos que errText() en sketch.ino (el 4 y el 5 cambian segun el sensor)
ERRORES = {
    1: "SCL pegado en bajo, revisa cortos",
    2: "SCL forzado en alto, hay otra salida conectada",
    3: "SDA pegado en bajo",
    6: "comunicacion I2C inestable, revisa contactos",
    7: "dejo de entregar muestras, se reinicio o perdio alimentacion",
}
ERRORES_MAX = {**ERRORES, 4: "no responde en 0x57, revisa cableado y pull-ups",
               5: "el Part ID no es 0x15"}
ERRORES_MPU = {**ERRORES, 4: "no responde en 0x68, revisa cableado, AD0=GND y pull-ups",
               5: "el WHO_AM_I no es de un MPU6050"}

fallos = 0
almacen = Almacen()


def preparar_csv():
    """Crea datos.csv. Si ya existe con otras columnas, lo renombra para no mezclar formatos."""
    if CSV_PATH.exists():
        with CSV_PATH.open(newline="") as f:
            if next(csv.reader(f), None) == CAMPOS:
                return
        CSV_PATH.rename(CSV_PATH.with_name(f"datos_{time.strftime('%Y%m%d_%H%M%S')}.csv"))
    with CSV_PATH.open("w", newline="") as f:
        csv.writer(f).writerow(CAMPOS)


def texto_mpu(d):
    if not d.get("mpu_ok"):
        return "error: " + ERRORES_MPU.get(d.get("mpu_err"), "sin sensor")
    texto = f"|a| {d.get('mag_mg', 0) / 1000:.2f} g (pico {d.get('mag_max_mg', 0) / 1000:.2f} g)"
    if d.get("caida"):
        texto += " | CAIDA DETECTADA"
    return texto


def texto_max(d):
    if not d.get("max_ok"):
        return "error: " + ERRORES_MAX.get(d.get("max_err"), "sin sensor")
    if not d.get("dedo"):
        return "sin dedo"
    if not d.get("bpm"):
        return "midiendo..."
    spo2 = f"{d['spo2']}%" if d.get("spo2") else "--"
    return f"{d['bpm']} lpm, SpO2 {spo2}"


def loop():
    """Pide los datos al sketch una vez por segundo, los muestra y los guarda en datos.csv."""
    global fallos
    try:
        d = json.loads(Bridge.call("get_data"))
    except Exception as e:
        fallos += 1
        if fallos == 1 or fallos % 10 == 0:
            print(f"Sin respuesta del sketch ({fallos} intentos): {e}")
        time.sleep(2)
        return
    fallos = 0

    print(f"[MPU6050] {texto_mpu(d)} | [MAX30102] {texto_max(d)}")

    d["hora"] = time.strftime("%Y-%m-%d %H:%M:%S")
    try:
        with CSV_PATH.open("a", newline="") as f:
            csv.writer(f).writerow([d.get(c, "") for c in CAMPOS])
    except OSError as e:
        print(f"No se pudo escribir {CSV_PATH.name}: {e}")
    almacen.guardar(d)

    time.sleep(1)


preparar_csv()
App.run(user_loop=loop)
