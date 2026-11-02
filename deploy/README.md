# 部署说明（ECS）

目录建议：`/opt/mairy-chat`（与 `/opt/website` 并列）。

1. 将 `deploy/app.jar`（由 `backend` 打包）与本目录 `Dockerfile`、`docker-compose.yml` 放到服务器。
2. 前端 `npm run build` 产物放到 `/opt/mairy-chat/frontend/dist`，并挂载进 `site-web` 的 `/usr/share/nginx/html/chat`。
3. `.env` 使用服务器 MySQL 账号，库名为 `mairy_chat`；不要提交真实密码。
4. `CHAT_ALLOWED_ORIGINS` 需包含 `http://47.96.247.246`（及域名，若启用）。
5. 将 `nginx-chat.conf.snippet` 合并进 `/opt/website/nginx.conf` 后 reload `site-web`。