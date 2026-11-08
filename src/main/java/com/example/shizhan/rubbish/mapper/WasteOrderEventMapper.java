package com.example.shizhan.rubbish.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.shizhan.rubbish.entity.WasteOrderEvent;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface WasteOrderEventMapper extends BaseMapper<WasteOrderEvent> {
    @Select("SELECT * FROM waste_order_event WHERE id=#{id} FOR UPDATE") WasteOrderEvent lockById(@Param("id")Long id);
    @Select("SELECT COUNT(*) FROM waste_order_event WHERE bin_id=#{id} AND state NOT IN ('DONE','FAILED')") int pendingForBin(@Param("id")Long id);
    @Select("SELECT * FROM waste_order_event WHERE state IN ('NEW','SENDING','SENT') AND next_attempt_at <= #{now} ORDER BY next_attempt_at LIMIT 50")
    List<WasteOrderEvent> dispatchable(@Param("now")LocalDateTime now);
    @Update("UPDATE waste_order_event SET state='SENDING',publish_attempts=publish_attempts+1,next_attempt_at=#{lease},updated_at=#{now} WHERE id=#{id} AND state IN ('NEW','SENDING','SENT') AND next_attempt_at<=#{now}")
    int claim(@Param("id")Long id,@Param("now")LocalDateTime now,@Param("lease")LocalDateTime lease);
    @Update("UPDATE waste_order_event SET state='SENT',last_error=NULL WHERE id=#{id} AND state='SENDING' AND publish_attempts=#{attempt}")
    int confirmed(@Param("id")Long id,@Param("attempt")int attempt);
    @Update("UPDATE waste_order_event SET state='NEW',last_error=#{error},next_attempt_at=#{retry} WHERE id=#{id} AND state='SENDING' AND publish_attempts=#{attempt}")
    int publishFailed(@Param("id")Long id,@Param("attempt")int attempt,@Param("error")String error,@Param("retry")LocalDateTime retry);
    @Update("UPDATE waste_order_event SET state=CASE WHEN consume_attempts+1>=#{max} THEN 'FAILED' ELSE state END,consume_attempts=consume_attempts+1,last_error=#{error},updated_at=#{now} WHERE id=#{id} AND state NOT IN ('DONE','FAILED')")
    int consumeFailed(@Param("id")Long id,@Param("max")int max,@Param("error")String error,@Param("now")LocalDateTime now);
}
