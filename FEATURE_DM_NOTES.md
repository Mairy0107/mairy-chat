# 好友私信 v1

私信与多人房间完全独立。仅 `chat_friend_request.status=ACCEPTED` 的双方可以打开、发送及读取私信。每对好友只有一个会话，双方从朋友列表点击「发私信」进入 `/dm/:userId`。

## 内容与权限

- **私信当前未实现端到端加密：`enc=0`、`iv=NULL`，数据库和服务器可以读取内容。** 页面也会明确显示这一提示。
- 私信不依赖 Web Crypto，已有账号可在普通 HTTP IP 环境中使用。HTTP 传输同样没有加密，实际使用建议配置 HTTPS/WSS。
- 房间继续强制 `enc=1`，房间密钥、加密算法、成员凭证与 `/ws/chat` 均沿用原流程。
- 每次读写都检查会话成员、已接受好友关系及对方是否停用；无关用户和非好友返回 403 中文提示。无效、到期、已撤销或停用账号的登录返回 401。
- 私信实时连接按登录账号索引。只向会话双方的有效登录连接推送；推送前批量检查有效登录，不在循环中访问 SQL。注销、重置密码会关闭对应连接。
- 消息事务提交后才推送；推送失败不撤销已保存的消息。前端合并 REST 与 WebSocket 消息时按主键去重。
- 本版没有已读、未读提醒、会话预览、文件附件或自动重连。断线后可手动重连或刷新消息，重连会重新读取最近一页，更早记录使用游标加载。

## 本地数据库

新数据库使用 `backend/src/main/resources/schema.sql`。

已有数据库手动执行 `backend/src/main/resources/schema-upgrade-dm.sql`。脚本仅使用 `CREATE TABLE IF NOT EXISTS` 新增 `chat_dm_thread`、`chat_dm_message`，可重复执行，不修改房间表或现有消息。不要让应用自动在远程数据库执行升级。

所有写入时间使用既有 `ChatTime.now()`（Asia/Shanghai），接口返回无额外时区换算的本地时间。

## 接口

REST 均携带 `Authorization: Bearer <登录凭证>`，不使用 `X-Session-Token`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/dm/with/{userId}` | 获取或创建好友会话，返回 `id`、`peer`、`createdAt` |
| GET | `/api/dm/threads` | 本人仍有权限的最近 200 个会话，按会话 ID 倒序 |
| GET | `/api/dm/threads/{threadId}/messages?beforeId=&size=30` | 正序消息页，返回 `records`、`nextBeforeId`、`hasMore`；size 为 1～50 |
| POST | `/api/dm/threads/{threadId}/messages` | 请求体 `{"content":"你好"}`，最多 1000 字；发送人和明文标记由服务端决定 |

WebSocket：`/ws/dm?token=<登录凭证>`，也支持握手请求头 `Authorization: Bearer ...`。浏览器不记录带凭证的 URL。来源限制和 IP 限流沿用项目配置。不要在代理日志中记录查询凭证。

事件格式：`{"type":"dm-message","data":{"id":"...","threadId":"...","senderId":"...","content":"你好","enc":0,"iv":null,"createdAt":"..."}}`。客户端发送消息必须走 REST，WebSocket 不接受发信。

开发环境沿用 Vite 的 `/ws` 代理。生产路径从已有 `VITE_WS_PATH` 的结尾 `/chat` 推导为 `/dm`，例如 `/chat/ws/chat` 对应 `/chat/ws/dm`。特殊路径可配置 `VITE_DM_WS_PATH`；本次未修改任何 `.env`。反向代理需将 `/chat/ws/dm` 转发为后端 `/ws/dm` 并支持 Upgrade，若已有 `/chat/ws/` 通用转发则无需额外规则。

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

本次验证结果：

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
