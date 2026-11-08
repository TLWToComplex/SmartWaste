package com.example.shizhan.rubbish.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "smartwaste.order-reminder")
public class OrderReminderProperties {
    private long timeoutMinutes = 10;

    public long getTimeoutMinutes() {
        return timeoutMinutes;
    }

    public void setTimeoutMinutes(long timeoutMinutes) {
        if (timeoutMinutes <= 0 || timeoutMinutes > 525600) {
            throw new IllegalArgumentException("订单超时时间应在 1 至 525600 分钟之间");
        }
        this.timeoutMinutes = timeoutMinutes;
    }
}
