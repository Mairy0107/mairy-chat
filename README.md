# Mairy Chat

Spring Boot 3 + MyBatis-Plus + Vue 3 + Vite 聊天室 monorepo，仅用于本地开发。使用 MySQL 数据库 `mairy_chat`，不包含部署脚本或真实数据库凭据。

## 当前功能

- 房间列表分页、创建房间。
- 输入昵称加入房间，不需要注册账号；会话凭证只保存在页面内存，刷新或返回大厅后需重新加入。
- 消息通过 REST 保存到 MySQL，通过原生 WebSocket 向同房间连接推送。
- 消息历史按主键游标分页，每次默认 30 条；前端合并推送和发送结果时按消息 ID 去重。
- 断线后可以手动重连，重连会刷新最近一页，之后可继续加载更早消息。
- 匿名凭证有效期为 24 小时，读写历史和建立实时连接时校验所属房间；推送时也检查有效期。

## 目录

```text
mairy-chat/
├─ backend/
│  ├─ pom.xml
│  └─ src/main/
│     ├─ java/com/mairy/chat/
│     │  ├─ ChatApplication.java
│     │  ├─ config/        # 分页、跨域、WebSocket 注册
│     │  ├─ controller/    # 房间、进房、消息接口及异常处理
│     │  ├─ dto/           # 请求校验与响应模型
│     │  ├─ entity/        # Room / Member / Message
│     │  ├─ mapper/        # MyBatis-Plus BaseMapper
│     │  ├─ service/       # 房间、匿名会话、消息业务
│     │  └─ websocket/     # 握手鉴权与房间推送
│     └─ resources/
│        ├─ application.yml
│        └─ schema.sql
├─ frontend/
│  ├─ package.json
│  ├─ index.html
│  ├─ vite.config.js
│  └─ src/
│     ├─ api/chat.js
│     ├─ views/RoomListView.vue
│     ├─ views/ChatRoomView.vue
│     ├─ App.vue
│     ├─ main.js
│     └─ style.css
├─ .env.example
├─ .gitignore
└─ README.md
```

## 环境要求

- JDK 17、Maven 3.6.3 或更高版本。
- Node.js 20.19+（20.x）或 22.12+，以及 npm；推荐使用 Node.js 22/24 LTS。
- 本地 MySQL 8，数据库字符集使用 utf8mb4。

后端使用 Spring Boot 3.5.16、MyBatis-Plus 3.5.18；前端使用 Vue 3、Vite 7。不依赖 Redis，也不需要云服务器连接。

## 本地配置与数据库初始化

以下命令均以 Windows PowerShell 为例。

```powershell
Set-Location C:\Users\mrmai\Desktop\mairy-chat
Copy-Item .env.example .env
```

编辑根目录 `.env`，填写**本地** MySQL 主机、端口及已有且具备 `mairy_chat` 读写权限的用户名和密码。初始化脚本不会创建数据库用户。

| 变量 | 用途 / 默认值 |
| --- | --- |
| `DB_HOST` | 本地 MySQL 地址，默认 `127.0.0.1` |
| `DB_PORT` | MySQL 端口，默认 `3306` |
| `DB_USERNAME` | 数据库用户，示例为 `mairy_chat_user` |
| `DB_PASSWORD` | 自行填写本地密码，仓库中没有真实值 |
| `SERVER_PORT` | 后端端口，默认 `8080` |
| `CHAT_ALLOWED_ORIGINS` | 允许的前端来源，以英文逗号分隔，默认允许本机 `5173` |
| `DEV_BACKEND_URL` | Vite 代理目标，默认 `http://127.0.0.1:8080` |

数据库名固定为 `mairy_chat`。如修改后端端口，同步修改 `DEV_BACKEND_URL`。系统环境变量优先于 `.env`。

后端将 `.env` 按 Java properties 格式读取：不要在值外添加引号；值中如需字面量反斜杠，使用两个反斜杠。前端由 Vite 读取根目录 `.env`，仅将 `DEV_BACKEND_URL` 用作本地代理目标。不要将密码放到 `VITE_` 开头的变量中。

使用有建库建表权限的**本地**账号登录 MySQL，密码在提示时输入：

```powershell
mysql --host=127.0.0.1 --port=3306 --user=你的本地初始化用户 --password --default-character-set=utf8mb4
```

在 MySQL 命令行中执行：

```sql
SOURCE C:/Users/mrmai/Desktop/mairy-chat/backend/src/main/resources/schema.sql;
```

脚本创建 `mairy_chat` 和 `chat_room`、`chat_member`、`chat_message` 三张表，不插入默认账号。后端配置 `spring.sql.init.mode=never`，启动时不会自动执行 SQL。脚本适用于空库初始化，后续表结构变更需要单独管理。

## 启动后端

打开一个 PowerShell 终端：

```powershell
Set-Location C:\Users\mrmai\Desktop\mairy-chat\backend
mvn spring-boot:run
```

请从 `backend/` 启动，以便读取 `../.env`。默认监听 `127.0.0.1:8080`，后端没有单独的首页；业务接口入口是 `/api/rooms`。数据库配置错误时接口返回中文提示，应检查本地连接、权限和初始化脚本。

## 启动前端

另开一个 PowerShell 终端：

```powershell
Set-Location C:\Users\mrmai\Desktop\mairy-chat\frontend
npm.cmd install
npm.cmd run dev
```

然后手动打开 `http://localhost:5173`。使用 `npm.cmd` 可避开 Windows 上 `npm.ps1` 的执行策略限制。Vite 会代理 `/api` 和 `/ws` 到后端，端口被占用时直接报错，不会自动切换端口。

## 接口约定

所有请求体均为 JSON。错误响应为 `{ "message": "中文错误说明" }`。对外 ID 均为字符串，避免 JavaScript 长整数精度丢失。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/rooms?page=1&size=20` | 房间分页，`size` 为 1～100 |
| POST | `/api/rooms` | 创建房间，`{"name":"闲聊"}`，名称最多 50 字 |
| POST | `/api/rooms/{roomId}/members` | 昵称进房，`{"nickname":"小明"}`，昵称最多 20 字 |
| GET | `/api/rooms/{roomId}/messages?size=30&beforeId=123` | 历史消息，需 `X-Session-Token`；首次省略 `beforeId`，`size` 为 1～50 |
| POST | `/api/rooms/{roomId}/messages` | 发送消息，需 `X-Session-Token`，`{"content":"你好"}`，最多 1000 字 |
| WS | `/ws/chat?token={进房返回的token}` | 按凭证所属房间接收推送，客户端不通过此连接发送消息 |

房间列表返回 `{records,total,page,size}`。历史返回 `{records,nextBeforeId,hasMore}`，`records` 从旧到新排列；查询更早历史时将 `nextBeforeId` 作为下一次的 `beforeId`。

实时事件为 `{ "type": "message", "data": { ...消息字段 } }`。浏览器原生 WebSocket 无法自定义请求头，因此握手凭证放在查询参数中；本项目不打印该地址，后续若配置访问日志应隐藏此参数。

## 手动验收

1. 初始化本地数据库，配置 `.env`，启动后端和前端。
2. 创建房间，在两个页面分别用不同昵称加入同一个房间，检查消息互相实时出现，发送者消息不重复。
3. 加入另一个房间，确认不会收到前一个房间的推送；使用前一个房间的凭证请求新房间历史或发送消息应返回 403。
4. 累积超过 30 条消息后检查“加载更早消息”；刷新页面并重新进房，确认历史仍然存在。
5. 断开并恢复后端后点击“重新连接”，确认同步最近消息；返回大厅后再次进入需要重新输入昵称。

## 验证记录与后续范围

2026-11-02 本地验证通过：在 `backend/` 运行 `mvn -q -DskipTests package`；在 `frontend/` 运行 `npm.cmd install`，成功后运行 `npm.cmd run build`，三个命令均退出成功。后端命令跳过测试，构建通过不能代替数据库和 WebSocket 联调。

后端曾因三个 Service 引用旧的 `com.baomidou.mybatisplus.extension.service.impl.ServiceImpl` 而编译失败；已按本机 MyBatis-Plus 3.5.18 JAR 中的实际路径改为 `com.baomidou.mybatisplus.spring.service.impl.ServiceImpl`，保留现有 Service 和 Mapper 分层。

前端本次安装和生产构建通过，已生成 `frontend/package-lock.json` 固定依赖版本。此前安装日志中的 `EBADSIZE`（下载流大小不符）和清理目录时的 `EPERM` 本次未复现，具体原因尚未确认。安装仍提示 esbuild 的安装脚本待授权，但当前生产构建成功，未调整全局 npm 配置。

未启动服务、访问浏览器、连接数据库、部署或 push；房间、消息及 WebSocket 的运行行为仍需按上面的手动验收步骤验证。

当前为单后端实例的本地骨架：连接保存在进程内存中；没有账号体系、限流、消息撤回、成员列表、会话撤销或过期数据清理。离开页面只关闭连接和丢弃前端凭证，数据库中的会话会在 24 小时后过期。消息已提交但推送失败时，可通过历史接口补读；发送请求超时的结果可能未知，重新发送可能产生重复消息，尚未实现幂等键。
