package com.example.shizhan.rubbish;

import com.example.shizhan.rubbish.config.OrderReminderProperties;
import com.example.shizhan.rubbish.dto.OrderReminderNotice;
import com.example.shizhan.rubbish.dto.OrderReminderPage;
import com.example.shizhan.rubbish.entity.orders;
import com.example.shizhan.rubbish.mapper.OrderMapper;
import com.example.shizhan.rubbish.service.OrderReminderService;
import com.example.shizhan.rubbish.task.OrderReminderTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class OrderReminderServiceTest {
    @Test
    void includesExactDeadlineButNotOneNanosecondLater() {
        OrderMapper mapper = mock(OrderMapper.class);
        when(mapper.selectOverdueCandidates(anyString())).thenReturn(Arrays.asList(
                order(1, "2026-12-31T23:50:00"), order(2, "2026-12-31 23:50:00.000000001")));
        OrderReminderService service = service(mapper, new OrderReminderProperties(), mock(ApplicationEventPublisher.class));
        OrderReminderPage page = service.getReminders(1, 5);
        assertEquals(1, page.getTotal());
        assertEquals("2027-01-01 00:00:00", page.getCheckedAt());
        assertEquals(10, page.getRecords().get(0).getWaitingMinutes());
        assertEquals(0, page.getRecords().get(0).getOverdueMinutes());
        verify(mapper).selectOverdueCandidates("2026-12-31 23:50:00.000000000");
    }

    @Test
    void timeoutIsConfigurableAndInvalidConfigurationFailsEarly() {
        OrderReminderProperties properties = new OrderReminderProperties();
        properties.setTimeoutMinutes(30);
        OrderMapper mapper = mock(OrderMapper.class);
        when(mapper.selectOverdueCandidates(anyString())).thenReturn(Collections.emptyList());
        assertEquals(30, service(mapper, properties, mock(ApplicationEventPublisher.class)).getReminders(1, 5).getTimeoutMinutes());
        verify(mapper).selectOverdueCandidates("2026-12-31 23:30:00.000000000");
        assertThrows(IllegalArgumentException.class, () -> properties.setTimeoutMinutes(0));
        assertThrows(IllegalArgumentException.class, () -> properties.setTimeoutMinutes(-1));
    }

    @Test
    void scansPublishUpdatesEvenWhenNoOverdueOrdersRemain() {
        OrderMapper mapper = mock(OrderMapper.class);
        when(mapper.selectOverdueCandidates(anyString())).thenReturn(Collections.emptyList());
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        new OrderReminderTask(service(mapper, new OrderReminderProperties(), publisher)).scan();
        ArgumentCaptor<OrderReminderNotice> notice = ArgumentCaptor.forClass(OrderReminderNotice.class);
        verify(publisher).publishEvent(notice.capture());
        assertEquals("order-reminders.updated", notice.getValue().getType());
        assertEquals(0, notice.getValue().getTotal());
    }

    @Test
    void aFailedScanDoesNotPublishMisleadingEmptySuccess() {
        OrderReminderService service = mock(OrderReminderService.class);
        doThrow(new IllegalStateException("database unavailable")).when(service).scanAndNotify();
        assertDoesNotThrow(() -> new OrderReminderTask(service).scan());
        verify(service).notifyScanFailure();
    }

    private OrderReminderService service(OrderMapper mapper, OrderReminderProperties properties, ApplicationEventPublisher publisher) {
        return new OrderReminderService(mapper, properties, publisher,
                Clock.fixed(Instant.parse("2026-12-31T16:00:00Z"), ZoneId.of("Asia/Shanghai")));
    }

    private orders order(long id, String time) {
        orders value = new orders();
        value.setId(BigInteger.valueOf(id));
        value.setOrderTime(time);
        return value;
    }
}
