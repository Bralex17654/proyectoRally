import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:geolocator/geolocator.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:url_launcher/url_launcher.dart';

import 'services/alert_service.dart';

const _navy = Color(0xFF122A3A);
const _teal = Color(0xFF147D75);
const _mint = Color(0xFFE8F5F1);
const _paper = Color(0xFFF6F8F7);

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await Alerts.initialize();
  runApp(const GuardianApp());
}

class GuardianApp extends StatelessWidget {
  const GuardianApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'Guardian IoT',
    debugShowCheckedModeBanner: false,
    theme: ThemeData(
      useMaterial3: true,
      scaffoldBackgroundColor: _paper,
      colorScheme: ColorScheme.fromSeed(seedColor: _teal, primary: _teal),
      fontFamily: 'Roboto',
      appBarTheme: const AppBarTheme(
        backgroundColor: _paper,
        foregroundColor: _navy,
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: Colors.white,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: BorderSide.none,
        ),
      ),
    ),
    home: const GuardianHome(),
  );
}

class GuardianHome extends StatefulWidget {
  const GuardianHome({super.key});

  @override
  State<GuardianHome> createState() => _GuardianHomeState();
}

class _GuardianHomeState extends State<GuardianHome>
    with WidgetsBindingObserver {
  static const _monitorChannel = MethodChannel('guardian_iot/monitor');
  int _tab = 0;
  String _contactName = '';
  String _contactPhone = '';
  List<Map<String, String>> _contacts = [];
  String _statusMessage = 'Monitoreo detenido';
  DateTime? _lastEvent;
  Position? _position;
  bool _monitorActive = false;
  bool _automaticActions = false;
  Timer? _fallDialogTimer;
  Timer? _pendingCheckTimer;
  bool _fallDialogOpen = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadContact();
    _loadMonitorState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _presentPendingFall());
    _pendingCheckTimer = Timer.periodic(
      const Duration(seconds: 2),
      (_) => _presentPendingFall(),
    );
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _fallDialogTimer?.cancel();
    _pendingCheckTimer?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _loadMonitorState();
      _presentPendingFall();
    }
  }

  Future<void> _loadContact() async {
    final prefs = await SharedPreferences.getInstance();
    if (!mounted) return;
    setState(() {
      _contactName = prefs.getString('contact_name') ?? '';
      _contactPhone = prefs.getString('contact_phone') ?? '';
      final savedContacts = prefs.getString('trusted_contacts');
      if (savedContacts != null) {
        try {
          _contacts = (jsonDecode(savedContacts) as List)
              .whereType<Map>()
              .map(
                (entry) => <String, String>{
                  'name': entry['name']?.toString() ?? '',
                  'phone': entry['phone']?.toString() ?? '',
                },
              )
              .take(5)
              .toList();
        } catch (_) {
          _contacts = [];
        }
      }
      if (_contacts.isEmpty && _contactPhone.isNotEmpty) {
        _contacts = [
          {'name': _contactName, 'phone': _contactPhone},
        ];
      }
      _automaticActions = prefs.getBool('automatic_actions') ?? true;
    });
  }

  Future<void> _presentPendingFall() async {
    if (!mounted ||
        _fallDialogOpen ||
        ModalRoute.of(context)?.isCurrent != true)
      return;
    final prefs = await SharedPreferences.getInstance();
    await prefs.reload();
    if (prefs.getBool('fall_pending') != true || !mounted) return;
    var seconds = 20;
    _fallDialogTimer?.cancel();
    _fallDialogOpen = true;
    showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) => StatefulBuilder(
        builder: (context, setDialogState) {
          _fallDialogTimer ??= Timer.periodic(const Duration(seconds: 1), (
            timer,
          ) async {
            final stillPending =
                (await SharedPreferences.getInstance()).getBool(
                  'fall_pending',
                ) ==
                true;
            if (!stillPending || seconds <= 1) {
              timer.cancel();
              _fallDialogTimer = null;
              if (dialogContext.mounted) Navigator.of(dialogContext).pop();
              if (mounted)
                setState(
                  () => _statusMessage = stillPending
                      ? 'Alerta enviada a contactos'
                      : 'Alerta cancelada',
                );
            } else {
              seconds--;
              if (dialogContext.mounted) setDialogState(() {});
            }
          });
          return AlertDialog(
            icon: const Icon(Icons.health_and_safety, color: _teal, size: 38),
            title: const Text('¿Estás bien?'),
            content: Text(
              'Detectamos un movimiento que podría ser una caída. Si no confirmas en $seconds segundos, se activará el protocolo de avisos configurado.',
            ),
            actions: [
              FilledButton.icon(
                onPressed: () async {
                  await _monitorChannel.invokeMethod('cancelAlert');
                  final prefs = await SharedPreferences.getInstance();
                  await prefs.setBool('fall_pending', false);
                  _fallDialogTimer?.cancel();
                  _fallDialogTimer = null;
                  if (dialogContext.mounted) Navigator.of(dialogContext).pop();
                  if (mounted)
                    setState(
                      () => _statusMessage = 'Confirmaste que estás bien',
                    );
                },
                icon: const Icon(Icons.check_circle_outline),
                label: const Text('Estoy bien'),
              ),
            ],
          );
        },
      ),
    ).whenComplete(() => _fallDialogOpen = false);
  }

  Future<void> _loadMonitorState() async {
    try {
      final active =
          await _monitorChannel.invokeMethod<bool>('isMonitoring') ?? false;
      if (mounted) setState(() => _monitorActive = active);
    } catch (_) {}
  }

  Future<void> _saveContacts(List<Map<String, String>> contacts) async {
    final validContacts = contacts
        .map(
          (entry) => {
            'name': entry['name']?.trim() ?? '',
            'phone': entry['phone']?.trim() ?? '',
          },
        )
        .where((entry) => entry['phone']!.isNotEmpty)
        .take(5)
        .toList();
    if (validContacts.isEmpty) {
      if (mounted)
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Agrega al menos un número de contacto.'),
          ),
        );
      return;
    }
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('trusted_contacts', jsonEncode(validContacts));
    await prefs.setString('contact_name', validContacts.first['name']!);
    await prefs.setString('contact_phone', validContacts.first['phone']!);
    if (!mounted) return;
    setState(() {
      _contacts = validContacts;
      _contactName = validContacts.first['name']!;
      _contactPhone = validContacts.first['phone']!;
    });
    if (_automaticActions && !_monitorActive) {
      await _startMonitorService();
    } else if (_monitorActive) {
      await _monitorChannel.invokeMethod('startMonitoring', {
        'contactsJson': jsonEncode(validContacts),
        'automatic': _automaticActions,
      });
    }
  }

  Future<void> _saveAutomaticActions(bool enabled) async {
    if (enabled && _contacts.isEmpty) {
      if (mounted)
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Guarda al menos un número de contacto primero.'),
          ),
        );
      return;
    }
    if (enabled && !await _requestAlertPermissions(automatic: true)) return;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('automatic_actions', enabled);
    if (mounted) setState(() => _automaticActions = enabled);
    if (enabled && !_monitorActive) {
      await _startMonitorService(skipPermissionRequest: true);
    } else if (_monitorActive) {
      await _startMonitorService(skipPermissionRequest: true);
    }
  }

  Future<bool> _requestAlertPermissions({bool? automatic}) async {
    await Alerts.requestPermission();
    final requireAutomatic = automatic ?? _automaticActions;
    final permissions = <Permission>[
      Permission.locationWhenInUse,
      if (requireAutomatic) Permission.sms,
      if (requireAutomatic) Permission.phone,
    ];
    await permissions.request();
    if (requireAutomatic) {
      try {
        final status = await _monitorChannel.invokeMapMethod<String, bool>(
          'alertPermissionStatus',
        );
        final smsGranted = status?['sms'] == true;
        final callsGranted = status?['call'] == true;
        if (!smsGranted && !callsGranted) {
          if (mounted) {
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(
                content: Text(
                  'Android no autorizó SMS ni llamadas. Activa al menos uno en Ajustes > Aplicaciones > Guardian IoT > Permisos.',
                ),
              ),
            );
          }
          return false;
        }
        if (!smsGranted && callsGranted && mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              duration: Duration(seconds: 6),
              content: Text(
                'Android bloqueó SMS en esta instalación. Se activarán las llamadas al primer contacto como respaldo.',
              ),
            ),
          );
        } else if (smsGranted && !callsGranted && mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text(
                'SMS activado. Android no autorizó llamadas automáticas.',
              ),
            ),
          );
        }
      } on PlatformException catch (error) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text(
                'No se pudieron comprobar los permisos: ${error.message ?? error.code}',
              ),
            ),
          );
        }
        return false;
      }
    }
    return true;
  }

  Future<void> _startMonitorService({
    bool skipPermissionRequest = false,
  }) async {
    if (_contacts.isEmpty) {
      if (mounted)
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Agrega al menos un número de contacto en Ajustes.'),
          ),
        );
      return;
    }
    if (!skipPermissionRequest && !await _requestAlertPermissions()) return;
    try {
      await _monitorChannel.invokeMethod('startMonitoring', {
        'contactsJson': jsonEncode(_contacts),
        'automatic': _automaticActions,
      });
      if (mounted)
        setState(() {
          _monitorActive = true;
          _statusMessage = 'Monitoreo activo en segundo plano';
        });
      final fullScreenAllowed =
          await _monitorChannel.invokeMethod<bool>('ensureFullScreenIntent') ??
          true;
      if (!fullScreenAllowed && mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text(
              'Activa “Alertas a pantalla completa” para mostrar la pregunta automáticamente.',
            ),
          ),
        );
      }
    } on PlatformException catch (error) {
      if (mounted)
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              'No se pudo iniciar el monitoreo: ${error.message ?? error.code}',
            ),
          ),
        );
    }
  }

  Future<void> _toggleMonitor() async {
    if (_monitorActive) {
      await _monitorChannel.invokeMethod('stopMonitoring');
      if (mounted)
        setState(() {
          _monitorActive = false;
          _statusMessage = 'Monitoreo detenido';
        });
      return;
    }
    await _startMonitorService();
  }

  Future<void> _raiseAlert({required bool automatic}) async {
    Position? position;
    try {
      if (await Geolocator.isLocationServiceEnabled()) {
        var permission = await Geolocator.checkPermission();
        if (permission == LocationPermission.denied)
          permission = await Geolocator.requestPermission();
        if (permission != LocationPermission.denied &&
            permission != LocationPermission.deniedForever) {
          position = await Geolocator.getCurrentPosition(
            locationSettings: const LocationSettings(
              accuracy: LocationAccuracy.high,
              timeLimit: Duration(seconds: 12),
            ),
          );
        }
      }
    } catch (_) {
      position = null;
    }
    if (!mounted) return;
    setState(() {
      _position = position;
      _lastEvent = DateTime.now();
      _statusMessage = automatic
          ? 'Alerta preparada · revisa el SMS'
          : 'Alerta manual · revisa el SMS';
    });
    await Alerts.show(automatic: automatic);
    if (_contactPhone.isNotEmpty) await _composeSms();
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            _contactPhone.isEmpty
                ? 'Agrega un contacto para preparar el SMS.'
                : 'Revisa el mensaje y presiona Enviar en tu app de SMS.',
          ),
        ),
      );
    }
  }

  Future<void> _composeSms() async {
    final location = _position == null
        ? 'Ubicación no disponible. Llama para confirmar dónde estoy.'
        : 'Ubicación aproximada: https://maps.google.com/?q=${_position!.latitude},${_position!.longitude}';
    final who = _contactName.isEmpty ? 'Hola' : 'Hola $_contactName';
    final body =
        '$who, Guardian IoT detectó un patrón compatible con una posible caída. ¿Puedes verificar cómo estoy? $location';
    final uri = Uri(
      scheme: 'smsto',
      path: _contactPhone,
      queryParameters: {'body': body},
    );
    try {
      await launchUrl(uri);
    } catch (_) {}
  }

  Future<void> _simulateAlert() async {
    setState(() {
      _statusMessage = 'Prueba · verifica el mensaje';
    });
    await _raiseAlert(automatic: false);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: _tab == 0
            ? _dashboard()
            : _tab == 1
            ? _activity()
            : _settings(),
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _tab,
        onDestinationSelected: (value) => setState(() => _tab = value),
        backgroundColor: Colors.white,
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.shield_outlined),
            selectedIcon: Icon(Icons.shield),
            label: 'Inicio',
          ),
          NavigationDestination(icon: Icon(Icons.history), label: 'Actividad'),
          NavigationDestination(icon: Icon(Icons.tune), label: 'Ajustes'),
        ],
      ),
    );
  }

  Widget _dashboard() {
    final isActive = _monitorActive;
    return ListView(
      padding: const EdgeInsets.fromLTRB(22, 16, 22, 28),
      children: [
        Row(
          children: [
            Container(
              width: 46,
              height: 46,
              decoration: BoxDecoration(
                color: _mint,
                borderRadius: BorderRadius.circular(15),
              ),
              child: const Icon(Icons.health_and_safety, color: _teal),
            ),
            const SizedBox(width: 12),
            const Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'GUARDIAN IoT',
                    style: TextStyle(
                      fontSize: 12,
                      letterSpacing: 1.5,
                      fontWeight: FontWeight.w800,
                      color: _teal,
                    ),
                  ),
                  SizedBox(height: 2),
                  Text(
                    'Acompañamiento cerca',
                    style: TextStyle(
                      fontSize: 20,
                      fontWeight: FontWeight.w700,
                      color: _navy,
                    ),
                  ),
                ],
              ),
            ),
            IconButton(
              onPressed: () => setState(() => _tab = 2),
              icon: const Icon(Icons.settings_outlined, color: _navy),
            ),
          ],
        ),
        const SizedBox(height: 24),
        Container(
          padding: const EdgeInsets.all(22),
          decoration: BoxDecoration(
            color: _navy,
            borderRadius: BorderRadius.circular(28),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Container(
                    width: 11,
                    height: 11,
                    decoration: BoxDecoration(
                      color: isActive
                          ? const Color(0xFF6EE7B7)
                          : Colors.white38,
                      shape: BoxShape.circle,
                    ),
                  ),
                  const SizedBox(width: 9),
                  Text(
                    isActive ? 'PROTECCIÓN ACTIVA' : 'LISTO PARA COMENZAR',
                    style: const TextStyle(
                      color: Colors.white70,
                      fontSize: 11,
                      letterSpacing: 1.3,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 20),
              Text(
                isActive
                    ? 'Te estamos\nacompañando.'
                    : 'Tu teléfono,\na tu cuidado.',
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 29,
                  height: 1.12,
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(height: 12),
              Text(
                _statusMessage,
                style: const TextStyle(
                  color: Colors.white70,
                  fontSize: 14,
                  height: 1.45,
                ),
              ),
              const SizedBox(height: 22),
              SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  onPressed: _toggleMonitor,
                  icon: Icon(
                    isActive
                        ? Icons.pause_circle_outline
                        : Icons.play_arrow_rounded,
                  ),
                  label: Text(
                    isActive ? 'Pausar monitoreo' : 'Iniciar monitoreo',
                  ),
                  style: FilledButton.styleFrom(
                    backgroundColor: const Color(0xFFB9F2DC),
                    foregroundColor: _navy,
                    padding: const EdgeInsets.symmetric(vertical: 16),
                  ),
                ),
              ),
              const SizedBox(height: 10),
              Center(
                child: TextButton.icon(
                  onPressed: _simulateAlert,
                  icon: const Icon(
                    Icons.notifications_active_outlined,
                    color: Colors.white70,
                  ),
                  label: const Text(
                    'Probar alerta manual',
                    style: TextStyle(color: Colors.white70),
                  ),
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 18),
        Row(
          children: [
            Expanded(
              child: _infoCard(
                Icons.sensors,
                'Sensor',
                isActive ? 'Acelerómetro activo' : 'En espera',
                isActive ? 'Movimiento' : 'Sin lectura',
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: _infoCard(
                Icons.location_on_outlined,
                'Ubicación',
                _position == null ? 'Alerta puntual' : 'Disponible',
                _position == null ? 'Solo al alertar' : 'Última alerta',
              ),
            ),
          ],
        ),
        const SizedBox(height: 18),
        Container(
          padding: const EdgeInsets.all(17),
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.circular(21),
          ),
          child: Row(
            children: [
              Container(
                width: 42,
                height: 42,
                decoration: const BoxDecoration(
                  color: _mint,
                  shape: BoxShape.circle,
                ),
                child: const Icon(Icons.people_outline, color: _teal),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      'Contactos de confianza',
                      style: TextStyle(
                        color: _navy,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 3),
                    Text(
                      _contacts.isEmpty
                          ? 'Aún no configurados'
                          : '${_contacts.length} de 5 contactos configurados',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: Colors.black54,
                        fontSize: 12,
                      ),
                    ),
                  ],
                ),
              ),
              IconButton(
                onPressed: () => setState(() => _tab = 2),
                icon: const Icon(Icons.chevron_right),
              ),
            ],
          ),
        ),
        const SizedBox(height: 17),
        const Text(
          'Guardian IoT es un apoyo preventivo experimental. No sustituye atención médica ni servicios de emergencia.',
          style: TextStyle(color: Colors.black54, fontSize: 12, height: 1.5),
        ),
      ],
    );
  }

  Widget _infoCard(IconData icon, String title, String value, String detail) =>
      Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(21),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(icon, color: _teal, size: 22),
            const SizedBox(height: 13),
            Text(
              title,
              style: const TextStyle(color: Colors.black54, fontSize: 12),
            ),
            const SizedBox(height: 3),
            Text(
              value,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                color: _navy,
                fontSize: 14,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 3),
            Text(
              detail,
              style: const TextStyle(color: Colors.black45, fontSize: 11),
            ),
          ],
        ),
      );

  Widget _activity() => ListView(
    padding: const EdgeInsets.fromLTRB(22, 28, 22, 28),
    children: [
      const Text(
        'Actividad',
        style: TextStyle(
          color: _navy,
          fontSize: 28,
          fontWeight: FontWeight.w700,
        ),
      ),
      const SizedBox(height: 8),
      const Text(
        'Un registro sencillo de eventos en este teléfono.',
        style: TextStyle(color: Colors.black54),
      ),
      const SizedBox(height: 24),
      if (_lastEvent == null)
        Container(
          padding: const EdgeInsets.all(24),
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.circular(22),
          ),
          child: const Column(
            children: [
              Icon(Icons.event_note_outlined, size: 34, color: _teal),
              SizedBox(height: 12),
              Text(
                'Aún no hay eventos',
                style: TextStyle(fontWeight: FontWeight.w700, color: _navy),
              ),
              SizedBox(height: 5),
              Text(
                'Las confirmaciones y pruebas aparecerán aquí.',
                textAlign: TextAlign.center,
                style: TextStyle(color: Colors.black54),
              ),
            ],
          ),
        )
      else
        Container(
          padding: const EdgeInsets.all(18),
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.circular(22),
          ),
          child: Row(
            children: [
              const Icon(Icons.notifications_active_outlined, color: _teal),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      _statusMessage,
                      style: const TextStyle(
                        fontWeight: FontWeight.w700,
                        color: _navy,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      _formatTime(_lastEvent!),
                      style: const TextStyle(
                        color: Colors.black54,
                        fontSize: 12,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      const SizedBox(height: 18),
      const _NoticeCard(
        icon: Icons.info_outline,
        title: 'Registro local',
        body:
            'Los eventos de esta demostración se guardan solo en la pantalla actual; aún no se sincronizan con otros dispositivos.',
      ),
    ],
  );

  Widget _settings() => ListView(
    padding: const EdgeInsets.fromLTRB(22, 28, 22, 28),
    children: [
      const Text(
        'Ajustes',
        style: TextStyle(
          color: _navy,
          fontSize: 28,
          fontWeight: FontWeight.w700,
        ),
      ),
      const SizedBox(height: 8),
      const Text(
        'Agrega hasta cinco números. Si Android bloquea el SMS, Guardian IoT intentará llamar al primer contacto.',
        style: TextStyle(color: Colors.black54),
      ),
      const SizedBox(height: 24),
      _ContactsForm(contacts: _contacts, onSave: _saveContacts),
      const SizedBox(height: 18),
      SwitchListTile(
        value: _automaticActions,
        onChanged: _saveAutomaticActions,
        contentPadding: const EdgeInsets.symmetric(horizontal: 4),
        title: const Text(
          'SMS y llamada automáticos',
          style: TextStyle(color: _navy, fontWeight: FontWeight.w700),
        ),
        subtitle: const Text(
          'Tras una posible caída y 20 s sin cancelar, enviará SMS a los contactos. Si Android no permite enviarlos, llamará al primero; si los acepta, intentará llamar a los dos primeros.',
          style: TextStyle(color: Colors.black54, fontSize: 12, height: 1.4),
        ),
        secondary: const Icon(Icons.sos_outlined, color: _teal),
      ),
      const SizedBox(height: 10),
      const _NoticeCard(
        icon: Icons.privacy_tip_outlined,
        title: 'Tu privacidad',
        body:
            'La ubicación se solicita al momento de una alerta. No se transmite a un servidor en este prototipo.',
      ),
      const SizedBox(height: 12),
      const _NoticeCard(
        icon: Icons.battery_saver_outlined,
        title: 'Uso responsable',
        body:
            'El monitoreo usa un servicio en primer plano con notificación persistente y se reactiva al reiniciar el teléfono. Mantén la app configurada y los permisos concedidos; el modo ahorro puede afectar el funcionamiento.',
      ),
      const SizedBox(height: 12),
      const _NoticeCard(
        icon: Icons.notifications_active_outlined,
        title: 'Pregunta al detectar una posible caída',
        body:
            'Guardian IoT intentará mostrar “¿Estás bien?” sobre otras apps. En Android 14 o posterior, concede “Alertas a pantalla completa” cuando se abra Ajustes; si el sistema no lo permite, recibirás la alarma sonora/vibratoria y podrás responder desde la notificación.',
      ),
      const SizedBox(height: 12),
      const _NoticeCard(
        icon: Icons.sensors_outlined,
        title: 'Detección experimental',
        body:
            'Combinamos un impacto fuerte con un periodo corto de baja actividad. Movimientos cotidianos pueden parecerse; la persona siempre puede cancelar.',
      ),
    ],
  );
}

class _ContactsForm extends StatefulWidget {
  const _ContactsForm({required this.contacts, required this.onSave});
  final List<Map<String, String>> contacts;
  final Future<void> Function(List<Map<String, String>>) onSave;
  @override
  State<_ContactsForm> createState() => _ContactsFormState();
}

class _ContactsFormState extends State<_ContactsForm> {
  late final _names = List.generate(
    5,
    (i) => TextEditingController(
      text: i < widget.contacts.length ? widget.contacts[i]['name'] : '',
    ),
  );
  late final _phones = List.generate(
    5,
    (i) => TextEditingController(
      text: i < widget.contacts.length ? widget.contacts[i]['phone'] : '',
    ),
  );
  @override
  void dispose() {
    for (final c in [..._names, ..._phones]) {
      c.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(19),
    decoration: BoxDecoration(
      color: Colors.white,
      borderRadius: BorderRadius.circular(22),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          'Contactos de confianza',
          style: TextStyle(
            fontWeight: FontWeight.w700,
            color: _navy,
            fontSize: 17,
          ),
        ),
        const SizedBox(height: 6),
        const Text(
          'El orden define a quién se llama primero.',
          style: TextStyle(color: Colors.black54, fontSize: 12),
        ),
        const SizedBox(height: 15),
        for (var i = 0; i < 5; i++) ...[
          Text(
            'Contacto ${i + 1}${i < 2 ? ' · llamada prioritaria' : ''}',
            style: const TextStyle(
              color: _teal,
              fontWeight: FontWeight.w700,
              fontSize: 13,
            ),
          ),
          const SizedBox(height: 7),
          TextField(
            controller: _names[i],
            textCapitalization: TextCapitalization.words,
            decoration: const InputDecoration(
              labelText: 'Nombre (opcional)',
              prefixIcon: Icon(Icons.person_outline),
            ),
          ),
          const SizedBox(height: 9),
          TextField(
            controller: _phones[i],
            keyboardType: TextInputType.phone,
            decoration: const InputDecoration(
              labelText: 'Teléfono con lada',
              hintText: '+52 55 1234 5678',
              prefixIcon: Icon(Icons.phone_outlined),
            ),
          ),
          if (i < 4) const SizedBox(height: 15),
        ],
        const SizedBox(height: 17),
        SizedBox(
          width: double.infinity,
          child: FilledButton.icon(
            onPressed: () async {
              final contacts = List.generate(
                5,
                (i) => {
                  'name': _names[i].text.trim(),
                  'phone': _phones[i].text.trim(),
                },
              );
              if (contacts.every((c) => c['phone']!.isEmpty)) {
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(content: Text('Agrega al menos un número.')),
                );
                return;
              }
              await widget.onSave(contacts);
              if (context.mounted)
                ScaffoldMessenger.of(context).showSnackBar(
                  SnackBar(
                    content: const Text(
                      'Contactos guardados. Verifica que los permisos estén concedidos y que el monitoreo aparezca activo.',
                    ),
                  ),
                );
            },
            icon: const Icon(Icons.save_outlined),
            label: const Text('Guardar contactos'),
          ),
        ),
        const SizedBox(height: 8),
        const Text(
          'Al activar alertas automáticas, el SMS se enviará a los números configurados. Las llamadas se intentarán en orden con los dos primeros.',
          style: TextStyle(color: Colors.black54, fontSize: 12, height: 1.45),
        ),
      ],
    ),
  );
}

class _NoticeCard extends StatelessWidget {
  const _NoticeCard({
    required this.icon,
    required this.title,
    required this.body,
  });
  final IconData icon;
  final String title;
  final String body;
  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(17),
    decoration: BoxDecoration(
      color: Colors.white,
      borderRadius: BorderRadius.circular(21),
    ),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, color: _teal),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                title,
                style: const TextStyle(
                  color: _navy,
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(height: 5),
              Text(
                body,
                style: const TextStyle(
                  color: Colors.black54,
                  fontSize: 12,
                  height: 1.45,
                ),
              ),
            ],
          ),
        ),
      ],
    ),
  );
}

String _formatTime(DateTime date) =>
    '${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')} · ${date.day.toString().padLeft(2, '0')}/${date.month.toString().padLeft(2, '0')}/${date.year}';
