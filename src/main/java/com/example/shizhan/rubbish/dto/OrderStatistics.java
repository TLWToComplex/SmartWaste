package com.example.shizhan.rubbish.dto;

import lombok.Data;

/** 管理端订单概览，状态数量统计全部订单，今日数量包含当天创建的所有状态订单。 */
@Data
public class OrderStatistics {
    private long pendingCount;
    private long inTransitCount;
    private long completedCount;
    private long todayCount;
    private String statisticDate;
}
