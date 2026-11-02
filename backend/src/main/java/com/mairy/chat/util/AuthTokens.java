package com.mairy.chat.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/** 生成高熵凭证；数据库仅存登录凭证的 SHA-256。 */
public final class AuthTokens {
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 禁止实例化工具类。 */
    private AuthTokens() { }

    /** 生成256位随机登录凭证。 */
    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** 统一散列登录凭证和设备标识，不保留原始登录密钥。 */
    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少SHA-256", exception);
        }
    }
}
