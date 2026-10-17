# 🫠 PRUEBAS SENSORES (v7)

Prototipo para Arduino UNO Q con dos sensores: **MAX30102** (pulso y SpO2) y **MPU6050** (acelerómetro y detección de caídas).

Se quitaron el radar HLK-LD2410C y el nRF52840 (no hay Bluetooth). Los datos salen por el monitor de App Lab y se guardan en `python/datos.csv`.

## Cableado

Cada sensor tiene su propio bus I2C por software, con los pines de tu sketch original.

| Sensor | Pin del sensor | Pin del UNO Q |
|---|---|---|
| MAX30102 | VIN | 3V3 |
| MAX30102 | GND | GND |
| MAX30102 | SDA | D7 |
| MAX30102 | SCL | D6 |
| MAX30102 | INT | sin conectar |
| MPU6050 | VCC | 3V3 |
| MPU6050 | GND | GND |
| MPU6050 | SDA | D3 |
| MPU6050 | SCL | D2 |
| MPU6050 | AD0 | GND (0x68) |
| MPU6050 | INT | sin conectar |

- Todas las tierras van unidas.
- Si los módulos no traen pull-ups, o el MAX30102 las trae a 1,8 V, pon 4,7 kΩ de SDA a 3V3 y de SCL a 3V3 en cada bus.
- **Suelda los pines.** Si solo están insertados, el contacto se mueve y el sensor se cae y vuelve.

## Cómo funciona

- **I2C por software** (los pines D2, D3, D6 y D7 no son I2C de hardware), unos 25 kHz, con open-drain emulado: el micro nunca empuja una línea a 3,3 V. Dos buses independientes; el sketch elige el bus según la dirección del sensor.
- **Diagnóstico al arrancar** de cada sensor: revisa que SDA/SCL suban y bajen, libera el bus si quedó trabado, comprueba la dirección y el ID del chip. Si falla, dice la causa y reintenta cada 2 s. Un sensor caído no detiene al otro.
- **MAX30102.** Vacía la FIFO cada 40 ms. El BPM usa la mediana de los últimos 5 intervalos. La SpO2 usa una fórmula empírica (`SPO2_A - SPO2_B · R`): sirve para pruebas, no es una medición médica. **v7:** solo se publica tras 3 ventanas válidas seguidas (~6 s de dedo quieto), con índice de perfusión entre 0,05 % y 20 % y sin saturar; si la fórmula da menos de 70 % la lectura se descarta en vez de recortarse. Para calibrar, compara con un oxímetro comercial y ajusta `SPO2_A` y `SPO2_B`.
- **MPU6050.** Acelerómetro ±8 g a 50 muestras por segundo, filtro de ~21 Hz.
- **Caídas (v7).** La lógica está en `sketch/caida.h` y se confirma en cuatro pasos:
  1. **Caída libre:** |a| < 0,5 g durante 40 ms.
  2. **Impacto:** |a| > 2,5 g en el siguiente segundo.
  3. **Quietud:** tras 1 s de rebotes, durante 2 s |a| varía menos de 0,3 g.
  4. **Cambio de postura:** la gravedad giró al menos 35° respecto a antes de caer.

  Solo si se cumplen los cuatro se marca `caida` (3 s) y se cuenta en `caidas`, unos **3 s después del golpe**. Los golpes que no pasan se cuentan en `descartes` y el monitor dice por qué (`siguio moviendose` o `misma postura`). Umbrales en `CAIDA_*` al inicio de `sketch.ino`.

  En la v6 bastaban los pasos 1 y 2: levantar la placa y dejarla sobre la mesa contaba como caída (6 falsas alarmas en 2 minutos en las pruebas del 16/10). Con la v7 esos casos se descartan por "misma postura". Si la persona se levanta enseguida tampoco se avisa: para eso está el botón de ayuda de la app.
- **Pico por lectura (v7).** El sketch guarda el |a| máximo y mínimo entre una consulta de Python y la siguiente (`mag_max_mg`, `mag_min_mg`). Así la base ve el golpe aunque solo guarde una fila por segundo.
- **Python (Linux del UNO Q)** consulta `get_data` por Bridge cada segundo, imprime el resultado y lo guarda en `python/datos.csv` y en la tabla `lecturas` de PostgreSQL (Neon). Al conectar agrega las columnas que falten (`mag_max_mg`, `mag_min_mg`, `mag_max_g`, `mag_min_g`) sin borrar datos. Si ya existía un `datos.csv` con otras columnas, lo renombra con la fecha y empieza uno nuevo.

## Uso

1. Abre la carpeta en Arduino App Lab y pulsa **Run**.
2. Revisa el monitor: deben aparecer `MAX30102 listo` y `MPU6050 listo`.
3. Coloca el dedo sobre el MAX30102 sin apretar. El pulso tarda unos 5 s en aparecer.
4. Con el MPU6050 quieto, `|a|` debe rondar 1,00 g. Para probar la detección: con el módulo **vertical** (como iría en el bolsillo o colgado), déjalo caer sobre un cojín desde 30 a 50 cm de modo que quede **acostado** y no lo toques 3 s. Levantarlo y dejarlo en la mesa en la misma posición debe dar `Golpe descartado: misma postura`.

## Pruebas de la detección de caídas

`pruebas/test_caida.cpp` prueba `sketch/caida.h` con 9 señales sintéticas a 50 Hz: placa quieta, levantar y dejar en la mesa, 6 manipulaciones seguidas, caída real, resbalón, caer y levantarse, golpe de 4,84 g sin caída libre, lanzar y atrapar, y dos caídas seguidas. Con cualquier compilador de C++17:

```bash
g++ -std=c++17 -O2 pruebas/test_caida.cpp -o test_caida && ./test_caida
```

Debe terminar con `Todas las pruebas pasaron`.

## Mensajes del monitor

| Mensaje | Qué significa | Qué hacer |
|---|---|---|
| `SCL  pegado en bajo` | Algo lleva la línea a GND | Revisa cortos |
| `SCL forzado en alto` | Otra salida empuja la línea | Quita lo que más haya en ese pin |
| `SDA pegado en bajo` | La línea no sube ni liberando el bus | Revisa cortos o SDA/SCL invertidos |
| `no responde en 0x57` | MAX30102 no contesta | Revisa VIN=3V3, GND, SDA/SCL |
| `no responde en 0x68` | MPU6050 no contesta | Revisa VCC=3V3, GND, AD0=GND, SDA/SCL |
| `Part ID ... se esperaba 0x15` | El chip en 0x57 no es un MAX30102 | Revisa el modelo del módulo |
| `WHO_AM_I ... se esperaba 0x68` | El chip en 0x68 no es un MPU6050 | Revisa el módulo (los clones MPU6500 también se aceptan) |
| `comunicacion I2C inestable` | Contesta a veces | Suelda pines, cables cortos, pull-ups de 4,7 kΩ |
| `dejo de entregar muestras` | El chip se reinició (corte de alimentación) | Contacto flojo; el sketch lo reconfigura solo |
| `coloca el dedo` | MAX30102 sin dedo | Pon el dedo sin apretar |
| `midiendo...` | Hay dedo, aún no hay pulso estable | Dedo quieto unos segundos |
| `SpO2=--` | Falta completar la primera ventana de 2 s | Normal: espera 2 s |
| `saturado: baja MAX_LED_PA` | La luz IR llega al máximo | Baja `MAX_LED_PA` en `sketch.ino` (por ejemplo a `0x24`) |
| `(analizando golpe...)` | Hubo caída libre + impacto; se revisa quietud y postura | Espera unos 3 s |
| `CAIDA DETECTADA ... golpe X g, giro Y grados` | Se cumplieron los 4 pasos | Ajusta `CAIDA_*` si dispara de más o de menos |
| `Golpe descartado: siguio moviendose` | Tras el golpe hubo movimiento | Normal si la persona se levantó; si no, sube `CAIDA_QUIETO_RANGO_MG` |
| `Golpe descartado: misma postura` | Tras el golpe quedó igual que antes (p. ej. en la mesa) | Si una caída real se descarta así, baja `CAIDA_GIRO_MIN_DEG` |

Si falla la compilación en `Arduino_RouterBridge.h`, instálala desde el Library Manager de App Lab.
