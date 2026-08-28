import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/webview/webview_page.dart';
import 'package:ui/features/my/pages/account/mc_cloud_management_pages.dart';

void main() {
  test('redacts credentials, query, and fragment from WebView logs', () {
    expect(
      redactWebViewUrl(
        'https://user:password@example.com/oauth?code=secret#token',
      ),
      'https://example.com/oauth?redacted#redacted',
    );
  });

  test('uses a zip filename for VM directory downloads', () {
    expect(vmDownloadFilename('/workspace/project/'), 'project.zip');
    expect(vmDownloadFilename('/workspace/report.txt'), 'report.txt');
    expect(vmDownloadFilename('/'), 'workspace.zip');
  });
}
