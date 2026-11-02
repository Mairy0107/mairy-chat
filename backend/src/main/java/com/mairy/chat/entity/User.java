package com.mairy.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 账号体系数据访问模型。 */
@Data
@TableName("chat_user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Integer publicNo;
    private String username;
    private String nickname;
    private String passwordHash;
    private String role;
    private Boolean disabled;
    private java.time.LocalDateTime createdAt;
}
