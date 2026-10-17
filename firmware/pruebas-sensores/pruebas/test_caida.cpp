// Pruebas de sketch/caida.h con senales sinteticas a 50 Hz (fuera de sketch/
// para que App Lab no lo compile en la placa). Ver pruebas/README.md.
#include <stdio.h>
#include <stdlib.h>

#include <vector>

#include "../sketch/caida.h"

struct Muestra { int32_t x, y, z; };

struct Senal {
  std::vector<Muestra> m;
  uint32_t semilla = 12345;
  int32_t ruido(int32_t amp) {               // pseudoaleatorio determinista
    semilla = semilla * 1103515245u + 12345u;
    return (int32_t)((semilla >> 16) % (2 * amp + 1)) - amp;
  }
  // Postura fija (vector unitario * g) durante ms, con ruido.
  void quieto(float dx, float dy, float dz, uint32_t ms, int32_t amp = 15) {
    for (uint32_t t = 0; t < ms; t += 20)
      m.push_back({(int32_t)(dx * 1000) + ruido(amp), (int32_t)(dy * 1000) + ruido(amp), (int32_t)(dz * 1000) + ruido(amp)});
  }
  // Magnitud dada en la direccion (dx,dy,dz) durante ms.
  void modulo(float dx, float dy, float dz, int32_t mg, uint32_t ms) {
    const float n = sqrtf(dx * dx + dy * dy + dz * dz);
    for (uint32_t t = 0; t < ms; t += 20)
      m.push_back({(int32_t)(dx / n * mg), (int32_t)(dy / n * mg), (int32_t)(dz / n * mg)});
  }
  // Caminar / moverse: |a| oscila entre ~0,6 y ~1,4 g.
  void moverse(float dx, float dy, float dz, uint32_t ms) {
    for (uint32_t t = 0; t < ms; t += 20) {
      const int32_t mg = 1000 + (int32_t)(400 * sinf(t * 0.0126f));   // ~2 pasos por segundo
      modulo(dx, dy, dz, mg, 20);
    }
  }
};

struct Resumen { int caidas = 0, movimiento = 0, postura = 0; float giro = 0; int32_t pico = 0; };

static Resumen correr(const Senal &s) {
  DetectorCaidas d;
  Resumen r;
  uint32_t t = 0;
  for (const Muestra &m : s.m) {
    switch (d.procesar(m.x, m.y, m.z, t)) {
      case CaidaResultado::CAIDA: r.caidas++; r.giro = d.ultimoGiroDeg(); r.pico = d.ultimoPicoMg(); break;
      case CaidaResultado::DESCARTADA_MOVIMIENTO: r.movimiento++; break;
      case CaidaResultado::DESCARTADA_POSTURA: r.postura++; r.giro = d.ultimoGiroDeg(); break;
      case CaidaResultado::NADA: break;
    }
    t += 20;
  }
  return r;
}

static int fallos = 0;
static void revisar(const char *nombre, bool ok, const Resumen &r) {
  printf("%s %-62s caidas=%d mov=%d postura=%d giro=%.0f pico=%d\n", ok ? "ok  " : "FALLA", nombre, r.caidas,
         r.movimiento, r.postura, r.giro, (int)r.pico);
  if (!ok) fallos++;
}

int main() {
  {  // 1. Placa quieta sobre la mesa
    Senal s; s.quieto(0, 0, 1, 20000);
    Resumen r = correr(s);
    revisar("placa quieta 20 s: nada", r.caidas == 0 && r.movimiento == 0 && r.postura == 0, r);
  }
  {  // 2. Levantar la placa y dejarla sobre la mesa (las falsas alarmas de los logs)
    Senal s; s.quieto(0, 0, 1, 2000);
    s.modulo(0, 0, 1, 1300, 200);            // se levanta
    s.modulo(0, 0, 1, 350, 80);              // baja rapido: 80 ms de "caida libre"
    s.modulo(0, 0.2f, 1, 3200, 20);          // golpe seco contra la mesa
    s.quieto(0, 0, 1, 4000);                 // queda igual que antes
    Resumen r = correr(s);
    revisar("levantar y dejar en la mesa: descartada por postura", r.caidas == 0 && r.postura == 1, r);
  }
  {  // 3. Seis manipulaciones seguidas, como en los logs
    Senal s;
    for (int i = 0; i < 6; i++) {
      s.quieto(0, 0, 1, 3000);
      s.modulo(0, 0, 1, 1700, 120);
      s.modulo(0, 0, 1, 450, 60);
      s.modulo(0.1f, 0, 1, 2900, 20);
    }
    s.quieto(0, 0, 1, 4000);
    Resumen r = correr(s);
    revisar("6 manipulaciones seguidas: ninguna caida", r.caidas == 0, r);
  }
  {  // 4. Caida real: de pie, cae, golpe y queda tendida
    Senal s; s.quieto(0, 1, 0, 3000);
    for (int i = 0; i < 15; i++) s.modulo(0, 1 - i / 15.0f, i / 15.0f, 200, 20);   // 300 ms girando en caida libre
    s.modulo(0, 0.5f, 0.8f, 3500, 40);
    s.quieto(0, 0, 1, 4000, 25);
    Resumen r = correr(s);
    revisar("caida real (de pie a tendida): caida", r.caidas == 1 && r.giro > 70, r);
  }
  {  // 5. Resbalon con caida libre corta (60 ms) y golpe de 2,6 g
    Senal s; s.quieto(0, 1, 0, 3000);
    s.modulo(0, 0.7f, 0.7f, 400, 60);
    s.modulo(0.3f, 0.3f, 0.9f, 2600, 20);
    s.quieto(0.3f, 0, 0.95f, 4000, 25);
    Resumen r = correr(s);
    revisar("resbalon (caida libre corta, golpe 2,6 g): caida", r.caidas == 1, r);
  }
  {  // 6. Cae pero se levanta y camina
    Senal s; s.quieto(0, 1, 0, 3000);
    s.modulo(0, 0.7f, 0.7f, 200, 300);
    s.modulo(0, 0.5f, 0.8f, 3500, 40);
    s.moverse(0, 1, 0, 4000);
    Resumen r = correr(s);
    revisar("cae y se levanta caminando: descartada por movimiento", r.caidas == 0 && r.movimiento == 1, r);
  }
  {  // 7. Golpe fuerte sin caida libre (los 4,84 g de los logs)
    Senal s; s.quieto(0, 0, 1, 2000);
    s.modulo(0, 0, 1, 4840, 20);
    s.quieto(0, 0, 1, 3000);
    Resumen r = correr(s);
    revisar("golpe de 4,84 g sin caida libre: nada", r.caidas == 0 && r.postura == 0 && r.movimiento == 0, r);
  }
  {  // 8. Lanzar y atrapar: caida libre sin impacto
    Senal s; s.quieto(0, 0, 1, 2000);
    s.modulo(0, 0, 1, 150, 300);
    s.modulo(0, 0, 1, 1200, 200);
    s.quieto(0, 0, 1, 3000);
    Resumen r = correr(s);
    revisar("lanzar y atrapar (sin impacto): nada", r.caidas == 0 && r.postura == 0 && r.movimiento == 0, r);
  }
  {  // 9. Despues de una caida, una segunda caida tambien se detecta
    Senal s; s.quieto(0, 1, 0, 3000);
    s.modulo(0, 0.7f, 0.7f, 200, 300); s.modulo(0, 0.5f, 0.8f, 3500, 40); s.quieto(0, 0, 1, 4000, 25);
    s.quieto(0, 1, 0, 3000);   // se levanto y quedo de pie
    s.modulo(0, 0.7f, 0.7f, 200, 300); s.modulo(0, 0.5f, 0.8f, 3500, 40); s.quieto(1, 0, 0, 4000, 25);
    Resumen r = correr(s);
    revisar("dos caidas separadas: dos caidas", r.caidas == 2, r);
  }

  printf(fallos ? "\n%d prueba(s) fallaron\n" : "\nTodas las pruebas pasaron\n", fallos);
  return fallos ? 1 : 0;
}
