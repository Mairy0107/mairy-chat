package com.mairy.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mairy.chat.entity.AiThread;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 会话锁只覆盖入库事务，不在模型等待期间占用数据库连接。 */
public interface AiThreadMapper extends BaseMapper<AiThread> {
    /** 直接按归属锁行，不泄露其他账号的会话。 */
    @Select("SELECT * FROM chat_ai_thread WHERE id=#{id} AND user_id=#{userId} FOR UPDATE")
    AiThread lockMine(Long id, Long userId);

    /** 只释放本次生成的租约，超时旧请求不能覆盖新请求。 */
    @Update("UPDATE chat_ai_thread SET active_request_id=NULL,busy_until=NULL,updated_at=#{now} "
            + "WHERE id=#{id} AND user_id=#{userId} AND active_request_id=#{requestId}")
    int release(Long id, Long userId, String requestId, LocalDateTime now);
}
