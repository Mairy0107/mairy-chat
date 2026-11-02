-- 已有 mairy_chat 数据库手动执行一次；先备份，切勿连接错误环境。
USE mairy_chat;
-- 保留旧内容并标记 enc=0；部署新版后所有新消息必须 enc=1。
ALTER TABLE chat_message
    MODIFY content VARCHAR(5400) NOT NULL COMMENT '旧明文或Base64 AES-GCM密文',
    ADD COLUMN enc TINYINT NOT NULL DEFAULT 0 COMMENT '0旧明文，1端到端密文',
    ADD COLUMN iv VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Base64随机96位IV',
    ADD UNIQUE KEY uk_message_room_iv (room_id, iv);

-- 私钥只在用户设备，此处只有公开密钥及其 SHA-256 指纹。
CREATE TABLE IF NOT EXISTS chat_user_pubkey (
    user_id BIGINT NOT NULL PRIMARY KEY,
    public_key VARCHAR(1024) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT fk_pubkey_user FOREIGN KEY (user_id) REFERENCES chat_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可变设备公钥，无私钥托管';

-- 房主客户端只为普通成员生成 RSA-OAEP 信封，没有管理员或服务器信封。
CREATE TABLE IF NOT EXISTS chat_room_key_wrap (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    room_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    wrapped_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_wrap_room_user (room_id, user_id),
    CONSTRAINT fk_wrap_room FOREIGN KEY (room_id) REFERENCES chat_room(id),
    CONSTRAINT fk_wrap_user FOREIGN KEY (user_id) REFERENCES chat_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='成员专属密钥信封，无管理员托管';
