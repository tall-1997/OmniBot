import 'dart:async';

import 'package:flutter/services.dart';

typedef McJson = Map<String, Object?>;

bool isMcCloudOAuthCallbackUrl(String value) {
  final path = Uri.tryParse(value)?.path;
  if (path == null) return false;
  return path == '/api/v1/users/login/callback' ||
      RegExp(
        r'^/api/v1/(users/)?oauth/[a-z0-9_-]+/callback$',
      ).hasMatch(path);
}

class McCloudPage<T> {
  const McCloudPage(this.items, {this.nextCursor, this.hasMore = false});

  final List<T> items;
  final String? nextCursor;
  final bool hasMore;
}

class McCloudFailure implements Exception {
  const McCloudFailure(this.code, this.message, {this.statusCode});

  final String code;
  final String message;
  final int? statusCode;

  bool get sessionExpired => code == 'MC_CLOUD_UNAUTHENTICATED';

  @override
  String toString() => message;
}

class McCloudSession {
  const McCloudSession({required this.signedIn, this.user});

  final bool signedIn;
  final McCloudUser? user;

  factory McCloudSession.fromJson(McJson json) {
    final user = _mapOrNull(json['user']);
    return McCloudSession(
      signedIn: _bool(json['signedIn']),
      user: user == null ? null : McCloudUser.fromJson(user),
    );
  }
}

class McCloudUser {
  const McCloudUser({
    required this.id,
    required this.name,
    required this.username,
    required this.email,
    required this.avatarUrl,
  });

  final String id;
  final String name;
  final String username;
  final String email;
  final String avatarUrl;

  String get displayName =>
      name.isNotEmpty ? name : (username.isNotEmpty ? username : email);

  factory McCloudUser.fromJson(McJson json) => McCloudUser(
    id: _string(json['id']),
    name: _string(json['name']),
    username: _string(json['username']),
    email: _string(json['email']),
    avatarUrl: _string(json['avatarUrl'] ?? json['avatar_url']),
  );
}

class McCloudWallet {
  const McCloudWallet({
    required this.balance,
    required this.dailyBalance,
    required this.dailyLimit,
  });

  final int balance;
  final int dailyBalance;
  final int dailyLimit;
  int get credits => balance ~/ 1000;
  double get dailyProgress => dailyLimit <= 0
      ? 0
      : ((dailyLimit - dailyBalance) / dailyLimit).clamp(0, 1).toDouble();

  factory McCloudWallet.fromJson(McJson json) => McCloudWallet(
    balance: _int(json['balance']),
    dailyBalance: _int(
      json['dailyTokenBalance'] ?? json['daily_token_balance'],
    ),
    dailyLimit: _int(json['dailyTokenLimit'] ?? json['daily_token_limit']),
  );
}

class McCloudSubscription {
  const McCloudSubscription({required this.plan, this.expiresAt});
  final String plan;
  final String? expiresAt;

  factory McCloudSubscription.fromJson(McJson json) => McCloudSubscription(
    plan: _string(json['plan'], fallback: 'basic'),
    expiresAt: _nullableString(json['expiresAt'] ?? json['expires_at']),
  );
}

class McCloudInvitation {
  const McCloudInvitation({
    required this.id,
    required this.name,
    required this.avatarUrl,
    required this.credits,
  });

  final String id;
  final String name;
  final String avatarUrl;
  final int credits;

  factory McCloudInvitation.fromJson(McJson json) => McCloudInvitation(
    id: _string(json['id']),
    name: _string(json['name']),
    avatarUrl: _string(json['avatarUrl'] ?? json['avatar_url']),
    credits: _int(json['credits']),
  );
}

class McCloudDashboard {
  const McCloudDashboard({
    this.wallet,
    this.checkedIn = false,
    this.invitationCount = 0,
    this.subscription,
    this.errors = const {},
  });
  final McCloudWallet? wallet;
  final bool checkedIn;
  final int invitationCount;
  final McCloudSubscription? subscription;
  final Map<String, String> errors;

  factory McCloudDashboard.fromJson(McJson json) {
    final invitation = _mapOrNull(json['invitations']);
    return McCloudDashboard(
      wallet: _mapOrNull(json['wallet']) == null
          ? null
          : McCloudWallet.fromJson(_map(json['wallet'])),
      checkedIn: _bool(
        _mapOrNull(json['checkin'])?['checkedIn'] ??
            _mapOrNull(json['checkin'])?['checked_in'],
      ),
      invitationCount: _int(invitation?['count']),
      subscription: _mapOrNull(json['subscription']) == null
          ? null
          : McCloudSubscription.fromJson(_map(json['subscription'])),
      errors: _stringMap(json['errors']),
    );
  }
}

class McCloudGitIdentity {
  const McCloudGitIdentity({
    required this.id,
    required this.platform,
    required this.username,
    required this.email,
    required this.baseUrl,
    this.remark,
    this.repositories = const [],
  });
  final String id;
  final String platform;
  final String username;
  final String email;
  final String baseUrl;
  final String? remark;
  final List<McCloudRepository> repositories;

  factory McCloudGitIdentity.fromJson(McJson json) => McCloudGitIdentity(
    id: _string(json['id']),
    platform: _string(json['platform']),
    username: _string(json['username']),
    email: _string(json['email']),
    baseUrl: _string(json['baseUrl'] ?? json['base_url']),
    remark: _nullableString(json['remark']),
    repositories: _maps(
      json['authorizedRepositories'] ?? json['authorized_repositories'],
    ).map(McCloudRepository.fromJson).toList(),
  );
}

class McCloudRepository {
  const McCloudRepository({required this.url, required this.fullName});
  final String url;
  final String fullName;
  factory McCloudRepository.fromJson(McJson json) => McCloudRepository(
    url: _string(json['url']),
    fullName: _string(json['fullName'] ?? json['full_name']),
  );
}

class McCloudModel {
  const McCloudModel({
    required this.id,
    required this.model,
    required this.provider,
    required this.ownerType,
    required this.locked,
    this.remark,
    this.baseUrl,
    this.interfaceType,
  });
  final String id;
  final String model;
  final String provider;
  final String ownerType;
  final bool locked;
  final String? remark;
  final String? baseUrl;
  final String? interfaceType;
  bool get editable => ownerType == 'private';

  factory McCloudModel.fromJson(McJson json) => McCloudModel(
    id: _string(json['id']),
    model: _string(json['model']),
    provider: _string(json['provider']),
    ownerType: _string(_mapOrNull(json['owner'])?['type'], fallback: 'public'),
    locked: _bool(json['locked']),
    remark: _nullableString(json['remark']),
    baseUrl: _nullableString(json['baseUrl'] ?? json['base_url']),
    interfaceType: _nullableString(
      json['interfaceType'] ?? json['interface_type'],
    ),
  );
}

class McCloudProject {
  const McCloudProject({
    required this.id,
    required this.name,
    this.description,
    this.repoUrl,
    this.platform,
  });
  final String id;
  final String name;
  final String? description;
  final String? repoUrl;
  final String? platform;
  factory McCloudProject.fromJson(McJson json) => McCloudProject(
    id: _string(json['id']),
    name: _string(json['name'] ?? json['fullName'] ?? json['full_name']),
    description: _nullableString(json['description']),
    repoUrl: _nullableString(json['repoUrl'] ?? json['repo_url']),
    platform: _nullableString(json['platform']),
  );
}

class McCloudTask {
  const McCloudTask({
    required this.id,
    required this.title,
    required this.status,
    this.content,
    this.summary,
    this.repoUrl,
    this.branch,
    this.createdAt,
  });
  final String id;
  final String title;
  final String status;
  final String? content;
  final String? summary;
  final String? repoUrl;
  final String? branch;
  final int? createdAt;

  factory McCloudTask.fromJson(McJson json) => McCloudTask(
    id: _string(json['id']),
    title: _string(
      json['title'],
      fallback: _string(json['content'], fallback: '未命名任务'),
    ),
    status: _string(json['status'], fallback: 'unknown'),
    content: _nullableString(json['content']),
    summary: _nullableString(json['summary']),
    repoUrl: _nullableString(json['repoUrl'] ?? json['repo_url']),
    branch: _nullableString(json['branch']),
    createdAt: _nullableInt(json['createdAt'] ?? json['created_at']),
  );
}

class McCloudTaskChunk {
  const McCloudTaskChunk({
    required this.event,
    required this.kind,
    required this.data,
    required this.timestamp,
    required this.sequence,
  });
  final String event;
  final String kind;
  final String data;
  final int timestamp;
  final int sequence;
  factory McCloudTaskChunk.fromJson(McJson json) => McCloudTaskChunk(
    event: _string(json['event']),
    kind: _string(json['kind']),
    data: _string(json['data']),
    timestamp: _normalizeTimestamp(_int(json['timestamp'])),
    sequence: _int(json['seq']),
  );
}

sealed class McCloudEvent {
  const McCloudEvent(this.type);
  final String type;

  factory McCloudEvent.fromJson(McJson json) {
    final type = _string(json['type']);
    if (type == 'wechatLoginState')
      return McWechatStateEvent(_string(json['state']));
    if (type == 'wechatLoginCompleted')
      return McWechatCompletedEvent(McCloudUser.fromJson(_map(json['user'])));
    if (type == 'wechatLoginFailed')
      return McWechatFailedEvent(_string(json['message'], fallback: '微信登录失败'));
    if (type == 'sessionExpired') return const McSessionExpiredEvent();
    if (type.toLowerCase().contains('transfer') ||
        type.toLowerCase().contains('progress') ||
        type.startsWith('upload') ||
        type.startsWith('download')) {
      final total = _double(json['total']);
      final transferred = _double(json['transferred']);
      return McTransferEvent(
        type,
        operationId: _string(json['operationId']),
        progress: total > 0
            ? (transferred / total).clamp(0, 1).toDouble()
            : _double(json['progress']),
        message: _nullableString(json['message']),
      );
    }
    return McTaskStreamEvent(
      type,
      taskId: _string(json['taskId'] ?? json['id']),
      payload: json,
    );
  }
}

class McWechatStateEvent extends McCloudEvent {
  const McWechatStateEvent(this.state) : super('wechatLoginState');
  final String state;
}

class McWechatCompletedEvent extends McCloudEvent {
  const McWechatCompletedEvent(this.user) : super('wechatLoginCompleted');
  final McCloudUser user;
}

class McWechatFailedEvent extends McCloudEvent {
  const McWechatFailedEvent(this.message) : super('wechatLoginFailed');
  final String message;
}

class McSessionExpiredEvent extends McCloudEvent {
  const McSessionExpiredEvent() : super('sessionExpired');
}

class McTaskStreamEvent extends McCloudEvent {
  const McTaskStreamEvent(
    super.type, {
    required this.taskId,
    required this.payload,
  });
  final String taskId;
  final McJson payload;
}

class McTransferEvent extends McCloudEvent {
  const McTransferEvent(
    super.type, {
    required this.operationId,
    required this.progress,
    this.message,
  });
  final String operationId;
  final double progress;
  final String? message;
}

class McCloudService {
  McCloudService._();
  static const MethodChannel _channel = MethodChannel(
    'cn.com.omnimind.bot/McCloudAccount',
  );
  static const EventChannel _events = EventChannel(
    'cn.com.omnimind.bot/McCloudAccountEvents',
  );
  static Stream<McCloudEvent>? _eventStream;

  static Stream<McCloudEvent> get events => _eventStream ??= _events
      .receiveBroadcastStream()
      .map((value) => McCloudEvent.fromJson(_map(value)))
      .handleError((Object error) {
        throw _failure(error);
      });

  static Future<McCloudSession> getSessionState() =>
      _model('getSessionState', McCloudSession.fromJson);
  static Future<McCloudUser> loginWithPassword(String email, String password) =>
      _model('loginWithPassword', McCloudUser.fromJson, {
        'email': email,
        'password': password,
      });
  static Future<void> sendPhoneCode(String phone) =>
      _void('sendPhoneCode', {'phone': phone});
  static Future<McCloudUser> loginWithPhone(String phone, String code) =>
      _model('loginWithPhone', McCloudUser.fromJson, {
        'phone': phone,
        'code': code,
      });
  static Future<McJson> prepareAlipayAppLogin() =>
      _json('prepareAlipayAppLogin');
  static Future<McJson> loginWithAlipayApp(String code, String requestId) =>
      _json('loginWithAlipayApp', {'code': code, 'requestId': requestId});
  static Future<McJson> prepareDouyinAppLogin() =>
      _json('prepareDouyinAppLogin');
  static Future<McJson> loginWithDouyinApp(String code) =>
      _json('loginWithDouyinApp', {'code': code});
  static Future<McJson> getThirdPartyLoginCapabilities() =>
      _json('getThirdPartyLoginCapabilities');
  static Future<McJson> authorizeThirdPartyApp(
    String platform,
    McJson authorization,
  ) => _json('authorizeThirdPartyApp', {
    'platform': platform,
    'authorization': authorization,
  });
  static Future<McCloudUser> completePhoneBind(String phone, String code) =>
      _model('completePhoneBind', McCloudUser.fromJson, {
        'phone': phone,
        'code': code,
      });
  static Future<Uri> getGithubLoginUrl({String? inviterId}) async => Uri.parse(
    _string((await _json('loginWithGithub', {'inviterId': inviterId}))['url']),
  );
  static Future<bool> completeGithubLogin(String callbackUrl) async => _bool(
    (await _json('completeGithubLogin', {
      'callbackUrl': callbackUrl,
    }))['completed'],
  );
  static Future<bool> importWebSession(String url) async =>
      _bool((await _json('importWebSession', {'url': url}))['imported']);
  static Future<Uri> getBaizhiGithubLoginUrl(String redirectUrl) async =>
      Uri.parse(
        _string(
          (await _json('getBaizhiGithubLoginUrl', {
            'redirectUrl': redirectUrl,
          }))['url'],
        ),
      );
  static Future<String> startWechatLogin() async =>
      _string((await _json('startWechatLogin'))['qrDataUrl']);
  static Future<void> cancelWechatLogin() => _void('cancelWechatLogin');
  static Future<void> logout() => _void('logout');
  static Future<void> deleteAccount() => _void('deleteAccount');
  static Future<void> bindEmail(String email) =>
      _void('bindEmail', {'email': email});
  static Future<McCloudWallet> getWallet() =>
      _model('getWallet', McCloudWallet.fromJson);
  static Future<bool> getCheckinStatus() async {
    final value = await _json('getCheckinStatus');
    return _bool(value['checkedIn'] ?? value['checked_in']);
  }

  static Future<List<McCloudInvitation>> listInvitations({
    int page = 1,
    int size = 50,
  }) => _list(
    'listInvitations',
    McCloudInvitation.fromJson,
    arguments: {'page': page, 'size': size},
    keys: const ['items', 'invitations'],
  );
  static Future<McCloudSubscription> getSubscription() =>
      _model('getSubscription', McCloudSubscription.fromJson);
  static Future<McCloudDashboard> getDashboard() =>
      _model('getDashboard', McCloudDashboard.fromJson);
  static Future<McCloudDashboard> submitCheckin() =>
      _model('submitCheckin', McCloudDashboard.fromJson);

  static Future<List<McCloudGitIdentity>> listGitIdentities() => _list(
    'listGitIdentities',
    McCloudGitIdentity.fromJson,
    keys: const ['items', 'identities'],
  );
  static Future<McCloudGitIdentity> getGitIdentity(
    String id, {
    bool flush = false,
  }) => _model('getGitIdentity', McCloudGitIdentity.fromJson, {
    'id': id,
    'flush': flush,
  });
  static Future<Uri> getGitOAuthUrl(String platform, {String? base}) async =>
      Uri.parse(
        _string(
          (await _json('getGitOAuthUrl', {
            'platform': platform,
            'base': base,
          }))['url'],
        ),
      );
  static Future<McCloudGitIdentity> addGitIdentity(McJson fields) =>
      _model('addGitIdentity', McCloudGitIdentity.fromJson, {'fields': fields});
  static Future<void> updateGitIdentity(String id, McJson fields) =>
      _void('updateGitIdentity', {'id': id, 'fields': fields});
  static Future<void> deleteGitIdentity(String id) =>
      _void('deleteGitIdentity', {'id': id});

  static Future<List<McCloudModel>> listModels() async {
    final value = await _json('listModels');
    return _maps(value['models'] ?? value['items'])
        .where((item) => !_bool(item['isHidden'] ?? item['is_hidden']))
        .map(McCloudModel.fromJson)
        .where((item) => item.id.isNotEmpty && item.model.isNotEmpty)
        .toList();
  }

  static Future<McCloudModel> createModel(McJson fields) =>
      _model('createModel', McCloudModel.fromJson, {'fields': fields});
  static Future<void> updateModel(String id, McJson fields) =>
      _void('updateModel', {'id': id, 'fields': fields});
  static Future<void> deleteModel(String id) =>
      _void('deleteModel', {'id': id});
  static Future<McJson> checkModelConfig(McJson fields) =>
      _json('checkModelConfig', {'fields': fields});
  static Future<List<String>> listProviderModels({
    required String apiKey,
    required String baseUrl,
    required String provider,
  }) async {
    final raw = await _invoke('listProviderModels', {
      'apiKey': apiKey,
      'baseUrl': baseUrl,
      'provider': provider,
    });
    final values = raw is List ? _maps(raw) : _maps(_map(raw)['models']);
    return values
        .map((item) => _string(item['model']))
        .where((item) => item.isNotEmpty)
        .toList();
  }

  static Future<McCloudPage<McCloudProject>> listProjects({
    String? cursor,
    int size = 20,
  }) async {
    final value = await _json('listProjects', {
      'cursor': cursor,
      'limit': size,
    });
    final page = _map(value['page']);
    return McCloudPage(
      _maps(
        value['projects'] ?? value['items'],
      ).map(McCloudProject.fromJson).toList(),
      nextCursor: _nullableString(page['cursor'] ?? value['next_cursor']),
      hasMore: _bool(page['has_more'] ?? value['has_more']),
    );
  }

  static Future<McCloudProject> createProject(McJson fields) =>
      _model('createProject', McCloudProject.fromJson, {'fields': fields});
  static Future<McCloudProject> getProjectDetail(String id) =>
      _model('getProjectDetail', McCloudProject.fromJson, {'id': id});
  static Future<McCloudPage<McCloudTask>> listTasks({
    String? projectId,
    int page = 1,
    int size = 20,
  }) async {
    final value = await _json('listTasks', {
      'projectId': projectId,
      'page': page,
      'size': size,
    });
    final pageInfo = _map(value['page_info'] ?? value['pageInfo']);
    return McCloudPage(
      _maps(
        value['tasks'] ?? value['items'],
      ).map(McCloudTask.fromJson).toList(),
      hasMore: _bool(pageInfo['has_next_page'] ?? pageInfo['hasNextPage']),
    );
  }

  static Future<McCloudTask> getTaskDetail(String id) =>
      _model('getTaskDetail', McCloudTask.fromJson, {'id': id});
  static Future<McCloudTask> createTask(McJson fields) =>
      _model('createTask', McCloudTask.fromJson, {'fields': fields});
  static Future<McJson> getTaskOptions() => _json('getTaskOptions');
  static Future<void> stopTask(String id) => _void('stopTask', {'id': id});
  static Future<void> deleteTask(String id) => _void('deleteTask', {'id': id});
  static Future<McCloudPage<McCloudTaskChunk>> getTaskRounds(
    String id, {
    String? cursor,
    int size = 20,
  }) async {
    final value = await _json('getTaskRounds', {
      'id': id,
      'cursor': cursor,
      'limit': size,
    });
    return McCloudPage(
      _maps(
        value['chunks'] ?? value['items'],
      ).map(McCloudTaskChunk.fromJson).toList(),
      nextCursor: _nullableString(value['next_cursor'] ?? value['nextCursor']),
      hasMore: _bool(value['has_more'] ?? value['hasMore']),
    );
  }

  static Future<McCloudPage<McJson>> getTaskUserInputs(
    String id, {
    String? cursor,
    int size = 20,
  }) async {
    final value = await _json('getTaskUserInputs', {
      'id': id,
      'cursor': cursor,
      'limit': size,
    });
    return McCloudPage(
      _maps(value['items']),
      nextCursor: _nullableString(value['next_cursor'] ?? value['nextCursor']),
      hasMore: _bool(value['has_more'] ?? value['hasMore']),
    );
  }

  static Future<void> openTaskStream(String id) =>
      _void('openTaskStream', {'id': id, 'mode': 'stream'});
  static Future<bool> sendTaskStreamMessage(String id, String data) async =>
      _bool(
        (await _json('sendTaskStreamMessage', {
          'id': id,
          'data': data,
        }))['sent'],
      );
  static Future<void> closeTaskStream(String id) =>
      _void('closeTaskStream', {'id': id});
  static Future<String> uploadAttachment(
    String operationId,
    String sourcePath,
  ) async => _string(
    (await _json('uploadAttachment', {
      'operationId': operationId,
      'sourcePath': sourcePath,
    }))['url'],
  );
  static Future<void> uploadVmFile(
    String operationId,
    String vmId,
    String path,
    String sourcePath,
  ) => _void('uploadVmFile', {
    'operationId': operationId,
    'vmId': vmId,
    'path': path,
    'sourcePath': sourcePath,
  });
  static Future<int> downloadVmFile(
    String operationId,
    String vmId,
    String path,
    String filename,
    String destinationPath,
  ) async => _int(
    (await _json('downloadVmFile', {
      'operationId': operationId,
      'vmId': vmId,
      'path': path,
      'filename': filename,
      'destinationPath': destinationPath,
    }))['bytes'],
  );
  static Future<bool> cancelTransfer(String operationId) async => _bool(
    (await _json('cancelTransfer', {'operationId': operationId}))['canceled'],
  );

  static Future<void> _void(String method, [McJson? arguments]) async {
    await _invoke(method, arguments);
  }

  static Future<McJson> _json(String method, [McJson? arguments]) async =>
      _map(await _invoke(method, arguments));
  static Future<T> _model<T>(
    String method,
    T Function(McJson) parse, [
    McJson? arguments,
  ]) async => parse(await _json(method, arguments));
  static Future<List<T>> _list<T>(
    String method,
    T Function(McJson) parse, {
    McJson? arguments,
    List<String> keys = const [],
  }) async {
    final raw = await _invoke(method, arguments);
    if (raw is List) return _maps(raw).map(parse).toList();
    final json = _map(raw);
    for (final key in keys) {
      if (json[key] is List) return _maps(json[key]).map(parse).toList();
    }
    return <T>[];
  }

  static Future<Object?> _invoke(String method, [McJson? arguments]) async {
    try {
      return await _channel.invokeMethod<Object?>(method, arguments);
    } catch (error) {
      throw _failure(error);
    }
  }
}

McCloudFailure _failure(Object error) {
  if (error is McCloudFailure) return error;
  if (error is MissingPluginException) {
    return McCloudFailure(
      'MC_CLOUD_NATIVE_METHOD_UNAVAILABLE',
      error.message ?? '当前安装包缺少所需原生能力',
    );
  }
  if (error is PlatformException) {
    final details = _mapOrNull(error.details);
    return McCloudFailure(
      error.code,
      _cleanMessage(error.message),
      statusCode: _nullableInt(details?['statusCode']),
    );
  }
  return const McCloudFailure(
    'MC_CLOUD_OPERATION_FAILED',
    'MonkeyCode 云操作失败，请稍后重试',
  );
}

String _cleanMessage(String? value) {
  final message = value?.trim() ?? '';
  if (message.isEmpty) return 'MonkeyCode 云操作失败，请稍后重试';
  return message
      .replaceFirst(
        RegExp(r'\s*\[trace[_ -]?id[:=][^\]]+\]\s*$', caseSensitive: false),
        '',
      )
      .trim();
}

McJson _map(Object? value) => _mapOrNull(value) ?? <String, Object?>{};
McJson? _mapOrNull(Object? value) => value is Map
    ? value.map((key, nested) => MapEntry(key.toString(), nested))
    : null;
List<McJson> _maps(Object? value) =>
    value is List ? value.map(_map).toList() : const [];
String _string(Object? value, {String fallback = ''}) {
  final text = value?.toString().trim();
  return text == null || text.isEmpty ? fallback : text;
}

String? _nullableString(Object? value) {
  final text = _string(value);
  return text.isEmpty ? null : text;
}

int _int(Object? value) =>
    value is num ? value.toInt() : int.tryParse('$value') ?? 0;
int? _nullableInt(Object? value) => value == null ? null : _int(value);
double _double(Object? value) =>
    value is num ? value.toDouble() : double.tryParse('$value') ?? 0;
bool _bool(Object? value) =>
    value == true || value == 1 || value?.toString().toLowerCase() == 'true';
Map<String, String> _stringMap(Object? value) =>
    _map(value).map((key, nested) => MapEntry(key, _string(nested)));
int _normalizeTimestamp(int value) =>
    value > 0 && value < 100000000000 ? value * 1000 : value;
