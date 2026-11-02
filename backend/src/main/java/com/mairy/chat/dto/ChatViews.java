package com.mairy.chat.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 对外主键统一转字符串；时间字段为上海本地时间，不携带偏移，前端直接展示。 */
public final class ChatViews {
    /** 禁止实例化响应容器。 */
    private ChatViews() {
    }

    /** 房间列表与创建结果。 */
    public record RoomView(String id, String name, LocalDateTime createdAt) {
    }

    /** 进房凭证绑定登录会话，仅在进房时返回并保存在页面内存中。 */
    public record MemberView(String id, String roomId, String nickname, String token,
                             LocalDateTime expiresAt) {
    }

    /** 历史与实时消息共用的结构。 */
    public record MessageView(String id, String roomId, String memberId, String nickname,
                              String content, Integer enc, String iv, LocalDateTime createdAt) {
    }

    /** 房间按页码查询。 */
    public record PageView<T>(List<T> records, long total, long page, long size) {
    }

    /** 消息按主键游标翻页，避免实时插入造成页码漂移。 */
    public record MessagePage(List<MessageView> records, String nextBeforeId, boolean hasMore) {
    }
}
