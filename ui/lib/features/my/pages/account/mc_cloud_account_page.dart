import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/core/mixins/page_lifecycle_mixin.dart';
import 'package:ui/features/home/pages/webview/webview_page.dart';
import 'package:ui/services/mc_cloud_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/settings_section_title.dart';

class AccountPage extends StatefulWidget {
  const AccountPage({super.key})
    : authOnly = false,
      onAuthenticated = null,
      showAuthHeading = true;
  const AccountPage.authOnly({
    super.key,
    this.onAuthenticated,
    this.showAuthHeading = true,
  }) : authOnly = true;

  final bool authOnly;
  final VoidCallback? onAuthenticated;
  final bool showAuthHeading;

  @override
  State<AccountPage> createState() => _AccountPageState();
}

class _AccountPageState extends State<AccountPage>
    with WidgetsBindingObserver, PageLifecycleMixin<AccountPage> {
  final _email = TextEditingController();
  final _password = TextEditingController();
  final _phone = TextEditingController();
  final _code = TextEditingController();
  StreamSubscription<McCloudEvent>? _events;
  McCloudSession? _session;
  McCloudDashboard? _dashboard;
  bool _phoneMode = false;
  bool _busy = false;
  bool _loading = true;
  int _countdown = 0;
  Timer? _timer;
  String? _error;
  int _loadGeneration = 0;
  final _wechatStatus = ValueNotifier<String>('waiting');
  BuildContext? _wechatDialogContext;
  Map<String, String?> _thirdPartyUnavailable = const {
    'alipay': '当前安装包未集成支付宝 App SDK',
    'douyin': '当前安装包未集成抖音 App SDK',
  };
  List<McCloudInvitation> _invitations = const [];

  bool get _english => Localizations.localeOf(context).languageCode != 'zh';
  String _t(String zh, String en) => _english ? en : zh;

  @override
  void initState() {
    super.initState();
    _events = McCloudService.events.listen(_handleEvent, onError: (_) {});
    _load();
  }

  @override
  void onPageResumed() => _load();

  @override
  void dispose() {
    _events?.cancel();
    _timer?.cancel();
    for (final controller in [_email, _password, _phone, _code]) {
      controller.clear();
      controller.dispose();
    }
    _wechatStatus.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    final generation = ++_loadGeneration;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final session = await McCloudService.getSessionState();
      final results = await Future.wait<Object?>([
        if (session.signedIn)
          McCloudService.getDashboard()
        else
          Future.value(null),
        if (session.signedIn)
          McCloudService.listInvitations()
        else
          Future.value(const <McCloudInvitation>[]),
        if (!session.signedIn)
          _loadThirdPartyCapabilities()
        else
          Future.value(null),
      ]);
      if (mounted && generation == _loadGeneration)
        setState(() {
          _session = session;
          _dashboard = results[0] as McCloudDashboard?;
          _invitations = results[1] as List<McCloudInvitation>;
          final capabilities = results[2] as McJson?;
          if (capabilities != null)
            _thirdPartyUnavailable = _capabilityReasons(capabilities);
        });
    } catch (error) {
      if (mounted && generation == _loadGeneration) {
        setState(() => _error = _message(error));
      }
    } finally {
      if (mounted && generation == _loadGeneration) {
        setState(() => _loading = false);
      }
    }
  }

  Future<McJson> _loadThirdPartyCapabilities() async {
    try {
      return await McCloudService.getThirdPartyLoginCapabilities();
    } on McCloudFailure catch (error) {
      if (error.code == 'MC_CLOUD_NATIVE_METHOD_UNAVAILABLE') return const {};
      rethrow;
    }
  }

  Future<void> _run(Future<void> Function() action) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await action();
    } catch (error) {
      if (mounted) setState(() => _error = _message(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _signIn() => _run(() async {
    try {
      if (_phoneMode) {
        if (_phone.text.trim().length < 11 || _code.text.trim().isEmpty)
          throw StateError(
            _t('请填写正确手机号和验证码', 'Enter a valid phone number and code'),
          );
        await McCloudService.loginWithPhone(
          _phone.text.trim(),
          _code.text.trim(),
        );
      } else {
        if (!_email.text.contains('@') || _password.text.isEmpty)
          throw StateError(
            _t('请填写正确邮箱和密码', 'Enter a valid email and password'),
          );
        await McCloudService.loginWithPassword(
          _email.text.trim(),
          _password.text,
        );
      }
      await _load();
      widget.onAuthenticated?.call();
    } finally {
      _password.clear();
      _code.clear();
    }
  });

  Future<void> _sendCode() => _run(() async {
    if (_phone.text.trim().length < 11)
      throw StateError(_t('请填写正确手机号', 'Enter a valid phone number'));
    await McCloudService.sendPhoneCode(_phone.text.trim());
    _timer?.cancel();
    setState(() => _countdown = 60);
    _timer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (!mounted || _countdown <= 1) {
        timer.cancel();
        if (mounted) setState(() => _countdown = 0);
      } else {
        setState(() => _countdown--);
      }
    });
  });

  Future<void> _github() => _run(() async {
    final url = await McCloudService.getGithubLoginUrl();
    if (!mounted) return;
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (webContext) => WebViewPage(
          url: url.toString(),
          title: 'GitHub',
          appBarBackClosesPage: true,
          onNavigationUrl: (callbackUrl) async {
            if (!isMcCloudOAuthCallbackUrl(callbackUrl)) return false;
            var completed = false;
            try {
              completed = await McCloudService.completeGithubLogin(callbackUrl);
            } on McCloudFailure catch (error) {
              if (error.code != 'MC_CLOUD_NATIVE_METHOD_UNAVAILABLE') rethrow;
            }
            if (!completed) {
              try {
                completed = await McCloudService.importWebSession(callbackUrl);
              } on McCloudFailure catch (error) {
                if (error.code != 'MC_CLOUD_NATIVE_METHOD_UNAVAILABLE') rethrow;
              }
            }
            if (completed && webContext.mounted) Navigator.of(webContext).pop();
            return completed;
          },
        ),
      ),
    );
    await _load();
    if (_session?.signedIn == true) widget.onAuthenticated?.call();
  });

  Future<void> _wechat() => _run(() async {
    _wechatStatus.value = 'waiting';
    final dataUrl = await McCloudService.startWechatLogin();
    if (!mounted) return;
    final comma = dataUrl.indexOf(',');
    final bytes = base64Decode(
      comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl,
    );
    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) {
        _wechatDialogContext = dialogContext;
        return AlertDialog(
          title: Text(_t('微信扫码登录', 'WeChat QR sign-in')),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Image.memory(bytes, width: 220, height: 220),
              const SizedBox(height: 12),
              ValueListenableBuilder<String>(
                valueListenable: _wechatStatus,
                builder: (_, state, _) => Text(
                  _wechatStateLabel(state),
                  key: const ValueKey('wechat-status'),
                ),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () async {
                await McCloudService.cancelWechatLogin();
                if (dialogContext.mounted) Navigator.pop(dialogContext);
              },
              child: Text(_t('取消', 'Cancel')),
            ),
          ],
        );
      },
    );
    _wechatDialogContext = null;
  });

  void _closeWechatDialog() {
    final dialogContext = _wechatDialogContext;
    if (dialogContext == null || !dialogContext.mounted) return;
    if (ModalRoute.of(dialogContext)?.isCurrent == true) {
      Navigator.of(dialogContext).pop();
    }
    _wechatDialogContext = null;
  }

  void _handleEvent(McCloudEvent event) {
    if (!mounted) return;
    if (event is McWechatStateEvent) {
      _wechatStatus.value = event.state;
      if (event.state == 'expired' || event.state == 'canceled') {
        _closeWechatDialog();
        setState(() {
          _error = event.state == 'expired'
              ? _t('二维码已过期，请重新发起', 'QR code expired. Start again.')
              : _t('微信登录已取消', 'WeChat sign-in was canceled');
        });
      }
    } else if (event is McWechatCompletedEvent) {
      _closeWechatDialog();
      _load().then((_) => widget.onAuthenticated?.call());
    } else if (event is McWechatFailedEvent) {
      _closeWechatDialog();
      setState(() => _error = event.message);
    } else if (event is McSessionExpiredEvent) {
      _loadGeneration++;
      setState(() {
        _session = const McCloudSession(signedIn: false);
        _dashboard = null;
        _error = _t('登录已过期，请重新登录', 'Session expired. Sign in again.');
      });
    }
  }

  String _wechatStateLabel(String state) => switch (state) {
    'scanned' ||
    'confirmed' => _t('已扫码，请在微信中确认', 'Scanned. Confirm in WeChat.'),
    'expired' => _t('二维码已过期', 'QR code expired'),
    'canceled' => _t('登录已取消', 'Sign-in canceled'),
    _ => _t('等待扫码', 'Waiting for scan'),
  };

  String _message(Object error) {
    if (error is McCloudFailure) return error.message;
    if (error is StateError) return '${error.message}';
    return _t(
      'MonkeyCode 云操作失败，请稍后重试',
      'MonkeyCode Cloud operation failed. Try again later.',
    );
  }

  @override
  Widget build(BuildContext context) {
    final body = Stack(
      children: [
        Positioned.fill(child: _body()),
        if (_busy)
          Positioned.fill(
            child: ColoredBox(
              color: context.omniPalette.overlayScrim,
              child: const Center(child: CircularProgressIndicator()),
            ),
          ),
      ],
    );
    if (widget.authOnly)
      return ColoredBox(
        key: const ValueKey('account-auth-only-surface'),
        color: Colors.transparent,
        child: body,
      );
    return Scaffold(
      backgroundColor: context.omniPalette.pageBackground,
      appBar: CommonAppBar(
        title: _t('MonkeyCode 云', 'MonkeyCode Cloud'),
        primary: true,
      ),
      body: body,
    );
  }

  Widget _body() {
    if (_loading) return const Center(child: CircularProgressIndicator());
    if (_session?.signedIn == true)
      return widget.authOnly ? _authenticated() : _profile();
    return _login();
  }

  Widget _login() => ListView(
    key: const ValueKey('mc-cloud-login'),
    padding: const EdgeInsets.fromLTRB(22, 18, 22, 32),
    children: [
      if (widget.showAuthHeading) ...[
        Icon(
          LucideIcons.cloud,
          size: 38,
          color: context.omniPalette.accentPrimary,
        ),
        const SizedBox(height: 12),
        Text(
          _t('连接 MonkeyCode 云', 'Connect to MonkeyCode Cloud'),
          textAlign: TextAlign.center,
          style: Theme.of(
            context,
          ).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w700),
        ),
        const SizedBox(height: 6),
        Text(
          _t('同步账户、模型与云端开发任务', 'Sync your account, models, and cloud tasks'),
          textAlign: TextAlign.center,
          style: TextStyle(color: context.omniPalette.textSecondary),
        ),
        const SizedBox(height: 20),
      ],
      SegmentedButton<bool>(
        key: const ValueKey('account-auth-mode-selector'),
        segments: [
          ButtonSegment(value: false, label: Text(_t('邮箱', 'Email'))),
          ButtonSegment(value: true, label: Text(_t('手机', 'Phone'))),
        ],
        selected: {_phoneMode},
        onSelectionChanged: (value) => setState(() => _phoneMode = value.first),
      ),
      const SizedBox(height: 18),
      if (_phoneMode) ...[
        TextField(
          controller: _phone,
          keyboardType: TextInputType.phone,
          decoration: InputDecoration(
            labelText: _t('手机号', 'Phone number'),
            prefixIcon: const Icon(LucideIcons.smartphone),
          ),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _code,
          keyboardType: TextInputType.number,
          decoration: InputDecoration(
            labelText: _t('验证码', 'Code'),
            prefixIcon: const Icon(LucideIcons.messageSquare),
            suffixIcon: TextButton(
              onPressed: _countdown == 0 ? _sendCode : null,
              child: Text(_countdown == 0 ? _t('发送', 'Send') : '$_countdown s'),
            ),
          ),
        ),
      ] else ...[
        TextField(
          controller: _email,
          keyboardType: TextInputType.emailAddress,
          decoration: InputDecoration(
            labelText: _t('邮箱', 'Email'),
            prefixIcon: const Icon(LucideIcons.mail),
          ),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _password,
          obscureText: true,
          onSubmitted: (_) => _signIn(),
          decoration: InputDecoration(
            labelText: _t('密码', 'Password'),
            prefixIcon: const Icon(LucideIcons.lockKeyhole),
          ),
        ),
      ],
      const SizedBox(height: 18),
      FilledButton(
        key: const ValueKey('submit-auth'),
        onPressed: _signIn,
        child: Text(_t('登录', 'Sign in')),
      ),
      const SizedBox(height: 20),
      Row(
        children: [
          const Expanded(child: Divider()),
          Flexible(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              child: Text(
                _t('第三方登录', 'Other sign-in options'),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ),
          ),
          const Expanded(child: Divider()),
        ],
      ),
      const SizedBox(height: 12),
      Wrap(
        alignment: WrapAlignment.center,
        spacing: 8,
        runSpacing: 8,
        children: [
          OutlinedButton.icon(
            onPressed: _github,
            icon: const Icon(LucideIcons.gitFork),
            label: const Text('GitHub'),
          ),
          OutlinedButton.icon(
            onPressed: _wechat,
            icon: const Icon(LucideIcons.scanLine),
            label: Text(_t('微信', 'WeChat')),
          ),
          Tooltip(
            message: _thirdPartyUnavailable['alipay'] ?? '',
            child: OutlinedButton.icon(
              onPressed: _thirdPartyUnavailable.containsKey('alipay')
                  ? null
                  : () => _loginThirdParty('alipay'),
              icon: const Icon(LucideIcons.walletCards),
              label: Text(_t('支付宝', 'Alipay')),
            ),
          ),
          Tooltip(
            message: _thirdPartyUnavailable['douyin'] ?? '',
            child: OutlinedButton.icon(
              onPressed: _thirdPartyUnavailable.containsKey('douyin')
                  ? null
                  : () => _loginThirdParty('douyin'),
              icon: const Icon(LucideIcons.music2),
              label: Text(_t('抖音', 'Douyin')),
            ),
          ),
        ],
      ),
      for (final reason
          in _thirdPartyUnavailable.values.whereType<String>().toSet())
        Padding(
          padding: const EdgeInsets.only(top: 6),
          child: Text(
            reason,
            textAlign: TextAlign.center,
            style: TextStyle(
              fontSize: 12,
              color: context.omniPalette.textTertiary,
            ),
          ),
        ),
      if (_error != null)
        Padding(
          padding: const EdgeInsets.only(top: 14),
          child: Text(
            _error!,
            textAlign: TextAlign.center,
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
        ),
    ],
  );

  Future<void> _loginThirdParty(String platform) => _run(() async {
    final prepared = platform == 'alipay'
        ? await McCloudService.prepareAlipayAppLogin()
        : await McCloudService.prepareDouyinAppLogin();
    final authorization = await McCloudService.authorizeThirdPartyApp(
      platform,
      prepared,
    );
    final code = '${authorization['code'] ?? authorization['authCode'] ?? ''}'
        .trim();
    if (code.isEmpty)
      throw StateError(
        _t('授权未返回有效 code', 'Authorization did not return a valid code'),
      );
    final result = platform == 'alipay'
        ? await McCloudService.loginWithAlipayApp(
            code,
            '${prepared['requestId'] ?? prepared['request_id'] ?? ''}',
          )
        : await McCloudService.loginWithDouyinApp(code);
    if (result['requiresPhoneBind'] == true ||
        result['requires_phone_bind'] == true) {
      await _showPhoneBind();
    }
    await _load();
    if (_session?.signedIn == true) widget.onAuthenticated?.call();
  });

  Future<void> _showPhoneBind() async {
    final phone = TextEditingController();
    final code = TextEditingController();
    try {
      final fields = await showDialog<List<String>>(
        context: context,
        barrierDismissible: false,
        builder: (dialogContext) => AlertDialog(
          title: Text(_t('绑定手机号', 'Bind phone number')),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: phone,
                keyboardType: TextInputType.phone,
                decoration: InputDecoration(
                  labelText: _t('手机号', 'Phone number'),
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: code,
                keyboardType: TextInputType.number,
                decoration: InputDecoration(
                  labelText: _t('验证码', 'Code'),
                  suffixIcon: TextButton(
                    onPressed: () =>
                        McCloudService.sendPhoneCode(phone.text.trim()),
                    child: Text(_t('发送', 'Send')),
                  ),
                ),
              ),
            ],
          ),
          actions: [
            FilledButton(
              onPressed: () => Navigator.pop(dialogContext, [
                phone.text.trim(),
                code.text.trim(),
              ]),
              child: Text(_t('完成绑定', 'Complete binding')),
            ),
          ],
        ),
      );
      if (fields == null || fields[0].length < 11 || fields[1].isEmpty)
        throw StateError(
          _t('请填写正确手机号和验证码', 'Enter a valid phone number and code'),
        );
      await McCloudService.completePhoneBind(fields[0], fields[1]);
    } finally {
      phone.clear();
      code.clear();
      phone.dispose();
      code.dispose();
    }
  }

  Widget _authenticated() => Center(
    child: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(
          LucideIcons.circleCheckBig,
          size: 42,
          color: context.omniPalette.accentPrimary,
        ),
        const SizedBox(height: 12),
        Text(
          _t('MonkeyCode 云已连接', 'MonkeyCode Cloud connected'),
          style: Theme.of(context).textTheme.titleLarge,
        ),
        const SizedBox(height: 6),
        Text(_session?.user?.email ?? ''),
        if (widget.onAuthenticated != null)
          Padding(
            padding: const EdgeInsets.only(top: 20),
            child: FilledButton(
              onPressed: widget.onAuthenticated,
              child: Text(_t('完成', 'Done')),
            ),
          ),
      ],
    ),
  );

  Widget _profile() {
    final user = _session!.user;
    final wallet = _dashboard?.wallet;
    final subscription = _dashboard?.subscription;
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(
        padding: edgeToEdgeScrollPadding(
          context,
          const EdgeInsets.fromLTRB(18, 10, 18, 32),
        ),
        children: [
          SettingsSectionTitle(label: _t('云账户', 'Cloud account')),
          ListTile(
            contentPadding: const EdgeInsets.symmetric(horizontal: 4),
            leading: _avatar(user?.avatarUrl, user?.displayName ?? 'M'),
            title: Text(user?.displayName ?? '-'),
            subtitle: Text('${user?.email ?? '-'}\nID ${user?.id ?? '-'}'),
            isThreeLine: true,
          ),
          const Divider(),
          Row(
            children: [
              Expanded(
                child: _metric(_t('积分', 'Credits'), '${wallet?.credits ?? 0}'),
              ),
              Expanded(
                child: _metric(
                  _t('会员', 'Plan'),
                  (subscription?.plan ?? 'basic').toUpperCase(),
                ),
              ),
              Expanded(
                child: _metric(
                  _t('邀请', 'Invites'),
                  '${_dashboard?.invitationCount ?? 0}',
                ),
              ),
            ],
          ),
          if (subscription?.expiresAt != null)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(
                _t(
                  '会员有效期：${subscription!.expiresAt}',
                  'Plan expires: ${subscription.expiresAt}',
                ),
                textAlign: TextAlign.center,
                style: TextStyle(color: context.omniPalette.textSecondary),
              ),
            ),
          if (wallet != null && wallet.dailyLimit > 0) ...[
            const SizedBox(height: 16),
            Text(_t('今日模型额度', 'Daily model quota')),
            const SizedBox(height: 7),
            LinearProgressIndicator(value: wallet.dailyProgress),
            const SizedBox(height: 4),
            Text(
              '${wallet.dailyLimit - wallet.dailyBalance} / ${wallet.dailyLimit}',
              style: TextStyle(color: context.omniPalette.textSecondary),
            ),
          ],
          const SizedBox(height: 16),
          OutlinedButton.icon(
            onPressed: _dashboard?.checkedIn == true
                ? null
                : () => _run(() async {
                    _dashboard = await McCloudService.submitCheckin();
                    if (mounted) setState(() {});
                  }),
            icon: const Icon(LucideIcons.calendarCheck),
            label: Text(
              _dashboard?.checkedIn == true
                  ? _t('今日已签到', 'Checked in today')
                  : _t('签到领取 100 积分', 'Check in for 100 credits'),
            ),
          ),
          const SizedBox(height: 24),
          SettingsSectionTitle(label: _t('云端能力', 'Cloud capabilities')),
          _route(
            LucideIcons.gitBranch,
            _t('Git 身份', 'Git identities'),
            '/my/account/git-identities',
          ),
          _route(
            LucideIcons.brainCircuit,
            _t('云模型', 'Cloud models'),
            '/my/account/cloud-models',
          ),
          _route(
            LucideIcons.cloudCog,
            _t('项目与任务', 'Projects & tasks'),
            '/my/account/cloud-projects',
          ),
          const SizedBox(height: 24),
          SettingsSectionTitle(label: _t('本地能力', 'Local capabilities')),
          _route(
            LucideIcons.keyRound,
            _t('本地 BYOK 模型设置', 'Local BYOK model settings'),
            '/home/model_provider_setting',
          ),
          const Divider(height: 1),
          ListTile(
            contentPadding: const EdgeInsets.symmetric(horizontal: 4),
            leading: const Icon(LucideIcons.userPlus),
            title: Text(_t('邀请好友', 'Invite friends')),
            subtitle: Text(
              _t('每位成功邀请奖励 5000 积分', 'Earn 5000 credits for each referral'),
            ),
            trailing: const Icon(LucideIcons.copy, size: 18),
            onTap: () async {
              await Clipboard.setData(
                ClipboardData(
                  text: 'https://monkeycode-ai.com/?ic=${user?.id ?? ''}',
                ),
              );
              showToast(
                _t('邀请链接已复制', 'Invitation link copied'),
                type: ToastType.success,
              );
            },
          ),
          if (_invitations.isNotEmpty) ...[
            const SizedBox(height: 20),
            SettingsSectionTitle(label: _t('邀请记录', 'Invitations')),
            for (final invitation in _invitations)
              ListTile(
                contentPadding: const EdgeInsets.symmetric(horizontal: 4),
                leading: _avatar(invitation.avatarUrl, invitation.name),
                title: Text(invitation.name),
                trailing: Text('+${invitation.credits}'),
              ),
          ],
          const Divider(height: 1),
          ListTile(
            contentPadding: const EdgeInsets.symmetric(horizontal: 4),
            leading: const Icon(LucideIcons.mailCheck),
            title: Text(_t('绑定邮箱', 'Bind email')),
            trailing: const Icon(LucideIcons.chevronRight, size: 18),
            onTap: _bindEmail,
          ),
          if (_dashboard?.errors.isNotEmpty == true)
            Padding(
              padding: const EdgeInsets.only(top: 12),
              child: Text(
                _t('部分云端数据暂未加载', 'Some cloud data is temporarily unavailable'),
                style: TextStyle(color: context.omniPalette.textSecondary),
              ),
            ),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.only(top: 12),
              child: Text(
                _error!,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ),
          const SizedBox(height: 22),
          TextButton.icon(
            onPressed: () => _run(() async {
              await McCloudService.logout();
              await _load();
            }),
            icon: const Icon(LucideIcons.logOut),
            label: Text(_t('退出 MonkeyCode 云', 'Sign out of MonkeyCode Cloud')),
            style: TextButton.styleFrom(
              foregroundColor: Theme.of(context).colorScheme.error,
            ),
          ),
          TextButton.icon(
            onPressed: _deleteAccount,
            icon: const Icon(LucideIcons.trash2),
            label: Text(_t('注销云账户', 'Delete cloud account')),
            style: TextButton.styleFrom(
              foregroundColor: Theme.of(context).colorScheme.error,
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _bindEmail() async {
    final controller = TextEditingController(text: _session?.user?.email);
    try {
      final email = await showDialog<String>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          title: Text(_t('绑定邮箱', 'Bind email')),
          content: TextField(
            controller: controller,
            keyboardType: TextInputType.emailAddress,
            decoration: InputDecoration(labelText: _t('邮箱', 'Email')),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(dialogContext),
              child: Text(_t('取消', 'Cancel')),
            ),
            FilledButton(
              onPressed: () =>
                  Navigator.pop(dialogContext, controller.text.trim()),
              child: Text(_t('发送验证邮件', 'Send verification email')),
            ),
          ],
        ),
      );
      if (email == null || !email.contains('@')) return;
      await _run(() async {
        await McCloudService.bindEmail(email);
        showToast(
          _t('验证邮件已发送', 'Verification email sent'),
          type: ToastType.success,
        );
      });
    } finally {
      controller.clear();
      controller.dispose();
    }
  }

  Future<void> _deleteAccount() async {
    final first =
        await showDialog<bool>(
          context: context,
          builder: (dialogContext) => AlertDialog(
            title: Text(_t('注销云账户？', 'Delete cloud account?')),
            content: Text(
              _t(
                '账户与全部云端数据将被永久删除。',
                'Your account and all cloud data will be permanently deleted.',
              ),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(dialogContext, false),
                child: Text(_t('取消', 'Cancel')),
              ),
              FilledButton(
                onPressed: () => Navigator.pop(dialogContext, true),
                child: Text(_t('继续', 'Continue')),
              ),
            ],
          ),
        ) ??
        false;
    if (!first || !mounted) return;
    final second =
        await showDialog<bool>(
          context: context,
          builder: (dialogContext) => AlertDialog(
            title: Text(_t('最终确认', 'Final confirmation')),
            content: Text(
              _t(
                '确认永久注销 MonkeyCode 云账户。',
                'Confirm permanent deletion of your MonkeyCode Cloud account.',
              ),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(dialogContext, false),
                child: Text(_t('返回', 'Back')),
              ),
              FilledButton(
                onPressed: () => Navigator.pop(dialogContext, true),
                child: Text(_t('永久注销', 'Delete permanently')),
              ),
            ],
          ),
        ) ??
        false;
    if (second)
      await _run(() async {
        await McCloudService.deleteAccount();
        await _load();
      });
  }

  Widget _metric(String label, String value) => Column(
    children: [
      Text(
        value,
        style: Theme.of(context).textTheme.titleLarge?.copyWith(
          fontWeight: FontWeight.w700,
          color: context.omniPalette.accentPrimary,
        ),
      ),
      const SizedBox(height: 4),
      Text(label, style: TextStyle(color: context.omniPalette.textSecondary)),
    ],
  );
  Widget _avatar(String? url, String fallback) => CircleAvatar(
    backgroundImage: url?.isNotEmpty == true ? NetworkImage(url!) : null,
    onBackgroundImageError: url?.isNotEmpty == true ? (_, _) {} : null,
    child: url?.isNotEmpty == true
        ? null
        : Text(
            (fallback.isEmpty ? 'M' : fallback).characters.first.toUpperCase(),
          ),
  );
  Widget _route(IconData icon, String title, String route) => Column(
    children: [
      ListTile(
        contentPadding: const EdgeInsets.symmetric(horizontal: 4),
        leading: Icon(icon),
        title: Text(title),
        trailing: const Icon(LucideIcons.chevronRight, size: 18),
        onTap: () => context.push(route),
      ),
      const Divider(height: 1),
    ],
  );
}

Map<String, String?> _capabilityReasons(McJson capabilities) {
  final result = <String, String?>{};
  for (final platform in const ['alipay', 'douyin']) {
    final raw = capabilities[platform];
    final value = raw is Map
        ? raw.map((key, value) => MapEntry('$key', value))
        : <String, Object?>{};
    final available =
        value['available'] == true || value['sdkAvailable'] == true;
    if (!available)
      result[platform] =
          '${value['reason'] ?? value['message'] ?? (platform == 'alipay' ? '当前安装包未集成支付宝 App SDK' : '当前安装包未集成抖音 App SDK')}';
  }
  return result;
}
