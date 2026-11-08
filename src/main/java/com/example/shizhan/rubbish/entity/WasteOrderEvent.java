package com.example.shizhan.rubbish.entity;
import lombok.Data;
import java.math.BigInteger;
import java.time.LocalDateTime;

@Data
public class WasteOrderEvent {
    private Long id,binId,binCycle;
    private String source,actorId,requestId;
    private LocalDateTime reportedAt;
    private BigInteger orderId;
    private String state;
    private Integer publishAttempts,consumeAttempts;
    private LocalDateTime nextAttemptAt,updatedAt;
    private String lastError;
}
