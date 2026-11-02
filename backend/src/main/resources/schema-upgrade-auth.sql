-- 仅在已有匿名版数据库手动执行一次；先备份本地数据，不可重复执行ALTER。
USE mairy_chat;
-- 账号相关结构；所有时间均按 Asia/Shanghai 写入。
CREATE TABLE IF NOT EXISTS chat_user (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '账号主键',
    public_no INT NOT NULL COMMENT '递增公开编号',
    username VARCHAR(32) NOT NULL COMMENT '登录用户名',
    nickname VARCHAR(20) NOT NULL COMMENT '展示昵称',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt密码散列',
    role VARCHAR(10) NOT NULL DEFAULT 'USER' COMMENT 'ADMIN或USER',
    disabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否停用',
    created_at DATETIME NOT NULL COMMENT '注册时间',
    PRIMARY KEY (id), UNIQUE KEY uk_user_number (public_no), UNIQUE KEY uk_user_name (username),
    CONSTRAINT ck_user_role CHECK (role IN ('ADMIN','USER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='注册账号';

CREATE TABLE IF NOT EXISTS chat_public_sequence (
    id INT PRIMARY KEY COMMENT '固定序列主键', last_no INT NOT NULL COMMENT '最近分配编号'
) ENGINE=InnoDB COMMENT='公开编号序列';
INSERT IGNORE INTO chat_public_sequence (id, last_no) VALUES (1, 1);

CREATE TABLE IF NOT EXISTS chat_device_quota (
    fingerprint_hash CHAR(64) PRIMARY KEY COMMENT '浏览器指纹散列',
    account_count INT NOT NULL DEFAULT 0 COMMENT '累计注册账号数',
    CONSTRAINT ck_device_quota CHECK (account_count BETWEEN 0 AND 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='浏览器注册配额锁';

CREATE TABLE IF NOT EXISTS chat_device_registration (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '设备记录主键',
    fingerprint_hash CHAR(64) NOT NULL COMMENT '浏览器指纹散列',
    user_id BIGINT NOT NULL COMMENT '注册账号',
    user_agent VARCHAR(512) NOT NULL COMMENT '客户端声明的UA',
    device_label VARCHAR(80) NOT NULL COMMENT '粗略设备与浏览器',
    created_at DATETIME NOT NULL COMMENT '注册时间',
    UNIQUE KEY uk_device_user (user_id), KEY idx_device_hash (fingerprint_hash),
    CONSTRAINT fk_device_user FOREIGN KEY (user_id) REFERENCES chat_user(id),
    CONSTRAINT fk_device_quota FOREIGN KEY (fingerprint_hash) REFERENCES chat_device_quota(fingerprint_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='注册设备记录';

CREATE TABLE IF NOT EXISTS chat_login_session (
    id CHAR(64) PRIMARY KEY COMMENT 'Bearer凭证SHA256散列',
    user_id BIGINT NOT NULL COMMENT '登录账号', expires_at DATETIME NOT NULL COMMENT '上海时间有效期',
    KEY idx_login_user (user_id), KEY idx_login_expiry (expires_at),
    CONSTRAINT fk_login_user FOREIGN KEY (user_id) REFERENCES chat_user(id)
) ENGINE=InnoDB COMMENT='可撤销登录会话';

CREATE TABLE IF NOT EXISTS chat_friend_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '好友请求主键',
    sender_id BIGINT NOT NULL COMMENT '申请人', receiver_id BIGINT NOT NULL COMMENT '接收人',
    status VARCHAR(12) NOT NULL COMMENT 'PENDING或ACCEPTED或REJECTED',
    created_at DATETIME NOT NULL COMMENT '申请时间',
    pair_low BIGINT GENERATED ALWAYS AS (LEAST(sender_id, receiver_id)) STORED COMMENT '用户对较小编号',
    pair_high BIGINT GENERATED ALWAYS AS (GREATEST(sender_id, receiver_id)) STORED COMMENT '用户对较大编号',
    UNIQUE KEY uk_friend_pair (pair_low, pair_high), KEY idx_friend_receiver (receiver_id),
    CONSTRAINT ck_friend_self CHECK (sender_id <> receiver_id),
    CONSTRAINT ck_friend_status CHECK (status IN ('PENDING','ACCEPTED','REJECTED')),
    CONSTRAINT fk_friend_sender FOREIGN KEY (sender_id) REFERENCES chat_user(id),
    CONSTRAINT fk_friend_receiver FOREIGN KEY (receiver_id) REFERENCES chat_user(id)
) ENGINE=InnoDB COMMENT='好友申请与已接受关系';

CREATE TABLE IF NOT EXISTS chat_password_reset_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '找回申请主键',
    user_id BIGINT NOT NULL COMMENT '申请账号', status VARCHAR(12) NOT NULL COMMENT 'PENDING或HANDLED',
    note VARCHAR(200) NOT NULL DEFAULT '' COMMENT '人工核实备注', handled_by BIGINT NULL COMMENT '处理管理员',
    created_at DATETIME NOT NULL COMMENT '申请时间', handled_at DATETIME NULL COMMENT '处理时间',
    KEY idx_reset_user_status (user_id, status),
    CONSTRAINT fk_reset_user FOREIGN KEY (user_id) REFERENCES chat_user(id),
    CONSTRAINT fk_reset_admin FOREIGN KEY (handled_by) REFERENCES chat_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='人工密码重置申请';

CREATE TABLE IF NOT EXISTS chat_ip_block (
    ip VARCHAR(45) PRIMARY KEY COMMENT '直连客户端IP', reason VARCHAR(120) NOT NULL COMMENT '封禁原因',
    until_at DATETIME NOT NULL COMMENT '上海时间封禁截止', KEY idx_ip_expiry (until_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='临时IP封禁';

ALTER TABLE chat_room
    ADD COLUMN owner_user_id BIGINT NULL COMMENT '房主账号，空值兼容旧房间',
    ADD UNIQUE KEY uk_room_owner(owner_user_id),
    ADD CONSTRAINT fk_room_owner FOREIGN KEY(owner_user_id) REFERENCES chat_user(id);
ALTER TABLE chat_member
    ADD COLUMN user_id BIGINT NULL COMMENT '登录账号',
    ADD COLUMN login_session_id CHAR(64) NULL COMMENT '绑定登录会话散列',
    ADD COLUMN stealth BOOLEAN NOT NULL DEFAULT FALSE COMMENT '管理员隐身只读';
-- 旧匿名凭证停止使用；保留成员及消息用于历史展示。
UPDATE chat_member SET expires_at = '2000-01-01 00:00:00' WHERE user_id IS NULL;
