import 'dart:async';
import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/core/mixins/page_lifecycle_mixin.dart';
import 'package:path_provider/path_provider.dart';
import 'package:ui/features/home/pages/webview/webview_page.dart';
import 'package:ui/services/mc_cloud_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/settings_section_title.dart';

abstract class _CloudListState<T extends StatefulWidget> extends State<T>
    with WidgetsBindingObserver, PageLifecycleMixin<T> {
  bool loading = true;
  bool busy = false;
  String? error;

  Future<void> load();

  @override
  void onPageResumed() => load();

  Future<void> run(Future<void> Function() action) async {
    setState(() {
      busy = true;
      error = null;
    });
    try {
      await action();
    } catch (value) {
      if (mounted) setState(() => error = _cloudMessage(value));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Widget shell(
    String title,
    List<Widget> children, {
    List<Widget>? actions,
    Widget? floatingActionButton,
  }) => Scaffold(
    backgroundColor: context.omniPalette.pageBackground,
    appBar: CommonAppBar(title: title, primary: true, actions: actions),
    floatingActionButton: floatingActionButton,
    body: Stack(
      children: [
        Positioned.fill(
          child: loading
              ? const Center(child: CircularProgressIndicator())
              : RefreshIndicator(
                  onRefresh: load,
                  child: ListView(
                    padding: edgeToEdgeScrollPadding(
                      context,
                      const EdgeInsets.fromLTRB(18, 10, 18, 90),
                    ),
                    children: [
                      ...children,
                      if (error != null)
                        Padding(
                          padding: const EdgeInsets.only(top: 16),
                          child: Text(
                            error!,
                            style: TextStyle(
                              color: Theme.of(context).colorScheme.error,
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
        ),
        if (busy)
          Positioned.fill(
            child: ColoredBox(
              color: context.omniPalette.overlayScrim,
              child: const Center(child: CircularProgressIndicator()),
            ),
          ),
      ],
    ),
  );
}

class McCloudGitIdentitiesPage extends StatefulWidget {
  const McCloudGitIdentitiesPage({super.key});
  @override
  State<McCloudGitIdentitiesPage> createState() =>
      _McCloudGitIdentitiesPageState();
}

class _McCloudGitIdentitiesPageState
    extends _CloudListState<McCloudGitIdentitiesPage> {
  List<McCloudGitIdentity> items = const [];
  @override
  void initState() {
    super.initState();
    load();
  }

  @override
  Future<void> load() async {
    try {
      final value = await McCloudService.listGitIdentities();
      if (mounted)
        setState(() {
          items = value.where((item) => item.platform != 'internal').toList();
          error = null;
        });
    } catch (value) {
      if (mounted) setState(() => error = _cloudMessage(value));
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  @override
  Widget build(BuildContext context) => shell(
    'Git 身份',
    [
      const SettingsSectionTitle(label: '已绑定身份', subtitle: '用于关联代码仓库与创建云端项目'),
      if (items.isEmpty)
        const _CloudEmpty(icon: LucideIcons.gitBranch, text: '尚未绑定 Git 身份'),
      for (final item in items) ...[
        ListTile(
          contentPadding: const EdgeInsets.symmetric(horizontal: 4),
          leading: const Icon(LucideIcons.gitFork),
          title: Text(item.username.isEmpty ? item.platform : item.username),
          subtitle: Text(
            '${item.platform.toUpperCase()}${item.email.isEmpty ? '' : ' · ${item.email}'}',
          ),
          trailing: PopupMenuButton<String>(
            onSelected: (value) {
              if (value == 'edit') _edit(item);
              if (value == 'delete') _delete(item);
            },
            itemBuilder: (_) => const [
              PopupMenuItem(value: 'edit', child: Text('编辑')),
              PopupMenuItem(value: 'delete', child: Text('删除')),
            ],
          ),
          onTap: () => _detail(item),
        ),
        const Divider(height: 1),
      ],
      const SizedBox(height: 24),
      const SettingsSectionTitle(label: 'OAuth 绑定'),
      Wrap(
        spacing: 8,
        runSpacing: 8,
        children: ['github', 'gitlab', 'gitee', 'codeup', 'cnb', 'atomgit']
            .map(
              (platform) => OutlinedButton(
                onPressed: () => _oauth(platform),
                child: Text(platform),
              ),
            )
            .toList(),
      ),
    ],
    floatingActionButton: FloatingActionButton.extended(
      onPressed: () => _edit(null),
      icon: const Icon(LucideIcons.plus),
      label: const Text('手动绑定'),
    ),
  );

  Future<void> _oauth(String platform) => run(() async {
    final uri = await McCloudService.getGitOAuthUrl(platform);
    if (!mounted) return;
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (webContext) => WebViewPage(
          url: uri.toString(),
          title: '$platform OAuth',
          appBarBackClosesPage: true,
          onNavigationUrl: (url) async {
            if (!isMcCloudOAuthCallbackUrl(url)) return false;
            try {
              final imported = await McCloudService.importWebSession(url);
              if (imported && webContext.mounted) Navigator.pop(webContext);
              return imported;
            } on McCloudFailure catch (error) {
              if (error.code == 'MC_CLOUD_NATIVE_METHOD_UNAVAILABLE')
                return false;
              rethrow;
            }
          },
        ),
      ),
    );
    await load();
  });
  Future<void> _detail(McCloudGitIdentity item) => run(() async {
    final detail = await McCloudService.getGitIdentity(item.id, flush: true);
    if (!mounted) return;
    await showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (_) => SafeArea(
        child: ListView(
          shrinkWrap: true,
          padding: const EdgeInsets.fromLTRB(20, 0, 20, 24),
          children: [
            Text(
              detail.username,
              style: Theme.of(context).textTheme.titleLarge,
            ),
            const SizedBox(height: 12),
            if (detail.repositories.isEmpty) const Text('该身份暂无可访问仓库'),
            for (final repo in detail.repositories)
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: const Icon(LucideIcons.folderGit2),
                title: Text(repo.fullName),
                subtitle: Text(repo.url),
              ),
          ],
        ),
      ),
    );
  });
  Future<void> _delete(McCloudGitIdentity item) async {
    final confirmed = await _confirm(context, '删除 Git 身份？', '关联项目可能阻止删除。');
    if (confirmed)
      await run(() async {
        await McCloudService.deleteGitIdentity(item.id);
        await load();
      });
  }

  Future<void> _edit(McCloudGitIdentity? item) async {
    final platform = TextEditingController(text: item?.platform ?? 'github');
    final base = TextEditingController(text: item?.baseUrl);
    final username = TextEditingController(text: item?.username);
    final email = TextEditingController(text: item?.email);
    final token = TextEditingController();
    try {
      final fields = await _form(
        context,
        title: item == null ? '手动绑定 Git 身份' : '编辑 Git 身份',
        fields: [
          _field(platform, '平台'),
          _field(base, '服务地址'),
          _field(username, '用户名'),
          _field(email, '邮箱', type: TextInputType.emailAddress),
          _field(token, item == null ? '访问令牌' : '新访问令牌（留空保持）', obscure: true),
        ],
        value: () => <String, Object?>{
          'platform': platform.text.trim(),
          'base_url': base.text.trim(),
          'username': username.text.trim(),
          'email': email.text.trim(),
          if (token.text.isNotEmpty) 'access_token': token.text,
        },
      );
      if (fields != null)
        await run(() async {
          if (item == null) {
            await McCloudService.addGitIdentity(fields);
          } else {
            await McCloudService.updateGitIdentity(item.id, fields);
          }
          await load();
        });
    } finally {
      token.clear();
      for (final controller in [platform, base, username, email, token]) {
        controller.dispose();
      }
    }
  }
}

class McCloudModelsPage extends StatefulWidget {
  const McCloudModelsPage({super.key});
  @override
  State<McCloudModelsPage> createState() => _McCloudModelsPageState();
}

class _McCloudModelsPageState extends _CloudListState<McCloudModelsPage> {
  List<McCloudModel> items = const [];
  @override
  void initState() {
    super.initState();
    load();
  }

  @override
  Future<void> load() async {
    try {
      final value = await McCloudService.listModels();
      if (mounted)
        setState(() {
          items = value;
          error = null;
        });
    } catch (value) {
      if (mounted) setState(() => error = _cloudMessage(value));
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  @override
  Widget build(BuildContext context) => shell(
    '云模型',
    [
      const SettingsSectionTitle(
        label: 'MonkeyCode 云模型',
        subtitle: '锁定模型需升级对应会员档位',
      ),
      if (items.isEmpty)
        const _CloudEmpty(icon: LucideIcons.brainCircuit, text: '云端暂无模型'),
      for (final item in items) ...[
        Opacity(
          opacity: item.locked ? .48 : 1,
          child: ListTile(
            enabled: !item.locked,
            contentPadding: const EdgeInsets.symmetric(horizontal: 4),
            leading: Icon(
              item.locked ? LucideIcons.lockKeyhole : LucideIcons.bot,
            ),
            title: Text(item.model),
            subtitle: Text(
              '${item.provider} · ${_ownerLabel(item.ownerType)}${item.remark == null ? '' : '\n${item.remark}'}',
            ),
            isThreeLine: item.remark != null,
            trailing: item.editable && !item.locked
                ? PopupMenuButton<String>(
                    onSelected: (value) {
                      if (value == 'edit') _edit(item);
                      if (value == 'delete') _delete(item);
                    },
                    itemBuilder: (_) => const [
                      PopupMenuItem(value: 'edit', child: Text('编辑')),
                      PopupMenuItem(value: 'delete', child: Text('删除')),
                    ],
                  )
                : null,
          ),
        ),
        const Divider(height: 1),
      ],
      const SizedBox(height: 24),
      const SettingsSectionTitle(label: '本地模型'),
      ListTile(
        contentPadding: const EdgeInsets.symmetric(horizontal: 4),
        leading: const Icon(LucideIcons.keyRound),
        title: const Text('本地 BYOK 设置'),
        subtitle: const Text('管理保存在本机的服务地址和 API Key'),
        trailing: const Icon(LucideIcons.chevronRight, size: 18),
        onTap: () => context.push('/home/model_provider_setting'),
      ),
    ],
    floatingActionButton: FloatingActionButton.extended(
      onPressed: () => _edit(null),
      icon: const Icon(LucideIcons.plus),
      label: const Text('添加模型'),
    ),
  );
  String _ownerLabel(String owner) =>
      {'private': '我的模型', 'team': '团队模型', 'public': '会员模型'}[owner] ?? '共享模型';
  Future<void> _delete(McCloudModel item) async {
    if (await _confirm(
      context,
      '删除 ${item.model}？',
      '此操作将从 MonkeyCode 云移除模型配置。',
    ))
      await run(() async {
        await McCloudService.deleteModel(item.id);
        await load();
      });
  }

  Future<void> _edit(McCloudModel? item) async {
    final model = TextEditingController(text: item?.model);
    final provider = TextEditingController(text: item?.provider ?? 'openai');
    final base = TextEditingController(text: item?.baseUrl);
    final key = TextEditingController();
    final interfaceType = TextEditingController(
      text: item?.interfaceType ?? 'openai',
    );
    try {
      final fields = await _form(
        context,
        title: item == null ? '添加云模型' : '编辑云模型',
        fields: [
          _field(model, '模型 ID'),
          _field(provider, '提供商'),
          _field(base, 'Base URL'),
          _field(
            key,
            item == null ? 'API Key' : '新 API Key（留空保持）',
            obscure: true,
          ),
          _field(interfaceType, '接口类型'),
        ],
        value: () => {
          'model': model.text.trim(),
          'provider': provider.text.trim(),
          'base_url': base.text.trim(),
          if (key.text.isNotEmpty) 'api_key': key.text,
          'interface_type': interfaceType.text.trim(),
        },
      );
      if (fields != null)
        await run(() async {
          final check = await McCloudService.checkModelConfig(fields);
          if (check['success'] != true)
            throw StateError('${check['error'] ?? '健康检查失败，模型未保存'}');
          if (item == null) {
            await McCloudService.createModel(fields);
          } else {
            await McCloudService.updateModel(item.id, fields);
          }
          await load();
        });
    } finally {
      key.clear();
      for (final controller in [model, provider, base, key, interfaceType]) {
        controller.dispose();
      }
    }
  }
}

class McCloudProjectsPage extends StatefulWidget {
  const McCloudProjectsPage({super.key});
  @override
  State<McCloudProjectsPage> createState() => _McCloudProjectsPageState();
}

class _McCloudProjectsPageState extends _CloudListState<McCloudProjectsPage> {
  static const _pageSize = 20;
  List<McCloudProject> projects = const [];
  List<McCloudTask> tasks = const [];
  String? _projectCursor;
  int _taskPage = 1;
  bool _hasMoreProjects = false;
  bool _hasMoreTasks = false;
  bool _loadingMore = false;
  @override
  void initState() {
    super.initState();
    load();
  }

  @override
  Future<void> load() async {
    try {
      final values = await Future.wait([
        McCloudService.listProjects(size: _pageSize),
        McCloudService.listTasks(size: _pageSize),
      ]);
      final projectPage = values[0] as McCloudPage<McCloudProject>;
      final taskPage = values[1] as McCloudPage<McCloudTask>;
      if (mounted)
        setState(() {
          projects = projectPage.items;
          tasks = taskPage.items;
          _projectCursor = projectPage.nextCursor;
          _taskPage = 1;
          _hasMoreProjects = projectPage.hasMore;
          _hasMoreTasks = taskPage.hasMore;
          error = null;
        });
    } catch (value) {
      if (mounted) setState(() => error = _cloudMessage(value));
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  @override
  Widget build(BuildContext context) => shell(
    '项目与任务',
    [
      const SettingsSectionTitle(label: '云端项目'),
      if (projects.isEmpty)
        const _CloudEmpty(icon: LucideIcons.folderGit2, text: '尚未创建云端项目'),
      for (final item in projects) ...[
        ListTile(
          contentPadding: const EdgeInsets.symmetric(horizontal: 4),
          leading: const Icon(LucideIcons.folderGit2),
          title: Text(item.name),
          subtitle: Text(item.description ?? item.repoUrl ?? '-'),
        ),
        const Divider(height: 1),
      ],
      if (_hasMoreProjects)
        Center(
          child: TextButton(
            onPressed: _loadingMore ? null : _loadMoreProjects,
            child: const Text('加载更多项目'),
          ),
        ),
      const SizedBox(height: 26),
      const SettingsSectionTitle(label: '最近任务'),
      if (tasks.isEmpty)
        const _CloudEmpty(icon: LucideIcons.listChecks, text: '尚无云端任务'),
      for (final task in tasks) ...[
        ListTile(
          contentPadding: const EdgeInsets.symmetric(horizontal: 4),
          leading: _StatusDot(status: task.status),
          title: Text(task.title),
          subtitle: Text(
            '${task.status}${task.branch == null ? '' : ' · ${task.branch}'}',
          ),
          trailing: const Icon(LucideIcons.chevronRight, size: 18),
          onTap: () async {
            await context.push(
              '/my/account/cloud-tasks/${task.id}',
              extra: task,
            );
            await load();
          },
        ),
        const Divider(height: 1),
      ],
      if (_hasMoreTasks)
        Center(
          child: TextButton(
            onPressed: _loadingMore ? null : _loadMoreTasks,
            child: const Text('加载更多任务'),
          ),
        ),
    ],
    actions: [
      IconButton(
        onPressed: _createProject,
        icon: const Icon(LucideIcons.folderPlus),
        tooltip: '创建项目',
      ),
    ],
    floatingActionButton: FloatingActionButton.extended(
      onPressed: _createTask,
      icon: const Icon(LucideIcons.play),
      label: const Text('创建任务'),
    ),
  );
  Future<void> _loadMoreProjects() async {
    setState(() => _loadingMore = true);
    try {
      final next = await McCloudService.listProjects(
        cursor: _projectCursor,
        size: _pageSize,
      );
      if (mounted)
        setState(() {
          projects = [...projects, ...next.items];
          _projectCursor = next.nextCursor;
          _hasMoreProjects = next.hasMore;
        });
    } catch (value) {
      if (mounted) setState(() => error = _cloudMessage(value));
    } finally {
      if (mounted) setState(() => _loadingMore = false);
    }
  }

  Future<void> _loadMoreTasks() async {
    setState(() => _loadingMore = true);
    try {
      final next = await McCloudService.listTasks(
        page: _taskPage + 1,
        size: _pageSize,
      );
      if (mounted)
        setState(() {
          tasks = [...tasks, ...next.items];
          _taskPage++;
          _hasMoreTasks = next.hasMore;
        });
    } catch (value) {
      if (mounted) setState(() => error = _cloudMessage(value));
    } finally {
      if (mounted) setState(() => _loadingMore = false);
    }
  }

  Future<void> _createProject() => run(() async {
    final identities = await McCloudService.listGitIdentities();
    if (!mounted) return;
    if (identities.isEmpty) throw StateError('请先绑定 Git 身份');
    final name = TextEditingController();
    final repo = TextEditingController();
    final description = TextEditingController();
    try {
      String identityId = identities.first.id;
      final fields = await showModalBottomSheet<McJson>(
        context: context,
        isScrollControlled: true,
        showDragHandle: true,
        builder: (sheetContext) => StatefulBuilder(
          builder: (context, setSheetState) => Padding(
            padding: EdgeInsets.fromLTRB(
              20,
              0,
              20,
              MediaQuery.viewInsetsOf(context).bottom + 24,
            ),
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text('创建云端项目', style: Theme.of(context).textTheme.titleLarge),
                  const SizedBox(height: 16),
                  DropdownButtonFormField<String>(
                    initialValue: identityId,
                    decoration: const InputDecoration(labelText: 'Git 身份'),
                    items: identities
                        .map(
                          (item) => DropdownMenuItem(
                            value: item.id,
                            child: Text('${item.platform} · ${item.username}'),
                          ),
                        )
                        .toList(),
                    onChanged: (value) =>
                        setSheetState(() => identityId = value ?? identityId),
                  ),
                  const SizedBox(height: 12),
                  _field(name, '项目名称'),
                  _field(repo, '仓库 URL'),
                  _field(description, '描述'),
                  FilledButton(
                    onPressed: () => Navigator.pop(sheetContext, {
                      'name': name.text.trim(),
                      'repo_url': repo.text.trim(),
                      'description': description.text.trim(),
                      'git_identity_id': identityId,
                      'platform': identities
                          .firstWhere((item) => item.id == identityId)
                          .platform,
                    }),
                    child: const Text('创建'),
                  ),
                ],
              ),
            ),
          ),
        ),
      );
      if (fields == null) return;
      if ('${fields['name']}'.trim().isEmpty ||
          '${fields['repo_url']}'.trim().isEmpty)
        throw StateError('请填写项目名称和仓库 URL');
      await McCloudService.createProject(fields);
      await load();
    } finally {
      for (final controller in [name, repo, description]) {
        controller.dispose();
      }
    }
  });
  Future<void> _createTask() => run(() async {
    final options = await McCloudService.getTaskOptions();
    if (!mounted) return;
    final content = TextEditingController();
    final title = TextEditingController();
    final models = _optionMaps(options['models']);
    final hosts = _optionMaps(options['hosts']);
    final images = _optionMaps(options['images']);
    final defaults = options['taskDefaults'] is Map
        ? Map<String, Object?>.from(options['taskDefaults'] as Map)
        : options['task_defaults'] is Map
        ? Map<String, Object?>.from(options['task_defaults'] as Map)
        : <String, Object?>{};
    try {
      String? model = _optionId(
        models.where((item) => !_optionLocked(item)).firstOrNull,
      );
      String? host = _optionId(hosts.firstOrNull);
      String? image = _optionId(images.firstOrNull);
      String? project = projects.firstOrNull?.id;
      final fields = await showModalBottomSheet<McJson>(
        context: context,
        isScrollControlled: true,
        showDragHandle: true,
        builder: (sheetContext) => StatefulBuilder(
          builder: (context, setSheetState) => Padding(
            padding: EdgeInsets.fromLTRB(
              20,
              0,
              20,
              MediaQuery.viewInsetsOf(context).bottom + 24,
            ),
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text('创建云端任务', style: Theme.of(context).textTheme.titleLarge),
                  const SizedBox(height: 16),
                  _field(title, '标题'),
                  _field(content, '任务说明', lines: 4),
                  if (models.isNotEmpty)
                    DropdownButtonFormField(
                      value: model,
                      decoration: const InputDecoration(labelText: '模型'),
                      items: _optionItems(models, disableLocked: true),
                      onChanged: (value) => setSheetState(() => model = value),
                    ),
                  if (hosts.isNotEmpty)
                    DropdownButtonFormField(
                      value: host,
                      decoration: const InputDecoration(labelText: '宿主'),
                      items: _optionItems(hosts),
                      onChanged: (value) => setSheetState(() => host = value),
                    ),
                  if (images.isNotEmpty)
                    DropdownButtonFormField(
                      value: image,
                      decoration: const InputDecoration(labelText: '镜像'),
                      items: _optionItems(images),
                      onChanged: (value) => setSheetState(() => image = value),
                    ),
                  if (projects.isNotEmpty)
                    DropdownButtonFormField(
                      value: project,
                      decoration: const InputDecoration(labelText: '项目'),
                      items: projects
                          .map(
                            (item) => DropdownMenuItem(
                              value: item.id,
                              child: Text(item.name),
                            ),
                          )
                          .toList(),
                      onChanged: (value) =>
                          setSheetState(() => project = value),
                    ),
                  const SizedBox(height: 18),
                  FilledButton(
                    onPressed: model == null && models.isNotEmpty
                        ? null
                        : () => Navigator.pop(sheetContext, {
                            'title': title.text.trim(),
                            'content': content.text.trim(),
                            if (model != null) 'model_id': model,
                            if (host != null) 'host_id': host,
                            if (image != null) 'image_id': image,
                            if (project != null) 'project_id': project,
                          }),
                    child: const Text('创建并运行'),
                  ),
                ],
              ),
            ),
          ),
        ),
      );
      if (fields != null) {
        if ('${fields['content']}'.trim().isEmpty) throw StateError('请填写任务说明');
        fields['cli_name'] =
            defaults['cli_name'] ?? defaults['cliName'] ?? 'opencode';
        fields['resource'] =
            defaults['resource'] ??
            <String, Object?>{
              'core': 2,
              'memory': 8 * 1024 * 1024 * 1024,
              'life': 3 * 60 * 60,
            };
        final task = await McCloudService.createTask(fields);
        await load();
        if (mounted)
          await context.push('/my/account/cloud-tasks/${task.id}', extra: task);
      }
    } finally {
      content.clear();
      for (final controller in [content, title]) {
        controller.dispose();
      }
    }
  });
}

class McCloudTaskDetailPage extends StatefulWidget {
  const McCloudTaskDetailPage({
    super.key,
    required this.taskId,
    this.initialTask,
  });
  final String taskId;
  final McCloudTask? initialTask;
  @override
  State<McCloudTaskDetailPage> createState() => _McCloudTaskDetailPageState();
}

class _McCloudTaskDetailPageState
    extends _CloudListState<McCloudTaskDetailPage> {
  static const _pageSize = 20;
  static const _maxLiveEvents = 300;
  McCloudTask? task;
  List<McCloudTaskChunk> chunks = const [];
  List<McJson> inputs = const [];
  final live = <String>[];
  StreamSubscription<McCloudEvent>? events;
  String? _roundCursor;
  String? _inputCursor;
  bool _hasMoreRounds = false;
  bool _hasMoreInputs = false;
  bool _loadingMore = false;
  @override
  void initState() {
    super.initState();
    task = widget.initialTask;
    events = McCloudService.events.listen(_event, onError: (_) {});
    load();
    McCloudService.openTaskStream(widget.taskId).catchError((_) {});
  }

  @override
  void dispose() {
    events?.cancel();
    McCloudService.closeTaskStream(widget.taskId).catchError((_) {});
    super.dispose();
  }

  void _event(McCloudEvent event) {
    if (event is McTaskStreamEvent && event.taskId == widget.taskId) {
      final data = event.payload['data']?.toString();
      if (mounted)
        setState(() {
          if (data != null && data.isNotEmpty) {
            live.add(data);
            if (live.length > _maxLiveEvents)
              live.removeRange(0, live.length - _maxLiveEvents);
          }
        });
      if (event.type == 'taskClosed') load();
    }
  }

  @override
  Future<void> load() async {
    try {
      final values = await Future.wait([
        McCloudService.getTaskDetail(widget.taskId),
        McCloudService.getTaskRounds(widget.taskId, size: _pageSize),
        McCloudService.getTaskUserInputs(widget.taskId, size: _pageSize),
      ]);
      final roundPage = values[1] as McCloudPage<McCloudTaskChunk>;
      final inputPage = values[2] as McCloudPage<McJson>;
      if (mounted)
        setState(() {
          task = values[0] as McCloudTask;
          chunks = roundPage.items;
          inputs = inputPage.items;
          _roundCursor = roundPage.nextCursor;
          _inputCursor = inputPage.nextCursor;
          _hasMoreRounds = roundPage.hasMore;
          _hasMoreInputs = inputPage.hasMore;
          error = null;
        });
    } catch (value) {
      if (mounted) setState(() => error = _cloudMessage(value));
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  @override
  Widget build(BuildContext context) => shell(
    task?.title ?? '任务详情',
    [
      SettingsSectionTitle(label: '状态', subtitle: task?.status ?? '加载中'),
      if (task?.content != null)
        Padding(
          padding: const EdgeInsets.only(bottom: 20),
          child: Text(task!.content!),
        ),
      Row(
        children: [
          Expanded(
            child: OutlinedButton.icon(
              onPressed: () => run(() async {
                await McCloudService.stopTask(widget.taskId);
                await load();
              }),
              icon: const Icon(LucideIcons.square),
              label: const Text('停止当前轮'),
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: OutlinedButton.icon(
              onPressed: _files,
              icon: const Icon(LucideIcons.folderUp),
              label: const Text('VM 文件'),
            ),
          ),
        ],
      ),
      const SizedBox(height: 24),
      const SettingsSectionTitle(label: '执行流'),
      Container(
        height: 320,
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: context.omniPalette.surfaceSecondary,
          borderRadius: BorderRadius.circular(14),
        ),
        child: ListView.builder(
          primary: false,
          itemCount: chunks.length + live.length,
          itemBuilder: (context, index) {
            if (index < chunks.length) {
              final item = chunks[index];
              return SelectableText(
                item.data,
                key: ValueKey('round-${item.sequence}'),
                style: const TextStyle(fontFamily: 'monospace', height: 1.45),
              );
            }
            final liveIndex = index - chunks.length;
            return SelectableText(
              live[liveIndex],
              key: ValueKey('live-$liveIndex'),
              style: const TextStyle(fontFamily: 'monospace', height: 1.45),
            );
          },
        ),
      ),
      if (_hasMoreRounds)
        Center(
          child: TextButton(
            onPressed: _loadingMore ? null : _loadMoreRounds,
            child: const Text('加载更多执行记录'),
          ),
        ),
      if (inputs.isNotEmpty) ...[
        const SizedBox(height: 24),
        const SettingsSectionTitle(label: '提问索引'),
        for (final input in inputs)
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: const Icon(LucideIcons.messageCircleQuestion),
            title: Text('${input['content'] ?? input['question'] ?? ''}'),
            subtitle: input['answer'] == null
                ? null
                : Text('${input['answer']}'),
          ),
      ],
      if (_hasMoreInputs)
        Center(
          child: TextButton(
            onPressed: _loadingMore ? null : _loadMoreInputs,
            child: const Text('加载更多提问'),
          ),
        ),
    ],
    actions: [
      IconButton(
        onPressed: _delete,
        icon: const Icon(LucideIcons.trash2),
        tooltip: '删除任务',
      ),
    ],
  );
  Future<void> _delete() async {
    if (await _confirm(context, '删除此任务？', '云端任务与执行记录将被移除。'))
      await run(() async {
        await McCloudService.deleteTask(widget.taskId);
        if (mounted) context.pop(true);
      });
  }

  Future<void> _loadMoreRounds() async {
    setState(() => _loadingMore = true);
    try {
      final next = await McCloudService.getTaskRounds(
        widget.taskId,
        cursor: _roundCursor,
        size: _pageSize,
      );
      if (mounted)
        setState(() {
          chunks = [...chunks, ...next.items];
          _roundCursor = next.nextCursor;
          _hasMoreRounds = next.hasMore;
        });
    } catch (value) {
      if (mounted) setState(() => error = _cloudMessage(value));
    } finally {
      if (mounted) setState(() => _loadingMore = false);
    }
  }

  Future<void> _loadMoreInputs() async {
    setState(() => _loadingMore = true);
    try {
      final next = await McCloudService.getTaskUserInputs(
        widget.taskId,
        cursor: _inputCursor,
        size: _pageSize,
      );
      if (mounted)
        setState(() {
          inputs = [...inputs, ...next.items];
          _inputCursor = next.nextCursor;
          _hasMoreInputs = next.hasMore;
        });
    } catch (value) {
      if (mounted) setState(() => error = _cloudMessage(value));
    } finally {
      if (mounted) setState(() => _loadingMore = false);
    }
  }

  Future<void> _files() => showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    builder: (_) => McCloudTaskFilesSheet(vmId: widget.taskId),
  );
}

class McCloudTaskFilesSheet extends StatefulWidget {
  const McCloudTaskFilesSheet({super.key, required this.vmId});
  final String vmId;
  @override
  State<McCloudTaskFilesSheet> createState() => _McCloudTaskFilesSheetState();
}

class _McCloudTaskFilesSheetState extends State<McCloudTaskFilesSheet> {
  final path = TextEditingController(text: '/workspace/');
  final remote = TextEditingController();
  String? operationId;
  double? progress;
  String? message;
  StreamSubscription<McCloudEvent>? events;
  @override
  void initState() {
    super.initState();
    events = McCloudService.events.listen((event) {
      if (event is McTransferEvent &&
          event.operationId == operationId &&
          mounted)
        setState(() {
          progress = event.progress;
          message = event.message ?? event.type;
        });
    });
  }

  @override
  void dispose() {
    events?.cancel();
    path.clear();
    remote.clear();
    path.dispose();
    remote.dispose();
    super.dispose();
  }

  String _id() => 'flutter-${DateTime.now().microsecondsSinceEpoch}';
  Future<void> _upload() async {
    final picked = await FilePicker.platform.pickFiles();
    final source = picked?.files.single.path;
    if (source == null) return;
    final id = _id();
    final target = path.text.trim().endsWith('/')
        ? '${path.text.trim()}${picked!.files.single.name}'
        : path.text.trim();
    setState(() {
      operationId = id;
      progress = 0;
      message = '上传中';
    });
    try {
      await McCloudService.uploadVmFile(id, widget.vmId, target, source);
      if (mounted)
        setState(() {
          progress = 1;
          message = '上传完成';
        });
    } catch (value) {
      if (mounted) setState(() => message = '$value');
    }
  }

  Future<void> _attachment() async {
    final picked = await FilePicker.platform.pickFiles();
    final source = picked?.files.single.path;
    if (source == null) return;
    final id = _id();
    setState(() {
      operationId = id;
      progress = 0;
      message = '附件上传中';
    });
    try {
      final url = await McCloudService.uploadAttachment(id, source);
      if (mounted)
        setState(() {
          progress = 1;
          message = '附件已上传：$url';
        });
    } catch (value) {
      if (mounted) setState(() => message = '$value');
    }
  }

  Future<void> _download() async {
    if (remote.text.trim().isEmpty) return;
    final directory = await getApplicationDocumentsDirectory();
    final filename = vmDownloadFilename(remote.text.trim());
    final destination = File('${directory.path}/$filename').absolute.path;
    final id = _id();
    setState(() {
      operationId = id;
      progress = 0;
      message = '下载中';
    });
    try {
      await McCloudService.downloadVmFile(
        id,
        widget.vmId,
        remote.text.trim(),
        filename,
        destination,
      );
      if (mounted)
        setState(() {
          progress = 1;
          message = '已保存到 $destination';
        });
    } catch (value) {
      if (mounted) setState(() => message = '$value');
    }
  }

  @override
  Widget build(BuildContext context) => SafeArea(
    child: Padding(
      padding: EdgeInsets.fromLTRB(
        20,
        0,
        20,
        MediaQuery.viewInsetsOf(context).bottom + 24,
      ),
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('VM 工作区文件', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 16),
            TextField(
              controller: path,
              decoration: const InputDecoration(labelText: '上传目标绝对路径'),
            ),
            const SizedBox(height: 10),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: _upload,
                    icon: const Icon(LucideIcons.upload),
                    label: const Text('上传到 VM'),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: _attachment,
                    icon: const Icon(LucideIcons.paperclip),
                    label: const Text('上传附件'),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 18),
            TextField(
              controller: remote,
              decoration: const InputDecoration(labelText: 'VM 文件或目录绝对路径'),
            ),
            const SizedBox(height: 10),
            FilledButton.icon(
              onPressed: _download,
              icon: const Icon(LucideIcons.download),
              label: const Text('下载到应用文档目录'),
            ),
            if (progress != null) ...[
              const SizedBox(height: 16),
              LinearProgressIndicator(value: progress == 0 ? null : progress),
              const SizedBox(height: 6),
              Text(message ?? ''),
            ],
            if (operationId != null && progress != 1)
              TextButton(
                onPressed: () => McCloudService.cancelTransfer(operationId!),
                child: const Text('取消传输'),
              ),
          ],
        ),
      ),
    ),
  );
}

class _CloudEmpty extends StatelessWidget {
  const _CloudEmpty({required this.icon, required this.text});
  final IconData icon;
  final String text;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 28),
    child: Column(
      children: [
        Icon(icon, size: 34, color: context.omniPalette.textTertiary),
        const SizedBox(height: 10),
        Text(text, style: TextStyle(color: context.omniPalette.textSecondary)),
      ],
    ),
  );
}

class _StatusDot extends StatelessWidget {
  const _StatusDot({required this.status});
  final String status;
  @override
  Widget build(BuildContext context) {
    final active = {
      'running',
      'pending',
      'created',
    }.contains(status.toLowerCase());
    return Container(
      width: 11,
      height: 11,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: active
            ? context.omniPalette.accentPrimary
            : context.omniPalette.textTertiary,
      ),
    );
  }
}

Future<bool> _confirm(
  BuildContext context,
  String title,
  String message,
) async =>
    await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: Text(title),
        content: Text(message),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('确认'),
          ),
        ],
      ),
    ) ??
    false;
Widget _field(
  TextEditingController controller,
  String label, {
  bool obscure = false,
  TextInputType? type,
  int lines = 1,
}) => Padding(
  padding: const EdgeInsets.only(bottom: 12),
  child: TextField(
    controller: controller,
    obscureText: obscure,
    keyboardType: type,
    maxLines: obscure ? 1 : lines,
    decoration: InputDecoration(labelText: label),
  ),
);
Future<McJson?> _form(
  BuildContext context, {
  required String title,
  required List<Widget> fields,
  required McJson Function() value,
  Widget? extraAction,
}) => showModalBottomSheet<McJson>(
  context: context,
  isScrollControlled: true,
  showDragHandle: true,
  builder: (sheetContext) => Padding(
    padding: EdgeInsets.fromLTRB(
      20,
      0,
      20,
      MediaQuery.viewInsetsOf(sheetContext).bottom + 24,
    ),
    child: SingleChildScrollView(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(title, style: Theme.of(sheetContext).textTheme.titleLarge),
          const SizedBox(height: 16),
          ...fields,
          if (extraAction != null)
            Align(alignment: Alignment.centerLeft, child: extraAction),
          FilledButton(
            onPressed: () => Navigator.pop(sheetContext, value()),
            child: const Text('保存'),
          ),
        ],
      ),
    ),
  ),
);
List<McJson> _optionMaps(Object? value) => value is List
    ? value
          .whereType<Map>()
          .map(
            (item) =>
                item.map((key, nested) => MapEntry(key.toString(), nested)),
          )
          .toList()
    : const [];
String? _optionId(McJson? item) =>
    item == null ? null : '${item['id'] ?? item['model'] ?? ''}';
bool _optionLocked(McJson item) =>
    item['locked'] == true ||
    item['locked'] == 1 ||
    '${item['locked']}'.toLowerCase() == 'true';
List<DropdownMenuItem<String>> _optionItems(
  List<McJson> items, {
  bool disableLocked = false,
}) => items.map((item) {
  final id = _optionId(item)!;
  final locked = disableLocked && _optionLocked(item);
  return DropdownMenuItem(
    value: id,
    enabled: !locked,
    child: Opacity(
      opacity: locked ? .45 : 1,
      child: Row(
        children: [
          if (locked)
            const Padding(
              padding: EdgeInsets.only(right: 6),
              child: Icon(LucideIcons.lockKeyhole, size: 15),
            ),
          Flexible(child: Text('${item['name'] ?? item['model'] ?? id}')),
        ],
      ),
    ),
  );
}).toList();
String vmDownloadFilename(String path) {
  final normalized = path.trim().replaceFirst(RegExp(r'/+$'), '');
  final basename = normalized.split('/').lastOrNull?.trim();
  final safeName = basename == null || basename.isEmpty
      ? 'workspace'
      : basename.replaceAll(RegExp(r'[^a-zA-Z0-9._-]'), '_');
  final looksLikeFile = safeName.contains('.') && !path.trim().endsWith('/');
  return looksLikeFile ? safeName : '$safeName.zip';
}

String _cloudMessage(Object error) {
  if (error is McCloudFailure) return error.message;
  if (error is StateError) return '${error.message}';
  return 'MonkeyCode 云操作失败，请稍后重试';
}
