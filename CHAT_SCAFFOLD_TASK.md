# 聊天室项目骨架搭建任务（仅本地脚手架）

请在当前目录（`C:\Users\mrmai\Desktop\mairy-chat`）从零搭建可编译的 monorepo 骨架。不要部署到 ECS，不要 git push，不要把真实数据库密码写入仓库。

## 技术栈

- `backend/`：Spring Boot 3 + MyBatis-Plus + WebSocket（或 STOMP），标准分层 controller / service / mapper / entity
- `frontend/`：Vue3 + Vite，中文 UI 骨架
- MySQL 库名：`mairy_chat`（已在云服务器 Docker MySQL 中创建，utf8mb4）。本仓库只用占位 datasource / `.env.example`，禁止提交真实密码
- README 用中文说明本地如何启动前后端

## 第一版产品范围（骨架可跑通即可，功能可先 stub）

- 多房间（房间列表 / 创建）
- 昵称进房（会话级，不做完整账号体系）
- 实时消息（WebSocket 推送 + 历史消息分页接口 stub）

## 必须产出

1. `backend/`：可识别的 Maven Spring Boot 3 工程（`pom.xml`），包含：
   - 应用入口、基础包结构（controller/service/mapper/entity/config）
   - WebSocket（或 STOMP）配置骨架
   - Room / Member(或 JoinSession) / Message 相关 entity + mapper + service + controller 骨架
   - `application.yml`（或 `application-dev.yml`）里 datasource 指向库名 `mairy_chat`，用户名/密码用占位符或环境变量；另附 `.env.example`（放仓库根或 backend）
   - 可选：`schema.sql` / Flyway/Liquibase 初始化脚本，表注释用中文
2. `frontend/`：Vue3 + Vite 工程（`package.json`），页面 stub：
   - 房间列表页
   - 输入昵称进房
   - 聊天页（消息列表 + 发送框，可先 mock / 接 WS stub）
3. 根目录中文 `README.md`：如何安装依赖、配置 `.env`、启动 backend / frontend
4. 根目录 `.gitignore`：忽略 `node_modules`、`target`、`.env`、IDE 文件等
5. 尽量让 `mvn -q -DskipTests package`（在 backend）和 `npm install && npm run build`（在 frontend）能通过；若本机缺 JDK/Node 则至少保证工程文件完整并在 README 说明

## 约束

- 不要部署服务器、不要改云端、不要 push
- 不要写入或打印真实 DB 密码
- 代码与注释可用中文说明关键业务字段
- 完成后列出主要目录与文件，并说明是否尝试过编译/构建及结果

请直接在本目录创建文件并实现上述骨架。
