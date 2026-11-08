package com.example.shizhan.rubbish.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.*;
import java.time.LocalDateTime;
@Data
public class DriverLocation {
    @TableId private Long driverId;
    private BigInteger orderId;
    private String carNumber;
    private BigDecimal longitude,latitude;
    private Double accuracyMeters;
    private Long observedAt;
    private LocalDateTime updatedAt;
    @TableField(exist=false) private String driverName,orderNumber;
    @TableField(exist=false) private Integer orderStatus;
    @TableField(exist=false) private String coordinateSystem;
}
