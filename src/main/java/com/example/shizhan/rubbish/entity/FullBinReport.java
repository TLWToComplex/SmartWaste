package com.example.shizhan.rubbish.entity;

import lombok.Data;
import java.math.BigInteger;
import java.time.LocalDateTime;

@Data
public class FullBinReport {
    private Long id;
    private Long binId;
    private BigInteger orderId;
    private String source;
    private String actorId;
    private String requestId;
    private LocalDateTime reportedAt;
}
