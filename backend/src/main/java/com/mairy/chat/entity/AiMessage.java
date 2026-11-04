package com.mairy.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 助手消息在服务端明文保存，与房间 E2EE 完全独立。 */
@Data
@TableName("chat_ai_message")
public class AiMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long threadId;
    private String role;
    private String content;
    private LocalDateTime createdAt;
}
