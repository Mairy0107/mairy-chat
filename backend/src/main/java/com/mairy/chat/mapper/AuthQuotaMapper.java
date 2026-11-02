package com.mairy.chat.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 注册事务锁定设备计数与编号行，避免并发越过上限。 */
public interface AuthQuotaMapper {
    /** 首次见到指纹时建立计数行。 */
    @Insert("INSERT IGNORE INTO chat_device_quota (fingerprint_hash, account_count) VALUES (#{hash}, 0)")
    void ensureDevice(String hash);

    /** 锁定同一指纹的注册配额直至事务完成。 */
    @Select("SELECT account_count FROM chat_device_quota WHERE fingerprint_hash = #{hash} FOR UPDATE")
    int lockDevice(String hash);

    /** 注册成功后占用一份设备配额。 */
    @Update("UPDATE chat_device_quota SET account_count = account_count + 1 WHERE fingerprint_hash = #{hash}")
    void incrementDevice(String hash);

    /** 锁定全局公开编号，管理员预占第一号。 */
    @Select("SELECT last_no FROM chat_public_sequence WHERE id = 1 FOR UPDATE")
    int lockNumber();

    /** 在同一事务中递增公开编号。 */
    @Update("UPDATE chat_public_sequence SET last_no = last_no + 1 WHERE id = 1")
    void incrementNumber();
}
