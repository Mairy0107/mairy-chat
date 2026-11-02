package com.mairy.chat.dto;

/** 加密响应只含公钥、指纹和密钥信封。 */
public final class E2eeViews {
    /** 禁止实例化响应容器。 */
    private E2eeViews() {
    }

    /** 公开身份密钥。 */
    public record PublicKeyView(String userId, String publicKey, String fingerprint) {
    }

    /** 当前成员专属密钥信封，不返回其他成员信封。 */
    public record KeyState(String ownerUserId, boolean initialized, String fingerprint, String wrappedKey) {
    }

    /** 房主可明确选择授权的普通成员。 */
    public record Candidate(String userId, String nickname, String publicKey, String fingerprint) {
    }
}
