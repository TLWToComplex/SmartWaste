package com.example.shizhan.rubbish.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.shizhan.rubbish.dto.OrderStatistics;
import com.example.shizhan.rubbish.entity.orders;
import com.example.shizhan.rubbish.event.OrderStatusChangedEvent;
import com.example.shizhan.rubbish.mapper.OrderMapper;
import com.example.shizhan.rubbish.service.Interface.orderService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * @author bz_c
 * @version 1.0
 */
@Service
public class orderServiceImpl extends ServiceImpl<OrderMapper, orders> implements orderService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    @Autowired
    private ApplicationEventPublisher publisher;

    @Override
    public boolean updateById(orders order) {
        boolean updated = super.updateById(order);
        if (updated) publisher.publishEvent(new OrderStatusChangedEvent());
        return updated;
    }

    @Override
    public OrderStatistics getStatistics() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        OrderStatistics statistics = baseMapper.selectStatistics(
                today.toString(), today.plusDays(1).toString());
        statistics.setStatisticDate(today.toString());
        return statistics;
    }
}
