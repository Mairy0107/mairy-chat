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

    /** 管理员可选的隐身进房请求。 */
    public record JoinRoom(
            boolean stealth) {
    }

    /** 新消息必须携带 AES-GCM 密文及随机 IV，不再接受明文写入。 */
    public record SendMessage(
            @NotBlank(message = "消息不能为空")
            @Size(max = 5400, message = "加密消息过长") String content,
            @jakarta.validation.constraints.NotNull Integer enc,
            @NotBlank @Size(max = 16) String iv) {
    }
}
