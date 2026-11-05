-- 已有私信库先备份，再手动执行一次；保留历史文字、enc=0与iv=NULL约束。
USE mairy_chat;
ALTER TABLE chat_dm_message
    MODIFY COLUMN content MEDIUMTEXT NOT NULL COMMENT '明文文字或音频Base64，服务器可读',
    ADD COLUMN kind VARCHAR(8) NOT NULL DEFAULT 'text' COMMENT 'text文字，voice明文语音' AFTER content,
    ADD COLUMN mime VARCHAR(32) NULL COMMENT '语音格式白名单' AFTER kind,
    ADD COLUMN duration INT NULL COMMENT '语音时长，1至60000毫秒' AFTER mime;
