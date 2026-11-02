package com.mairy.chat.dto;

import com.mairy.chat.entity.User;
import java.time.LocalDateTime;

/** 明确列出公开字段，禁止返回密码散列和登录会话实体。 */
public final class AuthViews {
    /** 禁止实例化响应容器。 */
    private AuthViews() { }

    public record UserView(String id, String publicNo, String username, String nickname,
                           String role, boolean disabled, LocalDateTime createdAt) { }
    public record LoginView(String token, LocalDateTime expiresAt, UserView user) { }
    public record RequestView(String id, UserView sender, UserView receiver, String status,
                              LocalDateTime createdAt) { }
    public record ResetView(String id, UserView user, String status, String note,
                            LocalDateTime createdAt, LocalDateTime handledAt) { }

    /** 编号存整数，对外至少补足四位。 */
    public static UserView user(User value) {
        return new UserView(value.getId().toString(), "#%04d".formatted(value.getPublicNo()),
                value.getUsername(), value.getNickname(), value.getRole(),
                Boolean.TRUE.equals(value.getDisabled()), value.getCreatedAt());
    }
}
