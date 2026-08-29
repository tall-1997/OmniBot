# Interfaces

## Flutter Channels

- MethodChannel: `cn.com.omnimind.bot/McCloudAccount`
- EventChannel: `cn.com.omnimind.bot/McCloudAccountEvents`

The method channel exposes session, password/phone/OAuth login, wallet/check-in/subscription/invitation, Git identity, model, project, task, WebSocket, and file-transfer methods. Sensitive fields such as passwords, cookies, API keys, access tokens, signing secrets, and presigned upload URLs are removed recursively from returned payloads.

The event channel emits WeChat login status, session expiry, task stream events, and transfer progress. Concurrent HTTP, WebSocket, and file 401 responses converge on one `McCloudSessionManager` transition.

## Service Domains

- MonkeyCode: `https://monkeycode-ai.com`
- Baizhi: `https://baizhi.cloud`

Each domain has an independent encrypted Cookie store and captcha challenge/redeem session. Baizhi OAuth sessions are bridged to the MonkeyCode session through a bounded redirect flow.

## Cloud Model Proxy

- Create key: `POST /api/v1/users/ohmyagent/api-keys`
- Revoke key: `DELETE /api/v1/users/ohmyagent/api-keys/{id}`
- Model catalog: `GET /api/v1/users/models?limit=200`
- Inference: `/v1/chat/completions`, `/v1/responses`, or `/v1/messages`
- Dynamic header: `X-OhMyAgent-Signature: v1=<hex-hmac-sha256>`
- Provider model inventory: synchronized server model names stored on each `sourceType=monkeycode` profile
- Proxy API key formats: current `oma_*`; legacy `omk-*`

## Cloud Tasks

- Task CRUD and history use `/api/v1/users/tasks` endpoints.
- Task stream uses `/api/v1/users/tasks/stream?id={id}&mode={mode}`.
- ACP session IDs use the `mccloud:<taskId>` namespace and runtime `mccloud`.
- `session/load` replays task rounds and attaches to processing tasks.
- `session/prompt` opens mode `new`; reconnects use mode `attach`.
- Task frames are emitted as the shared `session/update` and `turn/*` events.
- Attachments use `/api/v1/uploader/presign` followed by an unauthenticated presigned PUT.
- VM files use `/api/v1/users/files/upload` and `/api/v1/users/files/download`.
