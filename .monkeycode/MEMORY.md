# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Format

### User Instruction Entry
User instruction entries should follow this format:

[User Instruction Summary]
- Date: [YYYY-MM-DD]
- Context: [Mentioned scenario or time]
- Instructions:
  - [Content of user teaching or instruction, described line by line]

### Project Knowledge Entry
Entries discovered by the Agent during task execution should follow this format:

[Project Knowledge Summary]
- Date: [YYYY-MM-DD]
- Context: Discovered by Agent while performing [specific task description]
- Category: [Operations & Deployment|Build Methods|Testing Methods|Troubleshooting & Debugging|Workflow & Collaboration|Environment Configuration]
- Instructions:
  - [Specific knowledge points, described line by line]

## Deduplication Strategy
- Before adding a new entry, check for similar or identical instructions.
- If a duplicate is found, skip the new entry or merge it with the existing one.
- When merging, update the context or date information.
- This helps avoid redundant entries and keeps the memory file tidy.

## Entries

[User Instruction Summary]
- Date: 2026-08-28
- Context: MonkeyCode 云端集成功能实施阶段
- Instructions:
  - 连续完成规格任务清单中的剩余实现，全部功能落地后统一进行云端构建与验证。
  - Git 提交沿用仓库现有 `tall-1997 <tall-1997@chaitin.com>` 署名，不添加 MonkeyCode AI、Co-authored-by 或其他 AI 生成标记。
  - 用户提供的 GitHub 凭据仅用于必要的远程仓库操作，不写入项目文件、日志或回复。
  - 编译和测试尽可能使用受管理的云端后台终端；任务运行期间并行推进其他待办，并通过后台轮询获取结果。
  - 及时停止无关的本地预览或常驻进程，释放端口与计算资源。

[Project Knowledge Summary]
- Date: 2026-08-28
- Context: 用户要求将 chaitin/MonkeyCode 云端能力迁移进 OmniBot，已按 feature-design skill 产出需求与设计文档并通过审阅；用户要求将完整规格保存到记忆，防止上下文压缩后信息偏移。
- Category: Workflow & Collaboration
- Instructions:
  - 完整规格文档已定稿并持久化于 `/workspace/.monkeycode/specs/monkeycode-cloud-integration/requirements.md`（21 项 EARS 需求 + NFR + Registered Follow-ups + Out of Scope）与 `design.md`（9 条设计决策 + 架构图 + 组件表 + 数据模型 + 正确性 + 错误处理 + 测试策略 + 三阶段 + 21 条源码引用）。上下文压缩后先读这两个文件恢复全部细节，不要凭记忆重写。
  - 目标：把 MonkeyCode 移动端+桌面端的账户/云端功能迁移进 OmniBot Android（Flutter UI + Kotlin baselib），客户端直连 `https://monkeycode-ai.com`（云）+ `https://baizhi.cloud`（百智云 OAuth/短信/PoW），本地 omni-account 与官方 AI 渠道退役，本地 BYOK 与本地 Agent 运行时（AgentRuntimeManager/AgentOrchestrator/OmniAgentExecutor/SubagentDispatcher/LocalAcpRuntime、assists 状态机、Room 数据库）全保留。
  - 9 条设计决策：1)替换账户体系（数据源切到 MonkeyCode 云，六种登录：邮箱密码/手机号/支付宝/抖音/GitHub/微信扫码）;2)Kotlin 客户端+MethodChannel（baselib 新包 cn.com.omnimind.baselib.mccloud，OkHttp+加密 CookieJar）;3)新增云模型渠道、本地能力全保留;4)官方 AI 渠道退役（AiRequestAccessResolver platform 分支/PLATFORM_ROUTE_TAG/PlatformAiProvisioner/OmniOfficialProvider/PlatformModelApiClient/PlatformMediaGateway 移除或短路）;5)云模型走后端代理（OhMyAgent：POST /api/v1/users/ohmyagent/api-keys 领 {id, api_key(omk-*), signing_secret} → llmproxy {server}/v1/chat/completions|responses|messages，Bearer/X-Api-Key=omk key，请求头 X-OhMyAgent-Signature: v1=<hex(HMAC-SHA256(secret, system_prompt))>，model 传服务端模型名，退出 DELETE api-keys/{id} 吊销）;6)存量 omni-account 会话作废;7)微信扫码登录（百智云 qrconnect 协议：GET /api/v1/user/oauth/login?platform=wechat → 解析二维码 uuid → lp.<域> 长轮询 wx_errcode 408/404/403/402|500/405 → 405 取 wx_code 走百智云回调落 Cookie；微信 App 内授权已登记为服务端改造项，本期不做）;8)云端任务执行（创建/停止/删除/轮次回放/提问索引；WS 拨号 GET /api/v1/users/tasks/stream?id={}&mode=stream|control，http→wss，断线指数退避封顶 30s）;9)云端任务文件管理（附件 presign 直传、VM 工作区 multipart 上传/流式下载目录 zip、取消清残件）。
  - 关键端点锚点：邮箱登录 POST /api/v1/users/password-login；手机号 baizhi 域 POST /api/v1/user/phone_code + /login/phone；支付宝 prepare 用 baizhi /oauth/app-login/prepare；GitHub 用 WebView 打开 {mc}/api/v1/users/login?redirect=&inviter_id=；PoW 两域独立（monkeycode 与 baizhi 各一套 challenge/redeem，POST /api/v1/public/captcha/challenge|redeem，go-cap 协议 FNV-1a+xorshift32+SHA-256 前缀）；钱包 GET /wallet（余额/1000=积分）、签到 GET/POST /wallet/checkin（+100 积分）、邀请 GET /invitations（链接 {base}/?ic={userId}，+5000 积分）、订阅 GET /subscription、绑定邮箱 PUT /email/bind-request、注销 DELETE /account；Git 身份 /git-identities（含 POST /{platform}/authorize_url OAuth 绑定）；模型 /models（limit=200、owner.type 归类、is_hidden 跳过、locked 置灰、health-check 预校验）；任务列表 /tasks 分页、创建 POST /tasks（先 mc_task_options）、停止 PUT /tasks/stop、删除 DELETE /tasks/{id}、回放 GET /tasks/rounds、提问 GET /tasks/user-inputs。
  - 三阶段：Phase 1 = mccloud 客户端层+PoW+百智云 OAuth（含微信）+MethodChannel+账号中心替换+我的页面+GIT/模型/项目管理+任务列表+存量会话作废+官方渠道退役；Phase 2 = 云模型聊天接入（本地 Agent 用云模型，本地执行+云端代理）；Phase 3 = 云端任务执行+VM 文件管理。
  - 关键实现约束：用量聚合四路并发、部分失败容忍（仅全失败报错）；PoW 求解跨域全局唯一；系统提示词提取规则与后端 ValidateOhMyAgentPrompt 对齐（system 字段→instructions→messages 首个 system→input 首个 developer/system）；会话 Cookie 加密持久化；任意 401 统一清会话回登录（只处理一次）；omk key/signing_secret/模型 apiKey/Git accessToken 加密存储+日志脱敏。
  - 参照源码（被调研的参考实现，非本仓库）：/tmp/opencode/MonkeyCode-main/（mobile 端 client.ts/baizhi.ts/captcha.ts；desktop 端 wechat.rs/monkeycode.rs/config.rs/mod.rs/sync.rs/cloudapi.ts/useCloudTask.ts/newtask.tsx/uploads.rs；backend llmproxy register.go/proxy.go/ohmyagent_prompt.go、biz/task）。
