package com.mairy.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 账号体系数据访问模型。 */
@Data
@TableName("chat_login_session")
public class LoginSession {
    @TableId(type = IdType.INPUT)
    private String id;
    private Long userId;
    private java.time.LocalDateTime expiresAt;
}
