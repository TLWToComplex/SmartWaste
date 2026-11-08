package com.example.shizhan.rubbish.task;

import com.example.shizhan.rubbish.service.OrderReminderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@ConditionalOnProperty(prefix = "smartwaste.order-reminder", name = "scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class OrderReminderTask {
    private final OrderReminderService service;

    public OrderReminderTask(OrderReminderService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${smartwaste.order-reminder.scan-interval-millis:60000}",
            initialDelayString = "${smartwaste.order-reminder.initial-delay-millis:5000}")
    public void scan() {
        try {
            service.scanAndNotify();
        } catch (RuntimeException ex) {
            log.error("扫描超时待接订单失败，下轮扫描将重试", ex);
            service.notifyScanFailure();
        }
    }
}
