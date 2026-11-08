package com.example.shizhan.rubbish.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** WebSocket 只发送刷新通知；订单明细仍由已鉴权的 HTTP 接口提供。 */
@Data
@AllArgsConstructor
public class OrderReminderNotice {
    private String type;
    private long total;
    private String checkedAt;
}
