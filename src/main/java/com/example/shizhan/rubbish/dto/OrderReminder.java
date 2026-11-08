package com.example.shizhan.rubbish.dto;

import lombok.Data;

import java.math.BigInteger;

@Data
public class OrderReminder {
    private BigInteger orderId;
    private String number;
    private String userName;
    private String startAddress;
    private String orderTime;
    private long waitingMinutes;
    private long overdueMinutes;
}
