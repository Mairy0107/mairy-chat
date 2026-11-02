package com.mairy.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 账号体系数据访问模型。 */
@Data
@TableName("chat_ip_block")
public class IpBlock {
    @TableId(type = IdType.INPUT)
    private String ip;
    private String reason;
    private java.time.LocalDateTime untilAt;
}
