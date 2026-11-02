package com.mairy.chat.util;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** 统一业务时间基准，避免服务器默认 UTC 导致写入和过期判断偏移。 */
public final class ChatTime {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 禁止实例化时间工具。 */
    private ChatTime() {
    }

    /** 获取上海当前时间，与数据库 DATETIME 和接口无时区时间字符串保持一致。 */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }
}
