-- 在已有本地 MySQL 8 手动执行；CREATE IF NOT EXISTS 可重复运行。
USE mairy_chat;

-- 私信 v1 独立建表；明文内容可被服务器读取，不修改房间端到端加密。
CREATE TABLE IF NOT EXISTS chat_dm_thread (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '私信会话主键',
    user_low BIGINT NOT NULL COMMENT '用户对较小编号',
    user_high BIGINT NOT NULL COMMENT '用户对较大编号',
    created_at DATETIME NOT NULL COMMENT '上海时间创建时间',
    UNIQUE KEY uk_dm_pair (user_low, user_high),
    KEY idx_dm_high (user_high),
    CONSTRAINT ck_dm_pair CHECK (user_low < user_high),
    CONSTRAINT fk_dm_low FOREIGN KEY (user_low) REFERENCES chat_user(id),
    CONSTRAINT fk_dm_high FOREIGN KEY (user_high) REFERENCES chat_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='好友一对一私信会话';

CREATE TABLE IF NOT EXISTS chat_dm_message (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '私信消息主键',
    thread_id BIGINT NOT NULL COMMENT '所属私信会话',
    sender_id BIGINT NOT NULL COMMENT '登录发送账号',
    content VARCHAR(1000) NOT NULL COMMENT 'v1 明文内容，服务器可读',
    enc TINYINT NOT NULL DEFAULT 0 COMMENT 'v1 固定0明文',
    iv VARCHAR(16) NULL COMMENT 'v1 不使用IV',
    created_at DATETIME NOT NULL COMMENT '上海时间发送时间',
    KEY idx_dm_message_thread_id (thread_id, id),
    CONSTRAINT ck_dm_plain CHECK (enc=0 AND iv IS NULL),
    CONSTRAINT fk_dm_message_thread FOREIGN KEY (thread_id) REFERENCES chat_dm_thread(id),
    CONSTRAINT fk_dm_message_sender FOREIGN KEY (sender_id) REFERENCES chat_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='好友私信明文消息';
