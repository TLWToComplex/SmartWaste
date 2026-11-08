package com.example.shizhan.rubbish.dto;

import lombok.Data;

import java.util.List;

@Data
public class OrderReminderPage {
    private List<OrderReminder> records;
    private long total;
    private int page;
    private int pageSize;
    private long timeoutMinutes;
    private String checkedAt;
}
