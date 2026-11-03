package com.mairy.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mairy.chat.entity.DmThread;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 唯一用户对保证并发打开时不会创建重复会话。 */
public interface DmThreadMapper extends BaseMapper<DmThread> {
    /** 原子创建会话；重复打开保持原记录与创建时间。 */
    @Insert("INSERT INTO chat_dm_thread(user_low,user_high,created_at) VALUES(#{low},#{high},#{now}) "
            + "ON DUPLICATE KEY UPDATE id=id")
    void ensureThread(@Param("low") Long low, @Param("high") Long high, @Param("now") LocalDateTime now);

    /** 仅列出本人仍是好友且对方未停用的会话，不在循环中查好友。 */
    @Select("SELECT t.* FROM chat_dm_thread t "
            + "JOIN chat_friend_request f ON f.pair_low=t.user_low AND f.pair_high=t.user_high "
            + "JOIN chat_user u ON u.id=CASE WHEN t.user_low=#{me} THEN t.user_high ELSE t.user_low END "
            + "WHERE (t.user_low=#{me} OR t.user_high=#{me}) AND f.status='ACCEPTED' "
            + "AND u.disabled=FALSE ORDER BY t.id DESC LIMIT 200")
    List<DmThread> listMine(@Param("me") Long me);
}
