package com.example.shizhan.rubbish.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.shizhan.rubbish.dto.OrderStatistics;
import com.example.shizhan.rubbish.entity.orders;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.math.BigInteger;

/**
 * @author bz_c
 * @version 1.0
 */
@Mapper
public interface OrderMapper extends BaseMapper<orders> {
    @Select("SELECT o.* FROM orders o WHERE o.user_id = #{userId} OR EXISTS "
            + "(SELECT 1 FROM full_bin_report r WHERE r.order_id = o.id AND r.source = 'USER' AND r.actor_id = #{userId}) "
            + "ORDER BY o.order_time DESC, o.id DESC")
    List<orders> selectUserOrders(@Param("userId") String userId);

    @Select("SELECT COUNT(*) FROM orders o WHERE o.id = #{id} AND (o.user_id = #{userId} OR EXISTS "
            + "(SELECT 1 FROM full_bin_report r WHERE r.order_id = o.id AND r.source = 'USER' AND r.actor_id = #{userId}))")
    int countUserOrder(@Param("id") BigInteger id, @Param("userId") String userId);
    @Select("SELECT * FROM orders WHERE id = #{id} FOR UPDATE")
    orders lockById(@Param("id") BigInteger id);
    // 使用半开日期区间，兼容现有 order_time 的空格和 ISO T 分隔格式。
    @Select("SELECT "
            + "COUNT(CASE WHEN status = 1 THEN 1 END) AS pending_count, "
            + "COUNT(CASE WHEN status = 2 THEN 1 END) AS in_transit_count, "
            + "COUNT(CASE WHEN status = 3 THEN 1 END) AS completed_count, "
            + "COUNT(CASE WHEN order_time >= #{todayStart} "
            + "AND order_time < #{tomorrowStart} THEN 1 END) AS today_count "
            + "FROM orders")
    @Results({
            @Result(column = "pending_count", property = "pendingCount"),
            @Result(column = "in_transit_count", property = "inTransitCount"),
            @Result(column = "completed_count", property = "completedCount"),
            @Result(column = "today_count", property = "todayCount")
    })
    OrderStatistics selectStatistics(@Param("todayStart") String todayStart,
                                     @Param("tomorrowStart") String tomorrowStart);

    // 历史代码同时存有空格和 T 分隔的时间，先归一化再筛选。
    @Select("SELECT id, number, user_name, start_address, order_time FROM orders "
            + "WHERE status = 1 AND order_time IS NOT NULL "
            + "AND REPLACE(order_time, 'T', ' ') <= #{cutoff}")
    List<orders> selectOverdueCandidates(@Param("cutoff") String cutoff);
}
