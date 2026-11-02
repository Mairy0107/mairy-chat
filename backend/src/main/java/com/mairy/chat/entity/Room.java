package com.mairy.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 聊天房间。 */
@Data
@TableName("chat_room")
public class Room {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    // 数据库唯一约束保证每个账号最多一间自建房。
    private Long ownerUserId;
    private LocalDateTime createdAt;
}
