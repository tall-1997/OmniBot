# Requirements Document

## Introduction

将 chaitin/MonkeyCode 移动端与桌面端的账户、云端功能迁移进 OmniBot Android 应用。OmniBot 客户端直接对接 MonkeyCode 官方后端 `https://monkeycode-ai.com`（云服务）与 `https://baizhi.cloud`（百智云 OAuth 与短信验证码），复用其会话 Cookie、钱包、签到、邀请、订阅、Git 身份、项目任务、自定义模型与云端任务执行能力。功能面同时参考 MonkeyCode 移动端与 Windows 桌面版（微信扫码登录、云端任务流式执行、VM 文件管理）。

迁移基于"本项目已有相关代码和功能"的现状：OmniBot 已有 Flutter 账号中心（`ui/lib/features/my/pages/account`）与 Kotlin 账户客户端层（`baselib/account`）。本次在 OmniBot 技术栈（Kotlin 客户端 + Flutter UI + MethodChannel 桥接）内重写 MonkeyCode 移动端/桌面端能力，不搬运 React Native 与 Tauri 代码。

MonkeyCode 云替换现有 `omni-account` 账号与 platform 官方模型网关；升级后旧会话作废。OmniBot 本地 Agent、任务状态机、数据库、本地 BYOK 提供商、Key 与场景选择保持不变。

## Glossary

- **MonkeyCode 云**：`https://monkeycode-ai.com` 官方后端，提供用户、钱包、签到、邀请、订阅、Git 身份、项目、任务、模型接口。
- **百智云（baizhi.cloud）**：手机号验证码、支付宝、抖音、GitHub OAuth 的第三方身份服务。
- **PoW 验证码（captcha_token）**：与 `go-cap` 协议一致的 SHA-256 工作量证明，登录与签到前必须求解。
- **会话 Cookie**：`monkeycode_ai_session`，登录成功后由服务端下发，客户端持久化并随请求回传。
- **OmniBot 云会话**：本需求新增的、直连 MonkeyCode 云的登录状态。
- **omni-account 会话**：OmniBot 既有账户体系的登录状态，升级后由一次性迁移清除。
- **Git 身份**：用户在 MonkeyCode 云绑定的代码托管平台凭证（GitHub/GitLab/Gitea/Gitee/Codeup/CNB/AtomGit）。
- **云端任务**：MonkeyCode 云上隔离 VM 中执行的开发任务，支持流式输出、控制与终端。
- **云端任务选项**：创建云端任务所需的宿主、镜像、CLI 与资源（CPU/内存/时长）集合。
- **VM 工作区**：云端任务隔离 VM 内的文件目录，支持上传与下载（目录由服务端打包为 zip）。
- **微信扫码登录**：百智云微信 OAuth，客户端展示二维码并长轮询扫码结果。

## Requirements

### Requirement 1: 邮箱密码云端登录

**User Story:** AS 用户, I want 用 MonkeyCode 云账号（邮箱+密码）登录 OmniBot, so that 我能使用云端的钱包、订阅、项目与模型能力。

#### Acceptance Criteria

1. WHEN 用户提交邮箱与密码，且通过 PoW 验证码，系统 SHALL 调用 `POST /api/v1/users/password-login` 完成登录。
2. WHEN 登录成功，系统 SHALL 保存会话 Cookie，并记录当前登录用户信息。
3. IF 验证码求解失败或服务端返回业务错误，系统 SHALL 向用户展示服务端原始错误信息。
4. IF 已登录用户再次访问登录页，系统 SHALL 展示当前登录状态。

### Requirement 2: 手机号验证码登录

**User Story:** AS 用户, I want 用手机号+短信验证码登录, so that 我可以不记忆密码登录 MonkeyCode 云。

#### Acceptance Criteria

1. WHEN 用户输入中国大陆手机号并点击发送验证码，系统 SHALL 先向 `baizhi.cloud` 求解 PoW 验证码，再调用 `POST /api/v1/user/phone_code` 发送短信。
2. WHEN 用户输入验证码并提交，系统 SHALL 求解 PoW 验证码并调用 `POST /api/v1/user/login/phone` 完成登录。
3. WHEN 发送验证码成功后，系统 SHALL 启动 60 秒倒计时防止重复发送。

### Requirement 3: 支付宝 App 授权登录

**User Story:** AS 用户, I want 用支付宝授权登录 MonkeyCode 云, so that 我可以快速登录。

#### Acceptance Criteria

1. WHEN 用户点击支付宝登录，系统 SHALL 调用 `POST /api/v1/user/oauth/app-login/prepare` 获取授权参数，并唤起支付宝 App 完成授权。
2. WHEN 支付宝返回授权 code，系统 SHALL 调用 `POST /api/v1/user/oauth/app-login` 完成登录。
3. WHEN 支付宝账号未绑定手机号，系统 SHALL 进入手机号绑定流程，求解 PoW 验证码并使用返回的 `pending_phone_token` 调用 `POST /api/v1/user/oauth/complete-phone` 完成绑定。
4. IF 支付宝 App 未安装，系统 SHALL 提示用户安装或引导使用其他渠道。

### Requirement 4: 抖音 App 授权登录

**User Story:** AS 用户, I want 用抖音授权登录 MonkeyCode 云, so that 我可以快速登录。

#### Acceptance Criteria

1. WHEN 用户点击抖音登录，系统 SHALL 唤起抖音 App 完成授权。
2. WHEN 抖音返回授权 code，系统 SHALL 调用 `POST /api/v1/user/oauth/app-login`（platform=`douyin_app`）完成登录。

### Requirement 5: GitHub OAuth 登录

**User Story:** AS 用户, I want 用 GitHub 授权登录 MonkeyCode 云, so that 我可以快速登录并关联代码仓库。

#### Acceptance Criteria

1. WHEN 用户点击 GitHub 登录，系统 SHALL 在内嵌 WebView 打开 MonkeyCode 云登录页 `GET /api/v1/users/login?redirect=&inviter_id=`（`inviter_id` 用于邀请归因）。
2. WHEN 用户在内嵌 WebView 完成百智云授权，系统 SHALL 检测登录完成状态并结束登录流程。
3. IF GitHub 授权需要二次确认手机号，系统 SHALL 求解 PoW 验证码并使用 `pending_phone_token` 完成绑定。

### Requirement 6: PoW 验证码求解器

**User Story:** AS 系统, I want 实现 go-cap 兼容的 PoW 验证码求解, so that 登录、签到等敏感操作可通过服务端校验。

#### Acceptance Criteria

1. WHEN 调用方请求验证码，系统 SHALL 支持 `POST /api/v1/public/captcha/challenge` 与 `POST /api/v1/public/captcha/redeem` 完整流程。
2. WHEN 求解质询，系统 SHALL 按 go-cap 协议（FNV-1a 种子 + xorshift32 PRNG + SHA-256 十六进制前缀匹配）计算 nonce。
3. WHEN 求解完成，系统 SHALL 返回可用的 `captcha_token` 供登录、签到接口使用。
4. WHEN 质询难度超过上限，系统 SHALL 终止求解并提示用户重试。

### Requirement 7: 会话保持与过期处理

**User Story:** AS 用户, I want 登录后保持云端登录状态, so that 我无需重复登录。

#### Acceptance Criteria

1. WHEN 登录成功，系统 SHALL 持久化会话 Cookie，重启应用后 SHALL 自动恢复登录状态。
2. WHEN 云端接口返回 401，系统 SHALL 清除本地会话并引导用户重新登录。
3. WHEN 用户主动退出，系统 SHALL 调用 `POST /api/v1/users/logout` 并清除本地 Cookie。

### Requirement 8: 钱包积分与用量展示

**User Story:** AS 用户, I want 查看我的积分余额与会员额度, so that 我可以了解云端可用额度。

#### Acceptance Criteria

1. WHEN 用户查看我的页面，系统 SHALL 调用 `GET /api/v1/users/wallet` 展示积分余额（余额以 1/1000 换算为积分）。
2. WHEN 云端返回每日免费模型额度，系统 SHALL 展示今日已用与总额度进度条。

### Requirement 9: 每日签到

**User Story:** AS 用户, I want 每日签到领取积分, so that 我可以获得额外积分。

#### Acceptance Criteria

1. WHEN 用户查看我的页面，系统 SHALL 调用 `GET /api/v1/users/wallet/checkin` 展示今日是否已签到。
2. WHEN 用户点击签到且未签到，系统 SHALL 在 MonkeyCode 域求解 PoW 验证码并调用 `POST /api/v1/users/wallet/checkin`，成功后加 100 积分并刷新钱包。
3. WHEN 用户今日已签到，系统 SHALL 展示已签到状态并禁止重复签到。

### Requirement 10: 邀请好友

**User Story:** AS 用户, I want 邀请好友获得积分, so that 我可以获得邀请奖励。

#### Acceptance Criteria

1. WHEN 用户查看我的页面，系统 SHALL 调用 `GET /api/v1/users/invitations` 展示已邀请人数与头像列表。
2. WHEN 用户点击邀请好友，系统 SHALL 复制邀请链接（`{baseUrl}/?ic={userId}`）到剪贴板。
3. WHILE 每个邀请成功，系统 SHALL 奖励邀请者 5000 积分（服务端规则，客户端仅展示）。

### Requirement 11: 订阅与会员展示

**User Story:** AS 用户, I want 查看我的会员计划与有效期, so that 我可以了解当前订阅状态。

#### Acceptance Criteria

1. WHEN 用户查看我的页面，系统 SHALL 调用 `GET /api/v1/users/subscription` 展示会员计划（basic/pro/ultra/flagship）。
2. WHEN 计划为 pro/ultra/flagship，系统 SHALL 展示有效期；基础计划展示长期有效。

### Requirement 12: 绑定邮箱

**User Story:** AS 用户, I want 为云端账号绑定邮箱, so that 我可以找回账号或接收通知。

#### Acceptance Criteria

1. WHEN 用户点击绑定邮箱，系统 SHALL 校验邮箱格式并调用 `PUT /api/v1/users/email/bind-request` 发送验证邮件。
2. WHEN 验证邮件发送成功，系统 SHALL 提示用户前往邮箱完成验证。

### Requirement 13: 注销账号

**User Story:** AS 用户, I want 注销云端账号, so that 我可以删除账号及全部数据。

#### Acceptance Criteria

1. WHEN 用户两次确认后点击注销，系统 SHALL 调用 `DELETE /api/v1/users/account` 删除账号。
2. WHEN 注销成功，系统 SHALL 清除本地会话并回到登录页。
3. IF 注销失败，系统 SHALL 展示服务端错误信息并保留当前登录。

### Requirement 14: Git 身份管理

**User Story:** AS 用户, I want 管理绑定到 MonkeyCode 云的 Git 身份, so that 我可以关联代码仓库并创建项目。

#### Acceptance Criteria

1. WHEN 用户查看 Git 账号页，系统 SHALL 调用 `GET /api/v1/users/git-identities` 展示身份列表（过滤 `internal` 平台）。
2. WHEN 用户手动绑定身份，系统 SHALL 校验必填字段并调用 `POST /api/v1/users/git-identities`。
3. WHEN 用户点击 OAuth 授权绑定，系统 SHALL 调用 `POST /api/v1/{platform}/authorize_url` 获取授权地址并打开 WebView，授权完成后刷新身份列表。
4. WHEN 用户编辑身份，系统 SHALL 调用 `PUT /api/v1/users/git-identities/{id}` 更新可修改字段。
5. WHEN 用户删除身份，系统 SHALL 调用 `DELETE /api/v1/users/git-identities/{id}` 并在成功后刷新列表。
6. WHEN 用户查看身份详情，系统 SHALL 调用 `GET /api/v1/users/git-identities/{id}` 展示其可访问仓库列表。

### Requirement 15: 自定义模型管理

**User Story:** AS 用户, I want 在 MonkeyCode 云上接入自己的大模型, so that 我可以使用自备模型能力。

#### Acceptance Criteria

1. WHEN 用户查看模型页，系统 SHALL 调用 `GET /api/v1/users/models`（limit=200）展示云上模型列表（会员内置/自有/团队/共享）。
2. WHEN 同步模型清单，系统 SHALL 按 `owner.type`（public/private/team）归类；`is_hidden` 模型 SHALL 跳过；字段残缺或占位条目 SHALL 跳过并提示差额。
3. WHEN 会员档位不覆盖某内置模型，系统 SHALL 以 `locked` 置灰展示并禁止选择，升级会员重同步后解锁。
4. WHEN 用户创建自有模型，系统 SHALL 校验 base_url、api_key、interface_type 并调用 `POST /api/v1/users/models`。
5. WHEN 用户编辑自有模型，系统 SHALL 调用 `PUT /api/v1/users/models/{id}` 更新字段。
6. WHEN 用户删除自有模型，系统 SHALL 调用 `DELETE /api/v1/users/models/{id}` 并在成功后刷新列表。
7. WHEN 用户创建/编辑自有模型，系统 SHALL 调用 `POST /api/v1/users/models/health-check` 预校验 base_url、api_key 与接口兼容性并展示健康检查结果。

### Requirement 16: 项目与任务列表

**User Story:** AS 用户, I want 查看 MonkeyCode 云上的项目与任务, so that 我可以跟进开发进度。

#### Acceptance Criteria

1. WHEN 用户查看项目页，系统 SHALL 调用 `GET /api/v1/users/projects` 展示项目列表（支持分页）。
2. WHEN 用户查看任务列表，系统 SHALL 调用 `GET /api/v1/users/tasks` 展示任务及其状态。
3. WHEN 用户创建项目，系统 SHALL 调用 `POST /api/v1/users/projects` 关联已绑定 Git 身份与仓库。

### Requirement 17: 我的页面云端入口

**User Story:** AS 用户, I want 在我的页面集中查看云端状态与功能入口, so that 我可以一键访问所有云端能力。

#### Acceptance Criteria

1. WHILE 用户已登录 MonkeyCode 云，系统 SHALL 在我的页面展示用户身份卡片（头像/昵称/邮箱/用户 ID）。
2. WHILE 用户已登录 MonkeyCode 云，系统 SHALL 展示积分余额、签到按钮、邀请入口、会员计划与今日额度。
3. WHILE 用户已登录 MonkeyCode 云，系统 SHALL 提供 Git 账号、自定义模型入口。
4. WHILE 用户已登录 MonkeyCode 云，系统 SHALL 提供退出登录入口。
5. WHEN 页面获得焦点，系统 SHALL 并发刷新用户信息、钱包、订阅、邀请与签到状态。

### Requirement 18: 微信扫码登录

**User Story:** AS 用户, I want 用微信扫码登录 MonkeyCode 云, so that 我可以使用微信身份快速登录。

#### Acceptance Criteria

1. WHEN 用户点击微信登录，系统 SHALL 调用 `GET /api/v1/user/oauth/login?platform=wechat` 获取授权页地址，并解析出二维码 uuid。
2. WHEN 二维码图片就绪，系统 SHALL 在弹层展示二维码供用户扫码。
3. WHILE 用户等待扫码，系统 SHALL 以 `lp.<授权页域名>` 基址长轮询扫码结果，按 `wx_errcode`（408 待扫码 / 404 已扫码待确认 / 403 已取消 / 402|500 过期 / 405 成功）推进状态。
4. WHEN 长轮询返回 405 及 `wx_code`，系统 SHALL 携带会话 Cookie 请求百智云回调完成登录。
5. WHEN 用户取消或二维码过期，系统 SHALL 关闭弹层并支持重新发起。

### Requirement 19: 云端任务执行

**User Story:** AS 用户, I want 在 MonkeyCode 云端 VM 上创建并跟看开发任务, so that 我可以把任务托管到云端执行。

#### Acceptance Criteria

1. WHEN 用户进入云端任务详情，系统 SHALL 调用 `GET /api/v1/users/tasks/{id}` 展示任务详情、状态与用量；任务列表展示归 Requirement 16。
2. WHEN 用户创建任务，系统 SHALL 先调用任务选项接口拉取宿主/镜像/CLI/资源默认值，再调用 `POST /api/v1/users/tasks` 提交（正文、模型、宿主、镜像、可选仓库分支与项目关联）。
3. WHILE 任务执行中，系统 SHALL 通过云端 WebSocket（拨号 `GET /api/v1/users/tasks/stream?id={}&mode=stream`，http→wss）流式接收执行输出，断线 SHALL 自动重连（指数退避，封顶 30 秒）。
4. WHEN 用户切换查看，系统 SHALL 支持按游标回放历史轮次（`GET /api/v1/users/tasks/rounds`）并拉取提问索引（`GET /api/v1/users/tasks/user-inputs`）；轮次事件时间戳 SHALL 归一化为毫秒，与 WS 下行口径一致。
5. WHEN 用户停止当前执行，系统 SHALL 调用 `PUT /api/v1/users/tasks/stop` 仅中断当前轮。
6. WHEN 用户删除任务，系统 SHALL 调用 `DELETE /api/v1/users/tasks/{id}` 并刷新列表。
7. WHEN 任务收到结束帧，系统 SHALL 停止重连并展示完成状态。

### Requirement 20: 云端任务文件管理

**User Story:** AS 用户, I want 管理云端任务 VM 中的文件, so that 我可以上传附件并取回执行产物。

#### Acceptance Criteria

1. WHEN 用户向云端任务发送带附件消息，系统 SHALL 先请求预签名上传地址，再把文件字节直传对象存储，并将返回的访问地址放入消息附件。
2. WHEN 用户上传文件到任务 VM 工作区，系统 SHALL 以 multipart 方式上传到指定 VM 绝对路径。
3. WHEN 用户下载 VM 工作区文件，系统 SHALL 流式写入本地目标；目录 SHALL 由服务端打包为 zip。
4. WHEN 用户取消下载，系统 SHALL 终止传输并清理残件。
5. WHILE 下载进行中，系统 SHALL 经事件通道上报写入进度。

### Requirement 21: 云模型聊天接入（走后端代理）

**User Story:** AS 用户, I want 用 MonkeyCode 云上同步的模型直接聊天, so that 我可以使用会员模型能力而无需在客户端配置第三方凭据。

#### Acceptance Criteria

1. WHEN 用户首次使用云模型聊天，系统 SHALL 调用 `POST /api/v1/users/ohmyagent/api-keys` 领取代理凭据 `{id, api_key, signing_secret}` 并加密存储；`api_key` SHALL 兼容服务端当前 `oma_*` 与历史 `omk-*` 形式。
2. WHEN 同步模型清单，系统 SHALL 调用 `GET /api/v1/users/models?limit=200` 并按档位标记锁定（`locked`）与隐藏（`is_hidden` 跳过）。
3. WHEN 用户发起聊天，系统 SHALL 以 OpenAI 兼容协议请求后端 llmproxy 端点 `{server}/v1/chat/completions|responses|messages`，`Bearer/X-Api-Key` 携带 omk key，请求头携带 `X-OhMyAgent-Signature: v1=<hex(HMAC-SHA256(signing_secret, system_prompt))>`，请求体 `model` 字段传服务端模型名。
4. WHEN 模型超档（locked），系统 SHALL 禁止选择并在 UI 置灰。
5. WHEN 用户退出登录或断开云连接，系统 SHALL 调用 `DELETE /api/v1/users/ohmyagent/api-keys/{id}` 吊销代理凭据。
6. WHEN 服务端返回 401/403 且 omk key 失效，系统 SHALL 重新领取并重试一次；会话失效则清除会话回登录页。
7. WHEN 聊天输出为流式，系统 SHALL 透传 SSE 增量直至结束。
8. WHEN 客户端请求涉及第三方模型 `api_key`，系统 SHALL 保证不接收、不存储、不落日志。
9. WHILE 用户已登录 MonkeyCode 云，系统 SHALL 在现有模型选择器中展示同步得到的可用云模型，并保留服务端模型名、协议、归属和锁定状态。
10. WHEN 云模型目录同步完成，系统 SHALL 直接使用同步清单作为模型选择目录，避免依赖 llmproxy 的模型发现接口。
11. WHEN 用户选择云模型发起本地会话，系统 SHALL 在会话级模型覆盖与场景绑定两种路径中保留云 Provider 身份并执行代理签名。

### Requirement 22: 云端任务统一会话

**User Story:** AS 用户, I want 在任务侧边栏打开 MonkeyCode 云端任务并继续对话, so that 云端任务与本地会话具有一致的浏览和交互体验。

#### Acceptance Criteria

1. WHILE 用户已登录 MonkeyCode 云，系统 SHALL 在 Home 任务侧边栏展示云端进行中任务、项目任务与历史任务。
2. WHEN 用户点击云端任务，系统 SHALL 以 `mccloud` runtime 和云任务 ID 打开现有聊天页面。
3. WHEN 聊天页面加载云端任务，系统 SHALL 通过共享 ACP `session/load` 边界回放云端任务轮次，并将云端帧映射为标准 `session/update` 事件。
4. WHILE 云端任务处于 `processing` 状态，系统 SHALL 通过 attach stream 接收当前轮回放与实时更新，并交由 `AgentEventReducer` 投影到正常对话 UI。
5. WHEN 用户在云端任务会话发送消息，系统 SHALL 通过 `session/prompt` 开启新一轮云端任务流并回显用户消息与 Agent 增量输出。
6. WHEN 用户取消云端任务当前轮，系统 SHALL 通过 `session/cancel` 中断当前轮并保持任务会话可继续加载。
7. WHEN 云端任务结束，系统 SHALL 以只读会话展示完整历史，并支持按游标加载更早轮次。
8. WHEN MonkeyCode 会话过期，系统 SHALL 清除侧边栏云任务并引导用户重新登录，同时保留本地会话与本地 BYOK 数据。

## Non-Functional Requirements

1. **安全**：会话 Cookie 持久化使用系统级加密存储；不得将用户 API Key 明文写入日志。
2. **兼容**：MonkeyCode 云替换 omni-account 登录与 platform 官方渠道；迁移不得改写本地 BYOK 提供商、Key、场景选择或本地 Agent 数据。
3. **平台**：支持 Android（min SDK 29）；支付宝/抖音通过各自 App 唤起与回调完成授权。
4. **可配置**：云端服务地址可通过构建属性覆盖，便于切换环境。
5. **统一会话边界**：云端任务通过共享 ACP session 接口进入聊天页，事件归约与会话协调复用现有实现。

## Registered Follow-ups

- **微信 App 内一键授权（OpenSDK）**：本期微信登录保持 qrconnect 网页扫码形态（百智云 `platform=wechat` 仅暴露该协议）。移动端扫码需另一台设备，体验受限；后续可评估在服务端（百智云/MonkeyCode）新增微信 App OAuth 渠道（微信开放平台移动应用 AppID/AppSecret、openid/unionid 用户绑定），客户端再集成 WeChat OpenSDK 唤起微信 App 授权。此改造涉及服务端开发与微信开放平台应用资质，本期不实施。

## Out of Scope

- Apple 登录（iOS 专属，OmniBot 为 Android 应用；后端接口不强制）。
- 云端任务 VM 的资源/镜像市场管理、虚拟终端交互式输入（仅支持流式输出与控制管道）。
- 支付购买流程（钱包充值、会员购买）。
- 对 `omni-account` 服务端的任何修改。
