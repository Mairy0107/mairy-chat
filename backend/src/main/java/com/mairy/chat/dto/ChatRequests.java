package com.mairy.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 对外请求只接收业务字段，不接收客户端提供的身份。 */
public final class ChatRequests {
    /** 禁止实例化请求容器。 */
    private ChatRequests() {
    }

    /** 创建房间请求。 */
    public record CreateRoom(
            @NotBlank(message = "房间名称不能为空")
            @Size(max = 50, message = "房间名称最多 50 字") String name) {
    }

    /** 昵称进房请求。 */
    public record JoinRoom(
            @NotBlank(message = "昵称不能为空")
            @Size(max = 20, message = "昵称最多 20 字") String nickname) {
    }

    /** 发送纯文本消息请求。 */
    public record SendMessage(
            @NotBlank(message = "消息不能为空")
            @Size(max = 1000, message = "消息最多 1000 字") String content) {
    }
}
