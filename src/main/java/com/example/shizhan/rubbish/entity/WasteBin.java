package com.example.shizhan.rubbish.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;

@Data
public class WasteBin {
    private Long id;
    private String code;
    private String name;
    private String address;
    private String wasteType;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private Long stationId;
    private Integer status;
    private Integer fillStatus;
    private Long fillCycle;
    private BigInteger activeOrderId;
    private LocalDateTime lastReportTime;
    private LocalDateTime lastEmptiedAt;
    private String deviceId;
    @JsonIgnore
    private String deviceTokenHash;
}
