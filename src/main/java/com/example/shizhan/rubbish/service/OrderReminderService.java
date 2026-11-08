package com.example.shizhan.rubbish.service;

import com.example.shizhan.rubbish.config.OrderReminderProperties;
import com.example.shizhan.rubbish.dto.OrderReminder;
import com.example.shizhan.rubbish.dto.OrderReminderNotice;
import com.example.shizhan.rubbish.dto.OrderReminderPage;
import com.example.shizhan.rubbish.entity.orders;
import com.example.shizhan.rubbish.event.OrderStatusChangedEvent;
import com.example.shizhan.rubbish.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@Slf4j
public class OrderReminderService {
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter QUERY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS");
    private final OrderMapper mapper;
    private final OrderReminderProperties properties;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public OrderReminderService(OrderMapper mapper, OrderReminderProperties properties,
                                ApplicationEventPublisher publisher,
                                @Qualifier("orderReminderClock") Clock clock) {
        this.mapper = mapper;
        this.properties = properties;
        this.publisher = publisher;
        this.clock = clock;
    }

    public OrderReminderPage getReminders(int page, int pageSize) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime cutoff = now.minusMinutes(properties.getTimeoutMinutes());
        List<OrderReminder> reminders = new ArrayList<>();
        for (orders order : mapper.selectOverdueCandidates(cutoff.format(QUERY_TIME))) {
            if (order.getOrderTime().trim().isEmpty()) continue;
            try {
                LocalDateTime created = LocalDateTime.parse(order.getOrderTime().trim().replace(' ', 'T'));
                if (created.isAfter(cutoff)) continue;
                OrderReminder reminder = new OrderReminder();
                reminder.setOrderId(order.getId());
                reminder.setNumber(order.getNumber());
                reminder.setUserName(order.getUserName());
                reminder.setStartAddress(order.getStartAddress());
                reminder.setOrderTime(created.format(DISPLAY_TIME));
                reminder.setWaitingMinutes(Duration.between(created, now).toMinutes());
                reminder.setOverdueMinutes(Duration.between(created, cutoff).toMinutes());
                reminders.add(reminder);
            } catch (DateTimeParseException ex) {
                log.warn("跳过下单时间格式异常的订单，id={}", order.getId());
            }
        }
        reminders.sort(Comparator.comparing(OrderReminder::getOrderTime).thenComparing(OrderReminder::getOrderId));
        int from = (int) Math.min((long) (page - 1) * pageSize, reminders.size());
        int to = Math.min(from + pageSize, reminders.size());
        OrderReminderPage result = new OrderReminderPage();
        result.setRecords(new ArrayList<>(reminders.subList(from, to)));
        result.setTotal(reminders.size());
        result.setPage(page);
        result.setPageSize(pageSize);
        result.setTimeoutMinutes(properties.getTimeoutMinutes());
        result.setCheckedAt(now.format(DISPLAY_TIME));
        return result;
    }

    public void scanAndNotify() {
        OrderReminderPage result = getReminders(1, 1);
        // 包括 total=0 的通知，使管理端及时清除已接单、完成或取消的提醒。
        publisher.publishEvent(new OrderReminderNotice("order-reminders.updated", result.getTotal(), result.getCheckedAt()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        try {
            scanAndNotify();
        } catch (RuntimeException ex) {
            log.error("订单状态已更新，提醒刷新失败，将在下一轮扫描重试", ex);
            notifyScanFailure();
        }
    }

    public void notifyScanFailure() {
        publisher.publishEvent(new OrderReminderNotice("order-reminders.error", 0,
                LocalDateTime.now(clock).format(DISPLAY_TIME)));
    }
}
