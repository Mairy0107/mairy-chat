package com.mairy.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 每对已接受好友共享一个独立私信会话。 */
@Data
@TableName("chat_dm_thread")
public class DmThread {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userLow;
    private Long userHigh;
    private LocalDateTime createdAt;
}
