package com.mairy.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 消息保存昵称快照，读取列表时无需逐条查成员。 */
@Data
@TableName("chat_message")
public class Message {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long roomId;
    private Long memberId;
    private String nickname;
    private String content;
    private LocalDateTime createdAt;
}
