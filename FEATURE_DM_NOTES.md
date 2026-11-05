# 好友私信：明文文字与语音

核对日期：2026-11-06。保留下方文字 v1 阶段记录，本次增加私信明文语音。

私信与多人房间完全独立。仅 `chat_friend_request.status=ACCEPTED` 的双方可以打开、发送及读取私信。每对好友只有一个会话，双方从朋友列表点击「发私信」进入 `/dm/:userId`。

## 内容与权限

- **私信当前未实现端到端加密：`enc=0`、`iv=NULL`，数据库和服务器可以读取内容。** 页面也会明确显示这一提示。
- 私信不依赖 Web Crypto，已有账号可在普通 HTTP IP 环境中收发文字及播放收到的语音；录音需要 HTTPS / localhost 和麦克风权限。HTTP 传输同样没有加密，实际使用建议配置 HTTPS/WSS。
- 房间继续强制 `enc=1`，房间密钥、加密算法、成员凭证与 `/ws/chat` 均沿用原流程。
- 每次读写都检查会话成员、已接受好友关系及对方是否停用；无关用户和非好友返回 403 中文提示。无效、到期、已撤销或停用账号的登录返回 401。
- 私信实时连接按登录账号索引。只向会话双方的有效登录连接推送；推送前批量检查有效登录，不在循环中访问 SQL。注销、重置密码会关闭对应连接。
- 消息事务提交后才推送；推送失败不撤销已保存的消息。前端合并 REST 与 WebSocket 消息时按主键去重。
- 本版没有已读、未读提醒、会话预览、文件附件或自动重连。断线后可手动重连或刷新消息，重连会重新读取最近一页，更早记录使用游标加载。

## 本地数据库

新数据库使用 `backend/src/main/resources/schema.sql`。

已有账号库先执行 `backend/src/main/resources/schema-upgrade-dm.sql` 创建私信表；该脚本仅使用 `CREATE TABLE IF NOT EXISTS`，可重复执行。已有私信表再备份并手动执行一次 `backend/src/main/resources/schema-upgrade-20261106-dm-voice.sql`：将 `chat_dm_message.content` 扩为 MEDIUMTEXT，新增 `kind`（默认 `text`）、`mime`、`duration`。后者是 MySQL ALTER，不能重复执行；保留历史文字、索引及 `enc=0` / `iv=NULL` 约束，不修改房间、助手或身份表。全新库不需要这些升级脚本，先升级旧库结构再启用代码。应用不自动执行迁移。

所有写入时间使用既有 `ChatTime.now()`（Asia/Shanghai），接口返回无额外时区换算的本地时间。

## 接口

REST 均携带 `Authorization: Bearer <登录凭证>`，不使用 `X-Session-Token`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/dm/with/{userId}` | 获取或创建好友会话，返回 `id`、`peer`、`createdAt` |
| GET | `/api/dm/threads` | 本人仍有权限的最近 200 个会话，按会话 ID 倒序 |
| GET | `/api/dm/threads/{threadId}/messages?beforeId=&size=30` | 正序消息页，返回 `records`、`nextBeforeId`、`hasMore`；size 为 1～50 |
| POST | `/api/dm/threads/{threadId}/messages` | 文字 `{"content":"你好"}`（kind 可省略或为 text），最多 1000 字；语音 `{kind:"voice",content:"音频Base64",mime:"audio/webm",duration:1000}`；发送人和明文标记由服务端决定 |

WebSocket：`/ws/dm?token=<登录凭证>`，也支持握手请求头 `Authorization: Bearer ...`。浏览器不记录带凭证的 URL。来源限制和 IP 限流沿用项目配置。不要在代理日志中记录查询凭证。

事件格式：`{"type":"dm-message","data":{"id":"...","threadId":"...","senderId":"...","content":"你好","kind":"text","mime":null,"duration":null,"enc":0,"iv":null,"createdAt":"..."}}`。语音使用相同消息结构及历史 / 推送通道，kind 为 voice，mime、duration 有值，content 为音频 Base64。客户端发送消息必须走 REST，WebSocket 不接受发信。

开发环境沿用 Vite 的 `/ws` 代理。生产路径从已有 `VITE_WS_PATH` 的结尾 `/chat` 推导为 `/dm`，例如 `/chat/ws/chat` 对应 `/chat/ws/dm`。特殊路径可配置 `VITE_DM_WS_PATH`；本次未修改任何 `.env`。反向代理需将 `/chat/ws/dm` 转发为后端 `/ws/dm` 并支持 Upgrade，若已有 `/chat/ws/` 通用转发则无需额外规则。

## 明文语音

1. `DmChatView` 的「录语音」仅在显式点击后请求麦克风；格式按浏览器支持选择 WebM / Ogg Opus / MP4，目标码率 32 kbps。录音不使用设备身份或房间密钥。
2. 最多 60 秒自动停止；音频限 512 KiB，空录音、超限、权限拒绝及录音错误均提示中文。结束录音立即释放麦克风，提供本地播放器预览，不自动上传。
3. 点击「发送语音」才将原始音频编码为 Base64 上传，服务端固定保存 `enc=0`、`iv=NULL`。失败保留草稿供重试，取消则撤销预览；语音准备期间禁发文字并保留文字草稿。
4. 前后端分别验证规范 Base64、1～524288 字节、1～60000 整数毫秒，以及 `audio/webm;codecs=opus`、`audio/webm`、`audio/ogg;codecs=opus`、`audio/ogg`、`audio/mp4` 白名单。文字不允许夹带语音参数；未知 kind 被拒绝。时长是客户端声明值，后端不解码音频核验实际播放时长。
5. 历史与推送经本地校验后才生成 Blob URL，忽略网络传入的播放地址；原生播放器不自动播放，历史音频 `preload=none`。无效载荷和不支持的编解码器显示提示。私信慢连接缓冲为 1 MiB，保留 5 秒发送限制。
6. 切换好友、取消、权限失效、退出、跨标签页登录变化和卸载均清理录音资源；版本号拒绝迟到的授权、停止及发送回包。刷新历史、替换消息和离开页面撤销对应播放 URL。

房间 `ChatRoomView` 的 E2EE 语音保持原实现；`AssistantView` 没有语音入口，前端公共组件未修改。

## 双账号试用

1. 在本地 MySQL 手动执行上述升级脚本，按项目现有方式启动后端和前端。
2. 用两个独立浏览器配置、不同浏览器或普通窗口加隐私窗口分别登录账号 A、B，避免同一 localStorage 的登录互相覆盖。
3. A 搜索 B 的编号并申请好友，B 点击「同意」，A 刷新朋友列表。
4. 双方分别点击对方旁的「发私信」，应打开同一会话，页面显示明文提示。
5. A 发送消息，B 应立即收到；B 回复，A 应立即收到，发送方不会重复显示。刷新页面后记录仍在。
6. 发送超过 30 条后加载更早消息，检查连续性。断开后手动重连，检查最近消息同步。
7. 用第三个非好友账号尝试打开 `/dm/<A的账号ID>`，应收到「仅好友可私信」；调用其他人的 threadId 历史或发送接口应为 403。
8. 返回大厅进入房间，确认房间仍要求设备密钥及端到端加密。

## 验证范围

`DmIntegrationTest` 使用内存 H2，覆盖升级脚本重复执行、好友状态门禁、会话唯一性、游标历史、发送人防伪造、第三方隔离、停用账号、登录握手、双方推送及退出后关闭连接。现有账号、房间和时区回归测试一并运行。实际 MySQL 和浏览器双账号试用需在本地环境执行。

文字 v1 阶段验证结果（历史记录）：

- 后端 14 项测试通过（账号 5、私信 3、时区 6），无失败或跳过。
- 前端 9 项测试通过（私信 4、既有房间界面 4、加密交换 1）。命令：`node --experimental-vm-modules --test src/views/DmChatView.test.mjs src/views/ChatRoomView.test.mjs src/crypto/e2ee.test.mjs`。
- 后端打包通过。本次沙箱限制了默认 Maven 缓存和 Java JAR 路径解析，使用工作区临时依赖副本、显式编译及 `-Dmaven.compiler.useIncrementalCompilation=false` 后完成 `mvn -o -q -DskipTests package`；测试也使用相同缓存与增量编译参数。未修改 `pom.xml`。
- 前端生产构建通过：`npm.cmd run build -- --configLoader native`。默认配置打包器因沙箱无法读取工作区上级目录而失败，native 加载器绕过此限制；未修改 Vite 配置或环境文件。
- `git diff --check` 通过。未访问浏览器、部署或连接实际 MySQL。

正常本地环境可使用原项目的 `mvn test`、`mvn -q -DskipTests package` 和 `npm.cmd run build`。以上沙箱辅助产物均位于被忽略的 `target/`、`dist/`，不作为源代码提交。

## 文件变更清单

| 范围 | 文件 | 修改内容 |
| --- | --- | --- |
| 私信接口 | `controller/DmController.java` | 新增打开会话、列表、历史和发信接口 |
| 私信业务 | `service/DmService.java`、`service/DmMessageSent.java` | 会话归属校验、消息保存、事务提交事件 |
| 请求与响应 | `dto/DmRequests.java`、`dto/DmViews.java` | 输入长度校验与公开响应字段 |
| 数据模型 | `entity/DmThread.java`、`entity/DmMessage.java`、`mapper/DmThreadMapper.java`、`mapper/DmMessageMapper.java` | 独立私信实体与数据库访问 |
| 鉴权复用 | `service/FriendService.java`、`mapper/FriendRequestMapper.java`、`service/AuthService.java` | 好友用户对校验、批量有效登录检查 |
| 实时通道 | `websocket/DmHandshakeInterceptor.java`、`websocket/DmSocketHandler.java`、`config/WebSocketConfig.java`、`config/SecurityConfig.java` | 登录 Bearer 握手、双方推送和撤销连接 |
| 数据库 | `backend/src/main/resources/schema.sql`、`schema-upgrade-dm.sql` | 新增两个私信表及索引约束 |
| 前端 | `frontend/src/api/chat.js`、`frontend/src/router.js`、`frontend/src/views/RoomListView.vue`、`DmChatView.vue` | 私信 API、路由、好友入口与独立聊天页 |
| 测试 | `backend/src/test/java/com/mairy/chat/DmIntegrationTest.java`、`frontend/src/views/DmChatView.test.mjs` | 权限、历史、推送及异步页面隔离回归 |
| 说明 | `FEATURE_DM_NOTES.md` | 明文边界、升级、接口、试用步骤和验证结果 |

表中简写的后端 Java 路径均相对于 `backend/src/main/java/com/mairy/chat/`。

## 2026-11-06 Part B 验证与 Review

- 前端定向 Node 测试 23 项通过：`node --experimental-vm-modules --test src/media/dm-voice.test.mjs src/views/DmChatView.test.mjs src/views/ChatRoomView.test.mjs src/crypto/voice.test.mjs src/crypto/e2ee.test.mjs`。包含明文往返、录制 / 预览 / 发送 / 播放、60 秒停止、取消、失败重试、403 与跨标签页清理，以及原有房间密文回归。
- `DmIntegrationTest` 4 项通过：覆盖最大音频的 REST / 数据库 / 历史 / 双方推送、非法大小 / 时长 / MIME / Base64 拒绝、文字兼容、权限隔离，以及升级后历史与明文约束保留。H2 测试路径将正式 MySQL 多操作 ALTER 拆分并移除列位置，不修改生产迁移；显式使用 UTF-8，时间按数据库存储精度比较。
- `mvn test -Dtest=DmIntegrationTest,AuthIntegrationTest,TimezoneRegressionTest` 共 15 项通过（私信 4、账号 5、时区 6），无失败或跳过。使用离线本地 `~/.m2/repository` 和临时空 settings，避免用户默认镜像配置导致缓存仓库 ID 不匹配；临时设置不进入提交。
- `npm.cmd run build` 通过，dist 不提交；未访问浏览器、真实 MySQL、麦克风设备或线上服务。

Review 未发现阻断问题。维护者待决定项：音频随历史分页整页返回，50 条上限语音的 Base64 正文约 35 MB，前端还需解码内存；是否后续拆分媒体按需加载由维护者决定。不同浏览器的录制 / 播放兼容性与真实 MySQL ALTER 尚需环境验收，本次没有扩展实现范围。

| 文件 | 本次内容 |
| --- | --- |
| `backend/.../dto/DmRequests.java`、`DmViews.java`、`entity/DmMessage.java` | kind、mime、毫秒 duration 与 Base64 载荷 |
| `backend/.../service/DmService.java`、`websocket/DmSocketHandler.java` | 明文语音校验 / 保存 / 返回，1 MiB 推送缓冲 |
| `backend/src/main/resources/schema.sql`、`schema-upgrade-20261106-dm-voice.sql` | 新库结构与已有 MySQL 一次性迁移 |
| `backend/src/test/java/com/mairy/chat/DmIntegrationTest.java` | 语音全链路、边界校验与 H2 兼容迁移回归 |
| `frontend/src/api/chat.js`、`views/DmChatView.vue` | 复用私信接口，录制、预览、明文发送与播放清理 |
| `frontend/src/media/dm-voice.js`、`dm-voice.test.mjs`、`views/DmChatView.test.mjs` | 明文编解码与真实 Vue 内存渲染回归 |
| `docs/ARCHITECTURE.md`、本文 | 当前行为、API、迁移和验证范围 |
