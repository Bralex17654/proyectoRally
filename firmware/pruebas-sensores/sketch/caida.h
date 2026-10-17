/*
  Deteccion de caidas con el acelerometro (MPU6050 a 50 muestras/s).

  Una caida se confirma en cuatro pasos:
    1. Caida libre: |a| < CAIDA_LIBRE_MG durante CAIDA_LIBRE_MIN muestras.
    2. Impacto:     |a| > CAIDA_IMPACTO_MG dentro de CAIDA_VENTANA_MS.
    3. Quietud:     tras CAIDA_ASENTAR_MS, durante CAIDA_QUIETO_MS el modulo
                    varia menos de CAIDA_QUIETO_RANGO_MG (la persona no se movio).
    4. Postura:     la direccion de la gravedad giro al menos CAIDA_GIRO_MIN_DEG
                    (paso de estar de pie a estar tendida).

  Los pasos 3 y 4 evitan las falsas alarmas de la version anterior: levantar
  la placa y dejarla sobre la mesa produce caida libre + golpe, pero queda en
  la misma postura. Si la persona se levanta enseguida, tampoco se avisa
  (para eso esta el boton de ayuda de la app).

  Sin dependencias de Arduino: el mismo archivo se compila en las pruebas.
*/
#pragma once

#include <math.h>
#include <stdint.h>

struct CaidaConfig {
  int32_t  libreMg       = 500;    // por debajo: caida libre (0,5 g)
  uint8_t  libreMin      = 2;      // muestras seguidas (40 ms a 50 Hz)
  int32_t  impactoMg     = 2500;   // por encima tras la caida libre: impacto (2,5 g)
  uint32_t ventanaMs     = 1000;   // el impacto debe llegar dentro de este tiempo
  uint32_t asentarMs     = 1000;   // se ignoran rebotes justo despues del golpe
  uint32_t quietoMs      = 2000;   // ventana en la que se revisa la quietud
  int32_t  quietoRangoMg = 300;    // max - min de |a| permitido en esa ventana
  float    giroMinDeg    = 35.0f;  // cambio de postura minimo
};

enum class CaidaResultado : uint8_t {
  NADA,                   // nada que reportar en esta muestra
  CAIDA,                  // caida confirmada
  DESCARTADA_MOVIMIENTO,  // hubo golpe, pero despues siguio moviendose
  DESCARTADA_POSTURA,     // hubo golpe y quietud, pero sin cambio de postura (p. ej. la placa en la mesa)
};

class DetectorCaidas {
 public:
  explicit DetectorCaidas(const CaidaConfig &c = CaidaConfig()) : cfg(c) {}

  void reiniciar() {
    estado = Estado::NORMAL;
    libreN = 0;
    gInit = false;
  }

  /** Procesa una muestra en milli-g. `now` en milisegundos. */
  CaidaResultado procesar(int32_t x, int32_t y, int32_t z, uint32_t now) {
    const int32_t mag = (int32_t)(sqrtf((float)x * x + (float)y * y + (float)z * z) + 0.5f);

    switch (estado) {
      case Estado::NORMAL:
        // Gravedad de referencia: promedio lento de la postura habitual.
        if (!gInit) {
          gx = (float)x; gy = (float)y; gz = (float)z;
          gInit = true;
        } else if (mag > cfg.libreMg) {
          gx += 0.1f * (x - gx); gy += 0.1f * (y - gy); gz += 0.1f * (z - gz);
        }
        if (mag < cfg.libreMg) {
          if (libreN == 0) { antesX = gx; antesY = gy; antesZ = gz; }   // postura antes de caer
          if (++libreN >= cfg.libreMin) {
            estado = Estado::LIBRE;
            libreT = now;
            pico = mag;
          }
        } else {
          libreN = 0;
        }
        return CaidaResultado::NADA;

      case Estado::LIBRE:
        if (mag > pico) pico = mag;
        if (mag > cfg.impactoMg) {
          estado = Estado::ASENTANDO;
          impactoT = now;
        } else if (now - libreT > cfg.ventanaMs) {
          volverANormal();                         // sin impacto: era un salto o un movimiento
        }
        return CaidaResultado::NADA;

      case Estado::ASENTANDO:
        if (mag > pico) pico = mag;
        if (now - impactoT >= cfg.asentarMs) {
          estado = Estado::OBSERVANDO;
          minMag = maxMag = mag;
          sumX = sumY = sumZ = 0;
          n = 0;
        }
        return CaidaResultado::NADA;

      case Estado::OBSERVANDO: {
        if (mag < minMag) minMag = mag;
        if (mag > maxMag) maxMag = mag;
        sumX += x; sumY += y; sumZ += z;
        n++;
        if (now - impactoT < cfg.asentarMs + cfg.quietoMs) return CaidaResultado::NADA;

        rango = maxMag - minMag;
        const float dx = sumX / n, dy = sumY / n, dz = sumZ / n;
        giro = angulo(antesX, antesY, antesZ, dx, dy, dz);
        // La nueva postura es la referencia de aqui en adelante.
        gx = dx; gy = dy; gz = dz;
        volverANormal();
        if (rango > cfg.quietoRangoMg) return CaidaResultado::DESCARTADA_MOVIMIENTO;
        if (giro < cfg.giroMinDeg) return CaidaResultado::DESCARTADA_POSTURA;
        return CaidaResultado::CAIDA;
      }
    }
    return CaidaResultado::NADA;
  }

  /** true mientras se analiza un posible golpe (de la caida libre hasta el veredicto). */
  bool analizando() const { return estado != Estado::NORMAL; }

  // Datos del ultimo veredicto, para el monitor y la base.
  int32_t ultimoPicoMg() const { return pico; }
  int32_t ultimoRangoMg() const { return rango; }
  float ultimoGiroDeg() const { return giro; }

 private:
  enum class Estado : uint8_t { NORMAL, LIBRE, ASENTANDO, OBSERVANDO };

  static float angulo(float ax, float ay, float az, float bx, float by, float bz) {
    const float na = sqrtf(ax * ax + ay * ay + az * az);
    const float nb = sqrtf(bx * bx + by * by + bz * bz);
    if (na < 1.0f || nb < 1.0f) return 0.0f;
    float c = (ax * bx + ay * by + az * bz) / (na * nb);
    if (c > 1.0f) c = 1.0f;
    if (c < -1.0f) c = -1.0f;
    return acosf(c) * 57.29578f;
  }

  void volverANormal() {
    estado = Estado::NORMAL;
    libreN = 0;
  }

  CaidaConfig cfg;
  Estado estado = Estado::NORMAL;
  uint8_t libreN = 0;
  uint32_t libreT = 0, impactoT = 0;
  bool gInit = false;
  float gx = 0, gy = 0, gz = 0;
  float antesX = 0, antesY = 0, antesZ = 0;
  int32_t pico = 0, minMag = 0, maxMag = 0, rango = 0;
  float sumX = 0, sumY = 0, sumZ = 0, giro = 0;
  uint32_t n = 0;
};
