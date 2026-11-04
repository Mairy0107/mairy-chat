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
    // 仅房间区分文字与语音，格式和时长保留在密文中。
    private String kind;
    // 旧记录默认明文，新消息只允许 AES-GCM 密文。
    private Integer enc;
    private String iv;
    private LocalDateTime createdAt;
}
