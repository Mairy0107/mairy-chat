package com.mairy.chat.security;

/** 提交撤销后通知实时连接关闭；二者只需指定一种。 */
public record SessionRevoked(Long userId, String sessionId) { }
