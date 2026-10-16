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
          appBarTheme: const AppBarTheme(backgroundColor: _paper, foregroundColor: _navy),
          inputDecorationTheme: InputDecorationTheme(
            filled: true,
            fillColor: Colors.white,
            border: OutlineInputBorder(borderRadius: BorderRadius.circular(16), borderSide: BorderSide.none),
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

class _GuardianHomeState extends State<GuardianHome> with WidgetsBindingObserver {
  static const _monitorChannel = MethodChannel('guardian_iot/monitor');
  int _tab = 0;
  String _contactName = '';
  String _contactPhone = '';
  String _statusMessage = 'Monitoreo detenido';
  DateTime? _lastEvent;
  Position? _position;
  bool _monitorActive = false;
  bool _automaticActions = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadContact();
    _loadMonitorState();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _loadMonitorState();
    }
  }

  Future<void> _loadContact() async {
    final prefs = await SharedPreferences.getInstance();
    if (!mounted) return;
    setState(() {
      _contactName = prefs.getString('contact_name') ?? '';
      _contactPhone = prefs.getString('contact_phone') ?? '';
      _automaticActions = prefs.getBool('automatic_actions') ?? false;
    });
  }

  Future<void> _loadMonitorState() async {
    try {
      final active = await _monitorChannel.invokeMethod<bool>('isMonitoring') ?? false;
      if (mounted) setState(() => _monitorActive = active);
    } catch (_) {}
  }

  Future<void> _saveContact(String name, String phone) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('contact_name', name.trim());
    await prefs.setString('contact_phone', phone.trim());
    if (!mounted) return;
    setState(() {
      _contactName = name.trim();
      _contactPhone = phone.trim();
    });
    if (_monitorActive) {
      await _monitorChannel.invokeMethod('startMonitoring', {
        'name': name.trim(),
        'phone': phone.trim(),
        'automatic': _automaticActions,
      });
    }
  }

  Future<void> _saveAutomaticActions(bool enabled) async {
    if (enabled && _contactPhone.trim().isEmpty) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Guarda primero el teléfono del contacto de confianza.')));
      return;
    }
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('automatic_actions', enabled);
    if (mounted) setState(() => _automaticActions = enabled);
  }

  Future<void> _toggleMonitor() async {
    if (_monitorActive) {
      await _monitorChannel.invokeMethod('stopMonitoring');
      if (mounted) setState(() { _monitorActive = false; _statusMessage = 'Monitoreo detenido'; });
      return;
    }
    await Alerts.requestPermission();
    final permissions = <Permission>[
      Permission.locationWhenInUse,
      if (_automaticActions) Permission.sms,
      if (_automaticActions) Permission.phone,
    ];
    final permissionResults = await permissions.request();
    if (_automaticActions &&
        (permissionResults[Permission.sms] != PermissionStatus.granted ||
            permissionResults[Permission.phone] != PermissionStatus.granted)) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Concede los permisos de SMS y teléfono para activar las alertas automáticas.')));
      return;
    }
    try {
      await _monitorChannel.invokeMethod('startMonitoring', {
        'name': _contactName,
        'phone': _contactPhone,
        'automatic': _automaticActions,
      });
      if (mounted) setState(() { _monitorActive = true; _statusMessage = 'Monitoreo activo en segundo plano'; });
    } on PlatformException catch (error) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('No se pudo iniciar el monitoreo: ${error.message ?? error.code}')));
    }
  }

  Future<void> _raiseAlert({required bool automatic}) async {
    Position? position;
    try {
      if (await Geolocator.isLocationServiceEnabled()) {
        var permission = await Geolocator.checkPermission();
        if (permission == LocationPermission.denied) permission = await Geolocator.requestPermission();
        if (permission != LocationPermission.denied && permission != LocationPermission.deniedForever) {
          position = await Geolocator.getCurrentPosition(
            locationSettings: const LocationSettings(accuracy: LocationAccuracy.high, timeLimit: Duration(seconds: 12)),
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
      _statusMessage = automatic ? 'Alerta preparada · revisa el SMS' : 'Alerta manual · revisa el SMS';
    });
    await Alerts.show(automatic: automatic);
    if (_contactPhone.isNotEmpty) await _composeSms();
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(_contactPhone.isEmpty ? 'Agrega un contacto para preparar el SMS.' : 'Revisa el mensaje y presiona Enviar en tu app de SMS.')),
      );
    }
  }

  Future<void> _composeSms() async {
    final location = _position == null
        ? 'Ubicación no disponible. Llama para confirmar dónde estoy.'
        : 'Ubicación aproximada: https://maps.google.com/?q=${_position!.latitude},${_position!.longitude}';
    final who = _contactName.isEmpty ? 'Hola' : 'Hola $_contactName';
    final body = '$who, Guardian IoT detectó un patrón compatible con una posible caída. ¿Puedes verificar cómo estoy? $location';
    final uri = Uri(scheme: 'smsto', path: _contactPhone, queryParameters: {'body': body});
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
        child: _tab == 0 ? _dashboard() : _tab == 1 ? _activity() : _settings(),
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _tab,
        onDestinationSelected: (value) => setState(() => _tab = value),
        backgroundColor: Colors.white,
        destinations: const [
          NavigationDestination(icon: Icon(Icons.shield_outlined), selectedIcon: Icon(Icons.shield), label: 'Inicio'),
          NavigationDestination(icon: Icon(Icons.history), label: 'Actividad'),
          NavigationDestination(icon: Icon(Icons.tune), label: 'Ajustes'),
        ],
      ),
    );
  }

  Widget _dashboard() {
    final isActive = _monitorActive;
    return ListView(padding: const EdgeInsets.fromLTRB(22, 16, 22, 28), children: [
      Row(children: [
        Container(width: 46, height: 46, decoration: BoxDecoration(color: _mint, borderRadius: BorderRadius.circular(15)), child: const Icon(Icons.health_and_safety, color: _teal)),
        const SizedBox(width: 12),
        const Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text('GUARDIAN IoT', style: TextStyle(fontSize: 12, letterSpacing: 1.5, fontWeight: FontWeight.w800, color: _teal)), SizedBox(height: 2), Text('Acompañamiento cerca', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: _navy))])),
        IconButton(onPressed: () => setState(() => _tab = 2), icon: const Icon(Icons.settings_outlined, color: _navy)),
      ]),
      const SizedBox(height: 24),
      Container(
        padding: const EdgeInsets.all(22),
        decoration: BoxDecoration(color: _navy, borderRadius: BorderRadius.circular(28)),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Container(width: 11, height: 11, decoration: BoxDecoration(color: isActive ? const Color(0xFF6EE7B7) : Colors.white38, shape: BoxShape.circle)),
            const SizedBox(width: 9), Text(isActive ? 'PROTECCIÓN ACTIVA' : 'LISTO PARA COMENZAR', style: const TextStyle(color: Colors.white70, fontSize: 11, letterSpacing: 1.3, fontWeight: FontWeight.w700)),
          ]),
          const SizedBox(height: 20),
          Text(isActive ? 'Te estamos\nacompañando.' : 'Tu teléfono,\na tu cuidado.', style: const TextStyle(color: Colors.white, fontSize: 29, height: 1.12, fontWeight: FontWeight.w700)),
          const SizedBox(height: 12),
          Text(_statusMessage, style: const TextStyle(color: Colors.white70, fontSize: 14, height: 1.45)),
          const SizedBox(height: 22),
          SizedBox(width: double.infinity, child: FilledButton.icon(onPressed: _toggleMonitor, icon: Icon(isActive ? Icons.pause_circle_outline : Icons.play_arrow_rounded), label: Text(isActive ? 'Pausar monitoreo' : 'Iniciar monitoreo'), style: FilledButton.styleFrom(backgroundColor: const Color(0xFFB9F2DC), foregroundColor: _navy, padding: const EdgeInsets.symmetric(vertical: 16)))),
          const SizedBox(height: 10),
          Center(child: TextButton.icon(onPressed: _simulateAlert, icon: const Icon(Icons.notifications_active_outlined, color: Colors.white70), label: const Text('Probar alerta manual', style: TextStyle(color: Colors.white70)))),
        ]),
      ),
      const SizedBox(height: 18),
      Row(children: [
        Expanded(child: _infoCard(Icons.sensors, 'Sensor', isActive ? 'Acelerómetro activo' : 'En espera', isActive ? 'Movimiento' : 'Sin lectura')),
        const SizedBox(width: 12),
        Expanded(child: _infoCard(Icons.location_on_outlined, 'Ubicación', _position == null ? 'Alerta puntual' : 'Disponible', _position == null ? 'Solo al alertar' : 'Última alerta')),
      ]),
      const SizedBox(height: 18),
      Container(padding: const EdgeInsets.all(17), decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(21)), child: Row(children: [
        Container(width: 42, height: 42, decoration: const BoxDecoration(color: _mint, shape: BoxShape.circle), child: const Icon(Icons.people_outline, color: _teal)),
        const SizedBox(width: 12),
        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [const Text('Contacto de confianza', style: TextStyle(color: _navy, fontWeight: FontWeight.w700)), const SizedBox(height: 3), Text(_contactPhone.isEmpty ? 'Aún no configurado' : '$_contactName · $_contactPhone', maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: Colors.black54, fontSize: 12))])),
        IconButton(onPressed: () => setState(() => _tab = 2), icon: const Icon(Icons.chevron_right)),
      ])),
      const SizedBox(height: 17),
      const Text('Guardian IoT es un apoyo preventivo experimental. No sustituye atención médica ni servicios de emergencia.', style: TextStyle(color: Colors.black54, fontSize: 12, height: 1.5)),
    ]);
  }

  Widget _infoCard(IconData icon, String title, String value, String detail) => Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(21)),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Icon(icon, color: _teal, size: 22), const SizedBox(height: 13), Text(title, style: const TextStyle(color: Colors.black54, fontSize: 12)), const SizedBox(height: 3), Text(value, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: _navy, fontSize: 14, fontWeight: FontWeight.w700)), const SizedBox(height: 3), Text(detail, style: const TextStyle(color: Colors.black45, fontSize: 11))]),
      );

  Widget _activity() => ListView(padding: const EdgeInsets.fromLTRB(22, 28, 22, 28), children: [
        const Text('Actividad', style: TextStyle(color: _navy, fontSize: 28, fontWeight: FontWeight.w700)),
        const SizedBox(height: 8),
        const Text('Un registro sencillo de eventos en este teléfono.', style: TextStyle(color: Colors.black54)),
        const SizedBox(height: 24),
        if (_lastEvent == null)
          Container(padding: const EdgeInsets.all(24), decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(22)), child: const Column(children: [Icon(Icons.event_note_outlined, size: 34, color: _teal), SizedBox(height: 12), Text('Aún no hay eventos', style: TextStyle(fontWeight: FontWeight.w700, color: _navy)), SizedBox(height: 5), Text('Las confirmaciones y pruebas aparecerán aquí.', textAlign: TextAlign.center, style: TextStyle(color: Colors.black54))]))
        else
          Container(padding: const EdgeInsets.all(18), decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(22)), child: Row(children: [const Icon(Icons.notifications_active_outlined, color: _teal), const SizedBox(width: 12), Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(_statusMessage, style: const TextStyle(fontWeight: FontWeight.w700, color: _navy)), const SizedBox(height: 4), Text(_formatTime(_lastEvent!), style: const TextStyle(color: Colors.black54, fontSize: 12))]))])),
        const SizedBox(height: 18),
        const _NoticeCard(icon: Icons.info_outline, title: 'Registro local', body: 'Los eventos de esta demostración se guardan solo en la pantalla actual; aún no se sincronizan con otros dispositivos.'),
      ]);

  Widget _settings() => ListView(padding: const EdgeInsets.fromLTRB(22, 28, 22, 28), children: [
        const Text('Ajustes', style: TextStyle(color: _navy, fontSize: 28, fontWeight: FontWeight.w700)),
        const SizedBox(height: 8),
        const Text('Configura el contacto y el protocolo automático de alertas.', style: TextStyle(color: Colors.black54)),
        const SizedBox(height: 24),
        _ContactForm(name: _contactName, phone: _contactPhone, onSave: _saveContact),
        const SizedBox(height: 18),
        SwitchListTile(
          value: _automaticActions,
          onChanged: _saveAutomaticActions,
          contentPadding: const EdgeInsets.symmetric(horizontal: 4),
          title: const Text('SMS y llamada automáticos', style: TextStyle(color: _navy, fontWeight: FontWeight.w700)),
          subtitle: const Text('Tras una posible caída y 20 s sin cancelar, enviará un SMS y luego intentará llamar al contacto.', style: TextStyle(color: Colors.black54, fontSize: 12, height: 1.4)),
          secondary: const Icon(Icons.sos_outlined, color: _teal),
        ),
        const SizedBox(height: 10),
        const _NoticeCard(icon: Icons.privacy_tip_outlined, title: 'Tu privacidad', body: 'La ubicación se solicita al momento de una alerta. No se transmite a un servidor en este prototipo.'),
        const SizedBox(height: 12),
        const _NoticeCard(icon: Icons.battery_saver_outlined, title: 'Uso responsable', body: 'El monitoreo usa un servicio en primer plano con una notificación persistente. Mantén el teléfono cargado. Android o el fabricante aún pueden detenerlo; el modo ahorro de batería puede afectar el funcionamiento.'),
        const SizedBox(height: 12),
        const _NoticeCard(icon: Icons.sensors_outlined, title: 'Detección experimental', body: 'Combinamos un impacto fuerte con un periodo corto de baja actividad. Movimientos cotidianos pueden parecerse; la persona siempre puede cancelar.'),
      ]);
}

class _ContactForm extends StatefulWidget {
  const _ContactForm({required this.name, required this.phone, required this.onSave});
  final String name;
  final String phone;
  final Future<void> Function(String, String) onSave;
  @override
  State<_ContactForm> createState() => _ContactFormState();
}

class _ContactFormState extends State<_ContactForm> {
  late final _name = TextEditingController(text: widget.name);
  late final _phone = TextEditingController(text: widget.phone);
  @override
  void dispose() { _name.dispose(); _phone.dispose(); super.dispose(); }

  @override
  Widget build(BuildContext context) => Container(padding: const EdgeInsets.all(19), decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(22)), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const Text('Contacto principal', style: TextStyle(fontWeight: FontWeight.w700, color: _navy, fontSize: 17)),
        const SizedBox(height: 15),
        TextField(controller: _name, textCapitalization: TextCapitalization.words, decoration: const InputDecoration(labelText: 'Nombre (opcional)', prefixIcon: Icon(Icons.person_outline))),
        const SizedBox(height: 11),
        TextField(controller: _phone, keyboardType: TextInputType.phone, decoration: const InputDecoration(labelText: 'Teléfono con lada', hintText: '+52 55 1234 5678', prefixIcon: Icon(Icons.phone_outlined))),
        const SizedBox(height: 14),
        SizedBox(width: double.infinity, child: FilledButton.icon(onPressed: () async {
          if (_phone.text.trim().isEmpty) { ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Escribe un teléfono de contacto.'))); return; }
          await widget.onSave(_name.text, _phone.text);
          if (context.mounted) ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Contacto guardado en este dispositivo.')));
        }, icon: const Icon(Icons.save_outlined), label: const Text('Guardar contacto'))),
        const SizedBox(height: 8),
        const Text('La alerta automática solo se activa si habilitas SMS y llamada automáticos y concedes esos permisos. El botón de prueba manual sigue abriendo el compositor de SMS.', style: TextStyle(color: Colors.black54, fontSize: 12, height: 1.45)),
      ]));
}

class _NoticeCard extends StatelessWidget {
  const _NoticeCard({required this.icon, required this.title, required this.body});
  final IconData icon;
  final String title;
  final String body;
  @override
  Widget build(BuildContext context) => Container(padding: const EdgeInsets.all(17), decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(21)), child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [Icon(icon, color: _teal), const SizedBox(width: 12), Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(title, style: const TextStyle(color: _navy, fontWeight: FontWeight.w700)), const SizedBox(height: 5), Text(body, style: const TextStyle(color: Colors.black54, fontSize: 12, height: 1.45))]))]));
}

String _formatTime(DateTime date) => '${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')} · ${date.day.toString().padLeft(2, '0')}/${date.month.toString().padLeft(2, '0')}/${date.year}';
