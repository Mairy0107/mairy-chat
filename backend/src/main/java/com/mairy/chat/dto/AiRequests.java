package com.mairy.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 助手只接受文本，不接受客户端指定角色或系统指令。 */
public final class AiRequests {
    /** 禁止实例化请求容器。 */
    private AiRequests() { }

    public record SendMessage(@NotBlank(message = "请输入内容")
                              @Size(max = 2000, message = "内容不能超过2000字") String content) { }
}
