# Guardian IoT

Prototipo Flutter para reutilizar un teléfono Android como apoyo preventivo. Un servicio Android en primer plano mantiene el acelerómetro activo al salir de la pantalla, muestra una notificación persistente y puede enviar un SMS e intentar llamar al contacto guardado tras una cuenta regresiva cancelable.

## Alcance del prototipo

- La señal del acelerómetro es una heurística experimental (impacto seguido de poca actividad), no una detección médica ni infalible.
- La heurística escucha aceleración lineal (o aceleración corregida si el sensor lineal no está disponible), espera baja actividad y activa 20 segundos cancelables desde la notificación.
- El servicio requiere una activación inicial desde la app y permiso de notificaciones. Para SMS y llamadas automáticos, guarda primero el contacto, activa la opción explícita y concede permisos de SMS y teléfono. Tras reiniciar el teléfono, abre Guardian IoT e inicia de nuevo el monitoreo.
- El SMS se envía mediante la SIM del dispositivo, con la última ubicación conocida disponible. La llamada al contacto se intenta después; Android, el fabricante, el estado de la SIM y las restricciones de actividad en segundo plano pueden impedirla. El servicio muestra el resultado en la notificación.
- El servicio puede reiniciarse si Android lo mata por falta de recursos. Forzar la detención, reiniciar el teléfono o restricciones agresivas de batería pueden detenerlo y exigir activarlo de nuevo. No hay un servidor que supervise el dispositivo.
- “Probar alerta manual” abre el compositor SMS para revisar y enviar una prueba; no llama automáticamente.

## Ejecutar

Instala Flutter 3.47.5 o posterior y Android Studio (JDK 17 o posterior, Android SDK y Gradle según la versión de Flutter). Conecta un teléfono Android con Android 6.0 o posterior. Desde esta carpeta ejecuta:

```sh
flutter pub get
flutter run
```

Añade un teléfono en Ajustes. Para el protocolo automático, activa “SMS y llamada automáticos” y concede permisos. Inicia el monitoreo desde Inicio; después puedes apagar la pantalla o cambiar de app y confirmar que aparece la notificación persistente.

## Próximas etapas antes de uso real

Validar el algoritmo y el flujo de llamada con pruebas controladas y teléfonos variados; medir batería; adaptar la publicación a las políticas de permisos de SMS/llamada de Google Play; y probar el flujo con personas mayores y cuidadores. SMS, ubicación y llamada dependen de permisos, cobertura, saldo/plan y soporte del fabricante. No sustituye servicios de emergencia ni atención profesional.
