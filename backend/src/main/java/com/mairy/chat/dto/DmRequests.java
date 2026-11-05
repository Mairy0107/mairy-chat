package com.mairy.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** 私信请求不接受发送人、加密标记等服务端字段。 */
public final class DmRequests {
    /** 禁止实例化请求容器。 */
    private DmRequests() { }

    /** 文字兼容原请求；语音接收明文 Base64，具体类型与限额由业务层校验。 */
    public record SendMessage(
            @NotBlank(message = "消息不能为空")
            @Size(max = 699052, message = "消息载荷过大") String content,
            @Size(max = 8, message = "消息类型无效") String kind,
            @Size(max = 32, message = "语音格式无效") String mime,
            BigDecimal duration) { }
}
