package com.mairy.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 账号体系数据访问模型。 */
@Data
@TableName("chat_password_reset_request")
public class PasswordResetRequest {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String status;
    private String note;
    private Long handledBy;
    private java.time.LocalDateTime createdAt;
    private java.time.LocalDateTime handledAt;
}
