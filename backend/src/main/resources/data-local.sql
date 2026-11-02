-- 仅限本地手动导入；change-me 本地默认，上线前必须修改。
-- 编号#0001不占浏览器注册配额；重复导入不会覆盖已修改的密码。
USE mairy_chat;
INSERT INTO chat_user(public_no, username, nickname, password_hash, role, disabled, created_at)
SELECT 1, 'admin', '管理员', '$2a$10$vZSe4ShXTtyAsGZ0T7i8d.78EB.X0gl/ogEsiQkMDVK61hatsv4FO', 'ADMIN', FALSE, CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+08:00')
WHERE NOT EXISTS (SELECT 1 FROM chat_user WHERE username = 'admin' OR public_no = 1);