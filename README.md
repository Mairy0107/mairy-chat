# Mairy Chat

Spring Boot 3.5.16 + MyBatis-Plus 3.5.18 + Vue 3 + Vite 聊天室。当前支持注册登录、ADMIN/USER、用户编号与好友请求、每用户一房、房间端到端加密、好友一对一明文私信、管理员后台及隐身只读巡查。消息使用REST保存与原生WebSocket推送，时间统一Asia/Shanghai。

## 架构文档

当前实现、目录职责、认证与加密边界、数据库升级和部署路径见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，修改项目前请先阅读。

**凡影响结构 / API / 功能行为的代码改动，必须在同一次提交（或同一 PR）里同步更新 docs/ARCHITECTURE.md。**

## 本地环境

- JDK17、Maven3.6.3+、MySQL8（utf8mb4）。
- Node20.19+或22.12+，推荐Node22/24；Windows使用 `npm.cmd` 避免PowerShell脚本执行策略问题。
- 不依赖Redis，启动不会自动初始化数据库。

```powershell
Set-Location C:\Users\mrmai\Desktop\mairy-chat
Copy-Item .env.example .env
```

编辑 `.env` 仅填写本地数据库连接；不要复制生产凭据，也不要把密码放入VITE_变量。系统环境变量优先。

| 变量 | 用途及默认值 |
| --- | --- |
| DB_HOST / DB_PORT | 本地MySQL，127.0.0.1 / 3306 |
| DB_USERNAME / DB_PASSWORD | 本地数据库用户及自行设置的密码 |
| SERVER_PORT | 后端8080，默认只监听127.0.0.1 |
| CHAT_ALLOWED_ORIGINS | localhost:5173及127.0.0.1:5173，多个来源英文逗号分隔 |
| DEV_BACKEND_URL | Vite代理目标，默认 http://127.0.0.1:8080 |

后端把 `.env` 按Java properties读取，值不加引号，字面反斜杠需写两个。数据库名为 `mairy_chat`。

## 手动初始化数据库

以有本地建库权限的用户登录MySQL，交互输入本地密码：

```powershell
mysql --host=127.0.0.1 --port=3306 --user=你的本地用户 --password --default-character-set=utf8mb4
```

**全新本地库**执行：

```sql
SOURCE C:/Users/mrmai/Desktop/mairy-chat/backend/src/main/resources/schema.sql;
SOURCE C:/Users/mrmai/Desktop/mairy-chat/backend/src/main/resources/data-local.sql;
```

**已有匿名版数据库**先备份，再执行一次：

```sql
SOURCE C:/Users/mrmai/Desktop/mairy-chat/backend/src/main/resources/schema-upgrade-auth.sql;
SOURCE C:/Users/mrmai/Desktop/mairy-chat/backend/src/main/resources/schema-upgrade-e2ee.sql;
SOURCE C:/Users/mrmai/Desktop/mairy-chat/backend/src/main/resources/schema-upgrade-dm.sql;
SOURCE C:/Users/mrmai/Desktop/mairy-chat/backend/src/main/resources/data-local.sql;
```

完整schema已包含E2EE与私信表，CREATE TABLE IF NOT EXISTS不会更新旧表；旧匿名库执行账号升级后，还需依次补齐尚未执行的 `schema-upgrade-e2ee.sql`、`schema-upgrade-dm.sql`。已有账号库只执行缺失的对应升级，含ALTER的账号/E2EE脚本不可重复执行；DM建表脚本可重复执行。完整路径见[架构文档数据库章节](docs/ARCHITECTURE.md#10-数据库与迁移脚本)。旧房间及历史消息保留，房主为空；旧匿名凭证失效，需要登录后重新进入。`spring.sql.init.mode=never`保持不变，应用启动不改表。

本地默认管理员：`admin` / `ChangeMe@123`，公开编号 `#0001`。**change-me 本地默认，仅供本地，上线前必须修改。** 种子SQL只保存BCrypt散列，重复执行不会覆盖已修改的密码。可在后台选择自己的账号重置密码，随后重新登录。

## 启动与预览

终端一：

```powershell
Set-Location C:\Users\mrmai\Desktop\mairy-chat\backend
mvn spring-boot:run
```

从backend目录启动以读取 `../.env`，默认后端 `http://127.0.0.1:8080`，没有后端首页。

终端二：

```powershell
Set-Location C:\Users\mrmai\Desktop\mairy-chat\frontend
npm.cmd install
npm.cmd run dev
```

手动打开 `http://localhost:5173`。Vite继续代理 `/api` 与 `/ws`，端口被占用时不自动换端口。构建保留原有 `/chat/` base与API/WS环境配置，不涉及部署修改。

## 使用与规则

1. ADMIN登录进入 `/admin`，查看用户、粗略设备、密码申请及IP封禁，可指定临时密码、解封和隐身进入房间；也可前往用户主页。
2. USER登录进入 `/app`，显示公开编号、自己的房间、房间大厅和好友。**管理员与普通用户都最多创建一间房**，数据库唯一约束兜底。
3. 同一浏览器指纹最多累计注册三个普通账号，第四次返回中文错误；指纹不是真实硬件标识，可以被清理或伪造。本地种子管理员不占浏览器配额。
4. 搜索 `#0001`、`0001`、用户名或昵称，发起好友请求，对方同意后可从好友列表进入 `/dm/:userId` 发私信。**私信当前为明文（enc=0），服务器和数据库可读，不受房间E2EE保护。**
5. 聊天支持Enter发送、Shift+Enter换行、中文输入法确认保护及表情插入；历史按主键游标分页、消息按ID去重，可手动重连补读。
6. 隐身模式限管理员且只读，无公开成员列表或进房广播。普通用户不能通过修改前端参数取得隐身权限；管理员即使普通进房也不能获得加密身份或解密新房间消息。
7. 找回密码只提交人工待办，不自动发邮件；管理员需核实身份并安全交付临时密码。重置会撤销账号全部旧登录。
8. 新房间消息强制端到端加密（enc=1），需HTTPS或localhost、设备密钥及房主核验授权；不降级明文。旧明文可继续读取。设备密钥需自行备份，重置登录密码不能恢复原密钥；协议与恢复边界见 [SECURITY_E2EE_NOTES.md](SECURITY_E2EE_NOTES.md)。

## 主要接口

请求与错误均为JSON，错误为 `{ "message": "中文说明" }`，对外主键为字符串。

| 方法与路径 | 说明 |
| --- | --- |
| POST /api/auth/register、/login、/forgot-password | 注册、登录、人工找回（公开） |
| GET /api/auth/me；POST /api/auth/logout | 当前账号、撤销当前登录 |
| GET /api/rooms、/api/rooms/mine、/api/rooms/{id} | 房间分页、我的房间、房间详情 |
| POST /api/rooms | 创建唯一自建房，name最多50字 |
| POST /api/rooms/{id}/members | 登录进房，接收stealth布尔值 |
| GET /api/rooms/{id}/messages | 游标历史，beforeId可选，size为1至50 |
| POST /api/rooms/{id}/messages | 保存客户端生成的enc=1密文与IV，客户端正文最多1000字符 |
| WS /ws/chat?token={进房凭证} | 验证进房凭证及绑定登录后接收推送 |
| GET /api/friends/search?q=...；GET /api/friends | 搜索用户、好友与请求列表 |
| POST /api/friends/{userId} | 发起请求 |
| POST /api/friends/requests/{id}?accept=true或false | 同意或拒绝 |
| GET/PUT /api/e2ee/identity；GET /api/e2ee/rooms/{id} | 当前账号公钥登记与本人房间密钥信封 |
| GET /api/e2ee/rooms/{id}/candidates；PUT /api/e2ee/rooms/{id}/members/{userId} | 房主查询待授权成员、提交成员专属密钥信封 |
| POST /api/dm/with/{userId}；GET /api/dm/threads | 获取唯一好友私信会话、查询本人可访问会话 |
| GET/POST /api/dm/threads/{id}/messages | 私信游标历史、最多1000字符明文发送 |
| WS /ws/dm?token={登录凭证} | 独立私信推送，只向会话双方有效登录连接发送 |
| /api/admin/** | ADMIN后台接口，详见实现说明 |

除三个公开账号接口外，所有业务API均需 `Authorization: Bearer {登录凭证}`。房间消息与房间密钥接口额外携带 `X-Session-Token: {进房凭证}`，必须匹配同一账号、登录和房间；私信接口只使用登录Bearer。房间WS使用仅存页面内存的进房凭证，私信WS使用持久化于localStorage的登录凭证；不打印包含凭证的连接地址。E2EE的PUT接口应经同源代理访问，当前跨源CORS方法限制见[架构文档](docs/ARCHITECTURE.md#3-运行与配置)。

## 验证与范围

```powershell
# backend目录
mvn test
mvn -DskipTests package
# frontend目录
npm.cmd run build
```

账号集成测试只连接H2内存库，覆盖并发设备配额、编号、登录、角色隔离、单房规则、好友处理、隐身只读、握手、密码重置撤销、IP封禁解封及CORS；原有UTC默认环境的上海时区回归保留。真实MySQL脚本和浏览器交互仍需按上述步骤手动联调。

当前架构见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)；分阶段实现与验收记录见 [AUTH_IMPLEMENTATION_NOTES.md](AUTH_IMPLEMENTATION_NOTES.md)、[SECURITY_E2EE_NOTES.md](SECURITY_E2EE_NOTES.md)、[FEATURE_DM_NOTES.md](FEATURE_DM_NOTES.md)，安全策略和已知边界见 [SECURITY.md](SECURITY.md)。现有NOTES中的范围与测试结果属于对应交付阶段；当前按单实例设计。
