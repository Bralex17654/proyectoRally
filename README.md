# Guardian IoT

Prototipo Flutter para reutilizar un teléfono Android como apoyo preventivo. Un servicio Android en primer plano mantiene el acelerómetro activo fuera de la pantalla. Al guardar contactos y conceder permisos, el monitoreo se inicia y se reactiva al reiniciar el teléfono.

## Alcance del prototipo

- La señal del acelerómetro es una heurística experimental (impacto seguido de poca actividad), no una detección médica ni infalible.
- La heurística escucha aceleración lineal (o aceleración corregida si el sensor lineal no está disponible), espera baja actividad y activa una alarma de 20 segundos con sonido, vibración, una pantalla “¿Estás bien?” y un botón para cancelar.
- Ajustes admite hasta cinco contactos. Al habilitar SMS/llamada automáticos, se envía el mensaje a todos y se intenta llamar a los dos primeros de forma secuencial. Guarda contactos, concede permisos de notificaciones/SMS/teléfono/ubicación y permite el inicio del monitoreo desde la app.
- El SMS usa la SIM del dispositivo e incluye la última ubicación conocida si está disponible. La llamada y la apertura de la pantalla de confirmación dependen de permisos y restricciones de Android; si la pantalla completa está restringida, queda una notificación de alarma visible con botón para confirmar.
- Un receptor intenta reactivar el servicio al reiniciar el teléfono o actualizar la app. Forzar detención desde Ajustes o restricciones agresivas de batería pueden detenerlo. Tras forzar detención, hay que volver a abrir Guardian IoT. No hay un servidor que supervise el dispositivo.
- “Probar alerta manual” abre el compositor SMS para revisar y enviar una prueba; no llama automáticamente.

## Ejecutar

Instala Flutter 3.47.5 o posterior y Android Studio (JDK 17 o posterior, Android SDK y Gradle según la versión de Flutter). Conecta un teléfono Android con Android 6.0 o posterior. Desde esta carpeta ejecuta:

```sh
flutter pub get
flutter run
```

Añade hasta cinco teléfonos en Ajustes y activa “SMS y llamada automáticos”. Concede los permisos cuando Android los solicite; guardar los contactos inicia el monitoreo automáticamente. Después puedes apagar la pantalla o cambiar de app. Comprueba que aparezca la notificación persistente. Android 14 puede requerir habilitar el permiso especial de pantalla completa para que la alarma abra la app; si no, se presenta como notificación.

## Próximas etapas antes de uso real

Validar el algoritmo, las alarmas en pantalla completa y el flujo secuencial de llamadas con pruebas controladas y teléfonos variados; medir batería; adaptar la publicación a las políticas de permisos de SMS/llamada y pantalla completa de Google Play; y probarlo con personas mayores y cuidadores. SMS, ubicación, apertura de pantalla y llamada dependen de permisos, cobertura, saldo/plan y soporte del fabricante. No sustituye servicios de emergencia ni atención profesional.
