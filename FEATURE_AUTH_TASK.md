# FEATURE_AUTH_TASK — 登录/角色/好友/安全 本地可预览一版

工作目录：`C:\Users\mrmai\Desktop\mairy-chat`

## 硬性约束（违反即失败）

1. **禁止** `git commit` / `git push` / 任何部署到 ECS / 修改云端 nginx / SSH 改服务器。
2. **禁止** 把真实生产密码、云端 DB 密码写入代码或 README；本地默认管理员密码仅写「change-me 本地默认」。
3. 优先做出 **可本地运行预览** 的连贯第一版；完美度次之。
4. 完成后务必验证：
   - 在 `backend/`：`mvn -DskipTests package` 成功
   - 在 `frontend/`：`npm.cmd run build` 成功（必要时先 `npm.cmd install`）
5. MyBatis-Plus 3.5.18 本机 JAR 里 `ServiceImpl` 实际包名是 **`com.baomidou.mybatisplus.spring.service.impl.ServiceImpl`**（不是旧的 `extension.service.impl`）。新建 Service 继承时务必用这个路径。
6. 时区继续用 Asia/Shanghai（已有 `ChatTime` / Jackson time-zone），不要回退成 UTC。
7. 完成后写根目录 `AUTH_IMPLEMENTATION_NOTES.md`（中文）：实现了什么、刻意延后了什么、默认管理员账号、本地如何跑、关键文件列表。另写 `SECURITY.md`（中文短文）说明已做防护。

## 现有项目现状（在此基础上演进，不要推倒重写）

- `backend/`：Spring Boot 3.5.16 + MyBatis-Plus 3.5.18 + WebSocket；房间/昵称会话/消息已可用
- `frontend/`：Vue3 + Vite；`RoomListView` / `ChatRoomView`；尚无 vue-router
- 表：`chat_room` / `chat_member` / `chat_message`（见 `backend/src/main/resources/schema.sql`）
- 配置：根目录 `.env` + `application.yml`；CORS `chat.allowed-origins`
- 当前是「匿名昵称进房」模式；本任务要升级为「注册登录 + 角色」为主，匿名访客可弱化或保留为可选（若时间紧可要求必须登录后才能进房/建房）

## 产品需求（必须覆盖到可用程度）

### A. 账号与角色

1. **注册 + 登录**（用户名/密码即可；展示名可用昵称字段）。
2. **忘记密码**：用户提交「重置密码申请」→ 写入待处理表；**不自动发邮件**。管理员后台可看到申请并 **重置密码**（设成新临时密码或管理员指定密码）。
3. 角色：`ADMIN` / `USER`。登录后路由不同：
   - USER → `/app` 用户主页（自己的房间、好友搜索、进房）
   - ADMIN → `/admin` 后台；也可进入任意聊天室，且支持 **隐身（stealth）**：不出现在在线成员列表 / presence 中（若尚无 presence，至少：隐身进房不创建可见成员记录或标记 `stealth=true` 并对普通用户隐藏）。
4. 种子数据：本地启动或 `schema.sql` / `data-local.sql` 写入 **一个默认管理员**：
   - 用户名：`admin`
   - 密码：`ChangeMe@123`（BCrypt 存储；README/NOTES 标明「仅本地默认，上线前必须改」）
   - 编号：`#0001`（管理员也占编号序列）

### B. 编号与设备限制

1. 每个注册用户分配递增公开编号：`#0001`、`#0002`…（存 int + 展示时格式化；唯一）。
2. **每台机器最多注册 3 个账号**：
   - 前端生成/持久化 `machineFingerprint`（localStorage；可用 canvas/UA/screen/timezone 等拼 hash；诚实说明这是浏览器指纹不是硬件序列号）。
   - 后端表记录 fingerprint hash → 已注册账号数；第 4 次注册拒绝并返回中文错误。
   - 同时记录 User-Agent，解析并展示大致机型/浏览器（如 Windows / Chrome、iPhone Safari 等；能力有限要写进 NOTES）。

### C. 房间规则

1. **每个普通用户最多创建 1 个聊天室**（`owner_user_id` 唯一约束）；管理员可放宽或不限（自行合理选择并文档化）。
2. 用户主页显示「我的房间」：未创建则引导创建；已创建则进入。
3. 进房后仍用实时 WebSocket 收消息；发送可用 REST（保持现有模式亦可）或统一；**必须登录态鉴权**，废弃或并列匿名 token（优先：登录 JWT/session + 进房绑定 user）。

### D. 好友搜索（v1）

1. 可按 **编号**（`#0001` / `0001`）或 **用户名/昵称** 搜索其他用户。
2. v1 至少做到：搜索结果列表 + 「加好友」请求或直接成为好友（二选一；推荐：好友请求表，对方同意后出现在好友列表）。若时间紧：**可搜索 + 发起好友请求 + 同意/拒绝** 即可，私聊可延后。
3. 不要把密码哈希等敏感字段返回给前端。

### E. 聊天 UX

1. 输入框 **Enter 发送**（Shift+Enter 换行可选）。
2. 简单 **表情选择器**（一小组 unicode emoji 即可，插入到输入框）。
3. 登录页 / 注册页样式要 **更高级**：现代深色或干净玻璃拟态，克制、不花哨；整体中文 UI。

### F. 安全（Codex 自行设计并实现基础版，写进 SECURITY.md）

至少包含：

- 密码 **BCrypt**
- 登录/注册 **按 IP 限流**（例如滑动窗口或固定窗口；失败次数过多短时锁定）
- 可疑 IP **临时封禁** 表或内存+落库（管理员可在后台查看/解封更佳，没有则延后并注明）
- SPA 合理的 **CORS**（沿用 allowed-origins）；CSRF：若用 JWT Bearer 则说明为何不启用 Cookie CSRF；若用 Cookie Session 则加 CSRF 或 SameSite 策略
- 输入校验（长度、空白、房间名等）
- WebSocket **握手鉴权**（token/query 或 header；勿在日志打印完整 token）
- 管理接口仅 ADMIN
- 隐身进房权限校验

技术建议：

- 加依赖 `spring-boot-starter-security`；可用 **JWT（无状态）** 或 **HTTP Session**。推荐 JWT + `Authorization: Bearer`，前端存 memory/localStorage（NOTES 写明 XSS 风险与后续 HttpOnly 改进）。
- 表建议（名称可调整，保持中文 COMMENT 风格）：
  - `chat_user`（id, public_no, username, nickname, password_hash, role, created_at, disabled…）
  - `chat_room` 增加 `owner_user_id`（可空兼容旧数据；新逻辑必填）UNIQUE
  - `chat_friend_request` / `chat_friendship`（或合并）
  - `chat_password_reset_request`（user_id, status, created_at, handled_by, note…）
  - `chat_device_registration`（fingerprint_hash, user_id, user_agent, device_label, created_at）
  - `chat_ip_block` 或 `chat_security_event`（ip, reason, until_at, fail_count…）
  - 消息/成员表按需加 `user_id`、`stealth`
- 更新 `schema.sql`（完整可重建）+ 可选 `schema-upgrade-auth.sql`（给已有库 ALTER）；启动仍保持 `spring.sql.init.mode=never`，手动 SOURCE。
- 前端加 `vue-router`：`/login` `/register` `/forgot-password` `/app` `/admin` `/room/:id` 等；未登录跳转登录。
- Vite 代理继续 `/api` `/ws`。

## 前端页面最低集合

1. 高级感登录页 + 注册页 + 忘记密码申请页
2. 用户主页：我的编号、我的房间、搜索好友、好友/请求列表、退出
3. 聊天室页：消息、Enter 发送、表情、（可选）在线列表——隐身管理员不对普通用户可见
4. 管理后台：用户列表、重置密码、处理重置申请、（可选）IP 封禁列表、隐身进入某房间入口

## 验收标准

- 本地按 NOTES 能启动前后端；用默认 admin 登录进后台；再注册 2 个普通用户；第 4 个同指纹注册应失败（可用同一 localStorage 测）。
- 普通用户只能建 1 个房；搜索到 `#` 编号；Enter 发送与表情可用。
- `mvn -DskipTests package` 与 `npm.cmd run build` 通过。
- **工作区可以有文件改动，但不要 commit/push。**

## 交付物

1. 代码实现（本仓库内）
2. 更新中文 `README.md` 本地启动与默认管理员说明（强调仅本地）
3. `SECURITY.md`
4. `AUTH_IMPLEMENTATION_NOTES.md`

请直接在本目录改代码并完成上述验证。开始前先快速阅读现有 controller/service/websocket/前端视图，再增量改造。
