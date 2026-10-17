/*
  PRUEBAS SENSORES - Arduino UNO Q (v7)
  Sensores: MAX30102 (pulso / SpO2) + MPU6050 (acelerometro, deteccion de caidas)
  Cada sensor tiene su propio bus I2C por software, con tus pines:

  CABLEADO
  --------
  MAX30102                      MPU6050
    VIN -> 3V3                    VCC -> 3V3
    GND -> GND                    GND -> GND
    SDA -> D7                     SDA -> D3
    SCL -> D6                     SCL -> D2
    INT -> sin conectar           AD0 -> GND (direccion 0x68)

  Todas las tierras unidas. Si los modulos no traen pull-ups, pon 4,7 kOhm
  de SDA a 3V3 y de SCL a 3V3 en cada bus.

  VELOCIDADES
    I2C: ~25 kHz generado por software, en cada bus
    Monitor y Python: por Bridge, sin baudios que configurar
*/

#include <Arduino_RouterBridge.h>

#include "caida.h"   // deteccion de caidas (probada en pruebas/test_caida.cpp)

// ------------------------------------------------------------------
// Configuracion
// ------------------------------------------------------------------
constexpr uint8_t  MAX_SDA    = 7;
constexpr uint8_t  MAX_SCL    = 6;
constexpr uint8_t  MPU_SDA    = 3;
constexpr uint8_t  MPU_SCL    = 2;

constexpr uint8_t  MAX_ADDR        = 0x57;
constexpr uint8_t  MAX_PART_ID     = 0x15;
constexpr uint8_t  MAX_LED_PA      = 0x3C;    // corriente de cada LED (0x3C = ~12 mA)
constexpr uint32_t MS_POR_MUESTRA  = 10;      // 400 sps con promedio x4 = 100 Hz
constexpr uint32_t FINGER_ON       = 50000;   // IR por encima: hay dedo
constexpr uint32_t FINGER_OFF      = 30000;   // IR por debajo: se quito el dedo (histeresis)
constexpr uint32_t IR_SATURADO     = 250000;  // cerca del maximo de 18 bits
constexpr uint32_t SETTLE_MS       = 1500;    // tiempo para que el filtro se asiente con el dedo
constexpr uint32_t MAX_POLL_MS     = 40;      // cada cuanto se vacia la FIFO (guarda 320 ms)
constexpr uint32_t MAX_RETRY_MS    = 2000;    // reintento si el sensor no responde
constexpr uint8_t  MAX_ERR_LIMITE  = 5;       // lecturas fallidas seguidas antes de darlo por perdido
constexpr uint32_t MAX_STALL_MS    = 1000;    // sin muestras nuevas: el sensor se reinicio o perdio alimentacion

constexpr uint16_t I2C_HALF_US     = 10;      // medio periodo del reloj I2C
constexpr uint32_t PRINT_MS        = 500;

// MPU6050 y deteccion de caidas
constexpr uint8_t  MPU_ADDR        = 0x68;
constexpr uint32_t MPU_POLL_MS     = 20;      // 50 muestras por segundo
constexpr uint32_t MPU_RETRY_MS    = 2000;
constexpr uint8_t  MPU_ERR_LIMITE  = 5;
constexpr int32_t  CAIDA_LIBRE_MG  = 500;     // |a| por debajo: caida libre (0,5 g)
constexpr int32_t  CAIDA_IMPACTO_MG = 2500;   // |a| por encima tras la caida libre: impacto (2,5 g)
constexpr uint8_t  CAIDA_LIBRE_MIN = 2;       // muestras seguidas en caida libre (40 ms)
constexpr uint32_t CAIDA_VENTANA_MS = 1000;   // el impacto debe llegar dentro de este tiempo
constexpr uint32_t CAIDA_ASENTAR_MS = 1000;   // se ignoran los rebotes justo despues del golpe
constexpr uint32_t CAIDA_QUIETO_MS = 2000;    // despues, la persona debe quedar quieta este tiempo...
constexpr int32_t  CAIDA_QUIETO_RANGO_MG = 300;  // ...variando menos que esto
constexpr float    CAIDA_GIRO_MIN_DEG = 35.0f;   // y haber cambiado de postura al menos esto
constexpr uint32_t CAIDA_AVISO_MS  = 3000;    // cuanto tiempo se mantiene el aviso de caida

// SpO2: solo se publica con buena senal durante varias ventanas seguidas
constexpr float    SPO2_A = 110.0f;           // SpO2 = A - B * R. Calibrar contra un oximetro comercial
constexpr float    SPO2_B = 25.0f;
constexpr uint8_t  SPO2_VENTANAS_MIN = 3;     // ventanas de 2 s validas seguidas (~6 s de dedo quieto)
constexpr float    SPO2_PI_MIN = 0.05f;       // indice de perfusion (AC/DC del IR, %) aceptable
constexpr float    SPO2_PI_MAX = 20.0f;

// Codigos de error (python/main.py usa los mismos numeros). Los del bus
// (1 a 3) y los de lectura valen para los dos sensores.
enum : int32_t {
  ERR_OK            = 0,
  ERR_SCL_BAJO      = 1,
  ERR_SCL_FORZADO   = 2,
  ERR_SDA_BAJO      = 3,
  ERR_SIN_ACK       = 4,
  ERR_ID            = 5,
  ERR_LECTURA       = 6,
  ERR_SIN_MUESTRAS  = 7,
};

static const char *errText(int32_t e, bool mpu) {
  switch (e) {
    case ERR_OK:            return "ok";
    case ERR_SCL_BAJO:      return mpu ? "SCL (D2) pegado en bajo: algo lo lleva a GND, revisa cortos"
                                       : "SCL (D6) pegado en bajo: algo lo lleva a GND, revisa cortos";
    case ERR_SCL_FORZADO:   return mpu ? "SCL (D2) forzado en alto: hay otra salida conectada en D2"
                                       : "SCL (D6) forzado en alto: hay otra salida conectada en D6";
    case ERR_SDA_BAJO:      return mpu ? "SDA (D3) pegado en bajo: revisa cortos o SDA/SCL invertidos"
                                       : "SDA (D7) pegado en bajo: revisa cortos o SDA/SCL invertidos";
    case ERR_SIN_ACK:       return mpu ? "no responde en 0x68: revisa VCC=3V3, GND, AD0=GND, SDA=D3, SCL=D2"
                                       : "no responde en 0x57: revisa VIN=3V3, GND, SDA=D7, SCL=D6";
    case ERR_ID:            return mpu ? "responde en 0x68 pero WHO_AM_I no es de un MPU6050"
                                       : "responde en 0x57 pero el Part ID no es 0x15";
    case ERR_LECTURA:       return "comunicacion I2C inestable: contacto flojo, cables largos o pull-ups debiles";
    case ERR_SIN_MUESTRAS:  return "dejo de entregar muestras: se reinicio o perdio alimentacion (contacto flojo?)";
  }
  return "?";
}

// ------------------------------------------------------------------
// Estado compartido (lo lee la funcion de Bridge desde otro hilo)
// ------------------------------------------------------------------
volatile int32_t  g_bpm = 0;
volatile int32_t  g_spo2 = 0;
volatile int32_t  g_finger = 0;
volatile uint32_t g_ir = 0;
volatile uint32_t g_red = 0;
volatile int32_t  g_maxSat = 0;
volatile int32_t  g_maxOk = 0;
volatile int32_t  g_maxErr = ERR_SIN_ACK;
volatile int32_t  g_maxPartId = 0;
volatile uint32_t g_maxOvf = 0;          // muestras perdidas por FIFO llena
volatile int32_t  g_mpuOk = 0;
volatile int32_t  g_mpuErr = ERR_SIN_ACK;
volatile int32_t  g_mpuId = 0;
volatile int32_t  g_axMg = 0;            // aceleracion en milli-g
volatile int32_t  g_ayMg = 0;
volatile int32_t  g_azMg = 0;
volatile int32_t  g_magMg = 0;           // modulo del vector
volatile int32_t  g_caida = 0;           // 1 mientras dura el aviso de caida
volatile uint32_t g_caidas = 0;          // caidas detectadas desde el arranque
volatile uint32_t g_descartes = 0;       // golpes analizados y descartados (movimiento o misma postura)
volatile int32_t  g_magMaxMg = 0;        // |a| maximo y minimo desde la ultima consulta de Python:
volatile int32_t  g_magMinMg = 0;        //   el golpe dura centesimas y la base guarda 1 lectura por segundo
volatile bool     g_picoLeido = true;    // Python ya leyo el pico: empezar uno nuevo

// ------------------------------------------------------------------
// I2C por software con open-drain emulado en las dos lineas:
// "1" = pin en entrada con pull-up (la linea sube sola), "0" = salida en LOW.
// El micro nunca empuja una linea a 3,3 V, asi no choca con otra salida
// ni alimenta un pull-up de 1,8 V del modulo.
// ------------------------------------------------------------------
static bool i2cFault = false;

// Pines del bus activo. Cada transaccion elige el bus segun la direccion
// del sensor (MAX30102 en D7/D6, MPU6050 en D3/D2).
static uint8_t PIN_SDA = MAX_SDA;
static uint8_t PIN_SCL = MAX_SCL;

static void busSelect(uint8_t addr) {
  if (addr == MAX_ADDR) { PIN_SDA = MAX_SDA; PIN_SCL = MAX_SCL; }
  else                  { PIN_SDA = MPU_SDA; PIN_SCL = MPU_SCL; }
}

static inline void i2cDelay() { delayMicroseconds(I2C_HALF_US); }
static inline bool lineRead(uint8_t pin) { return digitalRead(pin) == HIGH; }
static inline void lineRelease(uint8_t pin) { pinMode(pin, INPUT_PULLUP); }
static inline void lineLow(uint8_t pin) {
  digitalWrite(pin, LOW);
  pinMode(pin, OUTPUT);
  digitalWrite(pin, LOW);
}

static inline void sdaHigh() { lineRelease(PIN_SDA); }
static inline void sdaLow()  { lineLow(PIN_SDA); }
static inline void sclLow()  { lineLow(PIN_SCL); }

// Suelta SCL y espera a que suba (flanco lento o clock stretching)
static void sclHigh() {
  lineRelease(PIN_SCL);
  uint32_t t0 = micros();
  while (!lineRead(PIN_SCL)) {
    if (micros() - t0 > 1000) { i2cFault = true; return; }
  }
}

static void i2cStart() {
  sdaHigh(); sclHigh(); i2cDelay();
  sdaLow();  i2cDelay();
  sclLow();  i2cDelay();
}

static void i2cStop() {
  sdaLow();  i2cDelay();
  sclHigh(); i2cDelay();
  sdaHigh(); i2cDelay();
}

// Devuelve true si el esclavo respondio ACK
static bool i2cWriteByte(uint8_t b) {
  for (uint8_t i = 0; i < 8; i++) {
    if (b & 0x80) sdaHigh(); else sdaLow();
    b <<= 1;
    i2cDelay();
    sclHigh(); i2cDelay();
    sclLow();
  }
  sdaHigh(); i2cDelay();
  sclHigh(); i2cDelay();
  bool ack = !lineRead(PIN_SDA);
  sclLow(); i2cDelay();
  return ack && !i2cFault;
}

static uint8_t i2cReadByte(bool ack) {
  uint8_t b = 0;
  sdaHigh();
  for (uint8_t i = 0; i < 8; i++) {
    i2cDelay();
    sclHigh(); i2cDelay();
    b = (b << 1) | (lineRead(PIN_SDA) ? 1 : 0);
    sclLow();
  }
  if (ack) sdaLow(); else sdaHigh();
  i2cDelay();
  sclHigh(); i2cDelay();
  sclLow(); i2cDelay();
  sdaHigh();
  return b;
}

// Libera el bus si un esclavo quedo a mitad de byte con SDA en bajo:
// hasta 9 pulsos de reloj y luego STOP.
static void i2cRecover() {
  sdaHigh();
  for (uint8_t i = 0; i < 9 && !lineRead(PIN_SDA); i++) {
    sclLow();  i2cDelay();
    sclHigh(); i2cDelay();
  }
  i2cStop();
}

// Revisa las dos lineas antes de hablar con el sensor
static int32_t i2cCheckBus(uint8_t addr) {
  busSelect(addr);
  i2cFault = false;
  sdaHigh();
  lineRelease(PIN_SCL);
  delayMicroseconds(100);
  if (!lineRead(PIN_SCL)) return ERR_SCL_BAJO;

  sclLow(); delayMicroseconds(5);              // SCL tiene que poder bajar
  bool forzado = lineRead(PIN_SCL);
  sclHigh(); delayMicroseconds(5);
  if (forzado) return ERR_SCL_FORZADO;
  if (i2cFault) return ERR_SCL_BAJO;

  if (!lineRead(PIN_SDA)) {
    i2cRecover();
    if (!lineRead(PIN_SDA)) return ERR_SDA_BAJO;
  }
  i2cFault = false;
  return ERR_OK;
}

static bool i2cProbe(uint8_t addr) {
  busSelect(addr);
  i2cFault = false;
  i2cStart();
  bool ack = i2cWriteByte(addr << 1);
  i2cStop();
  return ack && !i2cFault;
}

static bool regWrite(uint8_t addr, uint8_t reg, uint8_t val) {
  busSelect(addr);
  i2cFault = false;
  i2cStart();
  bool ok = i2cWriteByte((addr << 1) | 0)
         && i2cWriteByte(reg)
         && i2cWriteByte(val);
  i2cStop();
  return ok && !i2cFault;
}

static bool regRead(uint8_t addr, uint8_t reg, uint8_t *buf, uint8_t n) {
  busSelect(addr);
  i2cFault = false;
  i2cStart();
  bool ok = i2cWriteByte((addr << 1) | 0) && i2cWriteByte(reg);
  if (ok) {
    i2cStart();                                 // repeated start
    ok = i2cWriteByte((addr << 1) | 1);
  }
  if (ok) {
    for (uint8_t i = 0; i < n; i++) buf[i] = i2cReadByte(i + 1 < n);
  }
  i2cStop();
  return ok && !i2cFault;
}

// ------------------------------------------------------------------
// MAX30102
// ------------------------------------------------------------------
enum : uint8_t {
  REG_FIFO_WR     = 0x04,
  REG_FIFO_OVF    = 0x05,
  REG_FIFO_RD     = 0x06,
  REG_FIFO_DATA   = 0x07,
  REG_FIFO_CFG    = 0x08,
  REG_MODE_CFG    = 0x09,
  REG_SPO2_CFG    = 0x0A,
  REG_LED1_PA     = 0x0C,   // rojo
  REG_LED2_PA     = 0x0D,   // infrarrojo
  REG_PART_ID     = 0xFF
};

constexpr uint8_t FIFO_CFG = 0x40 | 0x10 | 0x0F;  // promedio x4, rollover, aviso a 17 muestras
constexpr uint8_t SPO2_CFG = 0x20 | 0x0C | 0x03;  // 4096 nA, 400 sps, 411 us (18 bits)
constexpr uint8_t MODE_SPO2 = 0x03;               // rojo + IR

static int32_t maxBegin() {
  int32_t e = i2cCheckBus(MAX_ADDR);
  if (e != ERR_OK) return e;
  if (!i2cProbe(MAX_ADDR)) return ERR_SIN_ACK;

  uint8_t id = 0;
  if (!regRead(MAX_ADDR, REG_PART_ID, &id, 1)) return ERR_LECTURA;
  g_maxPartId = id;
  if (id != MAX_PART_ID) return ERR_ID;

  if (!regWrite(MAX_ADDR, REG_MODE_CFG, 0x40)) return ERR_LECTURA;   // reset
  uint8_t m = 0x40;
  for (uint8_t i = 0; i < 20 && (m & 0x40); i++) {
    delay(10);
    if (!regRead(MAX_ADDR, REG_MODE_CFG, &m, 1)) m = 0x40;   // durante el reset puede no contestar
  }
  if (m & 0x40) return ERR_LECTURA;

  bool ok = regWrite(MAX_ADDR, REG_FIFO_CFG, FIFO_CFG)
         && regWrite(MAX_ADDR, REG_SPO2_CFG, SPO2_CFG)
         && regWrite(MAX_ADDR, REG_LED1_PA, MAX_LED_PA)
         && regWrite(MAX_ADDR, REG_LED2_PA, MAX_LED_PA)
         && regWrite(MAX_ADDR, REG_FIFO_WR, 0)
         && regWrite(MAX_ADDR, REG_FIFO_OVF, 0)
         && regWrite(MAX_ADDR, REG_FIFO_RD, 0)
         && regWrite(MAX_ADDR, REG_MODE_CFG, MODE_SPO2);
  if (!ok) return ERR_LECTURA;

  // Lee de vuelta MODE_CFG y SPO2_CFG para confirmar que la configuracion quedo
  uint8_t chk[2];
  if (!regRead(MAX_ADDR, REG_MODE_CFG, chk, 2)) return ERR_LECTURA;
  if ((chk[0] & 0x07) != MODE_SPO2 || chk[1] != SPO2_CFG) return ERR_LECTURA;
  return ERR_OK;
}

// Procesamiento de senal (100 muestras/s). Los tiempos se cuentan en
// muestras y no con millis(), porque la FIFO se lee en rafagas.
static float irDc = 0, redDc = 0, irLp = 0, env = 0, prevSig = 0;
static float irSq = 0, redSq = 0, irSum = 0, redSum = 0;
static uint16_t winN = 0;
static uint32_t muestras = 0;
static uint32_t lastBeatT = 0;
static uint32_t settleUntil = 0;
static uint32_t ibi[5];
static uint8_t ibiN = 0, ibiIdx = 0, ibiRechazos = 0;
static bool dcInit = false;
static float spo2Smooth = 0;
static uint8_t spo2Validas = 0;          // ventanas validas seguidas

static void resetAlgo() {
  dcInit = false; irLp = 0; env = 0; prevSig = 0;
  irSq = redSq = irSum = redSum = 0; winN = 0;
  lastBeatT = 0;
  ibiN = 0; ibiIdx = 0; ibiRechazos = 0;
  spo2Smooth = 0;
  spo2Validas = 0;
  g_bpm = 0;
  g_spo2 = 0;
}

// Mediana de los intervalos guardados: resiste latidos falsos o perdidos
static uint32_t ibiMediana() {
  uint32_t v[5];
  for (uint8_t i = 0; i < ibiN; i++) v[i] = ibi[i];
  for (uint8_t i = 1; i < ibiN; i++) {
    uint32_t x = v[i];
    int8_t j = i - 1;
    while (j >= 0 && v[j] > x) { v[j + 1] = v[j]; j--; }
    v[j + 1] = x;
  }
  return v[ibiN / 2];
}

static void addIbi(uint32_t d) {
  if (ibiN >= 3) {
    uint32_t med = ibiMediana();
    if (d < med * 2 / 3 || d > med * 3 / 2) {   // muy distinto: probable artefacto
      if (++ibiRechazos < 3) return;
      ibiN = 0; ibiIdx = 0;                     // 3 rechazos seguidos: el ritmo cambio de verdad
    }
  }
  ibiRechazos = 0;
  ibi[ibiIdx] = d;
  ibiIdx = (ibiIdx + 1) % 5;
  if (ibiN < 5) ibiN++;
  if (ibiN >= 3) {
    uint32_t med = ibiMediana();
    g_bpm = (int32_t)((60000UL + med / 2) / med);
  }
}

static void processSample(uint32_t red, uint32_t ir) {
  uint32_t t = (++muestras) * MS_POR_MUESTRA;
  g_ir = ir;
  g_red = red;
  g_maxSat = (ir >= IR_SATURADO) ? 1 : 0;

  // Deteccion de dedo con histeresis
  if (!g_finger) {
    if (ir < FINGER_ON) return;
    resetAlgo();
    g_finger = 1;
    settleUntil = t + SETTLE_MS;
  } else if (ir < FINGER_OFF) {
    resetAlgo();
    g_finger = 0;
    return;
  }

  if (!dcInit) { irDc = ir; redDc = red; dcInit = true; }
  irDc  += 0.02f * (ir  - irDc);
  redDc += 0.02f * (red - redDc);

  float irAc  = ir  - irDc;
  float redAc = red - redDc;

  // Suavizado y senal invertida: mas sangre = mas absorcion = menos IR
  irLp += 0.25f * (irAc - irLp);
  float sig = -irLp;

  if ((int32_t)(t - settleUntil) < 0) { prevSig = sig; return; }   // filtro asentandose

  float decayed = env * 0.995f;
  env = (sig > decayed) ? sig : decayed;
  float thr = 0.5f * env;

  if (env > 30.0f && sig > thr && prevSig <= thr && (t - lastBeatT) > 300) {
    if (lastBeatT != 0) {
      uint32_t d = t - lastBeatT;
      if (d >= 333 && d <= 1500) addIbi(d);     // 40 a 180 lpm
    }
    lastBeatT = t;
  }
  if (lastBeatT && (t - lastBeatT) > 3000) {    // se perdio el pulso
    lastBeatT = 0;
    ibiN = 0; ibiIdx = 0;
    spo2Smooth = 0;
    spo2Validas = 0;
    g_bpm = 0;
    g_spo2 = 0;
  }
  prevSig = sig;

  // SpO2 con razon de razones sobre ventanas de 2 s. Solo se publica tras
  // SPO2_VENTANAS_MIN ventanas validas seguidas: la primera ventana con el dedo
  // recien puesto da valores bajos (72-81 % en las pruebas) que no son reales.
  irSq += irAc * irAc;   redSq += redAc * redAc;
  irSum += irDc;         redSum += redDc;
  if (++winN >= 200) {
    float irRms = sqrtf(irSq / winN), redRms = sqrtf(redSq / winN);
    float irMean = irSum / winN, redMean = redSum / winN;
    float pi = (irMean > 0) ? 100.0f * irRms / irMean : 0;   // indice de perfusion en %
    bool valida = irRms > 20 && redRms > 20 && g_bpm > 0 && !g_maxSat
               && pi >= SPO2_PI_MIN && pi <= SPO2_PI_MAX;
    float s = 0;
    if (valida) {
      float R = (redRms / redMean) / (irRms / irMean);
      s = SPO2_A - SPO2_B * R;                   // formula empirica, no medica
      if (s > 100.0f) s = 100.0f;
      valida = s >= 70.0f;                       // fuera de rango: lectura mala, no se recorta
    }
    if (valida) {
      spo2Smooth = (spo2Smooth == 0) ? s : 0.7f * spo2Smooth + 0.3f * s;
      if (spo2Validas < 255) spo2Validas++;
      g_spo2 = (spo2Validas >= SPO2_VENTANAS_MIN) ? (int32_t)(spo2Smooth + 0.5f) : 0;
    } else {
      spo2Validas = 0;
      spo2Smooth = 0;
      g_spo2 = 0;
    }
    irSq = redSq = irSum = redSum = 0; winN = 0;
  }
}

static uint32_t maxLastSampleMs = 0;            // ultima vez que llegaron muestras nuevas

// Vacia la FIFO. Devuelve false si fallo la comunicacion.
static bool maxPoll() {
  uint8_t p[3];                                 // FIFO_WR, OVF, FIFO_RD son registros seguidos
  if (!regRead(MAX_ADDR, REG_FIFO_WR, p, 3)) return false;
  uint8_t n = (p[0] - p[2]) & 0x1F;
  if (p[1]) {
    g_maxOvf = g_maxOvf + p[1];
    if (n == 0) n = 32;                         // FIFO llena: punteros iguales
  }
  while (n) {
    uint8_t k = (n > 8) ? 8 : n;                // hasta 8 muestras por lectura
    uint8_t d[8 * 6];
    if (!regRead(MAX_ADDR, REG_FIFO_DATA, d, k * 6)) return false;
    bool todoCero = true;                       // SDA en bajo sin sensor: llegan solo ceros
    for (uint8_t i = 0; i < k * 6 && todoCero; i++) todoCero = (d[i] == 0);
    if (todoCero) return false;
    maxLastSampleMs = millis();
    for (uint8_t i = 0; i < k; i++) {
      const uint8_t *s = &d[i * 6];
      uint32_t red = (((uint32_t)s[0] << 16) | ((uint32_t)s[1] << 8) | s[2]) & 0x3FFFF;
      uint32_t ir  = (((uint32_t)s[3] << 16) | ((uint32_t)s[4] << 8) | s[5]) & 0x3FFFF;
      processSample(red, ir);
    }
    n -= k;
  }
  return true;
}

static void maxLost() {
  resetAlgo();
  g_finger = 0;
  g_ir = 0;
  g_red = 0;
  g_maxSat = 0;
}

static void reportMax(int32_t e) {
  char b[160];
  if (e == ERR_OK) {
    snprintf(b, sizeof b, "MAX30102 listo (SDA=D7, SCL=D6)");
  } else if (e == ERR_ID) {
    snprintf(b, sizeof b, "MAX30102 no disponible: Part ID=0x%02X, se esperaba 0x15", (unsigned)g_maxPartId);
  } else {
    snprintf(b, sizeof b, "MAX30102 no disponible: %s", errText(e, false));
  }
  Monitor.println(b);
}

static void maxFail(int32_t e) {
  g_maxOk = 0;
  g_maxErr = e;
  maxLost();
  reportMax(e);
}

// ------------------------------------------------------------------
// MPU6050: acelerometro +-8 g (4096 LSB/g) y deteccion de caidas
// Caida = caida libre + impacto + quietud + cambio de postura (ver caida.h).
// La v6 solo pedia caida libre + impacto: levantar la placa y dejarla sobre
// la mesa contaba como caida.
// ------------------------------------------------------------------
enum : uint8_t {
  MPU_REG_SMPLRT   = 0x19,
  MPU_REG_CONFIG   = 0x1A,
  MPU_REG_ACCEL    = 0x1C,
  MPU_REG_ACCEL_X  = 0x3B,
  MPU_REG_PWR1     = 0x6B,
  MPU_REG_WHO_AM_I = 0x75
};

static bool mpuIdValido(uint8_t id) {
  return id == 0x68 || id == 0x70 || id == 0x72;   // MPU6050 y clones comunes
}

static int32_t mpuBegin() {
  int32_t e = i2cCheckBus(MPU_ADDR);
  if (e != ERR_OK) return e;
  if (!i2cProbe(MPU_ADDR)) return ERR_SIN_ACK;

  uint8_t id = 0;
  if (!regRead(MPU_ADDR, MPU_REG_WHO_AM_I, &id, 1)) return ERR_LECTURA;
  g_mpuId = id;
  if (!mpuIdValido(id)) return ERR_ID;

  bool ok = regWrite(MPU_ADDR, MPU_REG_PWR1, 0x80);      // reset
  delay(100);
  ok = ok && regWrite(MPU_ADDR, MPU_REG_PWR1, 0x01)      // despierta, reloj PLL del giroscopio
          && regWrite(MPU_ADDR, MPU_REG_SMPLRT, 0x07)
          && regWrite(MPU_ADDR, MPU_REG_CONFIG, 0x04)    // filtro pasa-bajos ~21 Hz (como tu sketch)
          && regWrite(MPU_ADDR, MPU_REG_ACCEL, 0x10);    // +-8 g
  if (!ok) return ERR_LECTURA;

  uint8_t chk = 0;                                       // lee de vuelta el rango
  if (!regRead(MPU_ADDR, MPU_REG_ACCEL, &chk, 1) || chk != 0x10) return ERR_LECTURA;
  return ERR_OK;
}

static CaidaConfig caidaConfig() {
  CaidaConfig c;
  c.libreMg = CAIDA_LIBRE_MG;
  c.libreMin = CAIDA_LIBRE_MIN;
  c.impactoMg = CAIDA_IMPACTO_MG;
  c.ventanaMs = CAIDA_VENTANA_MS;
  c.asentarMs = CAIDA_ASENTAR_MS;
  c.quietoMs = CAIDA_QUIETO_MS;
  c.quietoRangoMg = CAIDA_QUIETO_RANGO_MG;
  c.giroMinDeg = CAIDA_GIRO_MIN_DEG;
  return c;
}

static DetectorCaidas detector(caidaConfig());
static uint32_t caidaT = 0;

static void caidaReset() {
  detector.reiniciar();
  g_caida = 0;
}

static void caidaProcesar(int32_t x, int32_t y, int32_t z, uint32_t now) {
  char b[160];
  switch (detector.procesar(x, y, z, now)) {
    case CaidaResultado::CAIDA: {
      g_caida = 1;
      g_caidas = g_caidas + 1;
      caidaT = now;
      int32_t p = detector.ultimoPicoMg();
      snprintf(b, sizeof b, "!!! CAIDA DETECTADA !!! golpe %d.%02d g, giro %d grados, quieto despues",
               (int)(p / 1000), (int)((p % 1000) / 10), (int)detector.ultimoGiroDeg());
      Monitor.println(b);
      break;
    }
    case CaidaResultado::DESCARTADA_MOVIMIENTO:
      g_descartes = g_descartes + 1;
      snprintf(b, sizeof b, "Golpe descartado: siguio moviendose (variacion %d mg)", (int)detector.ultimoRangoMg());
      Monitor.println(b);
      break;
    case CaidaResultado::DESCARTADA_POSTURA:
      g_descartes = g_descartes + 1;
      snprintf(b, sizeof b, "Golpe descartado: misma postura (giro %d grados), p. ej. la placa sobre la mesa",
               (int)detector.ultimoGiroDeg());
      Monitor.println(b);
      break;
    case CaidaResultado::NADA:
      break;
  }
  if (g_caida && now - caidaT > CAIDA_AVISO_MS) g_caida = 0;
}

// Lee una muestra. Devuelve false si fallo la comunicacion.
static bool mpuPoll() {
  uint8_t d[6];
  if (!regRead(MPU_ADDR, MPU_REG_ACCEL_X, d, 6)) return false;
  bool todoCero = true;                                  // SDA en bajo sin sensor: llegan solo ceros
  for (uint8_t i = 0; i < 6 && todoCero; i++) todoCero = (d[i] == 0);
  if (todoCero) return false;

  int16_t rx = (int16_t)((d[0] << 8) | d[1]);
  int16_t ry = (int16_t)((d[2] << 8) | d[3]);
  int16_t rz = (int16_t)((d[4] << 8) | d[5]);
  int32_t x = ((int32_t)rx * 1000) / 4096;               // milli-g
  int32_t y = ((int32_t)ry * 1000) / 4096;
  int32_t z = ((int32_t)rz * 1000) / 4096;
  int32_t mag = (int32_t)(sqrtf((float)x * x + (float)y * y + (float)z * z) + 0.5f);

  g_axMg = x; g_ayMg = y; g_azMg = z; g_magMg = mag;
  if (g_picoLeido) {                                     // Python leyo: empieza una ventana nueva
    g_magMaxMg = mag; g_magMinMg = mag; g_picoLeido = false;
  } else {
    if (mag > g_magMaxMg) g_magMaxMg = mag;
    if (mag < g_magMinMg) g_magMinMg = mag;
  }
  caidaProcesar(x, y, z, millis());
  return true;
}

static void mpuLost() {
  caidaReset();
  g_axMg = g_ayMg = g_azMg = g_magMg = 0;
  g_magMaxMg = g_magMinMg = 0;
}

static void reportMpu(int32_t e) {
  char b[160];
  if (e == ERR_OK) {
    snprintf(b, sizeof b, "MPU6050 listo (SDA=D3, SCL=D2)");
  } else if (e == ERR_ID) {
    snprintf(b, sizeof b, "MPU6050 no disponible: WHO_AM_I=0x%02X, se esperaba 0x68", (unsigned)g_mpuId);
  } else {
    snprintf(b, sizeof b, "MPU6050 no disponible: %s", errText(e, true));
  }
  Monitor.println(b);
}

static void mpuFail(int32_t e) {
  g_mpuOk = 0;
  g_mpuErr = e;
  mpuLost();
  reportMpu(e);
}

// ------------------------------------------------------------------
// Bridge: lo consulta main.py en el lado Linux
// ------------------------------------------------------------------
String getData() {
  static char b[480];
  snprintf(b, sizeof b,
    "{\"bpm\":%d,\"spo2\":%d,\"dedo\":%d,\"ir\":%lu,\"rojo\":%lu,\"saturado\":%d,"
    "\"max_ok\":%d,\"max_err\":%d,\"max_ovf\":%lu,"
    "\"mpu_ok\":%d,\"mpu_err\":%d,\"ax_mg\":%d,\"ay_mg\":%d,\"az_mg\":%d,\"mag_mg\":%d,"
    "\"mag_max_mg\":%d,\"mag_min_mg\":%d,"
    "\"caida\":%d,\"caidas\":%lu,\"descartes\":%lu}",
    (int)g_bpm, (int)g_spo2, (int)g_finger,
    (unsigned long)g_ir, (unsigned long)g_red, (int)g_maxSat,
    (int)g_maxOk, (int)g_maxErr, (unsigned long)g_maxOvf,
    (int)g_mpuOk, (int)g_mpuErr, (int)g_axMg, (int)g_ayMg, (int)g_azMg, (int)g_magMg,
    (int)g_magMaxMg, (int)g_magMinMg,
    (int)g_caida, (unsigned long)g_caidas, (unsigned long)g_descartes);
  g_picoLeido = true;                                    // el siguiente pico empieza desde aqui
  return String(b);
}

// Una sola linea por envio: menos llamadas al Bridge y sin lineas mezcladas
static void printStatus() {
  char mp[160], mx[160], b[360];

  if (!g_mpuOk) {
    snprintf(mp, sizeof mp, "ERROR %s", errText(g_mpuErr, true));
  } else {
    int32_t m = g_magMg;
    snprintf(mp, sizeof mp, "|a|=%d.%02dg%s", (int)(m / 1000), (int)((m % 1000) / 10),
             g_caida ? " >>> CAIDA <<<" : (detector.analizando() ? " (analizando golpe...)" : ""));
  }

  if (!g_maxOk) {
    snprintf(mx, sizeof mx, "ERROR %s", errText(g_maxErr, false));
  } else if (!g_finger) {
    snprintf(mx, sizeof mx, "coloca el dedo IR=%lu", (unsigned long)g_ir);
  } else if (g_bpm == 0) {
    snprintf(mx, sizeof mx, "midiendo... IR=%lu%s", (unsigned long)g_ir,
             g_maxSat ? " (saturado: baja MAX_LED_PA)" : "");
  } else {
    char sp[8];                                 // la SpO2 necesita una ventana de 2 s con pulso
    if (g_spo2 > 0) snprintf(sp, sizeof sp, "%d%%", (int)g_spo2);
    else            snprintf(sp, sizeof sp, "--");
    snprintf(mx, sizeof mx, "BPM=%d SpO2=%s IR=%lu%s", (int)g_bpm, sp,
             (unsigned long)g_ir, g_maxSat ? " (saturado: baja MAX_LED_PA)" : "");
  }

  snprintf(b, sizeof b, "MPU6050: %s | MAX30102: %s", mp, mx);
  Monitor.println(b);
}

// ------------------------------------------------------------------
void setup() {
  Bridge.begin();
  Monitor.begin();
  Bridge.provide("get_data", getData);

  g_maxErr = maxBegin();
  g_maxOk = (g_maxErr == ERR_OK) ? 1 : 0;
  maxLastSampleMs = millis();
  reportMax(g_maxErr);

  g_mpuErr = mpuBegin();
  g_mpuOk = (g_mpuErr == ERR_OK) ? 1 : 0;
  reportMpu(g_mpuErr);
}

void loop() {
  static uint32_t lastPrint = 0;
  static uint32_t lastPoll = 0;
  static uint32_t lastRetry = 0;
  static uint8_t errSeguidos = 0;
  static uint32_t lastMpuPoll = 0;
  static uint32_t lastMpuRetry = 0;
  static uint8_t mpuErrSeguidos = 0;
  uint32_t now = millis();

  if (g_maxOk) {
    if (now - lastPoll >= MAX_POLL_MS) {
      lastPoll = now;
      if (!maxPoll()) {
        if (++errSeguidos >= MAX_ERR_LIMITE) {        // un fallo suelto no tumba el sensor
          errSeguidos = 0;
          maxFail(ERR_LECTURA);
          lastRetry = now;
        }
      } else {
        errSeguidos = 0;
        // El bus responde pero no llegan muestras: el chip se reinicio
        // (corte de VIN) y quedo con la configuracion de fabrica
        if (millis() - maxLastSampleMs > MAX_STALL_MS) {
          maxFail(ERR_SIN_MUESTRAS);
          lastRetry = now;
        }
      }
    }
  } else if (now - lastRetry >= MAX_RETRY_MS) {       // reintento si se desconecto
    lastRetry = now;
    int32_t e = maxBegin();
    if (e != g_maxErr) reportMax(e);                  // solo avisa cuando cambia el diagnostico
    g_maxErr = e;
    g_maxOk = (e == ERR_OK) ? 1 : 0;
    maxLastSampleMs = millis();
  }

  if (g_mpuOk) {
    if (now - lastMpuPoll >= MPU_POLL_MS) {
      lastMpuPoll = now;
      if (!mpuPoll()) {
        if (++mpuErrSeguidos >= MPU_ERR_LIMITE) {
          mpuErrSeguidos = 0;
          mpuFail(ERR_LECTURA);
          lastMpuRetry = now;
        }
      } else {
        mpuErrSeguidos = 0;
      }
    }
  } else if (now - lastMpuRetry >= MPU_RETRY_MS) {
    lastMpuRetry = now;
    int32_t e = mpuBegin();
    if (e != g_mpuErr) reportMpu(e);
    g_mpuErr = e;
    g_mpuOk = (e == ERR_OK) ? 1 : 0;
  }

  if (now - lastPrint >= PRINT_MS) {
    lastPrint = now;
    printStatus();
  }
}
