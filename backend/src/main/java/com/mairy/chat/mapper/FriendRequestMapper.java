package com.mairy.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mairy.chat.entity.FriendRequest;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 账号体系数据访问模型。 */
public interface FriendRequestMapper extends BaseMapper<FriendRequest> {
    /** 复用好友表的 LEAST/GREATEST 用户对唯一索引。 */
    @Select("SELECT COUNT(*) FROM chat_friend_request "
            + "WHERE pair_low=#{low} AND pair_high=#{high} AND status='ACCEPTED'")
    int countAccepted(@Param("low") Long low, @Param("high") Long high);
}
