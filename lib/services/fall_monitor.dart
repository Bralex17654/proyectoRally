import 'dart:async';
import 'dart:math' as math;

import 'package:sensors_plus/sensors_plus.dart';

/// Foreground-only heuristic: strong linear acceleration followed by low motion.
/// This is an experiment and must not be treated as a medical detector.
class FallMonitor {
  StreamSubscription<UserAccelerometerEvent>? _subscription;
  final _events = StreamController<double>.broadcast();
  DateTime? _impactAt;
  DateTime? _lastTriggered;
  double _peak = 0;

  bool get isRunning => _subscription != null;
  Stream<double> get fallEvents => _events.stream;

  void start() {
    if (isRunning) return;
    _subscription = userAccelerometerEventStream(
      samplingPeriod: SensorInterval.normalInterval,
    ).listen((event) {
      final magnitude = math.sqrt(event.x * event.x + event.y * event.y + event.z * event.z);
      final now = DateTime.now();
      if (_impactAt == null &&
          magnitude > 25.0 &&
          (_lastTriggered == null || now.difference(_lastTriggered!) > const Duration(seconds: 45))) {
        _impactAt = now;
        _peak = magnitude;
        return;
      }
      if (_impactAt == null) return;
      if (magnitude > _peak) _peak = magnitude;
      final elapsed = now.difference(_impactAt!);
      if (elapsed > const Duration(seconds: 9)) {
        _impactAt = null;
        return;
      }
      // A few seconds of low linear motion can follow a fall, but can also
      // result from ordinary handling of the phone.
      if (elapsed > const Duration(seconds: 3) && magnitude < 2.2) {
        _lastTriggered = now;
        final peak = _peak;
        _impactAt = null;
        _events.add(peak);
      }
    }, onError: (_) {});
  }

  void stop() {
    _subscription?.cancel();
    _subscription = null;
    _impactAt = null;
  }

  void dispose() {
    stop();
    _events.close();
  }
}
