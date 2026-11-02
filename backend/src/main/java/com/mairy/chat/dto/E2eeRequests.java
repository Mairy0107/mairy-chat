package com.mairy.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 加密接口不接收私钥或未封装的房间密钥。 */
public final class E2eeRequests {
    /** 禁止实例化请求容器。 */
    private E2eeRequests() {
    }

    /** RSA-OAEP SHA-256 SPKI 公钥。 */
    public record PublishKey(@NotBlank @Size(max = 1024) String publicKey) {
    }

    /** 指纹绑定到收件人的不可变公钥。 */
    public record GrantKey(@Pattern(regexp = "[a-f0-9]{64}") @NotBlank String fingerprint,
                           @NotBlank @Size(max = 512) String wrappedKey) {
    }
}
