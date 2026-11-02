package com.mairy.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 昵称进房会话，凭证仅通过进房接口返回。 */
@Data
@TableName("chat_member")
public class Member {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long roomId;
    private String nickname;
    private String token;
    private LocalDateTime joinedAt;
    private LocalDateTime expiresAt;
}
