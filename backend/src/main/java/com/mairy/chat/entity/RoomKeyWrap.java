package com.mairy.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 房间密钥仅保存按成员公钥封装后的密文。 */
@Data
@TableName("chat_room_key_wrap")
public class RoomKeyWrap {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long roomId;
    private Long userId;
    private String fingerprint;
    private String wrappedKey;
    private LocalDateTime createdAt;
}
