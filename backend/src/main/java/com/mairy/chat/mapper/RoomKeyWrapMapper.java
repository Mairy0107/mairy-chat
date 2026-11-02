package com.mairy.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mairy.chat.entity.RoomKeyWrap;
import com.mairy.chat.dto.E2eeViews.Candidate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Select;

/** 一次关联查询授权候选人，不在成员循环中查询数据库。 */
public interface RoomKeyWrapMapper extends BaseMapper<RoomKeyWrap> {
    /** 只返回有有效普通会话、公钥且尚未授权的普通账号。 */
    @Select("""
            SELECT DISTINCT u.id AS user_id, u.nickname, p.public_key, p.fingerprint
            FROM chat_member m JOIN chat_user u ON u.id=m.user_id
            JOIN chat_login_session s ON s.id=m.login_session_id
            JOIN chat_user_pubkey p ON p.user_id=u.id
            LEFT JOIN chat_room_key_wrap w ON w.room_id=m.room_id AND w.user_id=u.id
            WHERE m.room_id=#{roomId} AND m.stealth=FALSE AND m.expires_at>#{now}
              AND s.expires_at>#{now} AND u.disabled=FALSE AND u.role='USER' AND w.id IS NULL
            ORDER BY user_id LIMIT 200
            """)
    List<Candidate> candidates(Long roomId, LocalDateTime now);
}
