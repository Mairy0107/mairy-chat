package com.mairy.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 账号独享助手会话，短租约防止并发生成混入上下文。 */
@Data
@TableName("chat_ai_thread")
public class AiThread {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    private String activeRequestId;
    private LocalDateTime busyUntil;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
