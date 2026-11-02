package com.mairy.chat.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 对外响应统一将主键转成字符串，避免前端长整数精度丢失。 */
public final class ChatViews {
    /** 禁止实例化响应容器。 */
    private ChatViews() {
    }

    /** 房间列表与创建结果。 */
    public record RoomView(String id, String name, LocalDateTime createdAt) {
    }

    /** 会话凭证仅在进房时返回，前端保存在页面内存中。 */
    public record MemberView(String id, String roomId, String nickname, String token,
                             LocalDateTime expiresAt) {
    }

    /** 历史与实时消息共用的结构。 */
    public record MessageView(String id, String roomId, String memberId, String nickname,
                              String content, LocalDateTime createdAt) {
    }

    /** 房间按页码查询。 */
    public record PageView<T>(List<T> records, long total, long page, long size) {
    }

    /** 消息按主键游标翻页，避免实时插入造成页码漂移。 */
    public record MessagePage(List<MessageView> records, String nextBeforeId, boolean hasMore) {
    }
}
