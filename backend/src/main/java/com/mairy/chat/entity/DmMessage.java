package com.mairy.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 私信 v1 保存明文，独立于房间加密消息。 */
@Data
@TableName("chat_dm_message")
public class DmMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long threadId;
    private Long senderId;
    private String content;
    private Integer enc;
    private String iv;
    private LocalDateTime createdAt;
}
