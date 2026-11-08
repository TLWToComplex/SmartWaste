package com.example.shizhan.rubbish.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.shizhan.rubbish.entity.DriverLocation;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper public interface DriverLocationMapper extends BaseMapper<DriverLocation>{
    @Select("SELECT l.*,d.name AS driver_name,o.number AS order_number,o.status AS order_status FROM driver_location l JOIN driver d ON l.driver_id=d.id JOIN orders o ON l.order_id=o.id ORDER BY l.updated_at DESC") List<DriverLocation> snapshots();
    @Update("UPDATE driver_location SET order_id=#{orderId},car_number=#{carNumber},longitude=#{longitude},latitude=#{latitude},accuracy_meters=#{accuracyMeters},observed_at=#{observedAt},updated_at=#{updatedAt} WHERE driver_id=#{driverId} AND observed_at<#{observedAt}") int newer(DriverLocation position);
}
