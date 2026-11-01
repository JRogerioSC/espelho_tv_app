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

class _HomePageState extends State<HomePage>
    with WidgetsBindingObserver, SingleTickerProviderStateMixin {
  static const _channel = MethodChannel('espelho_tv/channel');
  static const _porta = 8080;

  late final TabController _tabs;
  final _pinCtrl = TextEditingController();

  bool _transmitindo = false;
  bool _modoTela = true; // true = espelhando; false = só servindo filmes
  bool _carregando = false;
  bool _audio = true;
  bool _audioSuportado = false;
  bool _audioAtivo = false;
  String? _ip;
  double _qualidade = 60;
  int _resolucao = 960; // lado maior da imagem enviada à TV

  // Biblioteca
  List<Map<String, dynamic>> _filmes = [];
  String _pasta = '';
  Map<String, dynamic>? _copia; // progresso da cópia em andamento

  String? get _url => _ip == null ? null : 'http://$_ip:$_porta';

  static bool _pinValido(String s) => RegExp(r'^\d{4,8}$').hasMatch(s);

  static String _gerarPin() {
    final r = Random.secure();
    return List.generate(6, (_) => r.nextInt(10)).join();
  }

  static String _tamanho(num bytes) {
    if (bytes >= 1073741824) return '${(bytes / 1073741824).toStringAsFixed(1)} GB';
    return '${max(1, (bytes / 1048576).round())} MB';
  }

  @override
  void initState() {
    super.initState();
    _tabs = TabController(length: 2, vsync: this)
      ..addListener(() => setState(() {}));
    WidgetsBinding.instance.addObserver(this);
    _channel.setMethodCallHandler(_aoReceber);
    _carregarPrefs();
    _atualizarEstado();
    _carregarFilmes();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _tabs.dispose();
    _pinCtrl.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _atualizarEstado();
      _carregarFilmes();
    }
  }

  Future<dynamic> _aoReceber(MethodCall call) async {
    if (!mounted) return;
    switch (call.method) {
      case 'stopped':
        setState(() {
          _transmitindo = false;
          _audioAtivo = false;
        });
        break;
      case 'copyProgress':
        setState(() => _copia = Map<String, dynamic>.from(call.arguments as Map));
        break;
      case 'copyDone':
        final a = Map<String, dynamic>.from(call.arguments as Map);
        setState(() => _copia = null);
        await _carregarFilmes();
        final falhas = (a['failed'] as List?)?.cast<String>() ?? [];
        final ok = a['ok'] as int? ?? 0;
        if (falhas.isEmpty) {
          _aviso(ok == 1 ? 'Filme adicionado.' : '$ok filmes adicionados.');
        } else {
          _aviso('$ok adicionado(s). Falhou: ${falhas.join('; ')}');
        }
        break;
    }
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
      final tela = await _channel.invokeMethod<bool>('screenActive') ?? true;
      final ip = await _channel.invokeMethod<String>('getIp');
      final suporta = await _channel.invokeMethod<bool>('audioSupported') ?? false;
      final audioAtivo = await _channel.invokeMethod<bool>('audioActive') ?? false;
      if (!mounted) return;
      setState(() {
        _transmitindo = rodando;
        if (rodando) _modoTela = tela;
        _ip = ip;
        _audioSuportado = suporta;
        _audioAtivo = audioAtivo;
      });
    } on PlatformException {
      // ignora
    }
  }

  Future<void> _carregarFilmes() async {
    try {
      final lista = await _channel.invokeListMethod<Map>('libraryList') ?? [];
      final pasta = await _channel.invokeMethod<String>('libraryPath') ?? '';
      if (!mounted) return;
      setState(() {
        _filmes = lista.map((m) => Map<String, dynamic>.from(m)).toList();
        _pasta = pasta;
      });
    } on PlatformException {
      // ignora
    }
  }

  Future<void> _adicionarFilmes() async {
    try {
      await _channel.invokeMethod('libraryAdd');
    } on PlatformException catch (e) {
      _aviso('Não foi possível abrir o seletor: ${e.message}');
    }
  }

  Future<void> _excluirFilme(String nome) async {
    final confirmar = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('Excluir filme?'),
        content: Text('"$nome" será apagado do app. O arquivo original que você '
            'escolheu na galeria não é afetado.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('Cancelar')),
          FilledButton(onPressed: () => Navigator.pop(c, true), child: const Text('Excluir')),
        ],
      ),
    );
    if (confirmar != true) return;
    await _channel.invokeMethod('libraryDelete', {'name': nome});
    await _carregarFilmes();
  }

  Future<void> _iniciar({required bool tela}) async {
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
    final querAudio = tela && _audio && _audioSuportado;
    setState(() => _carregando = true);
    try {
      await _salvarPrefs();
      final r = await _channel.invokeMapMethod<String, dynamic>('start', {
        'port': _porta,
        'quality': _qualidade.round(),
        'maxWidth': _resolucao,
        'pin': _pinCtrl.text,
        'audio': querAudio,
        'screen': tela,
      });
      if (!mounted) return;
      final ok = r?['ok'] == true;
      setState(() {
        _transmitindo = ok;
        _modoTela = tela;
      });
      if (!ok) {
        _aviso('Permissão de captura de tela negada.');
      } else if (querAudio && r?['audioPermission'] != true) {
        _aviso('Sem permissão de áudio: transmitindo só a imagem.');
      } else if (querAudio) {
        // O serviço leva um instante para abrir a captura de áudio.
        await Future.delayed(const Duration(milliseconds: 900));
        await _atualizarEstado();
        if (mounted && _transmitindo && !_audioAtivo) {
          _aviso('Não foi possível capturar o áudio neste aparelho: só imagem.');
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
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(texto)));
  }

  // ---------- Interface ----------

  @override
  Widget build(BuildContext context) {
    final cores = Theme.of(context).colorScheme;
    final naAbaTela = _tabs.index == 0;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Espelho TV'),
        bottom: TabBar(
          controller: _tabs,
          tabs: const [
            Tab(icon: Icon(Icons.cast), text: 'Espelhar'),
            Tab(icon: Icon(Icons.movie), text: 'Filmes'),
          ],
        ),
      ),
      body: SafeArea(
        child: TabBarView(
          controller: _tabs,
          children: [_abaEspelhar(cores), _abaFilmes(cores)],
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
              onPressed: _carregando
                  ? null
                  : (_transmitindo ? _parar : () => _iniciar(tela: naAbaTela)),
              icon: Icon(_transmitindo
                  ? Icons.stop
                  : (naAbaTela ? Icons.cast : Icons.movie)),
              label: Text(
                _transmitindo
                    ? 'Parar'
                    : (naAbaTela ? 'Iniciar espelhamento' : 'Servir filmes na TV'),
                style: const TextStyle(fontSize: 18),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _abaEspelhar(ColorScheme cores) {
    return ListView(
      padding: const EdgeInsets.all(20),
      children: [
        _cartaoStatus(cores),
        const SizedBox(height: 16),
        if (_transmitindo) _cartaoEndereco(cores) else ...[
          _cartaoAcesso(),
          const SizedBox(height: 16),
          _cartaoImagem(),
        ],
        const SizedBox(height: 16),
        _cartaoPassos(),
      ],
    );
  }

  Widget _abaFilmes(ColorScheme cores) {
    return ListView(
      padding: const EdgeInsets.all(20),
      children: [
        _cartaoStatus(cores),
        const SizedBox(height: 16),
        if (_transmitindo) _cartaoEndereco(cores) else _cartaoAcesso(),
        const SizedBox(height: 16),
        _cartaoBiblioteca(cores),
      ],
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
                    !_transmitindo
                        ? 'Parado'
                        : (_modoTela ? 'Espelhando a tela' : 'Servindo filmes'),
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  Text(
                    _ip == null ? 'Sem rede Wi-Fi detectada' : 'Rede local: $_ip',
                    style: Theme.of(context).textTheme.bodyMedium,
                  ),
                  if (_transmitindo && _modoTela)
                    Row(
                      children: [
                        Icon(_audioAtivo ? Icons.volume_up : Icons.volume_off, size: 16),
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
            SelectableText(_pinCtrl.text, style: destaque?.copyWith(letterSpacing: 6)),
            const SizedBox(height: 8),
            TextButton.icon(
              onPressed: () {
                Clipboard.setData(ClipboardData(text: url));
                _aviso('Endereço copiado');
              },
              icon: const Icon(Icons.copy),
              label: const Text('Copiar endereço'),
            ),
            if (_transmitindo)
              Text(
                _modoTela
                    ? 'Na TV, escolha "Espelhar a tela" ou "Filmes".'
                    : 'Na TV, escolha o filme na lista.',
                style: Theme.of(context).textTheme.bodySmall,
              ),
          ],
        ),
      ),
    );
  }

  Widget _cartaoAcesso() {
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
          ],
        ),
      ),
    );
  }

  Widget _cartaoImagem() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Espelhamento', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Transmitir áudio'),
              subtitle: Text(_audioSuportado
                  ? 'Captura o som interno do celular'
                  : 'Precisa do Android 10 ou superior'),
              value: _audio && _audioSuportado,
              onChanged: _audioSuportado ? (v) => setState(() => _audio = v) : null,
            ),
            const Divider(height: 24),
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
            const Text('2. Toque em "Iniciar espelhamento" e permita a captura.'),
            const SizedBox(height: 6),
            const Text('3. Na TV, abra o navegador e digite o endereço mostrado.'),
            const SizedBox(height: 6),
            const Text('4. Digite o PIN na TV e escolha o que ver.'),
          ],
        ),
      ),
    );
  }

  Widget _cartaoBiblioteca(ColorScheme cores) {
    final copiando = _copia != null;
    double? fracao;
    if (copiando) {
      final size = (_copia!['size'] as num?)?.toDouble() ?? -1;
      final copied = (_copia!['copied'] as num?)?.toDouble() ?? 0;
      if (size > 0) fracao = (copied / size).clamp(0, 1).toDouble();
    }
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text('Meus filmes (${_filmes.length})',
                      style: Theme.of(context).textTheme.titleMedium),
                ),
                FilledButton.tonalIcon(
                  onPressed: copiando ? null : _adicionarFilmes,
                  icon: const Icon(Icons.add),
                  label: const Text('Adicionar'),
                ),
              ],
            ),
            if (copiando) ...[
              const SizedBox(height: 16),
              Text('Copiando ${_copia!['index']} de ${_copia!['total']}: ${_copia!['name']}',
                  maxLines: 2, overflow: TextOverflow.ellipsis),
              const SizedBox(height: 8),
              LinearProgressIndicator(value: fracao),
              const SizedBox(height: 4),
              Text(
                fracao == null
                    ? '${_tamanho((_copia!['copied'] as num?) ?? 0)} copiados'
                    : '${(fracao * 100).round()}%',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
            const SizedBox(height: 8),
            if (_filmes.isEmpty && !copiando)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 16),
                child: Text(
                  'Nenhum filme ainda. Toque em "Adicionar" e escolha vídeos do celular.',
                  style: Theme.of(context).textTheme.bodyMedium,
                ),
              ),
            for (final f in _filmes)
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: const Icon(Icons.movie_outlined),
                title: Text(f['name'] as String, maxLines: 2, overflow: TextOverflow.ellipsis),
                subtitle: Text(_tamanho(f['size'] as num)),
                trailing: IconButton(
                  tooltip: 'Excluir',
                  icon: const Icon(Icons.delete_outline),
                  onPressed: () => _excluirFilme(f['name'] as String),
                ),
              ),
            const Divider(height: 32),
            Text(
              'Os filmes são copiados para dentro do app e ocupam espaço do celular. '
              'A TV toca direto daqui, sem espelhar a tela. Prefira MP4 (H.264): '
              'MKV e AVI nem sempre rodam no navegador da TV.',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            if (_pasta.isNotEmpty) ...[
              const SizedBox(height: 8),
              Text(
                'Também dá para colocar arquivos pelo cabo USB nesta pasta:\n$_pasta',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ],
        ),
      ),
    );
  }
}