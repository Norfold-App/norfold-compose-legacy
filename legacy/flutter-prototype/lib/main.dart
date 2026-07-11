import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:cryptography/cryptography.dart';
import 'package:cross_file/cross_file.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_colorpicker/flutter_colorpicker.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';
import 'package:pdf/pdf.dart';
import 'package:pdf/widgets.dart' as pw;
import 'package:printing/printing.dart';
import 'package:share_plus/share_plus.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:syncfusion_flutter_pdfviewer/pdfviewer.dart';

void main() {
  runApp(const LibreNotesApp());
}

class LibreNotesApp extends StatefulWidget {
  const LibreNotesApp({super.key});

  @override
  State<LibreNotesApp> createState() => _LibreNotesAppState();
}

class _LibreNotesAppState extends State<LibreNotesApp> {
  final SharedPreferencesAsync prefs = SharedPreferencesAsync();
  Timer? settingsDebounce;
  ThemeMode themeMode = ThemeMode.dark;
  bool amoledDark = false;
  bool pureBlack = false;
  bool accentTintBackground = true;
  bool letterIcons = true;
  bool denseNotes = false;
  bool loading = true;
  Color accent = const Color(0xFF14B8A6);

  @override
  void initState() {
    super.initState();
    _loadAppSettings();
  }

  @override
  void dispose() {
    settingsDebounce?.cancel();
    super.dispose();
  }

  Future<void> _loadAppSettings() async {
    final raw = await prefs.getString(appSettingsStorageKey);
    if (!mounted || raw == null || raw.isEmpty) {
      return;
    }
    try {
      final decoded = jsonDecode(raw) as Map<String, dynamic>;
      setState(() {
        themeMode = ThemeMode.values.firstWhere(
          (mode) => mode.name == decoded['themeMode'],
          orElse: () => themeMode,
        );
        amoledDark = decoded['amoledDark'] as bool? ?? amoledDark;
        pureBlack = decoded['pureBlack'] as bool? ?? pureBlack;
        accentTintBackground = decoded['accentTintBackground'] as bool? ?? accentTintBackground;
        letterIcons = decoded['letterIcons'] as bool? ?? letterIcons;
        denseNotes = decoded['denseNotes'] as bool? ?? denseNotes;
        final accentValue = decoded['accent'] as int?;
        if (accentValue != null) {
          accent = Color(accentValue);
        }
      });
    } catch (_) {
      // Keep defaults if the settings snapshot was edited or corrupted.
    }
  }

  void _persistAppSettings() {
    settingsDebounce?.cancel();
    settingsDebounce = Timer(const Duration(milliseconds: 300), () async {
      await prefs.setString(appSettingsStorageKey, jsonEncode({
        'themeMode': themeMode.name,
        'amoledDark': amoledDark,
        'pureBlack': pureBlack,
        'accentTintBackground': accentTintBackground,
        'letterIcons': letterIcons,
        'denseNotes': denseNotes,
        'accent': accent.toARGB32(),
      }));
    });
  }

  void _updateAppSetting(VoidCallback apply) {
    setState(apply);
    _persistAppSettings();
  }

  @override
  Widget build(BuildContext context) {
    final lightScheme = ColorScheme.fromSeed(seedColor: accent);
    final darkScheme = ColorScheme.fromSeed(
      seedColor: accent,
      brightness: Brightness.dark,
    ).copyWith(
      surface: pureBlack || amoledDark ? Colors.black : const Color(0xFF101A17),
      surfaceContainerLowest: pureBlack ? Colors.black : null,
      surfaceContainerLow: pureBlack ? Colors.black : null,
      surfaceContainer: pureBlack ? Colors.black : null,
      surfaceContainerHigh: pureBlack ? Colors.black : null,
      surfaceContainerHighest: pureBlack ? Colors.black : null,
    );

    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'Libre Notes',
      themeMode: themeMode,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: lightScheme,
        visualDensity: VisualDensity.standard,
      ),
      darkTheme: ThemeData(
        useMaterial3: true,
        colorScheme: darkScheme,
        scaffoldBackgroundColor: pureBlack || amoledDark ? Colors.black : const Color(0xFF0E1714),
        visualDensity: VisualDensity.standard,
      ),
      home: AnimatedSwitcher(
        duration: const Duration(milliseconds: 300),
        child: loading
            ? SplashScreen(
                accent: accent,
                key: const ValueKey('splash'),
                onFinished: () {
                  if (mounted) {
                    setState(() => loading = false);
                  }
                },
              )
            : LibreNotesHome(
                key: const ValueKey('home'),
                accent: accent,
                themeMode: themeMode,
                amoledDark: amoledDark,
                pureBlack: pureBlack,
                accentTintBackground: accentTintBackground,
                letterIcons: letterIcons,
                denseNotes: denseNotes,
                onThemeModeChanged: (value) => _updateAppSetting(() => themeMode = value),
                onAmoledChanged: (value) => _updateAppSetting(() => amoledDark = value),
                onPureBlackChanged: (value) => _updateAppSetting(() => pureBlack = value),
                onAccentChanged: (value) => _updateAppSetting(() => accent = value),
                onAccentTintChanged: (value) => _updateAppSetting(() => accentTintBackground = value),
                onLetterIconsChanged: (value) => _updateAppSetting(() => letterIcons = value),
                onDenseNotesChanged: (value) => _updateAppSetting(() => denseNotes = value),
              ),
      ),
    );
  }
}

class SplashScreen extends StatefulWidget {
  const SplashScreen({super.key, required this.accent, required this.onFinished});

  final Color accent;
  final VoidCallback onFinished;

  @override
  State<SplashScreen> createState() => _SplashScreenState();
}

class _SplashScreenState extends State<SplashScreen> with SingleTickerProviderStateMixin {
  late final AnimationController controller;

  @override
  void initState() {
    super.initState();
    controller = AnimationController(vsync: this, duration: const Duration(milliseconds: 1150));
    controller.addStatusListener((status) {
      if (status == AnimationStatus.completed) {
        Future<void>.delayed(const Duration(milliseconds: 160), () {
          if (mounted) {
            widget.onFinished();
          }
        });
      }
    });
    controller.forward();
  }

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      backgroundColor: scheme.surface,
      body: Center(
        child: AnimatedBuilder(
          animation: controller,
          builder: (context, child) {
            final value = Curves.easeOutCubic.transform(controller.value);
            return Opacity(
              opacity: value.clamp(0.0, 1.0).toDouble(),
              child: Transform.translate(
                offset: Offset(0, 18 * (1 - value)),
                child: Transform.scale(
                  scale: 0.9 + value * 0.1,
                  child: child,
                ),
              ),
            );
          },
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              LibreLogo(accent: widget.accent, size: 84),
              const SizedBox(height: 18),
              const Text('Libre Notes', style: TextStyle(fontSize: 24, fontWeight: FontWeight.w900)),
              const SizedBox(height: 22),
              SizedBox(
                width: 180,
                child: LinearProgressIndicator(
                  minHeight: 6,
                  borderRadius: BorderRadius.circular(999),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class LibreLogo extends StatelessWidget {
  const LibreLogo({super.key, required this.accent, this.size = 42});

  final Color accent;
  final double size;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        color: accent,
        borderRadius: BorderRadius.circular(size * 0.24),
        boxShadow: [
          BoxShadow(
            color: accent.withValues(alpha: 0.28),
            blurRadius: size * 0.35,
            offset: Offset(0, size * 0.12),
          ),
        ],
      ),
      child: Stack(
        alignment: Alignment.center,
        children: [
          Icon(Icons.shield_outlined, color: Colors.white, size: size * 0.58),
          Positioned(
            right: size * 0.2,
            bottom: size * 0.18,
            child: Container(
              width: size * 0.2,
              height: size * 0.2,
              decoration: BoxDecoration(
                color: Colors.white,
                shape: BoxShape.circle,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class LibreNotesHome extends StatefulWidget {
  const LibreNotesHome({
    super.key,
    required this.accent,
    required this.themeMode,
    required this.amoledDark,
    required this.pureBlack,
    required this.accentTintBackground,
    required this.letterIcons,
    required this.denseNotes,
    required this.onThemeModeChanged,
    required this.onAmoledChanged,
    required this.onPureBlackChanged,
    required this.onAccentChanged,
    required this.onAccentTintChanged,
    required this.onLetterIconsChanged,
    required this.onDenseNotesChanged,
  });

  final Color accent;
  final ThemeMode themeMode;
  final bool amoledDark;
  final bool pureBlack;
  final bool accentTintBackground;
  final bool letterIcons;
  final bool denseNotes;
  final ValueChanged<ThemeMode> onThemeModeChanged;
  final ValueChanged<bool> onAmoledChanged;
  final ValueChanged<bool> onPureBlackChanged;
  final ValueChanged<Color> onAccentChanged;
  final ValueChanged<bool> onAccentTintChanged;
  final ValueChanged<bool> onLetterIconsChanged;
  final ValueChanged<bool> onDenseNotesChanged;

  @override
  State<LibreNotesHome> createState() => _LibreNotesHomeState();
}

class _LibreNotesHomeState extends State<LibreNotesHome> with TickerProviderStateMixin {
  final TextEditingController editor = TextEditingController();
  final TextEditingController search = TextEditingController();
  final GlobalKey<ScaffoldState> scaffoldKey = GlobalKey<ScaffoldState>();
  late final TabController editorTabs;
  final SharedPreferencesAsync prefs = SharedPreferencesAsync();
  Timer? saveDebounce;

  List<Note> notes = [...seedNotes];
  List<String> customFolders = [...seedFolders];
  Map<String, int> tagColorIndexes = {};
  Map<String, String> folderIconKeys = {...seedFolderIcons};
  Map<String, double> attachmentPositions = {};
  String userName = 'Local writer';
  String userHandle = 'offline-vault';
  String? vaultFolderPath;
  bool vaultEncrypted = false;
  String? vaultPassword;
  bool vaultSetupPromptShown = false;
  String? backgroundVisualPath;
  String selectedNoteId = seedNotes.first.id;
  String selectedFolder = 'All';
  String query = '';
  String sortMode = 'updatedDesc';
  String preferredExportType = 'md';
  int compactPage = 0;
  int trashRetentionDays = 30;
  bool previewCards = true;
  bool readingMode = false;
  bool highContrastText = false;
  bool reduceMotion = false;
  bool syncingEditor = false;

  Color get accent => widget.accent;
  ThemeMode get themeMode => widget.themeMode;
  bool get amoledDark => widget.amoledDark;
  bool get pureBlack => widget.pureBlack;
  bool get accentTintBackground => widget.accentTintBackground;
  bool get letterIcons => widget.letterIcons;
  bool get denseNotes => widget.denseNotes;
  ValueChanged<ThemeMode> get onThemeModeChanged => widget.onThemeModeChanged;
  ValueChanged<bool> get onAmoledChanged => widget.onAmoledChanged;
  ValueChanged<bool> get onPureBlackChanged => widget.onPureBlackChanged;
  ValueChanged<Color> get onAccentChanged => widget.onAccentChanged;
  ValueChanged<bool> get onAccentTintChanged => widget.onAccentTintChanged;
  ValueChanged<bool> get onLetterIconsChanged => widget.onLetterIconsChanged;
  ValueChanged<bool> get onDenseNotesChanged => widget.onDenseNotesChanged;

  Note get selectedNote => notes.firstWhere((note) => note.id == selectedNoteId, orElse: () => notes.first);

  List<String> get folders {
    final names = {
      ...customFolders,
      ...notes.where((note) => !note.isTrashed).map((note) => note.folder),
    }.toList()
      ..sort();
    return ['All', 'Starred', ...names, 'Archive', 'Trash'];
  }

  List<Note> get visibleNotes {
    final lowerQuery = query.trim().toLowerCase();
    final filtered = notes.where((note) {
      final folderMatch = switch (selectedFolder) {
        'All' => note.isActive,
        'Starred' => note.isActive && note.starred,
        'Archive' => note.isArchived,
        'Trash' => note.isTrashed,
        _ => note.isActive && note.folder == selectedFolder,
      };
      final queryMatch = lowerQuery.isEmpty ||
          note.title.toLowerCase().contains(lowerQuery) ||
          note.body.toLowerCase().contains(lowerQuery) ||
          note.tag.toLowerCase().contains(lowerQuery);
      return folderMatch && queryMatch;
    }).toList();
    filtered.sort((a, b) {
      return switch (sortMode) {
        'updatedAsc' => a.updatedAt.compareTo(b.updatedAt),
        'title' => a.title.toLowerCase().compareTo(b.title.toLowerCase()),
        _ => b.updatedAt.compareTo(a.updatedAt),
      };
    });
    return filtered;
  }

  @override
  void initState() {
    super.initState();
    editorTabs = TabController(length: 2, vsync: this);
    editor.text = selectedNote.body;
    editor.addListener(_updateSelectedNoteBody);
    search.addListener(() => setState(() => query = search.text));
    _loadVault();
  }

  @override
  void dispose() {
    saveDebounce?.cancel();
    editor.dispose();
    search.dispose();
    editorTabs.dispose();
    super.dispose();
  }

  Future<void> _loadVault() async {
    final raw = await prefs.getString(vaultStorageKey);
    if (!mounted || raw == null || raw.isEmpty) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _maybePromptVaultSetup());
      return;
    }
    try {
      dynamic decoded = jsonDecode(raw);
      final List<dynamic> noteItems;
      final List<String> folderItems;
      if (decoded is Map<String, dynamic>) {
        final settings = (decoded['settings'] as Map<String, dynamic>?) ?? const {};
        userName = (settings['userName'] as String?) ?? userName;
        userHandle = (settings['userHandle'] as String?) ?? userHandle;
        vaultFolderPath = settings['vaultFolderPath'] as String? ?? vaultFolderPath;
        vaultEncrypted = settings['vaultEncrypted'] as bool? ?? vaultEncrypted;
        final externalVault = await _readExternalVaultIfAvailable();
        if (externalVault != null) {
          decoded = jsonDecode(externalVault);
        }
      }
      if (decoded is Map<String, dynamic>) {
        final settings = (decoded['settings'] as Map<String, dynamic>?) ?? const {};
        noteItems = (decoded['notes'] as List<dynamic>?) ?? const [];
        folderItems = ((decoded['folders'] as List<dynamic>?) ?? const [])
            .map((folder) => folder.toString())
            .where((folder) => folder.trim().isNotEmpty)
            .toList();
        userName = (settings['userName'] as String?) ?? userName;
        userHandle = (settings['userHandle'] as String?) ?? userHandle;
        vaultFolderPath = settings['vaultFolderPath'] as String? ?? vaultFolderPath;
        vaultEncrypted = settings['vaultEncrypted'] as bool? ?? vaultEncrypted;
        trashRetentionDays = _readInt(decoded['trashRetentionDays'] ?? settings['trashRetentionDays'], fallback: trashRetentionDays);
        sortMode = (settings['sortMode'] as String?) ?? sortMode;
        preferredExportType = (settings['preferredExportType'] as String?) ?? preferredExportType;
        readingMode = settings['readingMode'] as bool? ?? readingMode;
        highContrastText = settings['highContrastText'] as bool? ?? highContrastText;
        reduceMotion = settings['reduceMotion'] as bool? ?? reduceMotion;
        backgroundVisualPath = settings['backgroundVisualPath'] as String? ?? backgroundVisualPath;
        final rawTagColors = Map<String, dynamic>.from((decoded['tagColors'] as Map?) ?? (settings['tagColors'] as Map?) ?? const {});
        tagColorIndexes = rawTagColors.map((key, value) {
          final parsed = value is int ? value : int.tryParse(value.toString()) ?? 0;
          return MapEntry(key, parsed < systemColors.length ? systemColors[parsed].toARGB32() : parsed);
        });
        final rawFolderIcons = Map<String, dynamic>.from((settings['folderIcons'] as Map?) ?? (decoded['folderIcons'] as Map?) ?? const {});
        folderIconKeys = {...seedFolderIcons, ...rawFolderIcons.map((key, value) => MapEntry(key, value.toString()))};
        final rawPositions = Map<String, dynamic>.from(settings['attachmentPositions'] as Map? ?? const {});
        attachmentPositions = rawPositions.map((key, value) => MapEntry(key, value is num ? value.toDouble() : double.tryParse(value.toString()) ?? 0));
      } else {
        noteItems = decoded as List<dynamic>;
        folderItems = const [];
      }
      final restored = _dropExpiredTrash(noteItems.map((item) => Note.fromJson(item as Map<String, dynamic>)).toList());
      if (restored.isEmpty) {
        return;
      }
      setState(() {
        notes = restored;
        customFolders = folderItems.isEmpty ? [...seedFolders] : folderItems;
        selectedNoteId = restored.firstWhere((note) => note.isActive, orElse: () => restored.first).id;
        selectedFolder = 'All';
        syncingEditor = true;
        editor.text = selectedNote.body;
        syncingEditor = false;
      });
      _queuePersist();
      WidgetsBinding.instance.addPostFrameCallback((_) => _maybePromptVaultSetup());
    } catch (_) {
      _showSnack('Saved vault data could not be loaded. Seed notes are still available.');
      WidgetsBinding.instance.addPostFrameCallback((_) => _maybePromptVaultSetup());
    }
  }

  void _queuePersist() {
    saveDebounce?.cancel();
    saveDebounce = Timer(const Duration(milliseconds: 450), () async {
      final encoded = _encodedVault();
      await _writeExternalVault(encoded);
      await prefs.setString(vaultStorageKey, vaultEncrypted && vaultFolderPath != null ? _encodedVaultPointer() : encoded);
    });
  }

  String _encodedVault() {
    return const JsonEncoder.withIndent('  ').convert({
      'version': 1,
      'exportedAt': DateTime.now().toIso8601String(),
      'folders': customFolders,
      'folderIcons': folderIconKeys,
      'trashRetentionDays': trashRetentionDays,
      'tagColors': tagColorIndexes,
      'settings': {
        'userName': userName,
        'userHandle': userHandle,
        'vaultFolderPath': vaultFolderPath,
        'vaultEncrypted': vaultEncrypted,
        'folderIcons': folderIconKeys,
        'trashRetentionDays': trashRetentionDays,
        'sortMode': sortMode,
        'preferredExportType': preferredExportType,
        'readingMode': readingMode,
        'highContrastText': highContrastText,
        'reduceMotion': reduceMotion,
        'attachmentPositions': attachmentPositions,
        'backgroundVisualPath': backgroundVisualPath,
        'themeMode': themeMode.name,
        'amoledDark': amoledDark,
        'pureBlack': pureBlack,
        'accentTintBackground': accentTintBackground,
        'letterIcons': letterIcons,
        'denseNotes': denseNotes,
        'accent': accent.toARGB32(),
      },
      'notes': notes.map((note) => note.toJson()).toList(),
    });
  }

  String _encodedVaultPointer() {
    return const JsonEncoder.withIndent('  ').convert({
      'version': 1,
      'folders': customFolders,
      'settings': {
        'userName': userName,
        'userHandle': userHandle,
        'vaultFolderPath': vaultFolderPath,
        'vaultEncrypted': vaultEncrypted,
        'folderIcons': folderIconKeys,
        'themeMode': themeMode.name,
        'amoledDark': amoledDark,
        'pureBlack': pureBlack,
        'accentTintBackground': accentTintBackground,
        'letterIcons': letterIcons,
        'denseNotes': denseNotes,
        'accent': accent.toARGB32(),
      },
      'notes': const [],
    });
  }

  String get _vaultFilePath {
    final root = vaultFolderPath;
    if (root == null || root.isEmpty) {
      return '';
    }
    return '${root.replaceAll(RegExp(r'[\\/]+$'), '')}/libre-notes.${vaultEncrypted ? 'vault' : 'json'}';
  }

  Future<String?> _readExternalVaultIfAvailable() async {
    final path = _vaultFilePath;
    if (path.isEmpty || !File(path).existsSync()) {
      return null;
    }
    final raw = await File(path).readAsString();
    if (!vaultEncrypted) {
      return raw;
    }
    var password = vaultPassword;
    if (password == null || password.isEmpty) {
      password = await _promptVaultPassword(title: 'Unlock vault', action: 'Unlock');
    }
    if (password == null || password.isEmpty) {
      return null;
    }
    try {
      vaultPassword = password;
      return await _decryptVaultText(raw, password);
    } catch (_) {
      _showSnack('Vault password did not unlock that file.');
      return null;
    }
  }

  Future<void> _writeExternalVault(String encoded) async {
    final path = _vaultFilePath;
    if (path.isEmpty) {
      return;
    }
    try {
      final file = File(path);
      await file.parent.create(recursive: true);
      final payload = vaultEncrypted && vaultPassword != null && vaultPassword!.isNotEmpty
          ? await _encryptVaultText(encoded, vaultPassword!)
          : encoded;
      await file.writeAsString(payload);
    } catch (_) {
      _showSnack('Could not write the external vault file.');
    }
  }

  Future<String> _encryptVaultText(String plainText, String password) async {
    final algorithm = AesGcm.with256bits();
    final salt = algorithm.newNonce();
    final nonce = algorithm.newNonce();
    final key = await Pbkdf2(
      macAlgorithm: Hmac.sha256(),
      iterations: 120000,
      bits: 256,
    ).deriveKey(secretKey: SecretKey(utf8.encode(password)), nonce: salt);
    final box = await algorithm.encrypt(utf8.encode(plainText), secretKey: key, nonce: nonce);
    return jsonEncode({
      'format': 'libre-vault-aes-gcm-v1',
      'salt': base64Encode(salt),
      'nonce': base64Encode(box.nonce),
      'mac': base64Encode(box.mac.bytes),
      'cipherText': base64Encode(box.cipherText),
    });
  }

  Future<String> _decryptVaultText(String payload, String password) async {
    final decoded = jsonDecode(payload) as Map<String, dynamic>;
    final algorithm = AesGcm.with256bits();
    final salt = base64Decode(decoded['salt'] as String);
    final key = await Pbkdf2(
      macAlgorithm: Hmac.sha256(),
      iterations: 120000,
      bits: 256,
    ).deriveKey(secretKey: SecretKey(utf8.encode(password)), nonce: salt);
    final clear = await algorithm.decrypt(
      SecretBox(
        base64Decode(decoded['cipherText'] as String),
        nonce: base64Decode(decoded['nonce'] as String),
        mac: Mac(base64Decode(decoded['mac'] as String)),
      ),
      secretKey: key,
    );
    return utf8.decode(clear);
  }

  Future<void> _maybePromptVaultSetup() async {
    if (!mounted || vaultSetupPromptShown || vaultFolderPath != null) {
      return;
    }
    vaultSetupPromptShown = true;
    final choice = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Choose vault storage'),
        content: const Text('Use the built-in app vault, or pick a folder so your notes live somewhere you control.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, 'builtin'), child: const Text('Built-in')),
          OutlinedButton(onPressed: () => Navigator.pop(context, 'folder'), child: const Text('Choose folder')),
          FilledButton(onPressed: () => Navigator.pop(context, 'encrypted'), child: const Text('Encrypted folder')),
        ],
      ),
    );
    if (choice == null || choice == 'builtin') {
      _queuePersist();
      return;
    }
    await _chooseVaultFolder(encrypt: choice == 'encrypted');
  }

  Future<void> _chooseVaultFolder({bool encrypt = false}) async {
    final path = await FilePicker.platform.getDirectoryPath(dialogTitle: 'Choose Libre Notes folder');
    if (path == null || path.isEmpty) {
      return;
    }
    var password = vaultPassword;
    if (encrypt) {
      password = await _promptVaultPassword(title: 'Create vault password', action: 'Use password');
      if (password == null || password.isEmpty) {
        _showSnack('Vault folder was not encrypted because no password was set.');
        return;
      }
    }
    setState(() {
      vaultFolderPath = path;
      vaultEncrypted = encrypt;
      vaultPassword = password;
    });
    _queuePersist();
    _showSnack(encrypt ? 'Encrypted vault folder selected.' : 'Vault folder selected.');
  }

  Future<String?> _promptVaultPassword({required String title, required String action}) {
    return showDialog<String>(
      context: context,
      builder: (context) => TextInputDialog(
        title: title,
        label: 'Password',
        primaryAction: action,
        obscureText: true,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final dark = Theme.of(context).brightness == Brightness.dark;
    final tint = widget.accentTintBackground && !widget.pureBlack && !reduceMotion
        ? BoxShadow(
            color: widget.accent.withValues(alpha: dark ? 0.12 : 0.14),
            blurRadius: 120,
            spreadRadius: 30,
            offset: const Offset(-120, -90),
          )
        : null;

    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth;
        final compact = width < 700;
        final medium = width >= 700 && width < 1050;

        final backgroundImage = backgroundVisualPath != null && File(backgroundVisualPath!).existsSync() && !pureBlack
            ? DecorationImage(
                image: FileImage(File(backgroundVisualPath!)),
                fit: BoxFit.cover,
                opacity: dark ? 0.14 : 0.1,
              )
            : null;

        return PopScope(
          canPop: !compact || compactPage == 0,
          onPopInvokedWithResult: (didPop, _) {
            if (!didPop && compact && compactPage != 0) {
              _setCompactPage(0);
            }
          },
          child: Scaffold(
            key: scaffoldKey,
            drawer: compact ? _AppDrawer(home: this) : null,
            appBar: compact ? _buildCompactAppBar() : null,
            bottomNavigationBar: compact ? _buildBottomNavigation() : null,
            floatingActionButton: compact && compactPage == 0
                ? FloatingActionButton.extended(
                    onPressed: _createNote,
                    icon: const Icon(Icons.note_add_outlined),
                    label: const Text('New'),
                  )
                : null,
            body: Container(
              decoration: BoxDecoration(
                color: widget.pureBlack && dark ? Colors.black : scheme.surface,
                boxShadow: tint == null ? null : [tint],
                image: backgroundImage,
              ),
              child: SafeArea(
                child: compact
                    ? _CompactHome(home: this)
                    : medium
                        ? _MediumHome(home: this)
                        : _ExpandedHome(home: this),
              ),
            ),
          ),
        );
      },
    );
  }

  PreferredSizeWidget _buildCompactAppBar() {
    return AppBar(
      title: Text(selectedNote.title, maxLines: 1, overflow: TextOverflow.ellipsis),
      leading: IconButton(
        tooltip: 'Menu',
        icon: const Icon(Icons.menu),
        onPressed: () => scaffoldKey.currentState?.openDrawer(),
      ),
      actions: [
        IconButton(
          tooltip: selectedNote.starred ? 'Unstar' : 'Star',
          onPressed: _toggleStar,
          icon: Icon(selectedNote.starred ? Icons.star : Icons.star_border),
        ),
        IconButton(
          tooltip: 'Share note',
          onPressed: _shareNote,
          icon: const Icon(Icons.share_outlined),
        ),
        PopupMenuButton<String>(
          onSelected: (value) {
            switch (value) {
              case 'rename':
                _renameNote();
                break;
              case 'save':
                _saveNote();
                break;
              case 'delete':
                _deleteSelectedNote();
                break;
              case 'archive':
                _archiveSelectedNote();
                break;
              case 'restore':
                _restoreSelectedNote();
                break;
              case 'deleteForever':
                _deleteForever(selectedNote);
                break;
              case 'share':
                _shareNote();
                break;
              case 'settings':
                _openSettings();
                break;
              case 'backup':
                _exportBackup();
                break;
              case 'restoreBackup':
                _importBackup();
                break;
              case 'importMarkdown':
                _importMarkdownFiles();
                break;
            }
          },
          itemBuilder: (context) => [
            const PopupMenuItem(value: 'rename', child: Text('Rename')),
            const PopupMenuItem(value: 'save', child: Text('Save')),
            const PopupMenuItem(value: 'share', child: Text('Share')),
            if (selectedNote.isArchived || selectedNote.isTrashed)
              const PopupMenuItem(value: 'restore', child: Text('Restore note'))
            else
              const PopupMenuItem(value: 'archive', child: Text('Archive note')),
            PopupMenuItem(value: selectedNote.isTrashed ? 'deleteForever' : 'delete', child: Text(selectedNote.isTrashed ? 'Delete forever' : 'Move to Trash')),
            const PopupMenuDivider(),
            const PopupMenuItem(value: 'backup', child: Text('Backup vault')),
            const PopupMenuItem(value: 'restoreBackup', child: Text('Restore backup')),
            const PopupMenuItem(value: 'importMarkdown', child: Text('Import Markdown')),
            const PopupMenuDivider(),
            const PopupMenuItem(value: 'settings', child: Text('Settings')),
          ],
        ),
      ],
    );
  }

  Widget _buildBottomNavigation() {
    final bottomPadding = MediaQuery.paddingOf(context).bottom;
    return SafeArea(
      minimum: const EdgeInsets.fromLTRB(12, 0, 12, 10),
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.surfaceContainerHighest.withValues(alpha: pureBlack ? 1 : 0.92),
          borderRadius: BorderRadius.circular(28),
          border: Border.all(color: Theme.of(context).colorScheme.outlineVariant.withValues(alpha: 0.7)),
          boxShadow: pureBlack
              ? null
              : [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: 0.18),
                    blurRadius: 20,
                    offset: const Offset(0, 10),
                  ),
                ],
        ),
        child: Padding(
          padding: EdgeInsets.fromLTRB(6, 6, 6, bottomPadding > 0 ? 6 : 6),
          child: Row(
            children: [
              _NavPill(index: 0, selectedIndex: compactPage, icon: Icons.article_outlined, selectedIcon: Icons.article, label: 'Notes', onTap: _setCompactPage),
              _NavPill(index: 1, selectedIndex: compactPage, icon: Icons.edit_outlined, selectedIcon: Icons.edit, label: 'Edit', onTap: _setCompactPage),
              _NavPill(index: 2, selectedIndex: compactPage, icon: Icons.visibility_outlined, selectedIcon: Icons.visibility, label: 'Preview', onTap: _setCompactPage),
              _NavPill(index: 3, selectedIndex: compactPage, icon: Icons.attach_file_outlined, selectedIcon: Icons.attach_file, label: 'Files', onTap: _setCompactPage),
            ],
          ),
        ),
      ),
    );
  }

  void _setCompactPage(int value) {
    if (value != compactPage) {
      _cleanupEmptyDraft();
    }
    setState(() => compactPage = value);
  }

  void _updateSelectedNoteBody() {
    if (syncingEditor) {
      return;
    }
    final index = notes.indexWhere((note) => note.id == selectedNoteId);
    if (index == -1 || notes[index].body == editor.text) {
      return;
    }
    setState(() {
      notes[index] = notes[index].copyWith(
        body: editor.text,
        updatedAt: DateTime.now(),
        wordCount: countWords(editor.text),
      );
    });
    _queuePersist();
  }

  void _selectNote(Note note, {int? page}) {
    _cleanupEmptyDraft(exceptNoteId: note.id);
    setState(() {
      selectedNoteId = note.id;
      compactPage = page ?? compactPage;
      syncingEditor = true;
      editor.text = note.body;
      syncingEditor = false;
    });
  }

  void _selectFolder(String folder) {
    _cleanupEmptyDraft();
    setState(() {
      selectedFolder = folder;
      compactPage = 0;
    });
    final candidates = visibleNotes;
    if (candidates.isNotEmpty && !candidates.any((note) => note.id == selectedNoteId)) {
      _selectNote(candidates.first);
    }
  }

  void _createNote() {
    if (selectedNote.isActive && selectedNote.title == 'Untitled note' && selectedNote.body.trim().isEmpty) {
      setState(() => compactPage = 1);
      return;
    }
    final folder = selectedFolder == 'All' || selectedFolder == 'Starred' || selectedFolder == 'Archive' || selectedFolder == 'Trash'
        ? 'Inbox'
        : selectedFolder;
    final now = DateTime.now();
    final note = Note(
      id: now.microsecondsSinceEpoch.toString(),
      title: 'Untitled note',
      folder: folder,
      tag: '#draft',
      summary: 'Empty draft',
      body: '',
      updatedAt: now,
      wordCount: 0,
      attachments: const [],
    );
    setState(() {
      notes = [note, ...notes];
      if (!customFolders.any((item) => item.toLowerCase() == folder.toLowerCase())) {
        customFolders = [...customFolders, folder]..sort();
      }
      selectedNoteId = note.id;
      selectedFolder = folder;
      compactPage = 1;
      syncingEditor = true;
      editor.text = note.body;
      syncingEditor = false;
    });
  }

  void _toggleStar() {
    final index = notes.indexWhere((note) => note.id == selectedNoteId);
    setState(() {
      notes[index] = notes[index].copyWith(starred: !notes[index].starred);
    });
    _queuePersist();
  }

  Future<void> _renameNote() async {
    final result = await showDialog<NoteEditResult>(
      context: context,
      builder: (context) => NoteDetailsDialog(
        title: selectedNote.title,
        folder: selectedNote.folder,
        tag: selectedNote.tag,
      ),
    );
    if (result == null || result.title.isEmpty || result.folder.isEmpty || result.tag.isEmpty) {
      return;
    }
    final index = notes.indexWhere((note) => note.id == selectedNoteId);
    final normalizedFolder = result.folder.trim();
    final normalizedTag = result.tag.trim().startsWith('#') ? result.tag.trim() : '#${result.tag.trim()}';
    setState(() {
      if (!customFolders.any((folder) => folder.toLowerCase() == normalizedFolder.toLowerCase())) {
        customFolders = [...customFolders, normalizedFolder]..sort();
      }
      notes[index] = notes[index].copyWith(
        title: result.title.trim(),
        folder: normalizedFolder,
        tag: normalizedTag,
        updatedAt: DateTime.now(),
      );
      selectedFolder = normalizedFolder;
    });
    _queuePersist();
  }

  void _deleteSelectedNote() {
    if (selectedNote.isTrashed) {
      _deleteForever(selectedNote);
    } else {
      _moveNoteToTrash(selectedNote);
    }
  }

  void _deleteNote(Note note) {
    if (note.isTrashed) {
      _deleteForever(note);
      return;
    }
    _moveNoteToTrash(note);
  }

  void _archiveListedNote(Note note) {
    _archiveNote(note);
  }

  void _archiveSelectedNote() {
    _archiveNote(selectedNote);
  }

  void _archiveNote(Note note) {
    final index = notes.indexWhere((item) => item.id == note.id);
    if (index == -1) {
      return;
    }
    final archived = note.copyWith(status: NoteStatus.archived, clearTrashedAt: true, updatedAt: DateTime.now());
    setState(() {
      notes[index] = archived;
      if (selectedNoteId == note.id) {
        _selectFallbackAfterMove(note.id);
      }
    });
    _queuePersist();
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('${note.title} archived.'),
        action: SnackBarAction(
          label: 'Undo',
          onPressed: () {
            setState(() {
              notes[index] = note;
              selectedNoteId = note.id;
              syncingEditor = true;
              editor.text = note.body;
              syncingEditor = false;
            });
            _queuePersist();
          },
        ),
      ),
    );
  }

  void _restoreSelectedNote() {
    _restoreNote(selectedNote);
  }

  void _restoreNote(Note note) {
    final index = notes.indexWhere((item) => item.id == note.id);
    if (index == -1) {
      return;
    }
    final restored = note.copyWith(status: NoteStatus.active, clearTrashedAt: true, updatedAt: DateTime.now());
    setState(() {
      notes[index] = restored;
      selectedFolder = restored.folder;
      selectedNoteId = restored.id;
      compactPage = 1;
      syncingEditor = true;
      editor.text = restored.body;
      syncingEditor = false;
    });
    _queuePersist();
    _showSnack('${note.title} restored.');
  }

  void _moveNoteToTrash(Note note) {
    final index = notes.indexWhere((item) => item.id == note.id);
    if (index == -1) {
      return;
    }
    final trashed = note.copyWith(status: NoteStatus.trashed, trashedAt: DateTime.now(), updatedAt: DateTime.now());
    setState(() {
      notes[index] = trashed;
      if (selectedNoteId == note.id) {
        _selectFallbackAfterMove(note.id);
      }
    });
    _queuePersist();
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('${note.title} moved to Trash.'),
        action: SnackBarAction(
          label: 'Undo',
          onPressed: () {
            setState(() {
              notes[index] = note;
              selectedNoteId = note.id;
              syncingEditor = true;
              editor.text = note.body;
              syncingEditor = false;
            });
            _queuePersist();
          },
        ),
      ),
    );
  }

  void _deleteForever(Note note) {
    final index = notes.indexWhere((item) => item.id == note.id);
    if (index == -1) {
      return;
    }
    setState(() {
      notes = notes.where((item) => item.id != note.id).toList();
      if (notes.isEmpty) {
        notes = [_newBlankNote()];
      }
      if (selectedNoteId == note.id) {
        _selectFallbackAfterMove(note.id);
      }
    });
    _queuePersist();
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('${note.title} deleted forever.'),
        action: SnackBarAction(
          label: 'Undo',
          onPressed: () {
            setState(() {
              notes = [...notes]..insert(index.clamp(0, notes.length).toInt(), note);
              selectedNoteId = note.id;
              syncingEditor = true;
              editor.text = note.body;
              syncingEditor = false;
            });
            _queuePersist();
          },
        ),
      ),
    );
  }

  Note _newBlankNote({String folder = 'Inbox'}) {
    final now = DateTime.now();
    return Note(
      id: now.microsecondsSinceEpoch.toString(),
      title: 'Untitled note',
      folder: folder,
      tag: '#draft',
      summary: 'Empty draft',
      body: '',
      updatedAt: now,
      wordCount: 0,
      attachments: const [],
    );
  }

  void _selectFallbackAfterMove(String movedId) {
    final visible = visibleNotes.where((note) => note.id != movedId).toList();
    final next = visible.isNotEmpty
        ? visible.first
        : notes.firstWhere((note) => note.id != movedId && note.isActive, orElse: () => notes.first);
    selectedNoteId = next.id;
    syncingEditor = true;
    editor.text = next.body;
    syncingEditor = false;
    compactPage = 0;
  }

  List<Note> _dropExpiredTrash(List<Note> source) {
    final now = DateTime.now();
    return source.where((note) {
      if (!note.isTrashed) {
        return true;
      }
      final trashedAt = note.trashedAt;
      if (trashedAt == null) {
        return true;
      }
      return now.difference(trashedAt).inDays < trashRetentionDays;
    }).toList();
  }

  int _readInt(Object? value, {required int fallback}) {
    if (value is int) {
      return value;
    }
    return int.tryParse(value?.toString() ?? '') ?? fallback;
  }

  bool _cleanupEmptyDraft({String? exceptNoteId}) {
    final current = selectedNote;
    if (current.id == exceptNoteId || !current.isEmptyDraft || notes.length == 1) {
      return false;
    }
    final remaining = notes.where((note) => note.id != current.id).toList();
    setState(() {
      notes = remaining;
      selectedNoteId = remaining.first.id;
      syncingEditor = true;
      editor.text = remaining.first.body;
      syncingEditor = false;
    });
    _queuePersist();
    return true;
  }

  Future<void> _createFolder() async {
    final result = await showDialog<FolderEditResult>(
      context: context,
      builder: (context) => const FolderDialog(),
    );
    final folder = result?.name.trim() ?? '';
    if (folder.isEmpty) {
      return;
    }
    if (folders.any((existing) => existing.toLowerCase() == folder.toLowerCase())) {
      _showSnack('$folder already exists.');
      _selectFolder(folders.firstWhere((existing) => existing.toLowerCase() == folder.toLowerCase()));
      return;
    }
    setState(() {
      customFolders = [...customFolders, folder]..sort();
      folderIconKeys = {...folderIconKeys, folder: result!.iconKey};
      selectedFolder = folder;
      compactPage = 0;
    });
    _queuePersist();
    _showSnack('$folder created.');
  }

  Future<void> _addAttachment() async {
    final result = await FilePicker.platform.pickFiles(
      allowMultiple: true,
      withData: false,
      type: FileType.any,
    );
    if (result == null || result.files.isEmpty) {
      return;
    }
    final files = result.files.map(AttachmentFile.fromPlatformFile).toList();
    final index = notes.indexWhere((note) => note.id == selectedNoteId);
    setState(() {
      notes[index] = notes[index].copyWith(
        attachments: [...notes[index].attachments, ...files],
        updatedAt: DateTime.now(),
      );
    });
    _queuePersist();
    _showSnack(files.length == 1 ? '${files.first.name} attached.' : '${files.length} files attached.');
  }

  void _removeAttachment(AttachmentFile file) {
    final index = notes.indexWhere((note) => note.id == selectedNoteId);
    setState(() {
      notes[index] = notes[index].copyWith(
        attachments: notes[index].attachments.where((item) => item.id != file.id).toList(),
      );
    });
    _queuePersist();
  }

  void _archiveAttachment(AttachmentFile file) {
    final index = notes.indexWhere((note) => note.id == selectedNoteId);
    if (index == -1) {
      return;
    }
    setState(() {
      notes[index] = notes[index].copyWith(
        attachments: notes[index]
            .attachments
            .map((item) => item.id == file.id ? item.copyWith(archived: true) : item)
            .toList(),
      );
    });
    _queuePersist();
    _showSnack('${file.name} archived.');
  }

  Future<void> _openAttachmentPreview(AttachmentFile file) async {
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (context) => AttachmentPreviewSheet(
        file: file,
        initialPosition: attachmentPositions[file.id] ?? 0,
        onPositionChanged: (value) {
          attachmentPositions = {...attachmentPositions, file.id: value};
          _queuePersist();
        },
        onShare: () => _shareAttachment(file),
        onShareAs: () => _shareAttachmentAs(file),
        onExport: () => _exportAttachment(file),
        onArchive: () {
          Navigator.pop(context);
          _archiveAttachment(file);
        },
        onDelete: () {
          Navigator.pop(context);
          _removeAttachment(file);
        },
      ),
    );
  }

  Future<void> _shareAttachment(AttachmentFile file) async {
    if (file.path != null && File(file.path!).existsSync()) {
      await SharePlus.instance.share(ShareParams(title: file.name, files: [XFile(file.path!)]));
      return;
    }
    await SharePlus.instance.share(ShareParams(title: file.name, text: '${file.name}\n${file.meta}'));
  }

  Future<void> _shareAttachmentAs(AttachmentFile file) async {
    final label = '${file.name}\nType: ${file.kindLabel}\nStored in Libre Notes';
    await SharePlus.instance.share(ShareParams(title: 'Libre Notes file', subject: file.name, text: label));
  }

  Future<void> _exportAttachment(AttachmentFile file) async {
    if (file.path == null || !File(file.path!).existsSync()) {
      _showSnack('This attachment was added before file paths were saved.');
      return;
    }
    final bytes = await File(file.path!).readAsBytes();
    final saved = await FilePicker.platform.saveFile(
      dialogTitle: 'Export ${file.name}',
      fileName: file.name,
      bytes: bytes,
    );
    if (saved != null) {
      _showSnack('${file.name} exported.');
    }
  }

  void _wrapSelection(String prefix, String suffix, String placeholder) {
    final selection = editor.selection;
    final source = editor.text;
    final hasSelection = selection.isValid && !selection.isCollapsed;
    final start = hasSelection ? selection.start : selection.baseOffset.clamp(0, source.length).toInt();
    final end = hasSelection ? selection.end : start;
    final selected = hasSelection ? source.substring(start, end) : placeholder;
    final inserted = '$prefix$selected$suffix';
    final next = source.replaceRange(start, end, inserted);
    editor.value = TextEditingValue(
      text: next,
      selection: TextSelection.collapsed(offset: start + inserted.length),
    );
  }

  void _insertAtCursor(String markdown) {
    final source = editor.text;
    final offset = editor.selection.baseOffset.clamp(0, source.length).toInt();
    final separator = offset > 0 && !source.substring(0, offset).endsWith('\n') ? '\n' : '';
    final inserted = '$separator$markdown';
    editor.value = TextEditingValue(
      text: source.replaceRange(offset, offset, inserted),
      selection: TextSelection.collapsed(offset: offset + inserted.length),
    );
  }

  Future<void> _insertLink() async {
    final result = await showDialog<LinkEditResult>(
      context: context,
      builder: (context) => const LinkDialog(),
    );
    if (result == null || result.url.isEmpty) {
      return;
    }
    _wrapSelection('[', '](${result.url})', result.label.isEmpty ? 'link' : result.label);
  }

  Future<void> _insertImage() async {
    final result = await FilePicker.platform.pickFiles(
      allowMultiple: false,
      withData: false,
      type: FileType.image,
    );
    if (result == null || result.files.isEmpty) {
      return;
    }
    final file = AttachmentFile.fromPlatformFile(result.files.first);
    final index = notes.indexWhere((note) => note.id == selectedNoteId);
    setState(() {
      notes[index] = notes[index].copyWith(
        attachments: [...notes[index].attachments, file],
        updatedAt: DateTime.now(),
      );
    });
    _insertAtCursor('![${file.name}](${file.name})');
    _queuePersist();
  }

  void _setSortMode(String value) {
    setState(() => sortMode = value);
    _queuePersist();
  }

  void _setTrashRetentionDays(int value) {
    setState(() {
      trashRetentionDays = value;
      notes = _dropExpiredTrash(notes);
      if (notes.isEmpty) {
        notes = [_newBlankNote()];
      }
      if (!notes.any((note) => note.id == selectedNoteId)) {
        selectedNoteId = notes.firstWhere((note) => note.isActive, orElse: () => notes.first).id;
        syncingEditor = true;
        editor.text = selectedNote.body;
        syncingEditor = false;
      }
    });
    _queuePersist();
  }

  void _setPreviewCards(bool value) {
    setState(() => previewCards = value);
  }

  Color colorForTag(String tag) {
    return Color(tagColorIndexes[tag] ?? systemColors[tag.hashCode.abs() % systemColors.length].toARGB32());
  }

  String folderIconKey(String folder) {
    return folderIconKeys[folder] ?? seedFolderIcons[folder] ?? 'folder';
  }

  Future<void> _chooseTagColor(String tag) async {
    final selected = await showModalBottomSheet<Color>(
      context: context,
      showDragHandle: true,
      builder: (context) => ColorPickerSheet(
        title: tag,
        initialColor: colorForTag(tag),
      ),
    );
    if (selected == null) {
      return;
    }
    setState(() => tagColorIndexes = {...tagColorIndexes, tag: selected.toARGB32()});
    _queuePersist();
  }

  Future<void> _chooseAccentColor() async {
    final selected = await showModalBottomSheet<Color>(
      context: context,
      showDragHandle: true,
      builder: (context) => ColorPickerSheet(title: 'Accent color', initialColor: accent),
    );
    if (selected != null) {
      onAccentChanged(selected);
    }
  }

  void _saveNote() {
    final index = notes.indexWhere((note) => note.id == selectedNoteId);
    setState(() {
      notes[index] = notes[index].copyWith(updatedAt: DateTime.now(), wordCount: countWords(editor.text));
    });
    _queuePersist();
    _showSnack('${selectedNote.title} saved locally.');
  }

  Future<void> _shareNote() async {
    final note = selectedNote;
    final params = ShareParams(
      title: note.title,
      subject: note.title,
      text: note.body,
    );
    await SharePlus.instance.share(params);
  }

  Future<void> _exportSelectedNote([String? type]) async {
    final exportType = type ?? preferredExportType;
    final bytes = await _noteExportBytes(selectedNote, exportType);
    final path = await FilePicker.platform.saveFile(
      dialogTitle: 'Export ${selectedNote.title}',
      fileName: '${safeFileName(selectedNote.title)}.$exportType',
      bytes: bytes,
      type: FileType.custom,
      allowedExtensions: [exportType],
    );
    if (path != null) {
      _showSnack('${selectedNote.title} exported.');
    }
  }

  Future<void> _shareSelectedNoteAs([String? type]) async {
    final exportType = type ?? preferredExportType;
    if (exportType == 'pdf') {
      await Printing.sharePdf(
        bytes: await _buildNotePdfBytes(selectedNote),
        filename: '${safeFileName(selectedNote.title)}.pdf',
      );
      return;
    }
    final fileName = '${safeFileName(selectedNote.title)}.$exportType';
    final bytes = await _noteExportBytes(selectedNote, exportType);
    await SharePlus.instance.share(ShareParams(
      title: selectedNote.title,
      subject: fileName,
      files: [XFile.fromData(bytes, name: fileName, mimeType: _exportMimeType(exportType))],
    ));
  }

  String _noteExportPayload(Note note, String type) {
    return switch (type) {
      'txt' => note.body,
      'json' => const JsonEncoder.withIndent('  ').convert(note.toJson()),
      'html' => '<!doctype html><html><head><meta charset="utf-8"><title>${htmlEscape.convert(note.title)}</title></head><body><pre>${htmlEscape.convert(note.body)}</pre></body></html>',
      'doc' => '<html><head><meta charset="utf-8"><title>${htmlEscape.convert(note.title)}</title></head><body><h1>${htmlEscape.convert(note.title)}</h1><pre>${htmlEscape.convert(note.body)}</pre></body></html>',
      _ => note.body,
    };
  }

  Future<Uint8List> _noteExportBytes(Note note, String type) async {
    if (type == 'pdf') {
      return _buildNotePdfBytes(note);
    }
    return Uint8List.fromList(utf8.encode(_noteExportPayload(note, type)));
  }

  Future<Uint8List> _buildNotePdfBytes(Note note) async {
    final doc = pw.Document();
    final lines = note.body.split('\n');
    doc.addPage(
      pw.MultiPage(
        pageFormat: PdfPageFormat.a4,
        margin: const pw.EdgeInsets.all(36),
        build: (context) => [
          pw.Text(note.title, style: pw.TextStyle(fontSize: 26, fontWeight: pw.FontWeight.bold)),
          pw.SizedBox(height: 6),
          pw.Text('${note.folder}  •  ${note.tag}  •  ${relativeDate(note.updatedAt)}', style: const pw.TextStyle(fontSize: 10, color: PdfColors.grey700)),
          pw.Divider(),
          ...lines.map((line) {
            final trimmed = line.trimRight();
            if (trimmed.startsWith('# ')) {
              return pw.Padding(
                padding: const pw.EdgeInsets.only(top: 14, bottom: 6),
                child: pw.Text(trimmed.substring(2), style: pw.TextStyle(fontSize: 20, fontWeight: pw.FontWeight.bold)),
              );
            }
            if (trimmed.startsWith('## ')) {
              return pw.Padding(
                padding: const pw.EdgeInsets.only(top: 12, bottom: 4),
                child: pw.Text(trimmed.substring(3), style: pw.TextStyle(fontSize: 16, fontWeight: pw.FontWeight.bold)),
              );
            }
            if (trimmed.isEmpty) {
              return pw.SizedBox(height: 8);
            }
            return pw.Padding(
              padding: const pw.EdgeInsets.only(bottom: 4),
              child: pw.Text(trimmed.replaceAll(RegExp(r'[*_`#>-]'), ''), style: const pw.TextStyle(fontSize: 11)),
            );
          }),
        ],
      ),
    );
    return doc.save();
  }

  String _exportMimeType(String type) {
    return switch (type) {
      'txt' => 'text/plain',
      'html' => 'text/html',
      'doc' => 'application/msword',
      'json' => 'application/json',
      _ => 'text/markdown',
    };
  }

  void _setPreferredExportType(String value) {
    setState(() => preferredExportType = value);
    _queuePersist();
  }

  void _setReadingMode(bool value) {
    setState(() => readingMode = value);
    _queuePersist();
  }

  void _setHighContrastText(bool value) {
    setState(() => highContrastText = value);
    _queuePersist();
  }

  void _setReduceMotion(bool value) {
    setState(() => reduceMotion = value);
    _queuePersist();
  }

  void _setUserProfile(String name, String handle) {
    setState(() {
      userName = name.trim().isEmpty ? 'Local writer' : name.trim();
      userHandle = handle.trim().isEmpty ? 'offline-vault' : handle.trim();
    });
    _queuePersist();
  }

  void _setVaultEncryption({required bool enabled, String? password}) {
    setState(() {
      vaultEncrypted = enabled;
      if (password != null && password.isNotEmpty) {
        vaultPassword = password;
      }
    });
    _queuePersist();
  }

  Future<void> _chooseBackgroundVisual() async {
    final result = await FilePicker.platform.pickFiles(
      dialogTitle: 'Choose background image or GIF',
      type: FileType.custom,
      allowedExtensions: ['png', 'jpg', 'jpeg', 'webp', 'gif'],
      withData: false,
    );
    if (result == null || result.files.isEmpty) {
      return;
    }
    final path = result.files.first.path;
    if (path == null) {
      _showSnack('Could not read that background file.');
      return;
    }
    setState(() {
      backgroundVisualPath = path;
      reduceMotion = false;
    });
    _queuePersist();
  }

  void _clearBackgroundVisual() {
    setState(() => backgroundVisualPath = null);
    _queuePersist();
  }

  Future<void> _exportBackup() async {
    final bytes = Uint8List.fromList(utf8.encode(_encodedVault()));
    final fileName = 'libre-notes-backup-${DateTime.now().millisecondsSinceEpoch}.json';
    final path = await FilePicker.platform.saveFile(
      dialogTitle: 'Save Libre Notes backup',
      fileName: fileName,
      bytes: bytes,
      type: FileType.custom,
      allowedExtensions: ['json'],
    );
    if (path != null) {
      _showSnack('Backup saved.');
    }
  }

  Future<void> _importBackup() async {
    final result = await FilePicker.platform.pickFiles(
      dialogTitle: 'Restore Libre Notes backup',
      type: FileType.custom,
      allowedExtensions: ['json'],
      withData: true,
    );
    if (result == null || result.files.isEmpty) {
      return;
    }
    final bytes = result.files.first.bytes;
    if (bytes == null) {
      _showSnack('Could not read that backup file.');
      return;
    }
    try {
      final decoded = jsonDecode(utf8.decode(bytes)) as Map<String, dynamic>;
      final settings = (decoded['settings'] as Map<String, dynamic>?) ?? const {};
      final restoredNotes = ((decoded['notes'] as List<dynamic>?) ?? const [])
          .map((item) => Note.fromJson(item as Map<String, dynamic>))
          .toList();
      final restoredFolders = ((decoded['folders'] as List<dynamic>?) ?? const [])
          .map((folder) => folder.toString().trim())
          .where((folder) => folder.isNotEmpty)
          .toSet()
          .toList()
        ..sort();
      final restoredTagColors = Map<String, dynamic>.from((decoded['tagColors'] as Map?) ?? (settings['tagColors'] as Map?) ?? const {})
          .map((key, value) {
        final parsed = value is int ? value : int.tryParse(value.toString()) ?? 0;
        return MapEntry(key, parsed < systemColors.length ? systemColors[parsed].toARGB32() : parsed);
      });
      final restoredFolderIcons = Map<String, dynamic>.from((settings['folderIcons'] as Map?) ?? (decoded['folderIcons'] as Map?) ?? const {})
          .map((key, value) => MapEntry(key, value.toString()));
      if (restoredNotes.isEmpty) {
        _showSnack('Backup did not contain any notes.');
        return;
      }
      trashRetentionDays = _readInt(decoded['trashRetentionDays'] ?? settings['trashRetentionDays'], fallback: trashRetentionDays);
      preferredExportType = (settings['preferredExportType'] as String?) ?? preferredExportType;
      readingMode = settings['readingMode'] as bool? ?? readingMode;
      highContrastText = settings['highContrastText'] as bool? ?? highContrastText;
      reduceMotion = settings['reduceMotion'] as bool? ?? reduceMotion;
      backgroundVisualPath = settings['backgroundVisualPath'] as String? ?? backgroundVisualPath;
      userName = (settings['userName'] as String?) ?? userName;
      userHandle = (settings['userHandle'] as String?) ?? userHandle;
      vaultFolderPath = settings['vaultFolderPath'] as String? ?? vaultFolderPath;
      vaultEncrypted = settings['vaultEncrypted'] as bool? ?? vaultEncrypted;
      final rawPositions = Map<String, dynamic>.from(settings['attachmentPositions'] as Map? ?? const {});
      attachmentPositions = rawPositions.map((key, value) => MapEntry(key, value is num ? value.toDouble() : double.tryParse(value.toString()) ?? 0));
      final cleanedNotes = _dropExpiredTrash(restoredNotes);
      _applyBackupSettings(settings);
      setState(() {
        notes = cleanedNotes.isEmpty ? [_newBlankNote()] : cleanedNotes;
        customFolders = restoredFolders.isEmpty ? [...seedFolders] : restoredFolders;
        tagColorIndexes = restoredTagColors;
        folderIconKeys = {...seedFolderIcons, ...restoredFolderIcons};
        selectedFolder = 'All';
        selectedNoteId = notes.firstWhere((note) => note.isActive, orElse: () => notes.first).id;
        syncingEditor = true;
        editor.text = selectedNote.body;
        syncingEditor = false;
        compactPage = 0;
      });
      _queuePersist();
      _showSnack('Backup restored.');
    } catch (_) {
      _showSnack('That backup file is not valid.');
    }
  }

  Future<void> _importMarkdownFiles() async {
    final result = await FilePicker.platform.pickFiles(
      dialogTitle: 'Import Markdown notes',
      allowMultiple: true,
      type: FileType.custom,
      allowedExtensions: ['md', 'markdown', 'txt'],
      withData: true,
    );
    if (result == null || result.files.isEmpty) {
      return;
    }
    final imported = <Note>[];
    for (final file in result.files) {
      final bytes = file.bytes ?? (file.path == null ? null : await File(file.path!).readAsBytes());
      if (bytes == null) {
        continue;
      }
      final body = utf8.decode(bytes, allowMalformed: true);
      final title = markdownTitle(body).trim().isEmpty ? baseNameWithoutExtension(file.name) : markdownTitle(body);
      final now = DateTime.now();
      imported.add(Note(
        id: '${now.microsecondsSinceEpoch}-${imported.length}',
        title: title,
        folder: selectedFolder == 'All' || selectedFolder == 'Starred' || selectedFolder == 'Archive' || selectedFolder == 'Trash' ? 'Inbox' : selectedFolder,
        tag: '#imported',
        summary: body.split('\n').firstWhere((line) => line.trim().isNotEmpty, orElse: () => 'Imported Markdown note'),
        body: body,
        updatedAt: now,
        wordCount: countWords(body),
        attachments: const [],
      ));
    }
    if (imported.isEmpty) {
      _showSnack('No Markdown files could be imported.');
      return;
    }
    setState(() {
      notes = [...imported, ...notes];
      selectedNoteId = imported.first.id;
      selectedFolder = imported.first.folder;
      if (!customFolders.any((folder) => folder.toLowerCase() == selectedFolder.toLowerCase())) {
        customFolders = [...customFolders, selectedFolder]..sort();
      }
      syncingEditor = true;
      editor.text = imported.first.body;
      syncingEditor = false;
      compactPage = 1;
    });
    _queuePersist();
    _showSnack(imported.length == 1 ? 'Markdown note imported.' : '${imported.length} Markdown notes imported.');
  }

  void _applyBackupSettings(Map<String, dynamic> settings) {
    final themeName = settings['themeMode'] as String?;
    if (themeName != null) {
      onThemeModeChanged(ThemeMode.values.firstWhere((mode) => mode.name == themeName, orElse: () => themeMode));
    }
    final accentValue = settings['accent'] as int?;
    if (accentValue != null) {
      onAccentChanged(Color(accentValue));
    }
    final backupAmoled = settings['amoledDark'] as bool?;
    if (backupAmoled != null) {
      onAmoledChanged(backupAmoled);
    }
    final backupPureBlack = settings['pureBlack'] as bool?;
    if (backupPureBlack != null) {
      onPureBlackChanged(backupPureBlack);
    }
    final backupTint = settings['accentTintBackground'] as bool?;
    if (backupTint != null) {
      onAccentTintChanged(backupTint);
    }
    final backupLetters = settings['letterIcons'] as bool?;
    if (backupLetters != null) {
      onLetterIconsChanged(backupLetters);
    }
    final backupDense = settings['denseNotes'] as bool?;
    if (backupDense != null) {
      onDenseNotesChanged(backupDense);
    }
  }

  void _showSnack(String message) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
  }

  void _openSettings() {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (context) => _SettingsSheet(home: this),
    );
  }
}

class _CompactHome extends StatelessWidget {
  const _CompactHome({required this.home});

  final _LibreNotesHomeState home;

  @override
  Widget build(BuildContext context) {
    return IndexedStack(
      sizing: StackFit.expand,
      index: home.compactPage,
      children: [
        _NotesPane(home: home, fullWidth: true),
        _EditorPane(home: home, compact: true),
        PreviewPane(home: home, note: home.selectedNote, compact: true),
        _AttachmentsPane(home: home, compact: true),
      ],
    );
  }
}

class _NavPill extends StatelessWidget {
  const _NavPill({
    required this.index,
    required this.selectedIndex,
    required this.icon,
    required this.selectedIcon,
    required this.label,
    required this.onTap,
  });

  final int index;
  final int selectedIndex;
  final IconData icon;
  final IconData selectedIcon;
  final String label;
  final ValueChanged<int> onTap;

  @override
  Widget build(BuildContext context) {
    final selected = index == selectedIndex;
    final scheme = Theme.of(context).colorScheme;
    return Expanded(
      child: InkWell(
        borderRadius: BorderRadius.circular(22),
        onTap: () => onTap(index),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 180),
          curve: Curves.easeOut,
          height: 52,
          decoration: BoxDecoration(
            color: selected ? scheme.primaryContainer : Colors.transparent,
            borderRadius: BorderRadius.circular(22),
          ),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(selected ? selectedIcon : icon, size: 21, color: selected ? scheme.onPrimaryContainer : scheme.onSurfaceVariant),
              const SizedBox(height: 2),
              Text(
                label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 11,
                  fontWeight: selected ? FontWeight.w800 : FontWeight.w600,
                  color: selected ? scheme.onPrimaryContainer : scheme.onSurfaceVariant,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _MediumHome extends StatelessWidget {
  const _MediumHome({required this.home});

  final _LibreNotesHomeState home;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        _FolderRail(home: home, railOnly: true),
        SizedBox(width: 310, child: _NotesPane(home: home)),
        Expanded(
          child: _EditorPreviewTabs(home: home),
        ),
      ],
    );
  }
}

class _ExpandedHome extends StatelessWidget {
  const _ExpandedHome({required this.home});

  final _LibreNotesHomeState home;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        SizedBox(width: 252, child: _FolderRail(home: home)),
        SizedBox(width: home.denseNotes ? 280 : 332, child: _NotesPane(home: home)),
        Expanded(
          child: Column(
            children: [
              _WorkspaceToolbar(home: home),
              Expanded(
                child: Row(
                  children: [
                    Expanded(child: _EditorPane(home: home)),
                    const VerticalDivider(width: 1),
                    Expanded(child: PreviewPane(home: home, note: home.selectedNote)),
                  ],
                ),
              ),
              _AttachmentsPane(home: home),
              StatusBar(note: home.selectedNote),
            ],
          ),
        ),
      ],
    );
  }
}

class _AppDrawer extends StatelessWidget {
  const _AppDrawer({required this.home});

  final _LibreNotesHomeState home;

  @override
  Widget build(BuildContext context) {
    return NavigationDrawer(
      selectedIndex: home.folders.indexOf(home.selectedFolder).clamp(0, home.folders.length - 1).toInt(),
      onDestinationSelected: (index) {
        Navigator.pop(context);
        home._selectFolder(home.folders[index]);
      },
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 18, 16, 18),
          child: Row(
            children: [
              CircleAvatar(
                backgroundColor: home.accent,
                foregroundColor: Colors.white,
                child: const Icon(Icons.shield_outlined),
              ),
              const SizedBox(width: 12),
              const Expanded(
                child: Text('Libre Notes', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w900)),
              ),
            ],
          ),
        ),
        for (final folder in home.folders)
          NavigationDrawerDestination(
            icon: Icon(folderIcon(home.folderIconKey(folder))),
            selectedIcon: Icon(folderIcon(home.folderIconKey(folder), selected: true)),
            label: Text(folder),
          ),
        const Divider(),
        ListTile(
          leading: const Icon(Icons.create_new_folder_outlined),
          title: const Text('New folder'),
          onTap: () {
            Navigator.pop(context);
            home._createFolder();
          },
        ),
        ListTile(
          leading: const Icon(Icons.upload_file_outlined),
          title: const Text('Import Markdown'),
          onTap: () {
            Navigator.pop(context);
            home._importMarkdownFiles();
          },
        ),
        ListTile(
          leading: const Icon(Icons.settings_outlined),
          title: const Text('Settings'),
          onTap: () {
            Navigator.pop(context);
            home._openSettings();
          },
        ),
      ],
    );
  }
}

class _FolderRail extends StatelessWidget {
  const _FolderRail({required this.home, this.railOnly = false});

  final _LibreNotesHomeState home;
  final bool railOnly;

  @override
  Widget build(BuildContext context) {
    final selectedIndex = home.folders.indexOf(home.selectedFolder).clamp(0, home.folders.length - 1).toInt();
    if (railOnly) {
      return NavigationRail(
        selectedIndex: selectedIndex,
        onDestinationSelected: (index) => home._selectFolder(home.folders[index]),
        labelType: NavigationRailLabelType.selected,
        leading: Padding(
          padding: const EdgeInsets.only(bottom: 12),
          child: IconButton.filled(
            tooltip: 'New note',
            onPressed: home._createNote,
            icon: const Icon(Icons.add),
          ),
        ),
        trailing: Expanded(
          child: Align(
            alignment: Alignment.bottomCenter,
            child: Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: IconButton(
                tooltip: 'Settings',
                onPressed: home._openSettings,
                icon: const Icon(Icons.settings_outlined),
              ),
            ),
          ),
        ),
        destinations: [
          for (final folder in home.folders)
            NavigationRailDestination(
              icon: Icon(folderIcon(home.folderIconKey(folder))),
              selectedIcon: Icon(folderIcon(home.folderIconKey(folder), selected: true)),
              label: Text(folder),
            ),
        ],
      );
    }

    return DecoratedBox(
      decoration: BoxDecoration(
        border: Border(right: BorderSide(color: Theme.of(context).dividerColor.withValues(alpha: 0.35))),
      ),
      child: ListView(
        padding: const EdgeInsets.fromLTRB(14, 16, 14, 16),
        children: [
          Row(
            children: [
              CircleAvatar(
                backgroundColor: home.accent,
                foregroundColor: Colors.white,
                child: const Icon(Icons.shield_outlined),
              ),
              const SizedBox(width: 10),
              const Expanded(
                child: Text('Libre Notes', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w900)),
              ),
            ],
          ),
          const SizedBox(height: 18),
          FilledButton.icon(
            onPressed: home._createNote,
            icon: const Icon(Icons.note_add_outlined),
            label: const Text('New note'),
          ),
          OutlinedButton.icon(
            onPressed: home._createFolder,
            icon: const Icon(Icons.create_new_folder_outlined),
            label: const Text('New folder'),
          ),
          OutlinedButton.icon(
            onPressed: home._importMarkdownFiles,
            icon: const Icon(Icons.upload_file_outlined),
            label: const Text('Import MD'),
          ),
          const SizedBox(height: 8),
          Row(
            children: [
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: home._exportBackup,
                  icon: const Icon(Icons.backup_outlined),
                  label: const Text('Backup'),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: home._importBackup,
                  icon: const Icon(Icons.restore_outlined),
                  label: const Text('Restore'),
                ),
              ),
            ],
          ),
          const SizedBox(height: 14),
          Text('Vault', style: Theme.of(context).textTheme.labelLarge),
          const SizedBox(height: 8),
          for (final folder in home.folders)
            FolderTile(
              name: folder,
              selected: folder == home.selectedFolder,
              letterIcons: home.letterIcons,
              iconKey: home.folderIconKey(folder),
              onTap: () => home._selectFolder(folder),
            ),
          const SizedBox(height: 18),
          Text('Tags', style: Theme.of(context).textTheme.labelLarge),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final tag in notesTags(home.notes)) ActionChip(label: Text(tag), onPressed: () => home.search.text = tag),
            ],
          ),
          const SizedBox(height: 20),
          ListTile(
            leading: const Icon(Icons.settings_outlined),
            title: const Text('Settings'),
            onTap: home._openSettings,
          ),
        ],
      ),
    );
  }
}

class FolderTile extends StatelessWidget {
  const FolderTile({
    super.key,
    required this.name,
    required this.selected,
    required this.letterIcons,
    required this.iconKey,
    required this.onTap,
  });

  final String name;
  final bool selected;
  final bool letterIcons;
  final String iconKey;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      selected: selected,
      dense: true,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
      leading: letterIcons && name != 'All' && name != 'Starred' && name != 'Archive' && name != 'Trash'
          ? CircleAvatar(
              radius: 14,
              child: Text(name.substring(0, 1), style: const TextStyle(fontWeight: FontWeight.w900)),
            )
          : Icon(folderIcon(iconKey, selected: selected)),
      title: Text(name, maxLines: 1, overflow: TextOverflow.ellipsis),
      onTap: onTap,
    );
  }
}

class _NotesPane extends StatelessWidget {
  const _NotesPane({required this.home, this.fullWidth = false});

  final _LibreNotesHomeState home;
  final bool fullWidth;

  @override
  Widget build(BuildContext context) {
    final notes = home.visibleNotes;
    return DecoratedBox(
      decoration: BoxDecoration(
        border: fullWidth ? null : Border(right: BorderSide(color: Theme.of(context).dividerColor.withValues(alpha: 0.35))),
      ),
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(14, 12, 14, 8),
            child: Column(
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        home.selectedFolder,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w900),
                      ),
                    ),
                    PopupMenuButton<String>(
                      tooltip: 'Sort notes',
                      icon: const Icon(Icons.sort),
                      onSelected: home._setSortMode,
                      itemBuilder: (context) => const [
                        PopupMenuItem(value: 'updatedDesc', child: Text('Newest first')),
                        PopupMenuItem(value: 'updatedAsc', child: Text('Oldest first')),
                        PopupMenuItem(value: 'title', child: Text('Title A-Z')),
                      ],
                    ),
                    IconButton(tooltip: 'Dense notes', onPressed: () => home.onDenseNotesChanged(!home.denseNotes), icon: const Icon(Icons.view_headline)),
                  ],
                ),
                SearchBar(
                  controller: home.search,
                  leading: const Icon(Icons.search),
                  hintText: 'Search notes',
                  trailing: home.query.isEmpty
                      ? null
                      : [
                          IconButton(
                            tooltip: 'Clear',
                            onPressed: home.search.clear,
                            icon: const Icon(Icons.close),
                          ),
                        ],
                ),
              ],
            ),
          ),
          Expanded(
            child: notes.isEmpty
                ? EmptyState(
                    icon: Icons.search_off,
                    title: 'No notes found',
                    action: 'Create note',
                    onPressed: home._createNote,
                  )
                : ListView.separated(
                    padding: const EdgeInsets.fromLTRB(14, 8, 14, 90),
                    itemCount: notes.length,
                    separatorBuilder: (_, __) => const SizedBox(height: 10),
                    itemBuilder: (context, index) {
                      final note = notes[index];
                      return NoteCard(
                        note: note,
                        dense: home.denseNotes,
                        selected: note.id == home.selectedNoteId,
                        tagColor: home.colorForTag(note.tag),
                        onTap: () => home._selectNote(note, page: 1),
                        onStar: () {
                          home._selectNote(note);
                          home._toggleStar();
                        },
                        onArchive: () => note.isArchived ? home._restoreNote(note) : home._archiveListedNote(note),
                        onDelete: () => home._deleteNote(note),
                        onTagColor: () => home._chooseTagColor(note.tag),
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }
}

class NoteCard extends StatelessWidget {
  const NoteCard({
    super.key,
    required this.note,
    required this.dense,
    required this.selected,
    required this.tagColor,
    required this.onTap,
    required this.onStar,
    required this.onArchive,
    required this.onDelete,
    required this.onTagColor,
  });

  final Note note;
  final bool dense;
  final bool selected;
  final Color tagColor;
  final VoidCallback onTap;
  final VoidCallback onStar;
  final VoidCallback onArchive;
  final VoidCallback onDelete;
  final VoidCallback onTagColor;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final fileCount = note.attachments.where((file) => !file.archived).length;
    return Dismissible(
      key: ValueKey(note.id),
      direction: DismissDirection.horizontal,
      background: Container(
        alignment: Alignment.centerLeft,
        padding: const EdgeInsets.only(left: 18),
        decoration: BoxDecoration(
          color: scheme.secondaryContainer,
          borderRadius: BorderRadius.circular(8),
        ),
        child: Icon(Icons.archive_outlined, color: scheme.onSecondaryContainer),
      ),
      secondaryBackground: Container(
        alignment: Alignment.centerRight,
        padding: const EdgeInsets.only(right: 18),
        decoration: BoxDecoration(
          color: scheme.errorContainer,
          borderRadius: BorderRadius.circular(8),
        ),
        child: Icon(Icons.delete_outline, color: scheme.onErrorContainer),
      ),
      onDismissed: (direction) {
        if (direction == DismissDirection.startToEnd) {
          onArchive();
        } else {
          onDelete();
        }
      },
      child: Card(
        margin: EdgeInsets.zero,
        color: selected ? scheme.primaryContainer.withValues(alpha: 0.42) : scheme.surfaceContainerHighest.withValues(alpha: 0.45),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(8),
          side: BorderSide(color: selected ? scheme.primary.withValues(alpha: 0.55) : scheme.outlineVariant),
        ),
        child: InkWell(
          borderRadius: BorderRadius.circular(8),
          onTap: onTap,
          child: Padding(
            padding: EdgeInsets.all(dense ? 10 : 14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        note.title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontWeight: FontWeight.w900),
                      ),
                    ),
                    IconButton(
                      tooltip: note.starred ? 'Unstar' : 'Star',
                      visualDensity: VisualDensity.compact,
                      onPressed: onStar,
                      icon: Icon(note.starred ? Icons.star : Icons.star_border, size: 20),
                    ),
                  ],
                ),
                if (!dense) ...[
                  const SizedBox(height: 6),
                  Text(note.summary, maxLines: 1, overflow: TextOverflow.ellipsis),
                  const SizedBox(height: 10),
                ],
                Row(
                  children: [
                    InkWell(
                      onTap: onTagColor,
                      borderRadius: BorderRadius.circular(999),
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
                        decoration: BoxDecoration(
                          color: tagColor.withValues(alpha: 0.18),
                          borderRadius: BorderRadius.circular(999),
                          border: Border.all(color: tagColor.withValues(alpha: 0.45)),
                        ),
                        child: Text(
                          note.tag,
                          style: TextStyle(fontSize: 12, fontWeight: FontWeight.w800, color: tagColor),
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        '${note.folder}  -  ${relativeDate(note.updatedAt)}  -  ${note.wordCount} words${fileCount == 0 ? '' : '  -  $fileCount files'}',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: Theme.of(context).textTheme.labelMedium,
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _WorkspaceToolbar extends StatelessWidget {
  const _WorkspaceToolbar({required this.home});

  final _LibreNotesHomeState home;

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 64,
      padding: const EdgeInsets.symmetric(horizontal: 14),
      decoration: BoxDecoration(
        border: Border(bottom: BorderSide(color: Theme.of(context).dividerColor.withValues(alpha: 0.35))),
      ),
      child: Row(
        children: [
          Expanded(
            child: Text(
              home.selectedNote.title,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w900),
            ),
          ),
          IconButton.filledTonal(tooltip: 'Rename', onPressed: home._renameNote, icon: const Icon(Icons.drive_file_rename_outline)),
          IconButton.filledTonal(tooltip: 'Save', onPressed: home._saveNote, icon: const Icon(Icons.save_outlined)),
          IconButton.filledTonal(tooltip: 'Share', onPressed: home._shareNote, icon: const Icon(Icons.share_outlined)),
          IconButton.filledTonal(tooltip: 'Attach', onPressed: home._addAttachment, icon: const Icon(Icons.attach_file)),
          IconButton.filledTonal(
            tooltip: home.selectedNote.isArchived || home.selectedNote.isTrashed ? 'Restore' : 'Archive',
            onPressed: home.selectedNote.isArchived || home.selectedNote.isTrashed ? home._restoreSelectedNote : home._archiveSelectedNote,
            icon: Icon(home.selectedNote.isArchived || home.selectedNote.isTrashed ? Icons.unarchive_outlined : Icons.archive_outlined),
          ),
          IconButton.filledTonal(
            tooltip: home.selectedNote.isTrashed ? 'Delete forever' : 'Move to Trash',
            onPressed: home._deleteSelectedNote,
            icon: Icon(home.selectedNote.isTrashed ? Icons.delete_forever_outlined : Icons.delete_outline),
          ),
          IconButton.filledTonal(tooltip: 'Settings', onPressed: home._openSettings, icon: const Icon(Icons.palette_outlined)),
        ],
      ),
    );
  }
}

class _EditorPreviewTabs extends StatelessWidget {
  const _EditorPreviewTabs({required this.home});

  final _LibreNotesHomeState home;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        _WorkspaceToolbar(home: home),
        TabBar(
          controller: home.editorTabs,
          tabs: const [
            Tab(icon: Icon(Icons.edit_outlined), text: 'Edit'),
            Tab(icon: Icon(Icons.visibility_outlined), text: 'Preview'),
          ],
        ),
        Expanded(
          child: TabBarView(
            controller: home.editorTabs,
            children: [
              _EditorPane(home: home),
              PreviewPane(home: home, note: home.selectedNote),
            ],
          ),
        ),
        _AttachmentsPane(home: home),
      ],
    );
  }
}

class _EditorPane extends StatelessWidget {
  const _EditorPane({required this.home, this.compact = false});

  final _LibreNotesHomeState home;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.all(compact ? 12 : 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (compact)
            Padding(
              padding: const EdgeInsets.only(bottom: 10),
              child: _NoteHeader(home: home),
            ),
          _MarkdownToolbar(home: home),
          const SizedBox(height: 10),
          Expanded(
            child: TextField(
              controller: home.editor,
              expands: true,
              maxLines: null,
              minLines: null,
              textAlignVertical: TextAlignVertical.top,
              keyboardType: TextInputType.multiline,
              style: TextStyle(
                fontFamily: 'monospace',
                fontSize: compact ? 15 : 16,
                height: 1.45,
              ),
              decoration: InputDecoration(
                filled: true,
                hintText: 'Write Markdown here...',
                alignLabelWithHint: true,
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(8)),
                contentPadding: EdgeInsets.all(compact ? 14 : 18),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _MarkdownToolbar extends StatelessWidget {
  const _MarkdownToolbar({required this.home});

  final _LibreNotesHomeState home;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(
        children: [
          _ToolButton(
            tooltip: 'Bold',
            icon: Icons.format_bold,
            onPressed: () => home._wrapSelection('**', '**', 'bold text'),
          ),
          _ToolButton(
            tooltip: 'Italic',
            icon: Icons.format_italic,
            onPressed: () => home._wrapSelection('_', '_', 'italic text'),
          ),
          _ToolButton(
            tooltip: 'Heading',
            icon: Icons.title,
            onPressed: () => home._insertAtCursor('## Heading'),
          ),
          _ToolButton(
            tooltip: 'Checklist',
            icon: Icons.check_box_outlined,
            onPressed: () => home._insertAtCursor('- [ ] Task'),
          ),
          _ToolButton(
            tooltip: 'Quote',
            icon: Icons.format_quote,
            onPressed: () => home._insertAtCursor('> Quote'),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 6),
            child: SizedBox(height: 28, child: VerticalDivider(color: scheme.outlineVariant)),
          ),
          _ToolButton(
            tooltip: 'Insert link',
            icon: Icons.link,
            onPressed: () => home._insertLink(),
          ),
          _ToolButton(
            tooltip: 'Insert image',
            icon: Icons.image_outlined,
            onPressed: () => home._insertImage(),
          ),
        ],
      ),
    );
  }
}

class _ToolButton extends StatelessWidget {
  const _ToolButton({required this.tooltip, required this.icon, required this.onPressed});

  final String tooltip;
  final IconData icon;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(right: 6),
      child: IconButton.filledTonal(
        tooltip: tooltip,
        constraints: const BoxConstraints.tightFor(width: 44, height: 42),
        onPressed: onPressed,
        icon: Icon(icon, size: 20),
      ),
    );
  }
}

class PreviewPane extends StatelessWidget {
  const PreviewPane({super.key, required this.home, required this.note, this.compact = false});

  final _LibreNotesHomeState home;
  final Note note;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.all(compact ? 12 : 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (compact)
            Padding(
              padding: const EdgeInsets.only(bottom: 10),
              child: _NoteHeader(note: note),
            ),
          Padding(
            padding: const EdgeInsets.only(bottom: 10),
            child: Row(
              children: [
                Expanded(
                  child: Text('Preview', style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w900)),
                ),
                _PreviewActionButton(compact: compact, icon: Icons.download_outlined, label: 'Export', onPressed: () => home._exportSelectedNote()),
                const SizedBox(width: 8),
                _PreviewActionButton(compact: compact, icon: Icons.share_outlined, label: 'Share', onPressed: home._shareNote),
                const SizedBox(width: 8),
                PopupMenuButton<String>(
                  tooltip: 'Share as',
                  icon: const Icon(Icons.ios_share_outlined),
                  onSelected: home._shareSelectedNoteAs,
                  itemBuilder: (context) => const [
                    PopupMenuItem(value: 'md', child: Text('Markdown')),
                    PopupMenuItem(value: 'txt', child: Text('Plain text')),
                    PopupMenuItem(value: 'html', child: Text('HTML')),
                    PopupMenuItem(value: 'pdf', child: Text('PDF')),
                    PopupMenuItem(value: 'doc', child: Text('Word DOC')),
                    PopupMenuItem(value: 'json', child: Text('JSON')),
                  ],
                ),
              ],
            ),
          ),
          Expanded(
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.surfaceContainerLow.withValues(alpha: 0.65),
                border: Border.all(color: Theme.of(context).colorScheme.outlineVariant),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Markdown(
                data: note.body,
                selectable: true,
                padding: EdgeInsets.all(compact ? 18 : 24),
                styleSheet: MarkdownStyleSheet.fromTheme(Theme.of(context)).copyWith(
                  h1: Theme.of(context).textTheme.headlineMedium?.copyWith(fontWeight: FontWeight.w900),
                  h2: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w900),
                  p: Theme.of(context).textTheme.bodyLarge?.copyWith(
                        height: home.readingMode ? 1.8 : 1.55,
                        fontSize: home.readingMode ? 18 : null,
                        color: home.highContrastText ? Theme.of(context).colorScheme.onSurface : null,
                      ),
                  codeblockDecoration: BoxDecoration(
                    color: Theme.of(context).colorScheme.surfaceContainerHighest,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  blockquoteDecoration: BoxDecoration(
                    color: Theme.of(context).colorScheme.primaryContainer.withValues(alpha: 0.35),
                    border: Border(left: BorderSide(color: Theme.of(context).colorScheme.primary, width: 4)),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _PreviewActionButton extends StatelessWidget {
  const _PreviewActionButton({required this.icon, required this.label, required this.onPressed, this.compact = false});

  final IconData icon;
  final String label;
  final VoidCallback onPressed;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    if (compact) {
      return IconButton.filledTonal(tooltip: label, onPressed: onPressed, icon: Icon(icon));
    }
    return FilledButton.tonalIcon(onPressed: onPressed, icon: Icon(icon, size: 18), label: Text(label));
  }
}

class _NoteHeader extends StatelessWidget {
  const _NoteHeader({this.home, this.note});

  final _LibreNotesHomeState? home;
  final Note? note;

  @override
  Widget build(BuildContext context) {
    final current = note ?? home!.selectedNote;
    return Row(
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(current.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w900)),
              Text('${current.folder}  -  ${current.tag}', maxLines: 1, overflow: TextOverflow.ellipsis),
            ],
          ),
        ),
        if (home != null) ...[
          IconButton(tooltip: 'Rename', onPressed: home!._renameNote, icon: const Icon(Icons.drive_file_rename_outline)),
          IconButton(tooltip: 'Save', onPressed: home!._saveNote, icon: const Icon(Icons.save_outlined)),
        ],
      ],
    );
  }
}

class _AttachmentsPane extends StatelessWidget {
  const _AttachmentsPane({required this.home, this.compact = false});

  final _LibreNotesHomeState home;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final attachments = home.selectedNote.attachments.where((file) => !file.archived).toList();
    return Container(
      constraints: BoxConstraints(maxHeight: compact ? double.infinity : 178),
      padding: EdgeInsets.fromLTRB(compact ? 12 : 16, 10, compact ? 12 : 16, compact ? 80 : 12),
      decoration: compact
          ? null
          : BoxDecoration(
              border: Border(top: BorderSide(color: Theme.of(context).dividerColor.withValues(alpha: 0.35))),
            ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text('Attachments', style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w900)),
              ),
              if (!compact)
                SegmentedButton<bool>(
                  segments: const [
                    ButtonSegment(value: true, icon: Icon(Icons.grid_view), label: Text('Cards')),
                    ButtonSegment(value: false, icon: Icon(Icons.list), label: Text('Rows')),
                  ],
                  selected: {home.previewCards},
                  onSelectionChanged: (value) => home._setPreviewCards(value.first),
                ),
              IconButton.filledTonal(tooltip: 'Attach file', onPressed: home._addAttachment, icon: const Icon(Icons.add)),
            ],
          ),
          const SizedBox(height: 10),
          Expanded(
            child: attachments.isEmpty
                ? EmptyState(
                    icon: Icons.attach_file,
                    title: 'No attachments yet',
                    action: 'Attach',
                    onPressed: home._addAttachment,
                  )
                : home.previewCards && !compact
                    ? ListView.separated(
                        scrollDirection: Axis.horizontal,
                        itemCount: attachments.length,
                        separatorBuilder: (_, __) => const SizedBox(width: 10),
                        itemBuilder: (context, index) => SizedBox(
                          width: 260,
                          child: AttachmentCard(
                            file: attachments[index],
                            onPreview: () => home._openAttachmentPreview(attachments[index]),
                            onArchive: () => home._archiveAttachment(attachments[index]),
                            onDelete: () => home._removeAttachment(attachments[index]),
                            onShare: () => home._shareAttachment(attachments[index]),
                            onExport: () => home._exportAttachment(attachments[index]),
                          ),
                        ),
                      )
                    : ListView.separated(
                        itemCount: attachments.length,
                        separatorBuilder: (_, __) => const SizedBox(height: 8),
                        itemBuilder: (context, index) => AttachmentRow(
                          file: attachments[index],
                          onPreview: () => home._openAttachmentPreview(attachments[index]),
                          onArchive: () => home._archiveAttachment(attachments[index]),
                          onDelete: () => home._removeAttachment(attachments[index]),
                          onShare: () => home._shareAttachment(attachments[index]),
                          onExport: () => home._exportAttachment(attachments[index]),
                        ),
                      ),
          ),
        ],
      ),
    );
  }
}

class AttachmentCard extends StatelessWidget {
  const AttachmentCard({
    super.key,
    required this.file,
    required this.onPreview,
    required this.onArchive,
    required this.onDelete,
    required this.onShare,
    required this.onExport,
  });

  final AttachmentFile file;
  final VoidCallback onPreview;
  final VoidCallback onArchive;
  final VoidCallback onDelete;
  final VoidCallback onShare;
  final VoidCallback onExport;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Dismissible(
      key: ValueKey(file.id),
      direction: DismissDirection.horizontal,
      background: _SwipeBackground(alignment: Alignment.centerLeft, icon: Icons.archive_outlined, color: scheme.secondaryContainer, iconColor: scheme.onSecondaryContainer),
      secondaryBackground: _SwipeBackground(alignment: Alignment.centerRight, icon: Icons.delete_outline, color: scheme.errorContainer, iconColor: scheme.onErrorContainer),
      onDismissed: (direction) => direction == DismissDirection.startToEnd ? onArchive() : onDelete(),
      child: Card(
        margin: EdgeInsets.zero,
        child: InkWell(
          borderRadius: BorderRadius.circular(8),
          onTap: onPreview,
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    CircleAvatar(
                      radius: 24,
                      backgroundColor: file.color.withValues(alpha: 0.18),
                      child: Icon(file.icon, color: file.color),
                    ),
                    const Spacer(),
                    PopupMenuButton<String>(
                      tooltip: 'File options',
                      onSelected: (value) {
                        switch (value) {
                          case 'share':
                            onShare();
                            break;
                          case 'export':
                            onExport();
                            break;
                          case 'archive':
                            onArchive();
                            break;
                          case 'delete':
                            onDelete();
                            break;
                        }
                      },
                      itemBuilder: (context) => const [
                        PopupMenuItem(value: 'share', child: Text('Share')),
                        PopupMenuItem(value: 'export', child: Text('Export')),
                        PopupMenuItem(value: 'archive', child: Text('Archive')),
                        PopupMenuItem(value: 'delete', child: Text('Delete')),
                      ],
                    ),
                  ],
                ),
                const Spacer(),
                Text(file.name, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w900)),
                const SizedBox(height: 4),
                Text(file.meta, maxLines: 1, overflow: TextOverflow.ellipsis),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class AttachmentRow extends StatelessWidget {
  const AttachmentRow({
    super.key,
    required this.file,
    required this.onPreview,
    required this.onArchive,
    required this.onDelete,
    required this.onShare,
    required this.onExport,
  });

  final AttachmentFile file;
  final VoidCallback onPreview;
  final VoidCallback onArchive;
  final VoidCallback onDelete;
  final VoidCallback onShare;
  final VoidCallback onExport;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Dismissible(
      key: ValueKey(file.id),
      direction: DismissDirection.horizontal,
      background: _SwipeBackground(alignment: Alignment.centerLeft, icon: Icons.archive_outlined, color: scheme.secondaryContainer, iconColor: scheme.onSecondaryContainer),
      secondaryBackground: _SwipeBackground(alignment: Alignment.centerRight, icon: Icons.delete_outline, color: scheme.errorContainer, iconColor: scheme.onErrorContainer),
      onDismissed: (direction) => direction == DismissDirection.startToEnd ? onArchive() : onDelete(),
      child: Card(
        margin: EdgeInsets.zero,
        child: ListTile(
          minVerticalPadding: 18,
          onTap: onPreview,
          leading: CircleAvatar(
            radius: 25,
            backgroundColor: file.color.withValues(alpha: 0.2),
            child: Icon(file.icon, color: file.color),
          ),
          title: Text(file.name, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
          subtitle: Text(file.meta, maxLines: 1, overflow: TextOverflow.ellipsis),
          trailing: PopupMenuButton<String>(
            tooltip: 'File options',
            icon: const Icon(Icons.more_vert),
            onSelected: (value) {
              switch (value) {
                case 'share':
                  onShare();
                  break;
                case 'export':
                  onExport();
                  break;
                case 'archive':
                  onArchive();
                  break;
                case 'delete':
                  onDelete();
                  break;
              }
            },
            itemBuilder: (context) => const [
              PopupMenuItem(value: 'share', child: Text('Share')),
              PopupMenuItem(value: 'export', child: Text('Export')),
              PopupMenuItem(value: 'archive', child: Text('Archive')),
              PopupMenuItem(value: 'delete', child: Text('Delete')),
            ],
          ),
        ),
      ),
    );
  }
}

class _SwipeBackground extends StatelessWidget {
  const _SwipeBackground({required this.alignment, required this.icon, required this.color, required this.iconColor});

  final Alignment alignment;
  final IconData icon;
  final Color color;
  final Color iconColor;

  @override
  Widget build(BuildContext context) {
    return Container(
      alignment: alignment,
      padding: EdgeInsets.only(left: alignment == Alignment.centerLeft ? 18 : 0, right: alignment == Alignment.centerRight ? 18 : 0),
      decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(8)),
      child: Icon(icon, color: iconColor),
    );
  }
}

class AttachmentPreviewSheet extends StatefulWidget {
  const AttachmentPreviewSheet({
    super.key,
    required this.file,
    required this.initialPosition,
    required this.onPositionChanged,
    required this.onShare,
    required this.onShareAs,
    required this.onExport,
    required this.onArchive,
    required this.onDelete,
  });

  final AttachmentFile file;
  final double initialPosition;
  final ValueChanged<double> onPositionChanged;
  final VoidCallback onShare;
  final VoidCallback onShareAs;
  final VoidCallback onExport;
  final VoidCallback onArchive;
  final VoidCallback onDelete;

  @override
  State<AttachmentPreviewSheet> createState() => _AttachmentPreviewSheetState();
}

class _AttachmentPreviewSheetState extends State<AttachmentPreviewSheet> {
  late double position = widget.initialPosition.clamp(0, 100).toDouble();

  @override
  void dispose() {
    widget.onPositionChanged(position);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final file = widget.file;
    final scheme = Theme.of(context).colorScheme;
    return SafeArea(
      child: DraggableScrollableSheet(
        expand: false,
        minChildSize: 0.45,
        initialChildSize: 0.78,
        maxChildSize: 0.96,
        builder: (context, controller) {
          return ListView(
            controller: controller,
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 24),
            children: [
              Row(
                children: [
                  CircleAvatar(radius: 26, backgroundColor: file.color.withValues(alpha: 0.18), child: Icon(file.icon, color: file.color)),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(file.name, maxLines: 2, overflow: TextOverflow.ellipsis, style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w900)),
                        Text(file.meta, maxLines: 1, overflow: TextOverflow.ellipsis),
                      ],
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 18),
              AspectRatio(
                aspectRatio: file.isImage ? 4 / 3 : 16 / 9,
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: scheme.surfaceContainerHighest.withValues(alpha: 0.65),
                    border: Border.all(color: scheme.outlineVariant),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(8),
                    child: _AttachmentPreviewBody(file: file),
                  ),
                ),
              ),
              const SizedBox(height: 18),
              if (file.isDocument) ...[
                Text('Reading position', style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w900)),
                Slider(
                  value: position,
                  min: 0,
                  max: 100,
                  divisions: 100,
                  label: '${position.round()}%',
                  onChanged: (value) => setState(() => position = value),
                ),
                Text('Libre Notes will reopen this document at ${position.round()}% next time.'),
                const SizedBox(height: 16),
              ],
              Wrap(
                spacing: 10,
                runSpacing: 10,
                children: [
                  FilledButton.icon(onPressed: widget.onShare, icon: const Icon(Icons.share_outlined), label: const Text('Share')),
                  FilledButton.tonalIcon(onPressed: widget.onShareAs, icon: const Icon(Icons.ios_share_outlined), label: const Text('Share as')),
                  FilledButton.tonalIcon(onPressed: widget.onExport, icon: const Icon(Icons.download_outlined), label: const Text('Export')),
                  OutlinedButton.icon(onPressed: widget.onArchive, icon: const Icon(Icons.archive_outlined), label: const Text('Archive')),
                  OutlinedButton.icon(onPressed: widget.onDelete, icon: const Icon(Icons.delete_outline), label: const Text('Delete')),
                ],
              ),
            ],
          );
        },
      ),
    );
  }
}

class _AttachmentPreviewBody extends StatelessWidget {
  const _AttachmentPreviewBody({required this.file});

  final AttachmentFile file;

  @override
  Widget build(BuildContext context) {
    if (file.isImage && file.path != null && File(file.path!).existsSync()) {
      return InteractiveViewer(child: Image.file(File(file.path!), fit: BoxFit.contain));
    }
    if (file.isPdf && file.path != null && File(file.path!).existsSync()) {
      return SfPdfViewer.file(File(file.path!));
    }
    if (file.isTextPreviewable && file.path != null && File(file.path!).existsSync()) {
      return FutureBuilder<String>(
        future: File(file.path!).readAsString().then((value) => value.length > 6000 ? '${value.substring(0, 6000)}\n\n...' : value),
        builder: (context, snapshot) {
          if (!snapshot.hasData) {
            return const Center(child: CircularProgressIndicator());
          }
          return SingleChildScrollView(
            padding: const EdgeInsets.all(16),
            child: SelectableText(snapshot.data!, style: const TextStyle(fontFamily: 'monospace')),
          );
        },
      );
    }
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(file.icon, size: 56, color: file.color),
            const SizedBox(height: 12),
            Text(file.kindLabel, textAlign: TextAlign.center, style: const TextStyle(fontWeight: FontWeight.w900)),
            const SizedBox(height: 6),
            Text(
              file.path == null
                  ? 'Preview metadata is available. Attach again to enable file export/share.'
                  : 'Libre Notes can keep and organize this file here. PDF, image, Markdown, text, CSV, JSON, HTML, and code files preview directly; Office files show metadata until a native renderer is added.',
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
    );
  }
}

class _SettingsSheet extends StatefulWidget {
  const _SettingsSheet({required this.home});

  final _LibreNotesHomeState home;

  @override
  State<_SettingsSheet> createState() => _SettingsSheetState();
}

class _SettingsSheetState extends State<_SettingsSheet> {
  int section = 0;
  late final TextEditingController nameController = TextEditingController(text: widget.home.userName);
  late final TextEditingController handleController = TextEditingController(text: widget.home.userHandle);

  _LibreNotesHomeState get home => widget.home;

  @override
  void dispose() {
    nameController.dispose();
    handleController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final compact = MediaQuery.sizeOf(context).width < 720;
    return SafeArea(
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: MediaQuery.sizeOf(context).height * 0.88),
        child: Row(
          children: [
            SizedBox(
              width: compact ? 92 : 220,
              child: NavigationRail(
                extended: !compact,
                minExtendedWidth: 210,
                selectedIndex: section,
                onDestinationSelected: (value) => setState(() => section = value),
                destinations: const [
                  NavigationRailDestination(icon: Icon(Icons.person_outline), label: Text('Account')),
                  NavigationRailDestination(icon: Icon(Icons.folder_copy_outlined), label: Text('Vault')),
                  NavigationRailDestination(icon: Icon(Icons.contrast_outlined), label: Text('Appearance')),
                  NavigationRailDestination(icon: Icon(Icons.palette_outlined), label: Text('Theme')),
                  NavigationRailDestination(icon: Icon(Icons.download_outlined), label: Text('Export')),
                  NavigationRailDestination(icon: Icon(Icons.accessibility_new_outlined), label: Text('Accessibility')),
                  NavigationRailDestination(icon: Icon(Icons.delete_outline), label: Text('Trash')),
                  NavigationRailDestination(icon: Icon(Icons.sell_outlined), label: Text('Tags')),
                ],
              ),
            ),
            VerticalDivider(width: 1, color: Theme.of(context).dividerColor.withValues(alpha: 0.45)),
            Expanded(
              child: ListView(
                padding: const EdgeInsets.fromLTRB(20, 0, 20, 24),
                children: [
                  Text(_sectionTitle, style: Theme.of(context).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w900)),
                  const SizedBox(height: 16),
                  ..._sectionChildren(context),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  String get _sectionTitle => const ['Account', 'Vault', 'Appearance', 'Theme', 'Export', 'Accessibility', 'Trash', 'Tag Colors'][section];

  List<Widget> _sectionChildren(BuildContext context) {
    return switch (section) {
      0 => _accountSection(),
      1 => _vaultSection(),
      2 => _appearanceSection(),
      3 => _themeSection(),
      4 => _exportSection(),
      5 => _accessibilitySection(),
      6 => _trashSection(),
      _ => _tagsSection(),
    };
  }

  List<Widget> _accountSection() => [
        SettingBlock(
          title: 'Local profile',
          child: Column(
            children: [
              TextField(controller: nameController, decoration: const InputDecoration(border: OutlineInputBorder(), labelText: 'Display name')),
              const SizedBox(height: 12),
              TextField(controller: handleController, decoration: const InputDecoration(border: OutlineInputBorder(), labelText: 'Handle')),
              const SizedBox(height: 12),
              SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  onPressed: () {
                    home._setUserProfile(nameController.text, handleController.text);
                    setState(() {});
                  },
                  icon: const Icon(Icons.save_outlined),
                  label: const Text('Save profile'),
                ),
              ),
            ],
          ),
        ),
        ListTile(
          contentPadding: EdgeInsets.zero,
          leading: CircleAvatar(backgroundColor: home.accent, foregroundColor: Colors.white, child: const Icon(Icons.shield_outlined)),
          title: Text(home.userName),
          subtitle: Text('@${home.userHandle} • offline local vault'),
        ),
      ];

  List<Widget> _vaultSection() => [
        SettingBlock(
          title: 'Vault location',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(home.vaultFolderPath ?? 'Built-in app storage'),
              const SizedBox(height: 10),
              Wrap(
                spacing: 10,
                runSpacing: 10,
                children: [
                  FilledButton.icon(onPressed: () => home._chooseVaultFolder(), icon: const Icon(Icons.folder_open_outlined), label: const Text('Choose folder')),
                  FilledButton.tonalIcon(onPressed: () => home._chooseVaultFolder(encrypt: true), icon: const Icon(Icons.lock_outline), label: const Text('Encrypted folder')),
                  OutlinedButton.icon(onPressed: home._importMarkdownFiles, icon: const Icon(Icons.upload_file_outlined), label: const Text('Import Markdown')),
                  OutlinedButton.icon(onPressed: home._exportBackup, icon: const Icon(Icons.backup_outlined), label: const Text('Backup')),
                  OutlinedButton.icon(onPressed: home._importBackup, icon: const Icon(Icons.restore_outlined), label: const Text('Restore')),
                ],
              ),
            ],
          ),
        ),
        SwitchListTile(
          title: const Text('External vault encryption'),
          subtitle: const Text('Writes the selected vault folder as AES-GCM encrypted data when a password is set.'),
          value: home.vaultEncrypted,
          onChanged: (value) async {
            if (value) {
              final password = await home._promptVaultPassword(title: 'Vault password', action: 'Enable');
              if (password == null || password.isEmpty) {
                return;
              }
              home._setVaultEncryption(enabled: true, password: password);
            } else {
              home._setVaultEncryption(enabled: false);
            }
            setState(() {});
          },
        ),
      ];

  List<Widget> _appearanceSection() => [
        SettingBlock(
          title: 'Mode',
          child: SegmentedButton<ThemeMode>(
            segments: const [
              ButtonSegment(value: ThemeMode.system, icon: Icon(Icons.monitor_outlined), label: Text('Auto')),
              ButtonSegment(value: ThemeMode.light, icon: Icon(Icons.light_mode_outlined), label: Text('Light')),
              ButtonSegment(value: ThemeMode.dark, icon: Icon(Icons.dark_mode_outlined), label: Text('Dark')),
            ],
            selected: {home.themeMode},
            onSelectionChanged: (value) => home.onThemeModeChanged(value.first),
          ),
        ),
        SwitchListTile(title: const Text('AMOLED dark'), value: home.amoledDark, onChanged: home.onAmoledChanged),
        SwitchListTile(title: const Text('Pure black'), value: home.pureBlack, onChanged: home.onPureBlackChanged),
        SwitchListTile(title: const Text('Letter folder icons'), value: home.letterIcons, onChanged: home.onLetterIconsChanged),
        SwitchListTile(title: const Text('Dense notes'), value: home.denseNotes, onChanged: home.onDenseNotesChanged),
      ];

  List<Widget> _themeSection() => [
        SettingBlock(
          title: 'Accent',
          child: Wrap(
            spacing: 10,
            runSpacing: 10,
            children: [
              FilledButton.icon(onPressed: home._chooseAccentColor, icon: const Icon(Icons.color_lens_outlined), label: const Text('Color wheel')),
              for (final color in accentChoices)
                InkWell(
                  onTap: () => home.onAccentChanged(color),
                  borderRadius: BorderRadius.circular(999),
                  child: CircleAvatar(radius: 22, backgroundColor: color, child: home.accent.toARGB32() == color.toARGB32() ? const Icon(Icons.check, color: Colors.white) : null),
                ),
            ],
          ),
        ),
        SettingBlock(
          title: 'Background',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SwitchListTile(title: const Text('Accent-tinted background'), value: home.accentTintBackground, onChanged: home.onAccentTintChanged),
              Wrap(
                spacing: 10,
                runSpacing: 10,
                children: [
                  OutlinedButton.icon(onPressed: home._chooseBackgroundVisual, icon: const Icon(Icons.image_outlined), label: const Text('Image or GIF')),
                  if (home.backgroundVisualPath != null) OutlinedButton.icon(onPressed: home._clearBackgroundVisual, icon: const Icon(Icons.clear), label: const Text('Clear visual')),
                ],
              ),
            ],
          ),
        ),
      ];

  List<Widget> _exportSection() => [
        SettingBlock(
          title: 'Preferred note export type',
          child: DropdownButtonFormField<String>(
            initialValue: home.preferredExportType,
            decoration: const InputDecoration(border: OutlineInputBorder()),
            items: const [
              DropdownMenuItem(value: 'md', child: Text('Markdown (.md)')),
              DropdownMenuItem(value: 'txt', child: Text('Plain text (.txt)')),
              DropdownMenuItem(value: 'html', child: Text('HTML (.html)')),
              DropdownMenuItem(value: 'pdf', child: Text('PDF (.pdf)')),
              DropdownMenuItem(value: 'doc', child: Text('Word DOC (.doc)')),
              DropdownMenuItem(value: 'json', child: Text('JSON (.json)')),
            ],
            onChanged: (value) {
              if (value != null) {
                home._setPreferredExportType(value);
              }
            },
          ),
        ),
        Wrap(
          spacing: 10,
          runSpacing: 10,
          children: [
            FilledButton.icon(onPressed: () => home._exportSelectedNote('pdf'), icon: const Icon(Icons.picture_as_pdf_outlined), label: const Text('Export PDF')),
            FilledButton.tonalIcon(onPressed: () => home._exportSelectedNote('doc'), icon: const Icon(Icons.description_outlined), label: const Text('Export DOC')),
            OutlinedButton.icon(onPressed: () => home._shareSelectedNoteAs('pdf'), icon: const Icon(Icons.share_outlined), label: const Text('Share PDF')),
          ],
        ),
      ];

  List<Widget> _accessibilitySection() => [
        SwitchListTile(title: const Text('Reading mode'), subtitle: const Text('Larger, more relaxed preview text.'), value: home.readingMode, onChanged: home._setReadingMode),
        SwitchListTile(title: const Text('High contrast text'), value: home.highContrastText, onChanged: home._setHighContrastText),
        SwitchListTile(title: const Text('Reduce motion'), value: home.reduceMotion, onChanged: home._setReduceMotion),
      ];

  List<Widget> _trashSection() => [
        SettingBlock(
          title: 'Keep deleted notes for',
          child: DropdownButtonFormField<int>(
            initialValue: trashRetentionOptions.contains(home.trashRetentionDays) ? home.trashRetentionDays : 30,
            decoration: const InputDecoration(border: OutlineInputBorder()),
            items: const [
              DropdownMenuItem(value: 1, child: Text('1 day')),
              DropdownMenuItem(value: 7, child: Text('7 days')),
              DropdownMenuItem(value: 30, child: Text('30 days')),
              DropdownMenuItem(value: 90, child: Text('90 days')),
              DropdownMenuItem(value: 365, child: Text('1 year')),
            ],
            onChanged: (value) {
              if (value != null) {
                home._setTrashRetentionDays(value);
              }
            },
          ),
        ),
      ];

  List<Widget> _tagsSection() => [
        SettingBlock(
          title: 'Tag colors',
          child: Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final tag in notesTags(home.notes.where((note) => !note.isTrashed).toList()))
                ActionChip(
                  avatar: CircleAvatar(backgroundColor: home.colorForTag(tag)),
                  label: Text(tag),
                  onPressed: () => home._chooseTagColor(tag),
                ),
            ],
          ),
        ),
      ];
}

class SettingBlock extends StatelessWidget {
  const SettingBlock({super.key, required this.title, required this.child});

  final String title;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: const TextStyle(fontWeight: FontWeight.w900)),
          const SizedBox(height: 10),
          child,
        ],
      ),
    );
  }
}

class ColorPickerSheet extends StatefulWidget {
  const ColorPickerSheet({super.key, required this.title, required this.initialColor});

  final String title;
  final Color initialColor;

  @override
  State<ColorPickerSheet> createState() => _ColorPickerSheetState();
}

class _ColorPickerSheetState extends State<ColorPickerSheet> {
  late Color selected = widget.initialColor;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 0, 20, 24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(widget.title, style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w900)),
            const SizedBox(height: 14),
            ColorPicker(
              pickerColor: selected,
              onColorChanged: (color) => setState(() => selected = color),
              pickerAreaHeightPercent: 0.68,
              enableAlpha: false,
              displayThumbColor: true,
              paletteType: PaletteType.hueWheel,
              labelTypes: const [],
            ),
            const SizedBox(height: 10),
            GridView.builder(
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: 6,
                mainAxisSpacing: 12,
                crossAxisSpacing: 12,
              ),
              itemCount: systemColors.length,
              itemBuilder: (context, index) {
                final color = systemColors[index];
                final isSelected = selected.toARGB32() == color.toARGB32();
                return InkWell(
                  borderRadius: BorderRadius.circular(999),
                  onTap: () => setState(() => selected = color),
                  child: CircleAvatar(
                    backgroundColor: color,
                    child: isSelected ? const Icon(Icons.check, color: Colors.white) : null,
                  ),
                );
              },
            ),
            const SizedBox(height: 16),
            SizedBox(
              width: double.infinity,
              child: FilledButton.icon(
                onPressed: () => Navigator.pop(context, selected),
                icon: const Icon(Icons.check),
                label: const Text('Use color'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class FolderEditResult {
  const FolderEditResult(this.name, this.iconKey);

  final String name;
  final String iconKey;
}

class FolderDialog extends StatefulWidget {
  const FolderDialog({super.key});

  @override
  State<FolderDialog> createState() => _FolderDialogState();
}

class _FolderDialogState extends State<FolderDialog> {
  final TextEditingController nameController = TextEditingController();
  String iconKey = 'folder';

  @override
  void dispose() {
    nameController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('New folder'),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 420),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            TextField(controller: nameController, autofocus: true, decoration: const InputDecoration(labelText: 'Folder name')),
            const SizedBox(height: 16),
            Text('Icon', style: Theme.of(context).textTheme.labelLarge),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (final item in folderIconChoices.entries)
                  ChoiceChip(
                    selected: iconKey == item.key,
                    avatar: Icon(folderIcon(item.key), size: 18),
                    label: Text(item.value),
                    onSelected: (_) => setState(() => iconKey = item.key),
                  ),
              ],
            ),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(onPressed: _submit, child: const Text('Create')),
      ],
    );
  }

  void _submit() {
    final name = nameController.text.trim();
    if (name.isEmpty) {
      return;
    }
    Navigator.pop(context, FolderEditResult(name, iconKey));
  }
}

class LinkEditResult {
  const LinkEditResult(this.label, this.url);

  final String label;
  final String url;
}

class LinkDialog extends StatefulWidget {
  const LinkDialog({super.key});

  @override
  State<LinkDialog> createState() => _LinkDialogState();
}

class _LinkDialogState extends State<LinkDialog> {
  final TextEditingController labelController = TextEditingController();
  final TextEditingController urlController = TextEditingController(text: 'https://');
  String? error;

  @override
  void dispose() {
    labelController.dispose();
    urlController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Insert link'),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 420),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(controller: labelController, decoration: const InputDecoration(labelText: 'Text')),
            const SizedBox(height: 12),
            TextField(
              controller: urlController,
              autofocus: true,
              keyboardType: TextInputType.url,
              decoration: const InputDecoration(labelText: 'URL'),
              onSubmitted: (_) => _submit(),
            ),
            if (error != null) ...[
              const SizedBox(height: 12),
              Text(error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(onPressed: _submit, child: const Text('Insert')),
      ],
    );
  }

  void _submit() {
    final url = urlController.text.trim();
    if (url.isEmpty || url == 'https://') {
      setState(() => error = 'Enter a URL.');
      return;
    }
    Navigator.pop(context, LinkEditResult(labelController.text.trim(), url));
  }
}

class TextInputDialog extends StatefulWidget {
  const TextInputDialog({
    super.key,
    required this.title,
    required this.label,
    required this.primaryAction,
    this.initialValue = '',
    this.hint,
    this.obscureText = false,
  });

  final String title;
  final String label;
  final String primaryAction;
  final String initialValue;
  final String? hint;
  final bool obscureText;

  @override
  State<TextInputDialog> createState() => _TextInputDialogState();
}

class _TextInputDialogState extends State<TextInputDialog> {
  late final TextEditingController controller;

  @override
  void initState() {
    super.initState();
    controller = TextEditingController(text: widget.initialValue);
  }

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.title),
      content: TextField(
        controller: controller,
        autofocus: true,
        obscureText: widget.obscureText,
        textInputAction: TextInputAction.done,
        decoration: InputDecoration(labelText: widget.label, hintText: widget.hint),
        onSubmitted: (_) => _submit(),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(onPressed: _submit, child: Text(widget.primaryAction)),
      ],
    );
  }

  void _submit() {
    Navigator.pop(context, controller.text.trim());
  }
}

class StatusBar extends StatelessWidget {
  const StatusBar({super.key, required this.note});

  final Note note;

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 36,
      padding: const EdgeInsets.symmetric(horizontal: 16),
      decoration: BoxDecoration(border: Border(top: BorderSide(color: Theme.of(context).dividerColor.withValues(alpha: 0.35)))),
      child: Row(
        children: [
          Text(relativeDate(note.updatedAt)),
          const Spacer(),
          Text('${note.wordCount} words'),
          const Spacer(),
          const Icon(Icons.check_circle_outline, size: 16),
          const SizedBox(width: 6),
          const Text('Autosaved'),
        ],
      ),
    );
  }
}

class EmptyState extends StatelessWidget {
  const EmptyState({
    super.key,
    required this.icon,
    required this.title,
    required this.action,
    required this.onPressed,
  });

  final IconData icon;
  final String title;
  final String action;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 42, color: Theme.of(context).colorScheme.primary),
            const SizedBox(height: 10),
            Text(title, textAlign: TextAlign.center, style: const TextStyle(fontWeight: FontWeight.w900)),
            const SizedBox(height: 12),
            FilledButton(onPressed: onPressed, child: Text(action)),
          ],
        ),
      ),
    );
  }
}

class NoteEditResult {
  const NoteEditResult(this.title, this.folder, this.tag);

  final String title;
  final String folder;
  final String tag;
}

class NoteDetailsDialog extends StatefulWidget {
  const NoteDetailsDialog({
    super.key,
    required this.title,
    required this.folder,
    required this.tag,
  });

  final String title;
  final String folder;
  final String tag;

  @override
  State<NoteDetailsDialog> createState() => _NoteDetailsDialogState();
}

class _NoteDetailsDialogState extends State<NoteDetailsDialog> {
  late final TextEditingController titleController;
  late final TextEditingController folderController;
  late final TextEditingController tagController;
  String? error;

  @override
  void initState() {
    super.initState();
    titleController = TextEditingController(text: widget.title);
    folderController = TextEditingController(text: widget.folder);
    tagController = TextEditingController(text: widget.tag);
  }

  @override
  void dispose() {
    titleController.dispose();
    folderController.dispose();
    tagController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Note details'),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 420),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(controller: titleController, decoration: const InputDecoration(labelText: 'Title')),
            const SizedBox(height: 12),
            TextField(controller: folderController, decoration: const InputDecoration(labelText: 'Folder')),
            const SizedBox(height: 12),
            TextField(controller: tagController, decoration: const InputDecoration(labelText: 'Tag')),
            if (error != null) ...[
              const SizedBox(height: 12),
              Text(error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(onPressed: _submit, child: const Text('Apply')),
      ],
    );
  }

  void _submit() {
    final title = titleController.text.trim();
    final folder = folderController.text.trim();
    final tag = tagController.text.trim();
    if (title.isEmpty || folder.isEmpty || tag.isEmpty) {
      setState(() => error = 'Title, folder, and tag are required.');
      return;
    }
    Navigator.pop(context, NoteEditResult(title, folder, tag));
  }
}

enum NoteStatus {
  active,
  archived,
  trashed;

  static NoteStatus fromJson(Object? value) {
    return NoteStatus.values.firstWhere(
      (status) => status.name == value,
      orElse: () => NoteStatus.active,
    );
  }
}

class Note {
  const Note({
    required this.id,
    required this.title,
    required this.folder,
    required this.tag,
    required this.summary,
    required this.body,
    required this.updatedAt,
    required this.wordCount,
    required this.attachments,
    this.starred = false,
    this.status = NoteStatus.active,
    this.trashedAt,
  });

  final String id;
  final String title;
  final String folder;
  final String tag;
  final String summary;
  final String body;
  final DateTime updatedAt;
  final int wordCount;
  final List<AttachmentFile> attachments;
  final bool starred;
  final NoteStatus status;
  final DateTime? trashedAt;

  bool get isEmptyDraft => title == 'Untitled note' && body.trim().isEmpty && attachments.isEmpty;
  bool get isActive => status == NoteStatus.active;
  bool get isArchived => status == NoteStatus.archived;
  bool get isTrashed => status == NoteStatus.trashed;

  factory Note.fromJson(Map<String, dynamic> json) {
    final body = json['body'] as String? ?? '';
    return Note(
      id: json['id'] as String? ?? DateTime.now().microsecondsSinceEpoch.toString(),
      title: json['title'] as String? ?? 'Untitled note',
      folder: json['folder'] as String? ?? 'Inbox',
      tag: json['tag'] as String? ?? '#draft',
      summary: json['summary'] as String? ?? 'Saved note',
      body: body,
      updatedAt: DateTime.tryParse(json['updatedAt'] as String? ?? '') ?? DateTime.now(),
      wordCount: json['wordCount'] as int? ?? countWords(body),
      attachments: ((json['attachments'] as List<dynamic>?) ?? const [])
          .map((item) => AttachmentFile.fromJsonCompatible(item))
          .toList(),
      starred: json['starred'] as bool? ?? false,
      status: NoteStatus.fromJson(json['status']),
      trashedAt: DateTime.tryParse(json['trashedAt'] as String? ?? ''),
    );
  }

  Note copyWith({
    String? title,
    String? folder,
    String? tag,
    String? summary,
    String? body,
    DateTime? updatedAt,
    int? wordCount,
    List<AttachmentFile>? attachments,
    bool? starred,
    NoteStatus? status,
    DateTime? trashedAt,
    bool clearTrashedAt = false,
  }) {
    return Note(
      id: id,
      title: title ?? this.title,
      folder: folder ?? this.folder,
      tag: tag ?? this.tag,
      summary: summary ?? this.summary,
      body: body ?? this.body,
      updatedAt: updatedAt ?? this.updatedAt,
      wordCount: wordCount ?? this.wordCount,
      attachments: attachments ?? this.attachments,
      starred: starred ?? this.starred,
      status: status ?? this.status,
      trashedAt: clearTrashedAt ? null : trashedAt ?? this.trashedAt,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'title': title,
      'folder': folder,
      'tag': tag,
      'summary': summary,
      'body': body,
      'updatedAt': updatedAt.toIso8601String(),
      'wordCount': wordCount,
      'attachments': attachments.map((file) => file.toJson()).toList(),
      'starred': starred,
      'status': status.name,
      'trashedAt': trashedAt?.toIso8601String(),
    };
  }
}

class AttachmentFile {
  const AttachmentFile({
    required this.name,
    required this.meta,
    required this.icon,
    required this.color,
    this.path,
    this.sizeBytes,
    this.archived = false,
  });

  factory AttachmentFile.fromJsonCompatible(Object item) {
    if (item is Map<String, dynamic>) {
      return AttachmentFile.fromName(
        item['name'] as String? ?? 'Untitled file',
        path: item['path'] as String?,
        sizeBytes: item['sizeBytes'] as int?,
        archived: item['archived'] as bool? ?? false,
      );
    }
    if (item is Map) {
      return AttachmentFile.fromJsonCompatible(Map<String, dynamic>.from(item));
    }
    return AttachmentFile.fromName(item.toString());
  }

  factory AttachmentFile.fromName(String name, {String? path, int? sizeBytes, bool archived = false}) {
    final lower = name.toLowerCase();
    if (lower.endsWith('.pdf')) {
      return AttachmentFile(name: name, meta: _fileMeta('PDF document', sizeBytes), icon: Icons.picture_as_pdf_outlined, color: Colors.orange, path: path, sizeBytes: sizeBytes, archived: archived);
    }
    if (lower.endsWith('.png') || lower.endsWith('.jpg') || lower.endsWith('.jpeg') || lower.endsWith('.webp')) {
      return AttachmentFile(name: name, meta: _fileMeta('Image preview', sizeBytes), icon: Icons.image_outlined, color: Colors.green, path: path, sizeBytes: sizeBytes, archived: archived);
    }
    if (lower.endsWith('.m4a') || lower.endsWith('.mp3') || lower.endsWith('.wav')) {
      return AttachmentFile(name: name, meta: _fileMeta('Audio note', sizeBytes), icon: Icons.graphic_eq, color: Colors.blue, path: path, sizeBytes: sizeBytes, archived: archived);
    }
    if (lower.endsWith('.xlsx') || lower.endsWith('.xls') || lower.endsWith('.csv')) {
      return AttachmentFile(name: name, meta: _fileMeta('Spreadsheet', sizeBytes), icon: Icons.table_chart_outlined, color: Colors.green, path: path, sizeBytes: sizeBytes, archived: archived);
    }
    if (lower.endsWith('.ppt') || lower.endsWith('.pptx')) {
      return AttachmentFile(name: name, meta: _fileMeta('Presentation', sizeBytes), icon: Icons.slideshow_outlined, color: Colors.deepOrange, path: path, sizeBytes: sizeBytes, archived: archived);
    }
    if (lower.endsWith('.doc') || lower.endsWith('.docx') || lower.endsWith('.rtf')) {
      return AttachmentFile(name: name, meta: _fileMeta('Word document', sizeBytes), icon: Icons.description_outlined, color: Colors.blue, path: path, sizeBytes: sizeBytes, archived: archived);
    }
    return AttachmentFile(name: name, meta: _fileMeta('Linked file', sizeBytes), icon: Icons.insert_drive_file_outlined, color: Colors.purple, path: path, sizeBytes: sizeBytes, archived: archived);
  }

  factory AttachmentFile.fromPlatformFile(PlatformFile file) {
    final name = file.name.isEmpty ? 'Untitled file' : file.name;
    return AttachmentFile.fromName(name, path: file.path, sizeBytes: file.size <= 0 ? null : file.size);
  }

  final String name;
  final String meta;
  final IconData icon;
  final Color color;
  final String? path;
  final int? sizeBytes;
  final bool archived;

  String get id => path ?? name;
  bool get isPdf => _extensionMatches(['.pdf']);
  bool get isImage => _extensionMatches(['.png', '.jpg', '.jpeg', '.webp', '.gif']);
  bool get isTextPreviewable => _extensionMatches(['.md', '.markdown', '.txt', '.csv', '.json', '.xml', '.html', '.css', '.js', '.dart']);
  bool get isDocument => _extensionMatches(['.pdf', '.doc', '.docx', '.rtf', '.xls', '.xlsx', '.csv', '.ppt', '.pptx']);
  String get kindLabel {
    if (_extensionMatches(['.pdf'])) return 'PDF document';
    if (_extensionMatches(['.xls', '.xlsx', '.csv'])) return 'Spreadsheet';
    if (_extensionMatches(['.ppt', '.pptx'])) return 'Presentation';
    if (_extensionMatches(['.doc', '.docx', '.rtf'])) return 'Word document';
    if (isImage) return 'Image';
    return 'File';
  }

  bool _extensionMatches(List<String> extensions) {
    final lower = name.toLowerCase();
    return extensions.any(lower.endsWith);
  }

  AttachmentFile copyWith({bool? archived}) {
    return AttachmentFile(
      name: name,
      meta: meta,
      icon: icon,
      color: color,
      path: path,
      sizeBytes: sizeBytes,
      archived: archived ?? this.archived,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'name': name,
      'path': path,
      'sizeBytes': sizeBytes,
      'archived': archived,
    };
  }
}

IconData folderIcon(String name, {bool selected = false}) {
  return switch (name) {
    'All' => Icons.all_inbox_outlined,
    'Starred' => selected ? Icons.star : Icons.star_border,
    'Archive' => selected ? Icons.archive : Icons.archive_outlined,
    'Trash' => selected ? Icons.delete : Icons.delete_outline,
    'inbox' => Icons.inbox_outlined,
    'projects' => selected ? Icons.folder : Icons.folder_outlined,
    'study' => Icons.school_outlined,
    'personal' => Icons.person_outline,
    'work' => Icons.work_outline,
    'book' => Icons.menu_book_outlined,
    'code' => Icons.code,
    'lock' => Icons.lock_outline,
    'photo' => Icons.photo_outlined,
    'music' => Icons.music_note_outlined,
    'travel' => Icons.flight_takeoff_outlined,
    'health' => Icons.favorite_border,
    _ => selected ? Icons.folder : Icons.folder_outlined,
  };
}

List<String> notesTags(List<Note> notes) {
  final tags = notes.where((note) => !note.isTrashed).map((note) => note.tag).toSet().toList()..sort();
  return tags;
}

int countWords(String text) {
  return RegExp(r"[A-Za-z0-9_']+").allMatches(text).length;
}

String relativeDate(DateTime date) {
  final diff = DateTime.now().difference(date);
  if (diff.inMinutes < 1) {
    return 'Just now';
  }
  if (diff.inHours < 1) {
    return '${diff.inMinutes}m ago';
  }
  if (diff.inDays < 1) {
    return '${diff.inHours}h ago';
  }
  if (diff.inDays == 1) {
    return 'Yesterday';
  }
  return '${diff.inDays}d ago';
}

String formatBytes(int bytes) {
  if (bytes < 1024) {
    return '$bytes B';
  }
  final kb = bytes / 1024;
  if (kb < 1024) {
    return '${kb.toStringAsFixed(kb >= 100 ? 0 : 1)} KB';
  }
  final mb = kb / 1024;
  if (mb < 1024) {
    return '${mb.toStringAsFixed(mb >= 100 ? 0 : 1)} MB';
  }
  final gb = mb / 1024;
  return '${gb.toStringAsFixed(gb >= 100 ? 0 : 1)} GB';
}

String _fileMeta(String type, int? bytes) {
  return bytes == null ? type : '$type - ${formatBytes(bytes)}';
}

String safeFileName(String value) {
  final cleaned = value.trim().replaceAll(RegExp(r'[\\/:*?"<>|]+'), '-');
  return cleaned.isEmpty ? 'untitled-note' : cleaned;
}

String baseNameWithoutExtension(String path) {
  final name = path.split(RegExp(r'[\\/]')).last;
  final dot = name.lastIndexOf('.');
  return dot <= 0 ? name : name.substring(0, dot);
}

String markdownTitle(String body) {
  for (final line in body.split('\n')) {
    final trimmed = line.trim();
    if (trimmed.startsWith('# ')) {
      return trimmed.substring(2).trim();
    }
  }
  return '';
}

const vaultStorageKey = 'libre_notes_vault_v1';
const appSettingsStorageKey = 'libre_notes_app_settings_v1';
const trashRetentionOptions = [1, 7, 30, 90, 365];

final sampleUpdatedAt = DateTime(2026, 4, 29, 19, 45);

final seedFolders = ['Inbox', 'Personal', 'Projects', 'Study'];
final seedFolderIcons = {
  'All': 'All',
  'Starred': 'Starred',
  'Archive': 'Archive',
  'Trash': 'Trash',
  'Inbox': 'inbox',
  'Personal': 'personal',
  'Projects': 'projects',
  'Study': 'study',
};

const folderIconChoices = {
  'folder': 'Folder',
  'inbox': 'Inbox',
  'projects': 'Project',
  'study': 'Study',
  'personal': 'Person',
  'work': 'Work',
  'book': 'Book',
  'code': 'Code',
  'lock': 'Private',
  'photo': 'Photo',
  'music': 'Audio',
  'travel': 'Travel',
  'health': 'Health',
};

final seedNotes = [
  Note(
    id: 'roadmap',
    title: 'Project Roadmap',
    folder: 'Projects',
    tag: '#planning',
    summary: 'Milestones for turning Libre Notes into a polished local-first Android notes app.',
    updatedAt: sampleUpdatedAt,
    wordCount: countWords(roadmapMarkdown),
    starred: true,
    attachments: const [
      AttachmentFile(name: 'lecture-outline.pdf', meta: 'PDF document', icon: Icons.picture_as_pdf_outlined, color: Colors.orange),
      AttachmentFile(name: 'whiteboard-photo.jpg', meta: 'Image preview', icon: Icons.image_outlined, color: Colors.green),
    ],
    body: roadmapMarkdown,
  ),
  Note(
    id: 'editor',
    title: 'Editor Improvements',
    folder: 'Projects',
    tag: '#ideas',
    summary: 'Android editing improvements with tabs, drawer actions, save flow, and preview parity.',
    updatedAt: sampleUpdatedAt.subtract(const Duration(hours: 3)),
    wordCount: countWords(editorMarkdown),
    attachments: const [
      AttachmentFile(name: 'meeting-audio.m4a', meta: 'Audio note', icon: Icons.graphic_eq, color: Colors.blue),
    ],
    body: editorMarkdown,
  ),
  Note(
    id: 'privacy',
    title: 'Privacy Principles',
    folder: 'Personal',
    tag: '#reference',
    summary: 'Local-first promises and product boundaries for a privacy-respecting notes app.',
    updatedAt: sampleUpdatedAt.subtract(const Duration(days: 1)),
    wordCount: countWords(privacyMarkdown),
    starred: true,
    attachments: const [],
    body: privacyMarkdown,
  ),
  Note(
    id: 'meeting',
    title: 'Meeting Notes - 2026-04-25',
    folder: 'Study',
    tag: '#meeting',
    summary: 'MVP decisions, Android responsive layout notes, and next implementation steps.',
    updatedAt: sampleUpdatedAt.subtract(const Duration(days: 3)),
    wordCount: countWords(meetingMarkdown),
    attachments: const [],
    body: meetingMarkdown,
  ),
];

const roadmapMarkdown = '''
# Project Roadmap

Libre Notes is a local-first Markdown notebook for quick capture, clean reading, and organized project work.

## Goals

- Make editing comfortable on small Android phones
- Keep preview one tap away, like SimpleMarkdown
- Support side-by-side editing on tablets and desktop
- Make folders, search, note actions, and attachments functional

## MVP checklist

- [x] Responsive Android layout
- [x] Live Markdown preview
- [x] Note creation and editing
- [x] Folder filtering and starred notes
- [x] Attachment management
- [ ] Real file-system persistence

## Release focus

| Area | Status | Notes |
| --- | --- | --- |
| Android | Active | Primary build target |
| Web | Later | Keep code portable |
| Windows | Later | Restore after Android stabilizes |

> Keep the first release small, fast, and honest.
''';

const editorMarkdown = '''
# Editor Improvements

The editor should feel like a real writing surface, not a resized desktop app.

## Android behavior

1. Notes list is its own screen on phones.
2. Editing and preview use tabs or bottom navigation.
3. Tablets can keep the note list visible while editing.
4. Wide displays get a split editor and preview.

```md
Use Markdown every day.
Keep the preview live.
Avoid overflowing controls.
```
''';

const privacyMarkdown = '''
# Privacy Principles

Libre Notes should be useful without requiring an account or network connection.

- Local notes stay local by default
- Attachments belong to the vault
- Sync should be optional and transparent
- Export should use plain Markdown whenever possible

## Product promise

The app should never make users wonder where their writing went.
''';

const meetingMarkdown = '''
# Meeting Notes - 2026-04-25

## Decisions

- Android build is the first CI priority.
- The desktop layout must collapse before it becomes cramped.
- Preview needs a maintained Markdown renderer, not a hand-rolled parser.

## Follow-ups

- Add persistent storage
- Add import and export
- Add tests after the Android UI stabilizes
''';

final systemColors = [
  const Color(0xFF2E8F83),
  const Color(0xFF14B8A6),
  const Color(0xFF22C55E),
  const Color(0xFF84CC16),
  const Color(0xFFEAB308),
  const Color(0xFFF97316),
  const Color(0xFFEF4444),
  const Color(0xFFEC4899),
  const Color(0xFFA855F7),
  const Color(0xFF6366F1),
  const Color(0xFF3B82F6),
  const Color(0xFF06B6D4),
];

final accentChoices = systemColors;
