package com.example.shizhan.rubbish.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
@EnableScheduling
public class OrderReminderConfiguration {
    @Bean
    public Clock orderReminderClock() {
        return Clock.system(ZoneId.of("Asia/Shanghai"));
    }
}
