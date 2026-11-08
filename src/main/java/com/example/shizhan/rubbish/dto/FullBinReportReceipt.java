package com.example.shizhan.rubbish.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.math.BigInteger;

@Data
@AllArgsConstructor
@lombok.NoArgsConstructor
public class FullBinReportReceipt {
    private BigInteger orderId;
    private String orderNumber;
    private int orderStatus;
    private boolean existingTask;
    private Long eventId;
    private String processingState;
    private String message;
}
