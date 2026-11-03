package com.mairy.chat.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 私信响应只包含当前会话与公开资料，主键使用字符串。 */
public final class DmViews {
    /** 禁止实例化响应容器。 */
    private DmViews() { }

    public record ThreadView(String id, AuthViews.UserView peer, LocalDateTime createdAt) { }
    public record MessageView(String id, String threadId, String senderId, String content,
                              int enc, String iv, LocalDateTime createdAt) { }
    public record MessagePage(List<MessageView> records, String nextBeforeId, boolean hasMore) { }
}
