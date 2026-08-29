# MonkeyCode Cloud Integration Task List

## Phase 1: Account And Cloud Foundation

- [x] 1.1 Add cloud endpoint, envelope, exception, and core domain models
- [x] 1.2 Add encrypted persistent Cookie store and dual-domain CookieJar
- [x] 1.3 Implement go-cap PoW solver and dual-domain captcha redemption
- [x] 1.4 Implement MonkeyCode account and session repositories
- [ ] 1.5 Implement Baizhi phone, Alipay, Douyin, GitHub, and WeChat authentication (Alipay complete; Douyin native SDK launch pending valid OmniBot ClientKey)
- [x] 1.6 Implement wallet, check-in, invitation, subscription, email, and account lifecycle APIs
- [x] 1.7 Implement Git identity, model management, project, and task-list APIs
- [x] 1.8 Add Android MethodChannel and event-channel bridges
- [x] 1.9 Replace Flutter account flows and add cloud management pages
- [x] 1.10 Retire omni-account platform routing while preserving local BYOK and local Agent runtime

## Phase 2: Local Agent With Cloud Models

- [x] 2.1 Implement OhMyAgent key lifecycle and encrypted storage
- [x] 2.2 Implement model synchronization, ownership mapping, hidden filtering, and plan locking
- [x] 2.3 Implement OhMyAgent prompt extraction and HMAC signing
- [x] 2.4 Add MonkeyCode cloud provider routing to AgentLlmClient
- [ ] 2.5 Verify SSE streaming, one-time key renewal, logout revocation, and BYOK isolation
- [x] 2.6 Connect synchronized MonkeyCode model inventory to the existing model selector
- [x] 2.7 Preserve MonkeyCode provider identity for scene bindings and conversation model overrides

## Phase 3: Cloud Task Execution

- [x] 3.1 Implement task options, creation, detail, rounds, user inputs, stop, and delete APIs
- [x] 3.2 Implement task WebSocket stream/control lifecycle and reconnection
- [x] 3.3 Implement attachment presign upload and VM workspace upload/download
- [x] 3.4 Add Flutter cloud task execution and file-management UI
- [ ] 3.5 Verify task lifecycle, stream recovery, progress events, and partial-file cleanup
- [x] 3.6 Add a McCloud ACP session adapter for task list, load, prompt, cancel, and updates
- [x] 3.7 Show cloud tasks in the Home task sidebar and open them in the shared chat page
- [x] 3.8 Map task rounds and live frames into the existing AgentEventReducer identity model

## Final Verification

- [x] 4.1 Run focused Kotlin and Flutter tests
- [x] 4.2 Run Flutter analyze and Android debug assembly
- [ ] 4.3 Verify all 21 requirements and correctness properties

## External Integration Gap

- [x] 5.1 Add Alipay Android authorization SDK and callback wiring
- [ ] 5.2 Add Douyin Android authorization SDK and callback wiring

## Verification Record

- 2026-08-28: Flutter focused service, account helper, account page, and startup prompt tests passed.
- 2026-08-28: Flutter analyze completed with no fatal warnings or infos.
- 2026-08-28: Isolated Kotlin verification passed for 33 baselib cloud tests, the assists cloud-request test, and the app payload-sanitizer test.
- 2026-08-28: Isolated `:app:compileDevelopStandardDebugKotlin` passed with the Alipay SDK and cloud channel wiring.
- 2026-08-28: Isolated `:app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart` completed successfully in 24m 15s; the generated APK is 181,682,747 bytes with SHA-256 `5b901ba0ab14c2cd6c426997b799ef343ef2299110c769943565216a6914aae7`.
- 2026-08-28: Cloud model HTTP and SSE requests now renew an expired omk credential once on 401/403 and replay with a regenerated signature; focused `HttpControllerMonkeyCodeCloudTest` and `:assists:compileDebugKotlin` passed.
- 2026-08-28: The active `HttpController` model route no longer resolves or forwards the retired omni-account platform gateway.
- 2026-08-28: Incremental Android assembly after the credential-renewal change completed successfully in 5m 44s; the final APK is 182,234,533 bytes with SHA-256 `330d9e71ab335ba16500dfd7f7045f017fdf077e9bdc39b013dda613df75641e`.
- 2026-08-29: Synchronized cloud model inventories now feed the existing Provider selector directly; current `oma_*` and legacy `omk-*` proxy keys are accepted, and scene/conversation overrides preserve cloud routing and dynamic signatures.
- 2026-08-29: Cloud tasks now appear in the Home task sidebar and open as `mccloud` ACP sessions in the shared chat page; rounds and live `new`/`attach` streams use the existing reducer and coordinator.
- 2026-08-29: Focused Kotlin verification passed for cloud model inventory, proxy signing, stream modes, and McCloud ACP session mapping; 128 focused Flutter model, drawer, routing, session, and coordinator tests passed.
- The full 21-requirement end-to-end acceptance remains open.
- Douyin authorization remains blocked on an OmniBot-bound ClientKey and signing configuration.
