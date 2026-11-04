-- 已有房间E2EE库升级；先备份，仅执行一次，不修改DM或助手表。
USE mairy_chat;
ALTER TABLE chat_message
    MODIFY COLUMN content MEDIUMTEXT NOT NULL COMMENT '旧文字或Base64 AES-GCM文字及语音密文',
    ADD COLUMN kind VARCHAR(8) NOT NULL DEFAULT 'text' COMMENT 'text文字，voice加密语音' AFTER content;
