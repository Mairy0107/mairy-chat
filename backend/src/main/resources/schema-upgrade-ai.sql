-- 已有账号版数据库手动执行；仅新增助手表，可重复执行，不自动迁移远程库。
USE mairy_chat;

-- 助手为服务端明文，与房间 E2EE 无关；租约只保护同一会话的生成顺序。
CREATE TABLE IF NOT EXISTS chat_ai_thread (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '助手会话主键',
    user_id BIGINT NOT NULL COMMENT '唯一归属账号',
    title VARCHAR(80) NULL COMMENT '首条消息摘要',
    active_request_id VARCHAR(36) NULL COMMENT '当前生成租约标识',
    busy_until DATETIME NULL COMMENT '上海时间租约到期',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    KEY idx_ai_thread_user (user_id, updated_at, id),
    CONSTRAINT fk_ai_thread_user FOREIGN KEY (user_id) REFERENCES chat_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户独享助手会话';

CREATE TABLE IF NOT EXISTS chat_ai_message (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    thread_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL COMMENT 'user或assistant，系统提示不入库',
    content TEXT NOT NULL COMMENT '服务端可读明文',
    created_at DATETIME NOT NULL,
    KEY idx_ai_message_thread (thread_id, id),
    CONSTRAINT ck_ai_role CHECK (role IN ('user','assistant','system')),
    CONSTRAINT fk_ai_message_thread FOREIGN KEY (thread_id) REFERENCES chat_ai_thread(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='助手明文消息';

CREATE TABLE IF NOT EXISTS chat_ai_usage_daily (
    user_id BIGINT NOT NULL,
    usage_date DATE NOT NULL COMMENT '上海自然日',
    request_count INT NOT NULL DEFAULT 0,
    token_estimate INT NULL COMMENT 'P0暂不统计token',
    PRIMARY KEY (user_id, usage_date),
    CONSTRAINT fk_ai_usage_user FOREIGN KEY (user_id) REFERENCES chat_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户每日助手请求配额';
