package com.mairy.chat.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 助手公开响应不包含归属账号、租约或上游凭证。 */
public final class AiViews {
    /** 禁止实例化响应容器。 */
    private AiViews() { }

    public record Status(boolean enabled, int quotaRemaining, int quotaLimit, boolean profileCompleted) { }
    public record ThreadView(String id, String title, LocalDateTime createdAt, LocalDateTime updatedAt) { }
    public record MessageView(String id, String threadId, String role, String content, LocalDateTime createdAt) { }
    public record MessagePage(List<MessageView> records, String nextBeforeId, boolean hasMore) { }
    public record Done(MessageView message, int quotaRemaining, String outcome) { }
}
