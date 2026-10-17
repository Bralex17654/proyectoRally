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

class GuardianApp extends StatefulWidget {
  const GuardianApp({super.key});

  @override
  State<GuardianApp> createState() => _GuardianAppState();
}

class _GuardianAppState extends State<GuardianApp> {
  bool _darkMode = false;

  @override
  void initState() {
    super.initState();
    _loadThemePreference();
  }

  Future<void> _loadThemePreference() async {
    final prefs = await SharedPreferences.getInstance();
    if (mounted)
      setState(() => _darkMode = prefs.getBool('dark_mode') ?? false);
  }

  Future<void> _setDarkMode(bool enabled) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('dark_mode', enabled);
    if (mounted) setState(() => _darkMode = enabled);
  }

  ThemeData _buildTheme(Brightness brightness) {
    final dark = brightness == Brightness.dark;
    final base = ColorScheme.fromSeed(seedColor: _teal, brightness: brightness);
    final scheme = dark
        ? base.copyWith(
            primary: const Color(0xFF72D1C4),
            surface: const Color(0xFF101918),
            onSurface: const Color(0xFFE8F1EF),
            onSurfaceVariant: const Color(0xFFB5C5C1),
            secondaryContainer: const Color(0xFF24433D),
            onSecondaryContainer: const Color(0xFFD9F3EE),
            surfaceContainerLow: const Color(0xFF182321),
            surfaceContainerHighest: const Color(0xFF263431),
          )
        : base.copyWith(
            primary: _teal,
            surface: _paper,
            onSurface: _navy,
            onSurfaceVariant: Colors.black54,
            secondaryContainer: _mint,
            onSecondaryContainer: _navy,
            surfaceContainerLow: Colors.white,
            surfaceContainerHighest: Colors.white,
          );
    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      scaffoldBackgroundColor: scheme.surface,
      fontFamily: 'Roboto',
      appBarTheme: AppBarTheme(
        backgroundColor: scheme.surface,
        foregroundColor: scheme.onSurface,
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: scheme.surfaceContainerHighest,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: BorderSide.none,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'TzindaApp',
    debugShowCheckedModeBanner: false,
    theme: _buildTheme(Brightness.light),
    darkTheme: _buildTheme(Brightness.dark),
    themeMode: _darkMode ? ThemeMode.dark : ThemeMode.light,
    home: GuardianHome(darkMode: _darkMode, onDarkModeChanged: _setDarkMode),
  );
}

class GuardianHome extends StatefulWidget {
  const GuardianHome({
    super.key,
    required this.darkMode,
    required this.onDarkModeChanged,
  });
  final bool darkMode;
  final ValueChanged<bool> onDarkModeChanged;

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
      _loadContact();
      _loadMonitorState();
      _presentPendingFall();
    }
  }

  Future<void> _loadContact() async {
    final prefs = await SharedPreferences.getInstance();
    // Un familiar puede cambiar los contactos desde el servidor TzindaApp.
    await prefs.reload();
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
    // Lo escribe el servicio cuando la alarma viene de la pulsera (con el golpe en g).
    final detail = prefs.getString('fall_detail');
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
            icon: Icon(
              detail != null ? Icons.watch_outlined : Icons.health_and_safety,
              color: _teal,
              size: 38,
            ),
            title: const Text('¿Estás bien?'),
            content: Text(
              '${detail ?? 'Detectamos un movimiento que podría ser una caída.'} Si no confirmas en $seconds segundos, se activará el protocolo de avisos configurado.',
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
                  'Android no autorizó SMS ni llamadas. Activa al menos uno en Ajustes > Aplicaciones > TzindaApp > Permisos.',
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
        '$who, TzindaApp detectó un patrón compatible con una posible caída. ¿Puedes verificar cómo estoy? $location';
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
        backgroundColor: Theme.of(context).colorScheme.surfaceContainerLow,
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
    final colors = Theme.of(context).colorScheme;
    return ListView(
      padding: const EdgeInsets.fromLTRB(22, 16, 22, 28),
      children: [
        Row(
          children: [
            Container(
              width: 46,
              height: 46,
              decoration: BoxDecoration(
                color: colors.secondaryContainer,
                borderRadius: BorderRadius.circular(15),
              ),
              child: Icon(Icons.health_and_safety, color: colors.primary),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'TZINDAAPP',
                    style: TextStyle(
                      fontSize: 12,
                      letterSpacing: 1.5,
                      fontWeight: FontWeight.w800,
                      color: colors.primary,
                    ),
                  ),
                  SizedBox(height: 2),
                  Text(
                    'Acompañamiento cerca',
                    style: TextStyle(
                      fontSize: 20,
                      fontWeight: FontWeight.w700,
                      color: colors.onSurface,
                    ),
                  ),
                ],
              ),
            ),
            IconButton(
              onPressed: () => setState(() => _tab = 2),
              icon: Icon(Icons.settings_outlined, color: colors.onSurface),
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
            color: colors.surfaceContainerLow,
            borderRadius: BorderRadius.circular(21),
          ),
          child: Row(
            children: [
              Container(
                width: 42,
                height: 42,
                decoration: BoxDecoration(
                  color: colors.secondaryContainer,
                  shape: BoxShape.circle,
                ),
                child: Icon(Icons.people_outline, color: colors.primary),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Contactos de confianza',
                      style: TextStyle(
                        color: colors.onSurface,
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
                      style: TextStyle(
                        color: colors.onSurfaceVariant,
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
        _BraceletCard(channel: _monitorChannel, monitorActive: _monitorActive),
        const SizedBox(height: 17),
        Text(
          'TzindaApp es un apoyo preventivo experimental. No sustituye atención médica ni servicios de emergencia.',
          style: TextStyle(
            color: colors.onSurfaceVariant,
            fontSize: 12,
            height: 1.5,
          ),
        ),
      ],
    );
  }

  Widget _infoCard(IconData icon, String title, String value, String detail) {
    final colors = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: colors.surfaceContainerLow,
        borderRadius: BorderRadius.circular(21),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, color: colors.primary, size: 22),
          const SizedBox(height: 13),
          Text(
            title,
            style: TextStyle(color: colors.onSurfaceVariant, fontSize: 12),
          ),
          const SizedBox(height: 3),
          Text(
            value,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              color: colors.onSurface,
              fontSize: 14,
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(height: 3),
          Text(
            detail,
            style: TextStyle(color: colors.onSurfaceVariant, fontSize: 11),
          ),
        ],
      ),
    );
  }

  Widget _activity() {
    final colors = Theme.of(context).colorScheme;
    return ListView(
      padding: const EdgeInsets.fromLTRB(22, 28, 22, 28),
      children: [
        Text(
          'Actividad',
          style: TextStyle(
            color: colors.onSurface,
            fontSize: 28,
            fontWeight: FontWeight.w700,
          ),
        ),
        const SizedBox(height: 8),
        Text(
          'Un registro sencillo de eventos en este teléfono.',
          style: TextStyle(color: colors.onSurfaceVariant),
        ),
        const SizedBox(height: 24),
        if (_lastEvent == null)
          Container(
            padding: const EdgeInsets.all(24),
            decoration: BoxDecoration(
              color: colors.surfaceContainerLow,
              borderRadius: BorderRadius.circular(22),
            ),
            child: Column(
              children: [
                Icon(
                  Icons.event_note_outlined,
                  size: 34,
                  color: colors.primary,
                ),
                const SizedBox(height: 12),
                Text(
                  'Aún no hay eventos',
                  style: TextStyle(
                    fontWeight: FontWeight.w700,
                    color: colors.onSurface,
                  ),
                ),
                const SizedBox(height: 5),
                Text(
                  'Las confirmaciones y pruebas aparecerán aquí.',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: colors.onSurfaceVariant),
                ),
              ],
            ),
          )
        else
          Container(
            padding: const EdgeInsets.all(18),
            decoration: BoxDecoration(
              color: colors.surfaceContainerLow,
              borderRadius: BorderRadius.circular(22),
            ),
            child: Row(
              children: [
                Icon(
                  Icons.notifications_active_outlined,
                  color: colors.primary,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        _statusMessage,
                        style: TextStyle(
                          fontWeight: FontWeight.w700,
                          color: colors.onSurface,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        _formatTime(_lastEvent!),
                        style: TextStyle(
                          color: colors.onSurfaceVariant,
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
  }

  Widget _settings() {
    final colors = Theme.of(context).colorScheme;
    return ListView(
      padding: const EdgeInsets.fromLTRB(22, 28, 22, 28),
      children: [
        Text(
          'Ajustes',
          style: TextStyle(
            color: colors.onSurface,
            fontSize: 28,
            fontWeight: FontWeight.w700,
          ),
        ),
        const SizedBox(height: 8),
        Text(
          'Agrega hasta cinco números. Si Android bloquea el SMS, TzindaApp intentará llamar al primer contacto.',
          style: TextStyle(color: colors.onSurfaceVariant),
        ),
        const SizedBox(height: 18),
        SwitchListTile(
          value: widget.darkMode,
          onChanged: widget.onDarkModeChanged,
          contentPadding: const EdgeInsets.symmetric(horizontal: 4),
          secondary: Icon(
            widget.darkMode ? Icons.dark_mode : Icons.light_mode,
            color: colors.primary,
          ),
          title: Text(
            widget.darkMode ? 'Modo oscuro' : 'Modo claro',
            style: TextStyle(
              color: colors.onSurface,
              fontWeight: FontWeight.w700,
            ),
          ),
          subtitle: Text(
            'Cambiar entre fondo claro y oscuro',
            style: TextStyle(color: colors.onSurfaceVariant, fontSize: 12),
          ),
        ),
        const SizedBox(height: 10),
        _ContactsForm(contacts: _contacts, onSave: _saveContacts),
        const SizedBox(height: 18),
        SwitchListTile(
          value: _automaticActions,
          onChanged: _saveAutomaticActions,
          contentPadding: const EdgeInsets.symmetric(horizontal: 4),
          title: Text(
            'SMS y llamada automáticos',
            style: TextStyle(
              color: colors.onSurface,
              fontWeight: FontWeight.w700,
            ),
          ),
          subtitle: Text(
            'Tras una posible caída y 20 s sin cancelar, enviará SMS a los contactos. Si Android no permite enviarlos, llamará al primero; si los acepta, intentará llamar a los dos primeros.',
            style: TextStyle(
              color: colors.onSurfaceVariant,
              fontSize: 12,
              height: 1.4,
            ),
          ),
          secondary: Icon(Icons.sos_outlined, color: colors.primary),
        ),
        const SizedBox(height: 10),
        _ServerCard(channel: _monitorChannel, contacts: _contacts),
        const SizedBox(height: 12),
        const _NoticeCard(
          icon: Icons.privacy_tip_outlined,
          title: 'Tu privacidad',
          body:
              'Sin servidor, la ubicación solo sale por SMS durante una alerta. Si conectas el servidor TzindaApp, también se envía en el latido (cada 15 min) y durante una alerta, solo a los familiares vinculados.',
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
              'TzindaApp intentará mostrar “¿Estás bien?” sobre otras apps. En Android 14 o posterior, concede “Alertas a pantalla completa” cuando se abra Ajustes; si el sistema no lo permite, recibirás la alarma sonora/vibratoria y podrás responder desde la notificación.',
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
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(19),
      decoration: BoxDecoration(
        color: colors.surfaceContainerLow,
        borderRadius: BorderRadius.circular(22),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'Contactos de confianza',
            style: TextStyle(
              fontWeight: FontWeight.w700,
              color: colors.onSurface,
              fontSize: 17,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            'El orden define a quién se llama primero.',
            style: TextStyle(color: colors.onSurfaceVariant, fontSize: 12),
          ),
          const SizedBox(height: 15),
          for (var i = 0; i < 5; i++) ...[
            Text(
              'Contacto ${i + 1}${i < 2 ? ' · llamada prioritaria' : ''}',
              style: TextStyle(
                color: colors.primary,
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
          Text(
            'Al activar alertas automáticas, se intentará enviar SMS a los contactos configurados. Si falla, se llamará al primero.',
            style: TextStyle(
              color: colors.onSurfaceVariant,
              fontSize: 12,
              height: 1.45,
            ),
          ),
        ],
      ),
    );
  }
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
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(17),
      decoration: BoxDecoration(
        color: colors.surfaceContainerLow,
        borderRadius: BorderRadius.circular(21),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, color: colors.primary),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: TextStyle(
                    color: colors.onSurface,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 5),
                Text(
                  body,
                  style: TextStyle(
                    color: colors.onSurfaceVariant,
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
}

String _formatTime(DateTime date) =>
    '${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')} · ${date.day.toString().padLeft(2, '0')}/${date.month.toString().padLeft(2, '0')}/${date.year}';

/// Conexión opcional con el servidor TzindaApp (carpeta backend/).
class _ServerCard extends StatefulWidget {
  const _ServerCard({required this.channel, required this.contacts});

  final MethodChannel channel;
  final List<Map<String, String>> contacts;

  @override
  State<_ServerCard> createState() => _ServerCardState();
}

class _ServerCardState extends State<_ServerCard> {
  final _url = TextEditingController();
  final _person = TextEditingController();
  Map<String, dynamic> _status = const {};
  String? _invite;
  bool _busy = false;

  bool get _configured => _status['configured'] == true;

  @override
  void initState() {
    super.initState();
    _loadStatus();
  }

  @override
  void dispose() {
    _url.dispose();
    _person.dispose();
    super.dispose();
  }

  Future<void> _loadStatus() async {
    try {
      final raw = await widget.channel.invokeMapMethod<String, dynamic>('apiStatus');
      if (!mounted || raw == null) return;
      setState(() {
        _status = raw;
        if (_url.text.isEmpty) _url.text = raw['baseUrl']?.toString() ?? '';
      });
    } catch (_) {}
  }

  Future<void> _run(Future<void> Function() action) async {
    setState(() => _busy = true);
    try {
      await action();
    } on PlatformException catch (error) {
      _toast(error.message ?? error.code);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _toast(String text) {
    if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
  }

  Future<void> _connect() => _run(() async {
    final url = _url.text.trim();
    if (!url.startsWith('http')) {
      _toast('Escribe la URL completa, por ejemplo http://192.168.1.50:8080');
      return;
    }
    await widget.channel.invokeMethod('configureApi', {
      'baseUrl': url,
      'personName': _person.text.trim(),
      'contactsJson': jsonEncode(widget.contacts),
    });
    await _loadStatus();
    _toast('Teléfono conectado al servidor TzindaApp');
  });

  Future<void> _createInvite() => _run(() async {
    final res = await widget.channel.invokeMapMethod<String, dynamic>('createInvite');
    if (mounted) setState(() => _invite = res?['code']?.toString());
  });

  Future<void> _disconnect() => _run(() async {
    await widget.channel.invokeMethod('disconnectApi');
    if (mounted) setState(() => _invite = null);
    await _loadStatus();
  });

  String _lastSync() {
    final ms = _status['lastOkAt'];
    if (ms is! int) return 'aún sin latido';
    final minutes = DateTime.now().difference(DateTime.fromMillisecondsSinceEpoch(ms)).inMinutes;
    return minutes < 1 ? 'último contacto hace un momento' : 'último contacto hace $minutes min';
  }

  @override
  Widget build(BuildContext context) {
    final error = _status['lastError']?.toString();
    final colors = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(color: colors.surfaceContainerLow, borderRadius: BorderRadius.circular(21)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(_configured ? Icons.cloud_done_outlined : Icons.cloud_outlined, color: colors.primary),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  'Servidor TzindaApp (opcional)',
                  style: TextStyle(color: colors.onSurface, fontWeight: FontWeight.w700),
                ),
              ),
            ],
          ),
          const SizedBox(height: 6),
          Text(
            _configured
                ? 'Conectado · ${_lastSync()}'
                : 'Agrega al SMS un link con mapa en vivo, avisa si el teléfono se apaga y deja que la familia responda "Voy en camino".',
            style: TextStyle(color: colors.onSurfaceVariant, fontSize: 12, height: 1.4),
          ),
          if (error != null && error.isNotEmpty) ...[
            const SizedBox(height: 6),
            Text('Último error: $error', style: TextStyle(color: colors.error, fontSize: 12)),
          ],
          const SizedBox(height: 12),
          if (!_configured) ...[
            TextField(
              controller: _url,
              keyboardType: TextInputType.url,
              decoration: const InputDecoration(labelText: 'URL del servidor', hintText: 'http://192.168.1.50:8080'),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: _person,
              textCapitalization: TextCapitalization.words,
              decoration: const InputDecoration(labelText: 'Nombre de la persona protegida'),
            ),
            const SizedBox(height: 12),
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                onPressed: _busy ? null : _connect,
                child: Text(_busy ? 'Conectando…' : 'Conectar'),
              ),
            ),
          ] else ...[
            if (_invite != null)
              Container(
                width: double.infinity,
                margin: const EdgeInsets.only(bottom: 10),
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(color: colors.primaryContainer, borderRadius: BorderRadius.circular(16)),
                child: Column(
                  children: [
                    Text('Código para vincular a un familiar', style: TextStyle(color: colors.onPrimaryContainer)),
                    const SizedBox(height: 4),
                    SelectableText(
                      _invite!,
                      style: TextStyle(fontSize: 32, fontWeight: FontWeight.w800, letterSpacing: 6, color: colors.onPrimaryContainer),
                    ),
                    Text('Vence en 30 minutos', style: TextStyle(color: colors.onPrimaryContainer, fontSize: 12)),
                  ],
                ),
              ),
            Row(
              children: [
                Expanded(
                  child: FilledButton.tonal(
                    onPressed: _busy ? null : _createInvite,
                    child: const Text('Código para familiar'),
                  ),
                ),
                const SizedBox(width: 10),
                TextButton(onPressed: _busy ? null : _disconnect, child: const Text('Desconectar')),
              ],
            ),
          ],
        ],
      ),
    );
  }
}

/// Pulsera física (MAX30102 + MPU6050) leída del servidor TzindaApp cada 3 s.
/// Solo aparece cuando el teléfono está conectado a un servidor con pulsera.
class _BraceletCard extends StatefulWidget {
  const _BraceletCard({required this.channel, required this.monitorActive});

  final MethodChannel channel;
  final bool monitorActive;

  @override
  State<_BraceletCard> createState() => _BraceletCardState();
}

class _BraceletCardState extends State<_BraceletCard> {
  /// Umbral de golpe de la pulsera v7 (CAIDA_IMPACTO_MG = 2500).
  static const _impactG = 2.5;

  Timer? _timer;
  bool _loading = false;
  bool _configured = false;
  String? _error;
  Map<String, dynamic>? _status;
  String _deviceId = '';
  final List<double> _peaks = [];
  int _lastReadingId = -1;

  @override
  void initState() {
    super.initState();
    _refresh();
    _timer = Timer.periodic(const Duration(seconds: 3), (_) {
      if (WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed) {
        _refresh();
      }
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _refresh() async {
    if (_loading) return;
    _loading = true;
    try {
      final api = await widget.channel.invokeMapMethod<String, dynamic>(
        'apiStatus',
      );
      _deviceId = api?['deviceId']?.toString() ?? '';
      final raw = await widget.channel.invokeMethod<String>('sensorStatus');
      if (!mounted) return;
      setState(() {
        _configured = raw != null;
        _error = null;
        _status = raw == null ? null : jsonDecode(raw) as Map<String, dynamic>;
        _remember(_status?['ultimaLectura']);
      });
    } on PlatformException catch (error) {
      if (mounted) setState(() => _error = error.message ?? error.code);
    } finally {
      _loading = false;
    }
  }

  /// Guarda el pico de cada lectura nueva para la gráfica (últimos ~90 s).
  void _remember(Object? reading) {
    if (reading is! Map) return;
    final id = (reading['id'] as num?)?.toInt() ?? -1;
    if (id == _lastReadingId) return;
    _lastReadingId = id;
    final peak = _peakOf(reading);
    if (peak == null) return;
    _peaks.add(peak);
    if (_peaks.length > 30) _peaks.removeAt(0);
  }

  /// Pulsera v7: pico de |a| en el último segundo; las lecturas viejas solo traen la magnitud.
  static double? _peakOf(Map reading) {
    final accel = reading['acelerometro'];
    if (accel is! Map) return null;
    return ((accel['pico'] ?? accel['magnitud']) as num?)?.toDouble();
  }

  static String _g(double value) =>
      '${value.toStringAsFixed(2).replaceAll('.', ',')} g';

  static String _ago(int seconds) {
    if (seconds < 60) return '$seconds s';
    if (seconds < 3600) return '${seconds ~/ 60} min';
    return '${seconds ~/ 3600} h';
  }

  @override
  Widget build(BuildContext context) {
    if (!_configured && _error == null) return const SizedBox.shrink();
    final colors = Theme.of(context).colorScheme;
    final status = _status;
    final reading = status?['ultimaLectura'] as Map<String, dynamic>?;
    final online = _error == null && status?['enLinea'] == true;
    final seconds = (status?['segundosDesdeUltima'] as num?)?.toInt();
    final name = status?['personName']?.toString() ?? '';
    final sensors = reading?['sensores'] as Map<String, dynamic>? ?? const {};
    final finger = reading?['dedo'] == true;
    final pulse = reading?['pulso'] as num?;
    final spo2 = reading?['spo2'] as num?;
    final peak = reading == null ? null : _peakOf(reading);
    final falls = (reading?['caidas'] as num?)?.toInt() ?? 0;
    final phones = (status?['telefonos'] as List?)?.whereType<Map>() ?? const [];
    final listed = phones.any((p) => p['id'] == _deviceId);

    final notes = <(String, bool)>[];
    if (reading != null) {
      if (sensors['acelerometro'] != true) {
        notes.add((
          'El acelerómetro no responde: la pulsera no puede detectar caídas. Revisa los cables.',
          true,
        ));
      }
      if (sensors['pulso'] != true) {
        notes.add(('El sensor de pulso no responde.', true));
      } else if (!finger) {
        notes.add((
          'Coloca el dedo en el sensor para medir pulso y oxígeno.',
          false,
        ));
      }
    }
    if (status != null && !listed) {
      notes.add((
        'Este teléfono no está asignado a la pulsera: no sonará si detecta una caída.',
        true,
      ));
    } else if (status != null && !widget.monitorActive) {
      notes.add((
        'Activa el monitoreo para que este teléfono suene cuando la pulsera detecte una caída.',
        true,
      ));
    } else if (status != null) {
      notes.add((
        'Este teléfono sonará unos 3 s después de una caída: la pulsera confirma golpe, quietud y cambio de postura.',
        false,
      ));
    }

    return Container(
      margin: const EdgeInsets.only(top: 18),
      padding: const EdgeInsets.all(17),
      decoration: BoxDecoration(
        color: colors.surfaceContainerLow,
        borderRadius: BorderRadius.circular(21),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 42,
                height: 42,
                decoration: BoxDecoration(
                  color: colors.secondaryContainer,
                  shape: BoxShape.circle,
                ),
                child: Icon(Icons.watch_outlined, color: colors.primary),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      name.isEmpty ? 'Pulsera' : 'Pulsera de $name',
                      style: TextStyle(
                        color: colors.onSurface,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 3),
                    Text(
                      _error != null
                          ? 'No se pudo leer: $_error'
                          : online
                          ? 'En línea · una lectura por segundo'
                          : seconds == null
                          ? 'Aún sin lecturas'
                          : 'Sin señal desde hace ${_ago(seconds)}',
                      style: TextStyle(
                        color: online ? colors.onSurfaceVariant : colors.error,
                        fontSize: 12,
                      ),
                    ),
                  ],
                ),
              ),
              Container(
                width: 10,
                height: 10,
                decoration: BoxDecoration(
                  color: online ? Colors.green : colors.outline,
                  shape: BoxShape.circle,
                ),
              ),
            ],
          ),
          if (reading != null) ...[
            const SizedBox(height: 14),
            Row(
              children: [
                _stat(
                  colors,
                  Icons.favorite_border,
                  'Pulso',
                  finger && pulse != null ? '${pulse.round()} lpm' : '—',
                ),
                _stat(
                  colors,
                  Icons.air,
                  'Oxígeno',
                  finger && spo2 != null ? '${spo2.round()} %' : '—',
                ),
                _stat(
                  colors,
                  Icons.vibration,
                  'Golpe',
                  peak == null ? '—' : _g(peak),
                ),
              ],
            ),
            if (_peaks.length > 1) ...[
              const SizedBox(height: 12),
              SizedBox(
                height: 54,
                width: double.infinity,
                child: CustomPaint(
                  painter: _PeakChartPainter(
                    peaks: List.of(_peaks),
                    threshold: _impactG,
                    line: colors.primary,
                    guide: colors.error,
                  ),
                ),
              ),
              const SizedBox(height: 4),
              Text(
                'Pico de movimiento por segundo · línea roja: golpe de ${_g(_impactG)}',
                style: TextStyle(color: colors.onSurfaceVariant, fontSize: 11),
              ),
            ],
            const SizedBox(height: 8),
            Text(
              falls == 0
                  ? 'Sin caídas desde que se encendió la pulsera'
                  : 'Caídas detectadas desde que se encendió: $falls',
              style: TextStyle(color: colors.onSurfaceVariant, fontSize: 12),
            ),
          ],
          for (final (text, warning) in notes) ...[
            const SizedBox(height: 6),
            Text(
              text,
              style: TextStyle(
                color: warning ? colors.error : colors.onSurfaceVariant,
                fontSize: 12,
                height: 1.4,
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _stat(ColorScheme colors, IconData icon, String label, String value) {
    return Expanded(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(icon, size: 15, color: colors.primary),
              const SizedBox(width: 4),
              Text(
                label,
                style: TextStyle(color: colors.onSurfaceVariant, fontSize: 12),
              ),
            ],
          ),
          const SizedBox(height: 3),
          Text(
            value,
            style: TextStyle(
              color: colors.onSurface,
              fontSize: 16,
              fontWeight: FontWeight.w700,
            ),
          ),
        ],
      ),
    );
  }
}

/// Picos recientes de la pulsera con la línea punteada del umbral de golpe.
class _PeakChartPainter extends CustomPainter {
  _PeakChartPainter({
    required this.peaks,
    required this.threshold,
    required this.line,
    required this.guide,
  });

  final List<double> peaks;
  final double threshold;
  final Color line;
  final Color guide;

  @override
  void paint(Canvas canvas, Size size) {
    final top = [threshold * 1.2, ...peaks].reduce((a, b) => a > b ? a : b);
    double y(double g) => size.height - (g / top).clamp(0.0, 1.0) * size.height;

    final guideY = y(threshold);
    final dash = Paint()
      ..color = guide.withValues(alpha: 0.7)
      ..strokeWidth = 1;
    for (var x = 0.0; x < size.width; x += 8) {
      canvas.drawLine(Offset(x, guideY), Offset(x + 4, guideY), dash);
    }

    final step = size.width / (peaks.length - 1);
    final path = Path()..moveTo(0, y(peaks.first));
    for (var i = 1; i < peaks.length; i++) {
      path.lineTo(i * step, y(peaks[i]));
    }
    canvas.drawPath(
      path,
      Paint()
        ..color = line
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2
        ..strokeJoin = StrokeJoin.round,
    );
  }

  @override
  bool shouldRepaint(_PeakChartPainter old) =>
      old.line != line ||
      old.peaks.length != peaks.length ||
      old.peaks.last != peaks.last;
}
