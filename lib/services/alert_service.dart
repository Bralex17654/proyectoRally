import 'package:flutter_local_notifications/flutter_local_notifications.dart';

class Alerts {
  static final _plugin = FlutterLocalNotificationsPlugin();

  static Future<void> initialize() async {
    const android = AndroidInitializationSettings('@drawable/ic_launcher');
    await _plugin.initialize(settings: const InitializationSettings(android: android));
  }

  static Future<void> requestPermission() async {
    await _plugin
        .resolvePlatformSpecificImplementation<AndroidFlutterLocalNotificationsPlugin>()
        ?.requestNotificationsPermission();
  }

  static Future<void> show({required bool automatic}) async {
    const details = NotificationDetails(
      android: AndroidNotificationDetails(
        'guardian_alerts',
        'Alertas Guardian',
        channelDescription: 'Avisos locales de Guardian IoT',
        importance: Importance.max,
        priority: Priority.high,
        category: AndroidNotificationCategory.alarm,
      ),
    );
    await _plugin.show(
      id: 1001,
      title: automatic ? 'Guardian IoT · alerta' : 'Guardian IoT · prueba',
      body: automatic
          ? 'Se preparó una alerta. Revisa el mensaje para tu contacto.'
          : 'Prueba de alerta solicitada.',
      notificationDetails: details,
    );
  }
}
