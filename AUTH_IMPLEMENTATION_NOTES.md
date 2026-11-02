# 账号功能实现说明

## 本版范围

沿用原有Controller / ServiceImpl / Mapper分层、REST消息保存、WebSocket推送和游标历史分页。所有ServiceImpl使用 `com.baomidou.mybatisplus.spring.service.impl.ServiceImpl`，时间继续通过ChatTime及Jackson的Asia/Shanghai配置处理。没有修改公共组件库，没有访问浏览器、提交、推送或执行部署及SSH。

- 注册、BCrypt登录、8小时可撤销Bearer会话、退出、登录失效跳转；ADMIN默认进入 `/admin`，USER进入 `/app`。
- 整数递增公开编号，展示为 `#0001`，超过四位自然扩展；注册事务锁定编号行，失败回滚不占用编号。
- 同一浏览器指纹累计最多注册3个普通账号，后端记录UA及粗略设备标签；第四次拒绝。失败注册不消耗设备配额。
- 每个账号最多一间房，**管理员同样限一间**，但任何已登录账号均可进入现有房间；管理员可通过后台选择隐身只读进入。
- 隐身成员保留 `stealth=true` 供服务端控制；没有公开成员列表或进房广播。隐身会话不能发消息，避免暴露身份。未来新增成员/presence接口必须继续过滤隐身成员。
- 按公开编号（带或不带#）、用户名或昵称搜索；发起好友请求、接收方同意/拒绝、好友与待办列表。
- 人工找回申请、后台用户分页、设备标签、指定临时密码并处理全部该账号待办、撤销该账号旧登录与实时连接。
- IP限流、持久化临时封禁、后台查看与解封、CORS白名单与Bearer权限校验。
- 中文登录、注册、找回页面；聊天支持Enter发送、Shift+Enter换行、输入法确认保护、光标位置插入Unicode表情。

## 默认管理员与数据库

本地管理员：用户名 `admin`，编号 `#0001`，密码 `ChangeMe@123`。**change-me 本地默认，仅用于本地，上线前必须改**。种子SQL只保存BCrypt散列；重复执行不会覆盖已修改的密码。后台选择自己的账号重置密码可修改本地默认密码，修改后需要重新登录。

空本地库先执行 `backend/src/main/resources/schema.sql`，再执行 `data-local.sql`。已有旧匿名版数据库则执行一次 `schema-upgrade-auth.sql`，再执行 `data-local.sql`；不要用完整schema代替升级脚本，CREATE TABLE IF NOT EXISTS不会修改已有表。

升级脚本保留旧房间、成员和消息，旧房间房主为空，不自动归属账号；旧匿名会话过期，登录用户重新进入后可读历史。请先备份本地数据。升级中的ALTER不可重复执行，脚本不是跨版本迁移框架。

编号1由种子管理员预留。种子管理员不来自浏览器注册，**不占指纹配额**；同一localStorage可以注册三个普通账号，第四个普通账号被拒绝。任务验收中的管理员加两个普通用户还没有达到浏览器配额，应再注册第三个普通用户后验证第四个被拒绝。

数据库初始化始终手动，`spring.sql.init.mode=never` 保持不变。SQL中的本地种子时间使用UTC时间显式加8小时，其余业务写入使用ChatTime。

## 本地运行

环境：JDK17、Maven、MySQL8、Node20.19+或22.12+。将根目录 `.env.example` 复制为 `.env`，仅填写本地MySQL配置，不改 `.env.production`。

```powershell
# 在本地MySQL客户端中SOURCE（登录时交互输入本地密码）
mysql --host=127.0.0.1 --port=3306 --user=你的本地用户 --password --default-character-set=utf8mb4
```

```sql
SOURCE C:/Users/mrmai/Desktop/mairy-chat/backend/src/main/resources/schema.sql;
SOURCE C:/Users/mrmai/Desktop/mairy-chat/backend/src/main/resources/data-local.sql;
```

```powershell
# 终端一，从backend启动以加载../.env
Set-Location C:\Users\mrmai\Desktop\mairy-chat\backend
mvn spring-boot:run
# 终端二
Set-Location C:\Users\mrmai\Desktop\mairy-chat\frontend
npm.cmd install
npm.cmd run dev
```

手动打开 `http://localhost:5173`。开发路由为 `/login`、`/register`、`/forgot-password`、`/app`、`/admin`、`/room/:id`。生产构建继续使用既有 `/chat/` base及现有API/WS环境配置；本次没有更改任何部署配置。

## 关键文件与接口

| 位置 | 内容 |
| --- | --- |
| `backend/.../config/SecurityConfig.java` | 无状态安全链、BCrypt、角色权限、CORS |
| `backend/.../security/BearerAuthFilter.java` | API登录鉴权及IP检查 |
| `backend/.../service/AuthService.java` | 注册、编号/设备事务、登录撤销、人工找回 |
| `backend/.../service/FriendService.java` | 搜索及双向好友请求 |
| `backend/.../service/AdminService.java` | 批量资料、人工重置与撤销 |
| `backend/.../service/IpSecurityService.java` | 固定窗口限流及落库封禁 |
| `backend/.../service/RoomService.java`、`MemberService.java` | 一人一房、绑定登录、隐身权限 |
| `backend/.../websocket/` | 登录绑定握手、消息推送与撤销连接 |
| `backend/src/main/resources/schema*.sql`、`data-local.sql` | 完整建表、旧库升级、本地种子 |
| `frontend/src/router.js`、`auth.js`、`api/chat.js` | 路由守卫、浏览器指纹、请求凭证 |
| `frontend/src/views/` | 账号页、用户主页、聊天室及后台 |
| `backend/src/test/java/com/mairy/chat/` | 账号集成与UTC环境时区回归 |

POST `/api/auth/register`、`/login`、`/forgot-password`为公开接口；GET `/api/auth/me`、POST `/api/auth/logout`需Bearer。

GET `/api/rooms/mine`返回自己的房间（没有时为空响应）；POST `/api/rooms/{id}/members`接收 `{ "stealth": false }`。历史及发送消息仍需X-Session-Token，并额外要求Bearer。WS使用进房响应token，其背后登录必须仍有效。

GET `/api/friends/search?q=...`、GET `/api/friends`，POST `/api/friends/{userId}`发起，POST `/api/friends/requests/{id}?accept=true|false`回应。

后台包括GET `/api/admin/users?page=1`、`/users/{id}/devices`、`/reset-requests`、`/ip-blocks`；POST `/api/admin/users/{id}/reset-password`接收 `{ "password": "管理员指定临时密码" }`；POST `/api/admin/ip-blocks/unblock?ip=...`解封。

好友列表及密码申请暂限最近200条，搜索最多30条，封禁最多100条。所有列表转换均批量补全资料，不在循环中查询SQL。

## 刻意延后与review待决定项

- 浏览器指纹可被清理或伪造，无法严格限制真实硬件三账号；暂不引入侵入式硬件采集。
- 好友拒绝后保留唯一关系，第一版不支持再次申请、删除好友或私聊；好友与重置申请未做完整游标分页。
- 暂无在线成员、presence、管理员角色编辑、停用按钮、用户自行改密码及临时密码首次登录强制修改。
- Bearer持久化在localStorage，需考虑后续HttpOnly与CSRF配套；当前内存限流与撤销通知适用于单实例。
- 代理后真实IP策略、连接总量控制、验证码、MFA、过期记录清理及审计导出延后，详见SECURITY.md。
- 不访问浏览器，因此视觉、真实浏览器多标签及中文输入法交互需手动验收；H2测试不替代真实MySQL升级与网络WebSocket联调。

这些是交付时保留的范围与限制，没有自动扩大产品需求。是否补做由维护者决定。

## 本次验证与review记录

2026-11-03 本地执行结果：

- `backend/`：`mvn -DskipTests package`，BUILD SUCCESS。
- `frontend/`：`npm.cmd run build`，成功，35个模块完成构建。
- `backend/`：`mvn -B test`，10项通过（4项账号集成场景、6项时区回归），0失败、0错误。
- `git diff --check`通过；部署目录、根目录及前端生产环境配置未修改。

review已检查鉴权入口、敏感响应、事务与唯一约束、批量资料查询、Vue文本展示和会话撤销。修正了请求路径映射导致过滤器跳过校验的兼容问题、握手后撤销的连接登记竞态，以及MySQL可重复读下找回申请使用旧快照的风险；找回首次读取即锁定账号，并增加并发申请回归。未在业务循环内调用SQL。

集成测试使用H2的MySQL兼容模式，从正式schema转换掉建库语句和存储选项，并验证本地种子BCrypt散列。没有连接真实MySQL、执行本地已有库升级、启动浏览器或做网络WebSocket联调。MySQL字符集/DDL差异、UI视觉和真实输入法交互仍需维护者手动确认。

Maven仍提示原时区测试中Mockito泛型的unchecked编译警告；不影响测试或打包。npm安装提示esbuild安装脚本尚未授权，当前构建成功，未修改全局npm配置或额外授权脚本。
