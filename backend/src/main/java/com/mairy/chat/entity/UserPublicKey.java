package com.mairy.chat.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 只保存不可覆盖的设备公钥，服务端没有私钥副本。 */
@Data
@TableName("chat_user_pubkey")
public class UserPublicKey {
    @TableId
    private Long userId;
    private String publicKey;
    private String fingerprint;
    private LocalDateTime createdAt;
}
