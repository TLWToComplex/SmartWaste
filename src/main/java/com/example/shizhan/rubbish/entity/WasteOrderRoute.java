package com.example.shizhan.rubbish.entity;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import java.math.BigInteger;
import java.time.LocalDateTime;
@Data
public class WasteOrderRoute {
    @TableId private BigInteger orderId;
    private String origin,destination,polyline;
    private Long distanceMeters,durationSeconds;
    private LocalDateTime updatedAt;
}
