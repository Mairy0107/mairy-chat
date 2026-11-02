package com.mairy.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mairy.chat.entity.User;

/** 账号体系数据访问模型。 */
public interface UserMapper extends BaseMapper<User> {
    /** 用当前读锁定用户名，登录和找回不复用旧事务快照。 */
    @org.apache.ibatis.annotations.Select("SELECT * FROM chat_user WHERE username = #{username} FOR UPDATE")
    User selectByUsernameForUpdate(String username);

    /** 锁定账号行以串行处理重置。 */
    @org.apache.ibatis.annotations.Select("SELECT * FROM chat_user WHERE id = #{id} FOR UPDATE")
    User selectByIdForUpdate(Long id);
}
