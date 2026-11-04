package com.mairy.chat.mapper;

import java.time.LocalDate;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 用户与上海日期组成唯一计数，条件更新保证并发不超额。 */
public interface AiUsageMapper {
    /** 第一次请求建立当日零计数行，重复请求保留原值。 */
    @Insert("INSERT IGNORE INTO chat_ai_usage_daily(user_id,usage_date,request_count) VALUES(#{userId},#{date},0)")
    void ensure(Long userId, LocalDate date);

    /** 原子占用一次额度，不通过先查后写实现配额。 */
    @Update("UPDATE chat_ai_usage_daily SET request_count=request_count+1 "
            + "WHERE user_id=#{userId} AND usage_date=#{date} AND request_count < #{limit}")
    int consume(Long userId, LocalDate date, int limit);

    /** 状态查询不建立空计数行。 */
    @Select("SELECT request_count FROM chat_ai_usage_daily WHERE user_id=#{userId} AND usage_date=#{date}")
    Integer count(Long userId, LocalDate date);
}
