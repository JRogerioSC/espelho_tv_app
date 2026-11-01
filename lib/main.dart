import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() => runApp(const EspelhoTvApp());

class EspelhoTvApp extends StatelessWidget {
  const EspelhoTvApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Espelho TV',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: const Color(0xFF2962FF),
        brightness: Brightness.dark,
      ),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  static const _channel = MethodChannel('espelho_tv/channel');
  static const _porta = 8080;

  final _pinCtrl = TextEditingController();

  bool _transmitindo = false;
  bool _carregando = false;
  bool _audio = true;
  bool _audioSuportado = false;
  bool _audioAtivo = false;
  String? _ip;
  double _qualidade = 60;
  // Largura máxima (lado maior) da imagem enviada à TV.
  int _resolucao = 960;

  String? get _url => _ip == null ? null : 'http://$_ip:$_porta';

  static bool _pinValido(String s) => RegExp(r'^\d{4,8}$').hasMatch(s);

  static String _gerarPin() {
    final r = Random.secure();
    return List.generate(6, (_) => r.nextInt(10)).join();
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'stopped' && mounted) {
        setState(() {
          _transmitindo = false;
          _audioAtivo = false;
        });
      }
    });
    _carregarPrefs();
    _atualizarEstado();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _pinCtrl.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _atualizarEstado();
  }

  Future<void> _carregarPrefs() async {
    final p = await SharedPreferences.getInstance();
    var pin = p.getString('pin');
    if (pin == null || !_pinValido(pin)) {
      pin = _gerarPin();
      await p.setString('pin', pin);
    }
    if (!mounted) return;
    setState(() {
      _pinCtrl.text = pin!;
      _qualidade = (p.getDouble('qualidade') ?? 60).clamp(30, 90).toDouble();
      _resolucao = p.getInt('resolucao') ?? 960;
      _audio = p.getBool('audio') ?? true;
    });
  }

  Future<void> _salvarPrefs() async {
    final p = await SharedPreferences.getInstance();
    await p.setString('pin', _pinCtrl.text);
    await p.setDouble('qualidade', _qualidade);
    await p.setInt('resolucao', _resolucao);
    await p.setBool('audio', _audio);
  }

  Future<void> _atualizarEstado() async {
    try {
      final rodando = await _channel.invokeMethod<bool>('isRunning') ?? false;
      final ip = await _channel.invokeMethod<String>('getIp');
      final suporta =
          await _channel.invokeMethod<bool>('audioSupported') ?? false;
      final audioAtivo =
          await _channel.invokeMethod<bool>('audioActive') ?? false;
      if (!mounted) return;
      setState(() {
        _transmitindo = rodando;
        _ip = ip;
        _audioSuportado = suporta;
        _audioAtivo = audioAtivo;
      });
    } on PlatformException {
      // ignora
    }
  }

  Future<void> _iniciar() async {
    if (!_pinValido(_pinCtrl.text)) {
      _aviso('O PIN precisa ter de 4 a 8 dígitos.');
      return;
    }
    await _atualizarEstado();
    if (_ip == null) {
      _aviso('Conecte o celular a uma rede Wi-Fi (a mesma da TV) '
          'ou ative o ponto de acesso.');
      return;
    }
    final querAudio = _audio && _audioSuportado;
    setState(() => _carregando = true);
    try {
      await _salvarPrefs();
      final r = await _channel.invokeMapMethod<String, dynamic>('start', {
        'port': _porta,
        'quality': _qualidade.round(),
        'maxWidth': _resolucao,
        'pin': _pinCtrl.text,
        'audio': querAudio,
      });
      if (!mounted) return;
      final ok = r?['ok'] == true;
      setState(() => _transmitindo = ok);
      if (!ok) {
        _aviso('Permissão de captura de tela negada.');
      } else {
        if (querAudio && r?['audioPermission'] != true) {
          _aviso('Sem permissão de áudio: transmitindo só a imagem.');
        } else if (querAudio) {
          // O serviço leva um instante para abrir a captura de áudio.
          await Future.delayed(const Duration(milliseconds: 900));
          await _atualizarEstado();
          if (mounted && _transmitindo && !_audioAtivo) {
            _aviso(
                'Não foi possível capturar o áudio neste aparelho: só imagem.');
          }
        }
      }
    } on PlatformException catch (e) {
      _aviso('Erro ao iniciar: ${e.message}');
    } finally {
      if (mounted) setState(() => _carregando = false);
    }
  }

  Future<void> _parar() async {
    await _channel.invokeMethod('stop');
    if (mounted) {
      setState(() {
        _transmitindo = false;
        _audioAtivo = false;
      });
    }
  }

  void _aviso(String texto) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(texto)));
  }

  @override
  Widget build(BuildContext context) {
    final cores = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('Espelho TV')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            _cartaoStatus(cores),
            const SizedBox(height: 16),
            if (_transmitindo) _cartaoEndereco(cores) else _cartaoAjustes(),
            const SizedBox(height: 16),
            _cartaoPassos(),
          ],
        ),
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: SizedBox(
            height: 56,
            child: FilledButton.icon(
              style: _transmitindo
                  ? FilledButton.styleFrom(backgroundColor: cores.error)
                  : null,
              onPressed:
                  _carregando ? null : (_transmitindo ? _parar : _iniciar),
              icon: Icon(_transmitindo ? Icons.stop : Icons.cast),
              label: Text(
                _transmitindo ? 'Parar espelhamento' : 'Iniciar espelhamento',
                style: const TextStyle(fontSize: 18),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _cartaoStatus(ColorScheme cores) {
    return Card(
      color: _transmitindo ? cores.primaryContainer : null,
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Row(
          children: [
            Icon(
              _transmitindo ? Icons.cast_connected : Icons.cast,
              size: 40,
              color: _transmitindo ? cores.primary : cores.outline,
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    _transmitindo ? 'Transmitindo' : 'Parado',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  Text(
                    _ip == null
                        ? 'Sem rede Wi-Fi detectada'
                        : 'Rede local: $_ip',
                    style: Theme.of(context).textTheme.bodyMedium,
                  ),
                  if (_transmitindo)
                    Row(
                      children: [
                        Icon(
                          _audioAtivo ? Icons.volume_up : Icons.volume_off,
                          size: 16,
                        ),
                        const SizedBox(width: 4),
                        Text(_audioAtivo ? 'Áudio ativo' : 'Sem áudio'),
                      ],
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _cartaoEndereco(ColorScheme cores) {
    final url = _url ?? '';
    final destaque = Theme.of(context).textTheme.headlineSmall?.copyWith(
          color: cores.primary,
          fontWeight: FontWeight.bold,
        );
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('1. Abra este endereço no navegador da TV:'),
            const SizedBox(height: 8),
            SelectableText(url, style: destaque),
            const SizedBox(height: 16),
            const Text('2. Digite este PIN quando a TV pedir:'),
            const SizedBox(height: 8),
            SelectableText(
              _pinCtrl.text,
              style: destaque?.copyWith(letterSpacing: 6),
            ),
            const SizedBox(height: 8),
            TextButton.icon(
              onPressed: () {
                Clipboard.setData(ClipboardData(text: url));
                _aviso('Endereço copiado');
              },
              icon: const Icon(Icons.copy),
              label: const Text('Copiar endereço'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _cartaoAjustes() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Acesso', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 12),
            TextField(
              controller: _pinCtrl,
              keyboardType: TextInputType.number,
              maxLength: 8,
              inputFormatters: [FilteringTextInputFormatter.digitsOnly],
              decoration: InputDecoration(
                labelText: 'PIN de acesso (4 a 8 dígitos)',
                border: const OutlineInputBorder(),
                suffixIcon: IconButton(
                  tooltip: 'Gerar outro PIN',
                  icon: const Icon(Icons.refresh),
                  onPressed: () => setState(() => _pinCtrl.text = _gerarPin()),
                ),
              ),
            ),
            const SizedBox(height: 8),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Transmitir áudio'),
              subtitle: Text(_audioSuportado
                  ? 'Captura o som interno do celular'
                  : 'Precisa do Android 10 ou superior'),
              value: _audio && _audioSuportado,
              onChanged:
                  _audioSuportado ? (v) => setState(() => _audio = v) : null,
            ),
            const Divider(height: 32),
            Text('Imagem', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 12),
            Text('Qualidade: ${_qualidade.round()}%'),
            Slider(
              value: _qualidade,
              min: 30,
              max: 90,
              divisions: 12,
              onChanged: (v) => setState(() => _qualidade = v),
            ),
            const SizedBox(height: 8),
            const Text('Resolução'),
            const SizedBox(height: 8),
            SegmentedButton<int>(
              segments: const [
                ButtonSegment(value: 640, label: Text('Leve')),
                ButtonSegment(value: 960, label: Text('Média')),
                ButtonSegment(value: 1280, label: Text('Alta')),
              ],
              selected: {_resolucao},
              onSelectionChanged: (s) => setState(() => _resolucao = s.first),
            ),
            const SizedBox(height: 8),
            Text(
              'Se a imagem travar, escolha "Leve" e reduza a qualidade.',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
        ),
      ),
    );
  }

  Widget _cartaoPassos() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Como usar', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 12),
            const Text('1. Conecte o celular e a TV na mesma rede Wi-Fi.'),
            const SizedBox(height: 6),
            const Text(
                '2. Toque em "Iniciar espelhamento" e permita a captura.'),
            const SizedBox(height: 6),
            const Text(
                '3. Na TV, abra o navegador e digite o endereço mostrado.'),
            const SizedBox(height: 6),
            const Text(
                '4. Digite o PIN na TV. Clique na tela para tela cheia.'),
          ],
        ),
      ),
    );
  }
}
