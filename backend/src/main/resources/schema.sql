-- 仅供手动在本地 MySQL 8 执行；不连接或修改云端数据库。
CREATE DATABASE IF NOT EXISTS mairy_chat CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE mairy_chat;

CREATE TABLE IF NOT EXISTS chat_room (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '房间主键',
    name VARCHAR(50) NOT NULL COMMENT '房间名称',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天房间';

CREATE TABLE IF NOT EXISTS chat_member (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '会话主键',
    room_id BIGINT NOT NULL COMMENT '所属房间',
    nickname VARCHAR(20) NOT NULL COMMENT '会话昵称',
    token VARCHAR(36) NOT NULL COMMENT '匿名会话凭证',
    joined_at DATETIME NOT NULL COMMENT '进房时间',
    expires_at DATETIME NOT NULL COMMENT '会话过期时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_member_token (token),
    KEY idx_member_room (room_id),
    CONSTRAINT fk_member_room FOREIGN KEY (room_id) REFERENCES chat_room (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='昵称进房会话';

CREATE TABLE IF NOT EXISTS chat_message (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '消息主键',
    room_id BIGINT NOT NULL COMMENT '所属房间',
    member_id BIGINT NOT NULL COMMENT '发送会话',
    nickname VARCHAR(20) NOT NULL COMMENT '发送时昵称快照',
    content VARCHAR(1000) NOT NULL COMMENT '纯文本消息',
    created_at DATETIME NOT NULL COMMENT '发送时间',
    PRIMARY KEY (id),
    KEY idx_message_room_id (room_id, id),
    CONSTRAINT fk_message_room FOREIGN KEY (room_id) REFERENCES chat_room (id),
    CONSTRAINT fk_message_member FOREIGN KEY (member_id) REFERENCES chat_member (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='房间消息';
