import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/mc_cloud_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/McCloudAccount');

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('recognizes only controlled MonkeyCode OAuth callback paths', () {
    expect(
      isMcCloudOAuthCallbackUrl(
        'https://monkeycode-ai.com/api/v1/users/login/callback?code=secret',
      ),
      isTrue,
    );
    expect(
      isMcCloudOAuthCallbackUrl(
        'https://baizhi.cloud/api/v1/users/oauth/github/callback?code=secret',
      ),
      isTrue,
    );
    expect(
      isMcCloudOAuthCallbackUrl('https://github.com/login/oauth/authorize'),
      isFalse,
    );
    expect(
      isMcCloudOAuthCallbackUrl('https://monkeycode-ai.com/api/v1/users/login'),
      isFalse,
    );
  });

  test('recognizes only cloud pages that require an active session', () {
    expect(isMcCloudProtectedLocation('/my/account/cloud-models'), isTrue);
    expect(
      isMcCloudProtectedLocation('/my/account/cloud-tasks/task-1?tab=logs'),
      isTrue,
    );
    expect(isMcCloudProtectedLocation('/my/account'), isFalse);
    expect(isMcCloudProtectedLocation('/home/chat'), isFalse);
  });

  test('parses session and dashboard payloads into typed models', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'getSessionState') {
            return <String, Object?>{
              'signedIn': true,
              'user': <String, Object?>{
                'id': 'user-1',
                'name': 'Cloud User',
                'email': 'cloud@example.com',
                'avatar_url': 'https://example.com/avatar.png',
              },
            };
          }
          if (call.method == 'getDashboard') {
            return <String, Object?>{
              'wallet': <String, Object?>{
                'balance': 123000,
                'daily_token_balance': 300,
                'daily_token_limit': 1000,
              },
              'checkin': <String, Object?>{'checked_in': true},
              'invitations': <String, Object?>{'count': 4},
              'subscription': <String, Object?>{'plan': 'pro'},
            };
          }
          return null;
        });

    final session = await McCloudService.getSessionState();
    final dashboard = await McCloudService.getDashboard();

    expect(session.signedIn, isTrue);
    expect(session.user?.id, 'user-1');
    expect(session.user?.avatarUrl, 'https://example.com/avatar.png');
    expect(dashboard.wallet?.credits, 123);
    expect(dashboard.wallet?.dailyProgress, .7);
    expect(dashboard.checkedIn, isTrue);
    expect(dashboard.invitationCount, 4);
    expect(dashboard.subscription?.plan, 'pro');
  });

  test('normalizes events and timestamps', () {
    final chunk = McCloudTaskChunk.fromJson(<String, Object?>{
      'event': 'message',
      'data': 'working',
      'timestamp': 1720000000,
      'seq': 7,
    });
    final event = McCloudEvent.fromJson(<String, Object?>{
      'type': 'downloadProgress',
      'operationId': 'download-1',
      'transferred': 25,
      'total': 100,
    });

    expect(chunk.timestamp, 1720000000000);
    expect(event, isA<McTransferEvent>());
    expect((event as McTransferEvent).progress, .25);
  });

  test('converts platform errors to stable cloud failures', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          throw PlatformException(
            code: 'MC_CLOUD_REQUEST_FAILED',
            message: '模型不可用 [trace_id: abc]',
            details: <String, Object?>{'statusCode': 409},
          );
        });

    expect(
      McCloudService.getDashboard(),
      throwsA(
        isA<McCloudFailure>()
            .having((error) => error.message, 'message', '模型不可用')
            .having((error) => error.statusCode, 'statusCode', 409),
      ),
    );
  });

  test('preserves backend business error codes', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          throw PlatformException(
            code: 'MC_CLOUD_REQUEST_FAILED',
            message: '验证码无效',
            details: <String, Object?>{'statusCode': 403, 'errorCode': 10601},
          );
        });

    expect(
      McCloudService.getDashboard(),
      throwsA(
        isA<McCloudFailure>()
            .having((error) => error.statusCode, 'statusCode', 403)
            .having((error) => error.errorCode, 'errorCode', 10601),
      ),
    );
  });

  test('passes controlled OAuth callbacks to native methods', () async {
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          return call.method == 'completeGithubLogin'
              ? <String, Object?>{'completed': true}
              : <String, Object?>{'imported': true};
        });

    expect(
      await McCloudService.completeGithubLogin('omni://oauth?code=secret'),
      isTrue,
    );
    expect(
      await McCloudService.importWebSession('https://example.com/callback'),
      isTrue,
    );
    expect(calls.map((call) => call.method), [
      'completeGithubLogin',
      'importWebSession',
    ]);
  });

  test('parses cursor and page based pagination metadata', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'listProjects') {
            return <String, Object?>{
              'projects': [
                <String, Object?>{'id': 'p1', 'name': 'Project'},
              ],
              'page': <String, Object?>{
                'cursor': 'next-project',
                'has_more': true,
              },
            };
          }
          return <String, Object?>{
            'tasks': [
              <String, Object?>{
                'id': 't1',
                'title': 'Task',
                'status': 'running',
              },
            ],
            'page_info': <String, Object?>{'has_next_page': true},
          };
        });

    final projects = await McCloudService.listProjects(size: 1);
    final tasks = await McCloudService.listTasks(size: 1);
    expect(projects.nextCursor, 'next-project');
    expect(projects.hasMore, isTrue);
    expect(tasks.items.single.id, 't1');
    expect(tasks.hasMore, isTrue);
  });
}
