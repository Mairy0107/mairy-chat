package com.mairy.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 账号体系数据访问模型。 */
@Data
@TableName("chat_device_registration")
public class DeviceRegistration {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String fingerprintHash;
    private Long userId;
    private String userAgent;
    private String deviceLabel;
    private java.time.LocalDateTime createdAt;
}
