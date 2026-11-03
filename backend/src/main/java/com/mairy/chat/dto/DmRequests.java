package com.mairy.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 私信请求不接受发送人、加密标记等服务端字段。 */
public final class DmRequests {
    /** 禁止实例化请求容器。 */
    private DmRequests() { }

    /** v1 仅接收至多1000字的明文消息。 */
    public record SendMessage(
            @NotBlank(message = "消息不能为空")
            @Size(max = 1000, message = "消息最多1000字") String content) { }
}
