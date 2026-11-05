# Mairy Chat 架构说明

本文描述当前仓库实现；核对日期：2026-11-04。功能边界以代码为准，既有 NOTES 保留对应阶段的实现与验收记录。

**凡影响结构 / API / 功能行为的代码改动，必须在同一次提交（或同一 PR）里同步更新本文。** 修改前先阅读本文；涉及认证、加密、数据库或部署时，同时核对下文链接的专项说明。

## 1. 总览与技术栈

| 层次 | 当前实现 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3.5.16、Spring MVC、Spring Security、Bean Validation |
| 持久化 | MyBatis-Plus 3.5.18、MySQL 8、InnoDB、utf8mb4；手动 SQL 初始化与升级 |
| 前端 | Vue 3.5、Vue Router 4、Vite 7、JavaScript、原生 fetch |
| 实时通信 | Spring 原生 WebSocket；REST 保存消息，WebSocket 只推送，不使用 STOMP / SockJS |
| 房间加密 | 浏览器 Web Crypto；AES-256-GCM 正文、RSA-OAEP 密钥信封 |
| 状态与缓存 | 登录会话落库；限流窗口及 WebSocket 连接索引在单进程内存；无 Redis |
| 时间 | `ChatTime.now()` 使用 Asia/Shanghai；MySQL DATETIME、接口 LocalDateTime 不带时区偏移 |
| 验证 | 后端 JUnit / Spring Boot Test + H2；前端 Node 原生测试，使用真实 Web Crypto 与存储 / 网络替身 |

后端沿用 `Controller → Service → Mapper → MySQL` 分层，DTO 明确筛选公开字段；常规 CRUD 复用 MyBatis-Plus，锁与关联查询位于 Mapper。列表资料采用批量补全或关联查询，不应在循环中调用 SQL。

当前按单实例设计。增加实例需要另行解决共享限流、消息跨实例推送与会话撤销通知；仓库尚未实现这些能力。

## 2. 仓库目录与职责

| 路径 | 职责 |
| --- | --- |
| `backend/pom.xml` | 后端依赖与 Spring Boot 打包 |
| `backend/src/main/java/com/mairy/chat/` | 后端应用根包；入口 `ChatApplication.java` 开启调度与 Mapper 扫描 |
| `backend/.../controller/` | 认证、房间、消息、好友、私信、E2EE、后台接口与统一异常响应 |
| `backend/.../service/` | 权限及业务规则、事务、会话撤销和私信提交事件 |
| `backend/.../mapper/`、`entity/`、`dto/` | 数据访问、数据库实体、请求与响应对象 |
| `backend/.../config/`、`security/` | Spring Security、CORS、分页、WebSocket 注册、Bearer 过滤器 |
| `backend/.../websocket/`、`util/` | 两种握手与推送处理；随机凭证、上海时间工具 |
| `backend/src/main/resources/` | `application.yml`、完整 schema、升级脚本、本地管理员种子 |
| `backend/src/test/java/com/mairy/chat/` | 账号 / E2EE、私信集成测试及时区回归 |
| `frontend/src/views/` | 登录注册找回、大厅、进房、房间聊天、私信、管理后台页面 |
| `frontend/src/router.js` | 基于 history 的路由、登录及角色守卫、跨标签登录变化处理 |
| `frontend/src/api/chat.js` | fetch 封装、房间与私信 API、两种 WebSocket 连接 |
| `frontend/src/auth.js` | Vue reactive 登录状态、localStorage 凭证、浏览器指纹；没有独立 `stores/` 目录或 Pinia |
| `frontend/src/crypto/e2ee.js` | 设备身份、加密保险箱、密钥信封、消息加解密与备份恢复 |
| `frontend/src/App.vue`、`style.css` | 应用外壳、退出入口和样式 |
| `deploy/` | Dockerfile、Compose、nginx 路径片段及部署说明 |
| `docs/` | 本文与现有设计参考资料；设计图片不表示已实现功能 |
| 根目录 `*_NOTES.md`、`SECURITY.md` | 分阶段实现记录、安全策略与已知限制 |

表中 `backend/.../` 均指 `backend/src/main/java/com/mairy/chat/`。

| 前端路由（开发路径） | 页面与职责 |
| --- | --- |
| `/login`、`/register`、`/forgot-password` | `AuthView.vue` 复用账号表单，公开访问 |
| `/app` | `RoomListView.vue`，房间大厅、自建房、好友与私信入口 |
| `/assistant` | `AssistantView.vue`，登录用户的独立文字助手、会话历史与 SSE 回复 |
| `/room/:id` | `RoomEntryView.vue` 读取房间并创建进房会话，再交给 `ChatRoomView.vue` |
| `/dm/:userId` | `DmChatView.vue`，按对方用户主键打开好友私信 |
| `/admin` | `AdminView.vue`，仅管理员进入 |

根路径及未匹配路径重定向 `/app`；生产路径统一加 `/chat` 前缀。登录守卫在页面访问前核验身份，服务端仍独立校验每个请求。

## 3. 运行与配置

### 本地启动

1. 准备 JDK 17、Maven 3.6.3+、MySQL 8；Node 满足 `^20.19.0 || >=22.12.0`。
2. 从根目录复制 `.env.example` 为 `.env`，填写本地数据库配置。不要提交真实 `.env`、生产凭据或密钥。
3. 按第 10 节选择初始化 / 升级脚本。`spring.sql.init.mode=never`，启动应用不会自动建库、建表或迁移。
4. 在 `backend/` 执行 `mvn spring-boot:run`；在另一终端的 `frontend/` 执行 `npm.cmd install`、`npm.cmd run dev`。
5. 本地前端地址为 `http://localhost:5173`，后端为 `http://127.0.0.1:8080`；后端没有首页。Vite 使用固定端口，占用时不会自动换端口。

后端从 `backend/` 启动时，以 Java properties 格式加载 `../.env`；值不加引号，字面反斜杠需写两个，系统环境变量优先。Vite 的 `envDir` 指向仓库根目录，生产变量读取根目录 `.env.production`；不要误以为只改 `frontend/.env.production` 就会生效。

| 配置 | 用途 / 默认值 |
| --- | --- |
| `DB_HOST` / `DB_PORT` | MySQL 地址，默认 `127.0.0.1:3306` |
| `DB_USERNAME` / `DB_PASSWORD` | 数据库账号 / 密码；数据库名固定为 `mairy_chat` |
| `SERVER_ADDRESS` / `SERVER_PORT` | 后端默认 `127.0.0.1:8080`；Compose 在容器内覆盖为 `0.0.0.0:8080` |
| `CHAT_ALLOWED_ORIGINS` | HTTP CORS 与 WS 来源白名单；默认 localhost:5173、127.0.0.1:5173，完整 origin 用英文逗号分隔 |
| `DEV_BACKEND_URL` | Vite 代理目标，默认 `http://127.0.0.1:8080`，不会作为前端业务配置导出 |
| `VITE_API_BASE` | 浏览器 API 前缀，默认 `/api`；现有生产配置 `/chat/api` |
| `VITE_WS_PATH` | 房间 WS 路径，默认 `/ws/chat`；现有生产配置 `/chat/ws/chat` |
| `VITE_DM_WS_PATH` | 可选私信 WS 路径；未配置时把房间 WS 路径末尾 `/chat` 替换为 `/dm` |

Vite 开发 base 为 `/`，生产 base 为 `/chat/`，路由通过 `import.meta.env.BASE_URL` 使用相同前缀。开发 / 预览代理覆盖 `/api`、`/ws`，并把 `/chat/api`、`/chat/ws` 改写为后端对应路径。所有 `VITE_` 变量均可进入浏览器，不能放密码或私钥。

当前 `WebConfig` 的 CORS 方法仅允许 GET、POST、OPTIONS，E2EE 的两个写入接口使用 PUT。因此独立来源直连后端的 E2EE PUT 预检会受限；本地 Vite 和生产 nginx 的同源代理路径不依赖此跨源调用方式。此限制尚未修复。

## 4. 认证与 Token

认证入口为 `AuthController` / `AuthService`，API 请求经 `BearerAuthFilter` 与 `SecurityConfig` 校验。系统使用用户名和密码登录，不接入 SSO / OAuth，也不使用 JWT。

| 凭证 | 产生与存储 | 使用与校验 |
| --- | --- | --- |
| 登录 Bearer | `SecureRandom` 生成 32 字节，返回 64 位十六进制字符串；数据库 `chat_login_session.id` 只保存 SHA-256，前端保存于 `mairy.auth.token` localStorage | REST 的 `Authorization: Bearer ...`；私信 WS 使用同一登录凭证；固定有效期 8 小时，无刷新令牌机制 |
| 进房 Token | 每次进入房间生成随机 UUID；原值保存于 `chat_member.token`，前端仅存页面内存 | 房间消息及房间密钥接口的 `X-Session-Token`，房间 WS 查询参数；绑定账号、房间、具体登录会话，有效期等于该登录会话 |

- POST `/api/auth/register`、`/login`、`/forgot-password` 为公开业务接口；GET `/api/auth/me`、POST `/api/auth/logout` 及其余 `/api/**` 要求有效 Bearer。OPTIONS 由 CORS 处理。
- 密码以 BCrypt（工作因子 10）散列；注册与重置要求 8～64 字符且 UTF-8 不超过 72 字节。不返回密码散列。
- 每次认证检查数据库会话、过期时间、账号存在性与停用状态。退出删除当前登录；管理员重置密码删除该账号全部登录，提交后通知本实例关闭相关 WS。
- 房间 REST 同时验证 Bearer 与进房 Token 属于同一个登录，不能跨账号、跨登录或跨房间复用。房间 WS 握手通过进房 Token 检查其背后的有效登录，无需另传登录 Bearer。
- 不创建 HttpSession，不用 Cookie 认证。前端刷新时通过 `/auth/me` 恢复用户资料；携带当前登录凭证的请求收到 401 时清理登录并跳转登录页。
- 登录状态通过 `auth.js` 共享；退出、换账号及其他标签页登录变化会锁定内存设备密钥。进房凭证不持久化，重新进入页面会创建新的进房会话。

localStorage 存储 Bearer 有 XSS 风险；WS 查询参数可能进入代理日志。应用不主动打印带凭证 URL，部署日志也应避免记录查询凭证；外部访问需 HTTPS / WSS。

## 5. 用户角色与规则

| 规则 | 当前行为 |
| --- | --- |
| `USER` | 注册只创建普通用户；登录默认进入 `/app`，可进房、建立好友与私信、登记 E2EE 身份 |
| `ADMIN` | 登录默认进入 `/admin`；可查看后台并隐身巡查；不能获得房间加密身份或内容密钥 |
| 公开编号 | 数据库存整数，响应格式为 `#0001`，超四位自然扩展；不等于用户主键。序列行锁保证注册分配，失败回滚不占编号 |
| 一人一房 | ADMIN 和 USER 都最多创建一间房，由 `chat_room.owner_user_id` 唯一约束兜底；不限制进入其他现有房间 |
| 注册配额 | 同一浏览器指纹累计最多注册三个普通账号；事务锁定配额行，失败不消耗配额；本地种子管理员不占配额 |
| 指纹边界 | 浏览器使用 UA、canvas、屏幕、语言、时区生成并持久化 SHA-256；服务端再散列保存。可清理或伪造，不是硬件身份 |
| 人工找回 | 只创建待办并统一回复，不自动发邮件；管理员核实身份后指定并线下交付临时密码 |

本地 `data-local.sql` 预留管理员 `#0001`，默认口令见 [README](../README.md)，仅供本地使用，上线前必须修改。后台尚无角色编辑、停用操作或用户自行改密码入口；代码会校验现有 `disabled` 状态。

## 6. 房间、消息与实时推送

主要实现：`RoomController` / `RoomService`、`MemberController` / `MemberService`、`MessageController` / `MessageService`、`ChatSocketHandler`。

| 方法与后端路径 | 行为 |
| --- | --- |
| GET `/api/rooms?page=1&size=20` | 房间倒序分页，size 为 1～100 |
| GET `/api/rooms/mine`、`/api/rooms/{id}` | 自建房（未创建时空响应）与房间详情 |
| POST `/api/rooms` | `{name}` 创建唯一自建房，名称必填、最多 50 字符 |
| POST `/api/rooms/{roomId}/members` | `{stealth:false}` 进房，返回成员 `id`、roomId、nickname、token、expiresAt；昵称取登录账号 |
| GET `/api/rooms/{roomId}/messages?beforeId=&size=30` | Bearer + 进房 Token；主键游标历史，size 为 1～50 |
| POST `/api/rooms/{roomId}/messages` | Bearer + 进房 Token；只接收 `{content,enc:1,iv,kind?}` 加密消息；kind 为 text / voice，省略为 text |
| WS `/ws/chat?token=进房Token` | 来源白名单、直连 IP 限流、有效进房与登录校验；按房间推送 `type=message` |

消息流程：客户端加密 → POST → 服务事务保存 → 控制器调用 WS 推送 → 前端合并回包与推送。WS 不接受发信，向 WS 发送文本会被关闭。推送失败不撤销已入库记录，可通过历史补读。

历史以 `id < beforeId` 倒序多取一条判断 `hasMore`，返回当前页正序 `records`、`nextBeforeId`、`hasMore`。前端按字符串主键去重，用 BigInt 排序；首次连接与手动重连成功后重新读取最近一页，更早记录继续用游标加载。没有自动重连或全量离线补偿队列。

客户端房间文字限制 1～1000 字符，语音限制 60 秒 / 512 KiB；服务端验证规范 Base64、`enc=1`、12 字节 IV，以及文字 17～4016 / 语音 17～700016 字节密文，不接触明文。`chat_message` 的 `(room_id,iv)` 唯一约束拒绝同房间重复 IV，冲突返回 409，需要重新加密发送。历史 `enc=0` 仍可读取。

两种 WS 均在连接登记时复核登录，使用慢连接发送限制；退出 / 重置事件关闭匹配连接，调度每 30 秒检查过期连接。房间连接按房间索引、推送循环只检查内存会话和到期时间；慢连接缓冲上限 1 MiB、发送时限 5 秒，可容纳单条语音密文；私信推送另有批量有效登录检查，见下一节。

接口错误统一为 JSON `{"message":"中文说明"}`，公开主键通常以字符串返回，避免 JavaScript 精度丢失。

## 7. 好友与一对一私信（DM）

好友由 `FriendService` 管理；`chat_friend_request` 同时保存申请和已接受关系。规范化双向用户对有唯一约束，状态为 PENDING / ACCEPTED / REJECTED。

| 方法与后端路径 | 行为 |
| --- | --- |
| GET `/api/friends/search?q=...` | 按公开编号（可带 `#`）或用户名 / 昵称搜索，排除自己与停用账号，最多 30 条 |
| GET `/api/friends` | 本人好友及申请，最近 200 条，批量补全双方资料 |
| POST `/api/friends/{userId}` | 发起好友申请，禁止自加与重复申请 |
| POST `/api/friends/requests/{id}?accept=true或false` | 仅接收方可处理待办申请 |
| POST `/api/dm/with/{userId}` | 为已接受好友获取或创建唯一私信会话 |
| GET `/api/dm/threads` | 本人仍有权限的最近 200 个会话，按会话主键倒序 |
| GET / POST `/api/dm/threads/{threadId}/messages` | 游标历史 / 发送 `{content}`；历史 size 默认 30、范围 1～50；正文最多 1000 字符 |
| WS `/ws/dm?token=登录Bearer` | 以登录账号索引连接，只向会话双方有效登录推送 `type=dm-message`；非浏览器客户端也可用 Authorization 握手 |

**私信当前是明文：`enc=0`、`iv=NULL`，服务端和数据库能够读取正文。房间 E2EE 不覆盖 DM。** 私信独立使用 `chat_dm_thread` / `chat_dm_message`，REST 只要 Bearer，不使用进房 Token、房间密钥或 Web Crypto；页面有明文提示。已有账号可在普通 HTTP 环境登录私信，但 HTTP 本身不加密传输，仍应使用 HTTPS / WSS。

每次打开、历史读取与发信都校验会话参与人、已接受好友关系及对方可用状态；服务端根据登录确定发送人。事务内发布 `DmMessageSent`，由 `DmSocketHandler` 提交后推送；推送前一次批量检查登录，发送循环不查 SQL。前端只合并当前 threadId 的事件，并在切换好友、卸载或权限失效时丢弃旧异步结果。

当前不支持删除好友、拒绝后重新申请、已读 / 未读、会话预览、附件与自动重连。好友关系不会自动授予房间密钥，房主仍需独立核验和授权。

## 8. 房间端到端加密与设备密钥

实现位于 `E2eeController` / `E2eeService`、`frontend/src/crypto/e2ee.js` 与 `ChatRoomView.vue`。仅 USER 可以登记身份、取得信封和发送加密消息。

| 对象 | 生成、保护与存放 |
| --- | --- |
| 账号设备身份 | 客户端生成 RSA-OAEP 3072 位密钥对，SHA-256；公钥 SPKI / 指纹登记到 `chat_user_pubkey`，登记后不能覆盖；每账号只有这一份登记身份，多设备需导入同一备份 |
| 本地私钥保险箱 | PKCS8 私钥以 PBKDF2-HMAC-SHA256（600000 次、16 字节随机盐）派生 AES-256-GCM 密钥加密；12 字节随机 IV，AAD 绑定版本 / 账号 / 公钥；只存 localStorage `mairy.e2ee.v1.vault.<账号ID>` |
| 解锁身份 | 私钥作为不可导出的 CryptoKey 留在内存；首次安全环境登录尝试用登录密码创建 / 解锁，刷新后需重新解锁，退出 / 切换账号会锁定 |
| 房间内容密钥 | 普通账号房主在客户端生成 AES-256-GCM 密钥；明文密钥仅留房间页面内存，服务端不接收 |
| 成员密钥信封 | 房主用成员 RSA 公钥封装房间密钥，OAEP label 绑定协议 / 房间 / 收件账号 / 公钥指纹；`chat_room_key_wrap` 按房间和账号唯一，禁止覆盖 |
| 消息密文 | 每条随机 96 位 IV、128 位 GCM 标签，AAD 绑定协议 / 房间 / 发送 memberId，语音另绑定 kind:voice；Base64 存储与传输，后端 / WS 不解密 |
| 房间信任指纹 | 通过可信渠道核验完整 SHA-256 指纹后存于 `mairy.e2ee.v1.room-pin.<账号ID>.<房间ID>`，不从服务器返回值自动建立信任 |

| 方法与后端路径 | 凭证与用途 |
| --- | --- |
| GET / PUT `/api/e2ee/identity` | Bearer；读取 / 首次登记当前账号公钥 |
| GET `/api/e2ee/rooms/{roomId}` | Bearer + 进房 Token；读取本人信封与房主 / 初始化状态 |
| GET `/api/e2ee/rooms/{roomId}/candidates` | Bearer + 进房 Token；房主关联查询待授权普通成员，最多 200 人 |
| PUT `/api/e2ee/rooms/{roomId}/members/{userId}` | Bearer + 进房 Token；仅房主可提交公钥指纹及成员专属 wrappedKey，房间行锁串行初始化 |

授权顺序：成员先有身份并正常进房 → 房主通过其他可信渠道向成员本人核验完整公钥指纹 → 房主提交专属信封 → 房主再通过可信渠道提供房间密钥指纹 → 成员首次解封时输入核验。房主初始化固定本机生成的房间密钥指纹；后续发现指纹变化或信封替换时停止收发。不能把服务器候选指纹直接复制作为身份核验。

密钥操作需要 HTTPS 或 localhost 安全环境和 Web Crypto。普通远程 HTTP 不能解锁 / 初始化密钥，发送被禁用，不降级明文；注册指纹优先复用 localStorage 中的 64 位十六进制 SHA-256；首次计算在缺少 SubtleCrypto 的 HTTP IP 环境使用等价纯 JS SHA-256，不改变三账号配额。注册、BCrypt 登录和人工找回不依赖 SubtleCrypto；账号页将浏览器异常转换为中文提示。管理员创建的房间、无账号房主的历史房间无法初始化当前 E2EE，应由普通账号创建加密房间。

备份由用户在本地导出加密保险箱，服务器不接收私钥、保险箱或派生密钥。新设备需登录同一账号、导入原备份并用原本地密码解锁，还要通过离线记录或可信渠道重新核验房间指纹；导入不覆盖已有保险箱。**重置登录密码不会解密旧保险箱，也不能替换已登记公钥。没有原设备 / 备份或忘记原本地密码时，管理员无法恢复历史密文。**

当前没有前向保密、双棘轮、自动密钥轮换、移除成员后的密钥撤销、独立多设备身份或发送者签名。授权共享整个房间密钥，因此可读全部房间历史和后续密文。旧明文不追溯加密；服务器仍可见元数据。安全保证依赖可信客户端与正确指纹核验，不能抵御被篡改的前端脚本、XSS 或失陷设备，未经过独立密码学审计。

### 房间语音

仅 `ChatRoomView` 使用 `getUserMedia` / `MediaRecorder` 录音，支持浏览器可录制的 WebM / Ogg Opus 或 MP4，目标码率 32 kbps。录完先本地预览，点击「发送语音」才加密上传；录制到 60 秒自动停止，超过 512 KiB 则丢弃并提示，失败保留草稿可重试。需要 HTTPS / localhost、麦克风权限及已解锁并核验的房间密钥，无明文回退。

`e2ee.js` 将 `{version:1,mime,duration,data}`（data 为音频 Base64，duration 为毫秒）作为 UTF-8 JSON，用既有房间 AES-GCM 密钥与新随机 IV 加密。语音 AAD 为 `mairy:e2ee:v1:message:<roomId>:member:<memberId>:kind:voice`；文字沿用原 AAD，因此无法将语音密文改标为文字播放。外部字段只有 `kind=voice`、enc / iv / content，音频格式、时长及字节均在密文内；服务端仍可见消息类型、长度与原有元数据。

解密校验通过且音频格式、时长和大小合法后，页面才创建本地 Blob URL 供原生播放器按需播放；不加载网络注入的播放 URL。刷新历史、替换消息、锁定密钥、跨标签页登录变化、会话失效或离开时撤销相应 URL；取消和离开同时停止麦克风及迟到回调。DM 仍为明文文字，助手仍为云端文字；两者均无语音入口。详见 [VOICE_STREAM_NOTES.md](../VOICE_STREAM_NOTES.md)。

## 9. 管理后台与隐身巡查

`/admin` 的前端守卫只负责导航，服务端 `SecurityConfig` 对 `/api/admin/**` 强制 ADMIN 权限。

| 后台接口 | 当前能力 |
| --- | --- |
| GET `/api/admin/users?page=1` | 每页 30 个用户，公开资料与停用状态，不返回密码散列 |
| GET `/api/admin/users/{id}/devices` | 粗略系统 / 浏览器标签和注册时间，不返回原始 UA |
| GET `/api/admin/reset-requests` | 最近 200 条人工密码申请，批量补全账号资料 |
| POST `/api/admin/users/{id}/reset-password` | 管理员指定临时密码、处理该账号全部待办、撤销全部登录及相关实时连接 |
| GET `/api/admin/ip-blocks` | 最多 100 条有效 IP 封禁 |
| POST `/api/admin/ip-blocks/unblock?ip=...` | 解封直连 IP 并清理本实例计数 |

后台房间列表复用普通房间接口，隐身进入仍调用进房接口 `{stealth:true}`。服务端限制只有管理员可隐身且只读；没有公开成员列表或进房广播。管理员无论普通还是隐身进入，都不能登记加密身份、领取信封、成为收件人或发送加密房间消息；可见旧明文、密文和元数据，不能解密 `enc=1` 正文。后台没有读取任意私信的接口。

API 与 WS 握手按直连 IP 合计每分钟 240 次；登录每 15 分钟 20 次、注册 / 找回各每小时 5 次。15 分钟内 5 次错误登录封禁 15 分钟，封禁落库。限流计数在内存；不信任 X-Forwarded-For / X-Real-IP，代理下用户可能共享配额。完整审计导出、MFA、验证码和过期数据库记录清理尚未实现。

## 10. 数据库与迁移脚本

以下文件均位于 [backend/src/main/resources](../backend/src/main/resources/)，MySQL 手动执行；没有 Flyway / Liquibase 或自动迁移机制。

| 文件 | 用途与执行条件 |
| --- | --- |
| [schema.sql](../backend/src/main/resources/schema.sql) | 新建库完整结构，含账号、房间文字 / 语音、E2EE、DM 和助手；CREATE TABLE IF NOT EXISTS 不会升级旧表 |
| [schema-upgrade-auth.sql](../backend/src/main/resources/schema-upgrade-auth.sql) | 从匿名版升级到账号版；新增账号相关表、房主与登录绑定字段，旧匿名会话置过期；含 ALTER，只执行一次 |
| [schema-upgrade-e2ee.sql](../backend/src/main/resources/schema-upgrade-e2ee.sql) | 已有账号库增加消息 enc / iv、扩大 content、新增公钥 / 信封表；旧行保持 `enc=0`、iv 为 NULL；含 ALTER，只执行一次 |
| [schema-upgrade-dm.sql](../backend/src/main/resources/schema-upgrade-dm.sql) | 已有账号库新增私信会话 / 消息表；仅 CREATE TABLE IF NOT EXISTS，可重复执行，不改房间消息 |
| [schema-upgrade-voice.sql](../backend/src/main/resources/schema-upgrade-voice.sql) | 已有房间 E2EE 库扩大 chat_message.content 为 MEDIUMTEXT，新增 kind 默认 text；含 ALTER，仅执行一次，不改 DM / 助手表 |
| [data-local.sql](../backend/src/main/resources/data-local.sql) | 仅本地管理员种子；不存在用户名 / 编号时才插入，不覆盖已改密码，不占浏览器注册配额 |

选择路径：全新库执行 `schema.sql → data-local.sql`；旧匿名库按 `schema-upgrade-auth.sql → schema-upgrade-e2ee.sql → schema-upgrade-dm.sql → schema-upgrade-voice.sql` 补齐，再按需导入本地种子；已经具备部分功能的账号库只执行尚未应用的对应脚本。执行前备份并确认结构，不要在新库重复执行 ALTER，也不要把全量 schema 当作旧库升级。

| 表组 | 表及关键约束 |
| --- | --- |
| 账号与配额 | `chat_user`（用户名 / public_no 唯一）、`chat_public_sequence`、`chat_device_quota`（计数 0～3）、`chat_device_registration` |
| 登录与管理 | `chat_login_session`（散列 Token）、`chat_password_reset_request`、`chat_ip_block` |
| 好友 | `chat_friend_request`，生成列 pair_low / pair_high 的用户对唯一；拒绝后关系仍保留 |
| 房间 | `chat_room`（房主唯一，旧房间可为空）、`chat_member`（登录绑定进房会话）、`chat_message`（room_id / id 游标索引、room_id / iv 唯一） |
| E2EE | `chat_user_pubkey`（账号唯一公钥）、`chat_room_key_wrap`（房间 / 收件账号唯一） |
| 私信 | `chat_dm_thread`（有序用户对唯一）、`chat_dm_message`（thread_id / id 索引，CHECK 固定 enc=0 且 iv 为 NULL） |

旧库迁移保留房间、成员及历史，不自动指定旧房间房主，不追溯加密。H2 测试不能替代 MySQL 实际 DDL、字符集和迁移验证。

## 11. 部署路径与现有配置

此节说明仓库提供的部署约定，不表示已核验 ECS 当前状态。现有说明以 `/opt/mairy-chat` 为应用目录，与 `/opt/website` 并列。

| 文件 / 路径 | 作用 |
| --- | --- |
| [deploy/README.md](../deploy/README.md) | 现有 ECS 部署步骤与文件摆放说明 |
| [deploy/Dockerfile](../deploy/Dockerfile) | Java 17 JRE，复制 `app.jar`，非 root 用户运行；JAR 需先由 backend 打包并放入部署目录 |
| [deploy/docker-compose.yml](../deploy/docker-compose.yml) | `chat-api` 容器，读取部署目录 `.env`，连接现有外部网络 `website_website` / `mysql_default`；数据库主机覆盖为 `mysql`，不负责新建 MySQL |
| `/opt/mairy-chat/frontend/dist` | 前端产物来源，按部署说明挂载到 `site-web` 的 `/usr/share/nginx/html/chat` |
| [deploy/nginx-chat.conf.snippet](../deploy/nginx-chat.conf.snippet) | 合并到 `/opt/website/nginx.conf` 的路径片段，处理 SPA、API 与 WS；不是独立完整 nginx 配置 |

| 外部路径 | nginx / 后端映射 |
| --- | --- |
| `/chat` | 301 跳转 `/chat/` |
| `/chat/` 与前端 history 路由 | 静态资源，找不到文件时回退 `/chat/index.html` |
| `/chat/api/` | 转发 `http://chat-api:8080/api/` |
| `/chat/ws/chat`、`/chat/ws/dm` | 通用 `/chat/ws/` 转发到 `http://chat-api:8080/ws/`，设置 Upgrade 与 3600 秒读写超时 |

部署时白名单需匹配真实访问 origin（协议、主机、端口，不含 `/chat/`）。现有部署说明包含 HTTP IP 示例，但仓库片段未配置 TLS，远程房间 E2EE 仍需外部 HTTPS / WSS 配置；不能据此声称普通 HTTP IP 已支持房间加密。

仓库没有独立 `.sh` / `.ps1` 自动部署或数据库迁移脚本，现有入口就是上述 Docker / Compose / nginx 文件及手动 SQL。部署不会自动升级数据库。

## 12. AI 通用助手 P0

大厅便签「助手」进入 `/assistant`，生产路径为 `/chat/assistant`。页面复用现有纸笺令牌；前端 `api/ai.js` 复用普通 REST 封装，流式发送使用带 Bearer 的 fetch / ReadableStream，`api/ai-stream.js` 增量解析 UTF-8 SSE。流期间提供停止按钮；用户阅读历史时不强制滚动到底部。内容按纯文本渲染。

后端 `AiController → service/ai/AiService → AiThreadMapper / AiMessageMapper / AiUsageMapper` 负责归属、历史与短事务；`AiStreamService` 管理 SSE 和过滤，`DeepSeekClient` 使用现有 Spring MVC 的 RestClient 调用 OpenAI 兼容的 `/chat/completions`，不依赖 Spring AI / WebFlux。所有 `/api/ai/**` 复用现有 Bearer 鉴权，只有归属账号可读写；管理员也没有读取他人助手会话的接口。房间 / DM WebSocket 不参与助手流程。

**助手对话为服务端明文，数据库及服务端可以读取，并会向配置的云端模型发送当前会话上下文；房间 E2EE 不覆盖助手。** 系统提示要求友善简洁中文、不确定说「不知道」、不编造用户经历、拒绝密码和秘密；助手没有房间、私信、档案或外部工具访问能力。

| 方法与路径 | 当前行为 |
| --- | --- |
| GET `/api/ai/status` | `{enabled,quotaRemaining,quotaLimit,profileCompleted:false}`；P1 问卷与人格覆盖未实现 |
| POST / GET `/api/ai/threads` | 创建本人空会话 / 最近100个本人会话；创建不消耗模型次数 |
| GET `/api/ai/threads/{id}/messages?beforeId=&size=30` | 本人会话的主键游标历史，size 1～50，返回 records / nextBeforeId / hasMore |
| POST `/api/ai/threads/{id}/messages` | `{content}`，非空、最多2000字符；校验成功后返回 text/event-stream |

SSE 事件依次为 `user`（已保存的用户消息）、`delta`（安全增量）、可选 `replace`（用安全提示替换临时文本）、`done`（已保存 assistant 消息、剩余次数及 outcome）。流开始前的失败沿用 JSON 中文错误，缺少密钥返回503「助手未配置」，配额耗尽返回429；流开始后的上游 / 过滤错误替换为安全提示并保存，保存或确认失败发 `error`，客户端提示刷新。只有收到 `done` 才确认回复完成；截断流不视为成功。

| 表 / 配置 | 当前实现 |
| --- | --- |
| `chat_ai_thread` | user_id 归属、首条消息摘要、上海创建 / 更新时间；active_request_id / busy_until 为3分钟生成租约，防止同一会话并发，旧租约不能写入新一轮回复 |
| `chat_ai_message` | thread_id、role、TEXT 明文、上海时间；索引 `(thread_id,id)`，系统提示不入库 |
| `chat_ai_usage_daily` | 主键 `(user_id,usage_date)`；日期来自 `ChatTime.now().toLocalDate()`，条件 UPDATE 原子计数；token_estimate 在 P0 留空 |
| `chat.ai.api-key` / `DEEPSEEK_API_KEY` | 服务端凭证环境占位，默认空；不得使用 VITE_ 或写入日志 |
| `chat.ai.base-url` / `DEEPSEEK_BASE_URL` | 默认 `https://api.deepseek.com`；支持兼容服务根路径 |
| `chat.ai.model` / `DEEPSEEK_MODEL` | 默认 `deepseek-chat` |
| `chat.ai.daily-quota` | 默认40次，允许0关闭额度；计数持久化，服务重启不重置 |
| `sensitive-words.txt` + `chat.ai.sensitive-words` | 合并资源和配置词表，忽略空行 / 注释，字面子串匹配并忽略英文大小写 |

已有账号版数据库手动执行 `backend/src/main/resources/schema-upgrade-ai.sql`，仅 `CREATE TABLE IF NOT EXISTS`，可重复执行；空库使用已包含这三张表的 `schema.sql`。启动不自动迁移数据库。

输入过滤在调用模型及占额前完成；输出扫描上次尾部与新分片，保留最长词长减一的尾部后才输出，防止跨分片命中词泄漏，完成时全量复查。命中停止读取上游，替换为统一中文拒绝并仅保存拒绝文本。日志只记 `ai_audit type=...`，不记录正文、命中词、上游错误体或凭证。此为基础词表过滤，不具备语义审核或反混淆能力。

用户消息、次数与租约在同一短事务中保存；最终 assistant 消息和释放租约共用另一个事务，模型等待不占用数据库事务。通过校验的请求计一次，模型失败 / 停止仍计次，不自动重试；未配置、越权、输入拦截、忙碌和超额均不调用模型。模型上下文限最近20条、合计24000字符，输出限16000字符 / 请求 max_tokens=2048，温度0.3。同步 MVC 最多8条并行流，连接等待10秒、单次读取等待45秒、总读取预算120秒；客户端180秒超时。停止读取会在服务端下一次传输发现断连后关闭上游，进程异常时租约到期恢复；可能留下只有用户消息的未完成一轮。

上游使用 4096 字符缓冲分块解码 UTF-8，跨块保留半行，完整 SSE 帧立即交付；单行及累计 data 仍限制 65536 字符，保留超时和 DONE 完整性校验。后端高效读取 SSE 并逐事件 flush；前端将 delta 排入打字机队列，约每 30ms 展示一个 Unicode 码点（含完整 emoji 码点）。done 保留已显示前缀并继续逐字展示余下内容，replace 清除旧队列后重新逐字展示。响应设置 `X-Accel-Buffering: no`、`Cache-Control: no-cache, no-transform`，nginx `/chat/api/` 显式设置 `proxy_buffering off`，用于逐步转发。部署方需保留该响应头行为及足够的 SSE 超时。P0 仅文字，不包含问卷、人格覆盖、语音、附件、删除记录、token计费、语义审核或工具调用。验收和边界见 [FEATURE_AI_NOTES.md](../FEATURE_AI_NOTES.md) 及 [VOICE_STREAM_NOTES.md](../VOICE_STREAM_NOTES.md)。

## 13. 相关文档与维护检查

| 文档 | 阅读用途 |
| --- | --- |
| [README.md](../README.md) | 本地环境、启动、账号规则、常用接口入口 |
| [AUTH_IMPLEMENTATION_NOTES.md](../AUTH_IMPLEMENTATION_NOTES.md) | 账号阶段实现、权限、并发控制与当时的验收记录；“无私聊”等是阶段性描述 |
| [SECURITY_E2EE_NOTES.md](../SECURITY_E2EE_NOTES.md) | 加密协议、指纹双向核验、设备备份恢复、威胁模型与手工验收 |
| [FEATURE_DM_NOTES.md](../FEATURE_DM_NOTES.md) | 明文私信边界、独立表、接口、升级和双账号验收 |
| [FEATURE_AI_NOTES.md](../FEATURE_AI_NOTES.md) | 助手 P0、SSE、配额、过滤、明文边界与本地验收 |
| [VOICE_STREAM_NOTES.md](../VOICE_STREAM_NOTES.md) | 房间语音密文协议、迁移、录音 / 播放边界与助手流式链路验证 |
| [SECURITY.md](../SECURITY.md) | 登录、角色、直连 IP 限流、代理与 localStorage 风险；房间正文规则结合 E2EE 说明阅读 |
| [LOGIN_MOTION_NOTES.md](../LOGIN_MOTION_NOTES.md) | 登录视觉 / 动效阶段记录，不作为业务能力规范 |
| [deploy/README.md](../deploy/README.md) | 现有 ECS 部署约定 |
| [AGENTS.md](../AGENTS.md) | 后续代理修改项目时的约束与文档同步要求 |

修改后逐项检查：

1. 目录、依赖、配置、API 路径 / 凭证或功能行为是否变化；同步对应章节及受影响的 README / 专项说明。
2. 认证、房间与 DM 是否仍区分登录 Bearer / 进房 Token、明文 / E2EE；不要把计划功能写成已实现。
3. 数据变更是否同时说明新建库与旧库迁移路径、执行次数和兼容边界；部署变更是否覆盖 `/chat/` 与 WS 转发。
4. 保留历史 NOTES 和验收范围，新增结论注明当前边界；不能把历史测试记录当成本次重新验证。
5. 复查 Markdown 链接、`git diff --check` 与变更清单，确保文档和对应代码在同一次提交或同一 PR 中交付。
